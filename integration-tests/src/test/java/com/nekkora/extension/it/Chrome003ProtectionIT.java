package com.nekkora.extension.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.nekkora.extension.it.support.BrowserLab;
import com.nekkora.extension.it.support.BrowserLab.Page;
import com.nekkora.extension.it.support.FrameIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Matriz P003 — Protection Diagnostics (CHROME-003 §28). Somente fixtures LOCAIS e SIMULADAS: referências de script
 * inertes ({@code type="application/x-nekkora-inert"}), URLs de widgets em {@code data-nekkora-simulated-src} e chaves
 * fictícias. Nenhum serviço real é contatado; nenhum desafio é executado ou resolvido.
 */
class Chrome003ProtectionIT extends FrameIT {

    private static final Pattern EVASION = Pattern.compile("(?i)contorn|burl|evadi|evas|disfar|ocult|falsific|bypass|desabilit|resolver o captcha|solver");

    private JsonNode fixture(String name, int frames) {
        Page p = openActive(PROTECTION + name + ".html", frames);
        JsonNode d = diagnose(p);
        evidence("P003-" + name + "-diagnosis.json", d);
        return d;
    }

    private Page page(String name, int frames) {
        return openActive(PROTECTION + name + ".html", frames);
    }

    private static void assertNoEvasion(JsonNode readiness) {
        for (JsonNode r : readiness.get("recommendations")) {
            String all = r.get("type").asText() + " " + r.get("reason").asText();
            assertFalse(EVASION.matcher(r.get("reason").asText()).find(), "recomendação sugere evasão: " + all);
        }
    }

    @Test
    @DisplayName("P003-001 — LAB-P01 reCAPTCHA v2: HIGH por canais independentes, CHALLENGE_PRESENT, evidência simulada rotulada")
    void recaptcha() {
        JsonNode d = fixture("p01-recaptcha", 1);
        JsonNode a = assessment(d.get("protection"), "RECAPTCHA_V2");
        assertNotNull(a, d.get("protection").toString());
        assertEquals("GOOGLE", a.get("provider").asText());
        assertEquals("DETECTED", a.get("status").asText());
        assertEquals("HIGH", a.get("confidence").asText());
        assertEquals("CHALLENGE_PRESENT", a.get("challengeState").asText());
        assertEquals("OFFICIAL_DOCUMENTATION", a.get("basis").asText());
        assertTrue(texts(a.get("evidence"), "indicatorId").containsAll(List.of("recaptcha.script", "recaptcha.widget", "recaptcha.anchor-frame")));
        assertTrue(a.get("limitations").toString().contains("simulação do laboratório"), "evidência simulada declarada");
        boolean simulated = false;
        for (JsonNode e : a.get("evidence")) {
            simulated |= e.get("simulated").asBoolean();
        }
        assertTrue(simulated);
        assertEquals("HUMAN_VERIFICATION", d.at("/readiness/obstacles").findValuesAsText("type").stream()
                .filter("HUMAN_VERIFICATION"::equals).findFirst().orElse(null));
        assertNoEvasion(d.get("readiness"));
    }

    @Test
    @DisplayName("P003-002 — Conclusão só com evidência explícita: campo de resposta preenchido ⇒ CHALLENGE_COMPLETED (valor não coletado)")
    void completedOnlyWithExplicitEvidence() {
        Page p = page("p01-recaptcha", 1);
        assertEquals("CHALLENGE_PRESENT", assessment(diagnose(p).get("protection"), "RECAPTCHA_V2").get("challengeState").asText());
        act(p, "concluir");
        // A mudança de valor de um campo oculto não gera mutação de DOM: a barreira de sincronização com captura parcial
        // do campo (mesma regra da CHROME-002 para valores) é acionada pelo diagnóstico.
        lab.js(p, "document.getElementById('g-recaptcha-response').setAttribute('data-sim', '1')");
        BrowserLab.waitUntil(Duration.ofSeconds(15), () -> "CHALLENGE_COMPLETED".equals(
                assessment(diagnose(p).get("protection"), "RECAPTCHA_V2").get("challengeState").asText()), "conclusão observada");
        JsonNode map = exportMap(p);
        assertFalse(map.toString().contains("SIMULADO"), "o valor do campo de resposta nunca é coletado");
    }

    @Test
    @DisplayName("P003-003 — LAB-P02 hCaptcha: HIGH; desafio aberto (frame=challenge) ⇒ CHALLENGE_ACTIVE")
    void hcaptcha() {
        Page p = page("p02-hcaptcha", 1);
        JsonNode a = assessment(diagnose(p).get("protection"), "HCAPTCHA");
        assertEquals("HIGH", a.get("confidence").asText());
        assertEquals("CHALLENGE_PRESENT", a.get("challengeState").asText());
        act(p, "abrir-desafio");
        BrowserLab.waitUntil(Duration.ofSeconds(15), () -> "CHALLENGE_ACTIVE".equals(
                assessment(diagnose(p).get("protection"), "HCAPTCHA").get("challengeState").asText()), "desafio ativo");
        JsonNode d = diagnose(p);
        evidence("P003-003-hcaptcha-active.json", d.get("protection"));
        assertEquals("REQUIRES_HUMAN_INTERVENTION", d.at("/readiness/status").asText());
    }

    @Test
    @DisplayName("P003-004 — LAB-P03 Cloudflare Turnstile: HIGH, CHALLENGE_PRESENT")
    void turnstile() {
        JsonNode a = assessment(fixture("p03-turnstile", 0).get("protection"), "TURNSTILE");
        assertEquals("CLOUDFLARE", a.get("provider").asText());
        assertEquals("HIGH", a.get("confidence").asText());
        assertEquals("CHALLENGE_PRESENT", a.get("challengeState").asText());
    }

    @Test
    @DisplayName("P003-005 — LAB-P04 página de desafio: observação de terceiros limitada a MEDIUM, CHALLENGE_ACTIVE ⇒ intervenção humana")
    void challengePage() {
        JsonNode d = fixture("p04-challenge-page", 0);
        JsonNode a = assessment(d.get("protection"), "CHALLENGE_PAGE");
        assertEquals("THIRD_PARTY_OBSERVATION", a.get("basis").asText());
        assertEquals("MEDIUM", a.get("confidence").asText(), "teto da assinatura de terceiros");
        assertTrue(a.get("confidenceReasons").toString().contains("limitada") || a.get("confidenceReasons").toString().contains("único canal"));
        assertEquals("CHALLENGE_ACTIVE", a.get("challengeState").asText());
        assertEquals("REQUIRES_HUMAN_INTERVENTION", d.at("/readiness/status").asText());
        assertEquals("HUMAN_INTERVENTION", d.at("/readiness/recommendations/0/type").asText());
        assertNoEvasion(d.get("readiness"));
    }

    @Test
    @DisplayName("P003-006 — LAB-P05 verificação não identificável: INSUFFICIENT_EVIDENCE, fornecedor UNKNOWN (nunca inferido)")
    void unknown() {
        JsonNode d = fixture("p05-unknown", 0);
        assertEquals("INSUFFICIENT_EVIDENCE", d.at("/protection/status").asText());
        JsonNode a = d.at("/protection/assessments/0");
        assertEquals("UNKNOWN", a.get("provider").asText());
        assertEquals("UNKNOWN", a.get("confidence").asText());
        assertEquals(1, d.at("/protection/assessments").size());
        assertTrue(d.at("/readiness/recommendations").toString().contains("UNKNOWN_PROTECTION"));
    }

    @Test
    @DisplayName("P003-007 — LAB-P06 falso positivo: texto sobre CAPTCHA ⇒ NOT_OBSERVED, sem afirmar ausência de proteção")
    void falsePositive() {
        JsonNode d = fixture("p06-false-positive", 0);
        assertEquals("NOT_OBSERVED", d.at("/protection/status").asText());
        assertEquals(0, d.at("/protection/assessments").size());
        assertTrue(d.at("/protection/genericIndicators").size() > 0, "menções registradas como indicadores genéricos");
        String lim = d.at("/protection/limitations").toString();
        assertTrue(lim.contains("não comprova ausência"), "nunca afirma que a página não possui proteção: " + lim);
        assertTrue(lim.contains("Menção textual"));
        assertEquals("NO_KNOWN_OBSTACLES", d.at("/readiness/status").asText());
    }

    @Test
    @DisplayName("P003-008 — LAB-P07 múltiplas tecnologias: v3/DataDome/Akamai; referência compartilhada v2×v3 reduz o produto menos específico")
    void multiple() {
        JsonNode d = fixture("p07-multiple", 0);
        JsonNode prot = d.get("protection");
        assertEquals("MEDIUM", assessment(prot, "RECAPTCHA_V3").get("confidence").asText());
        JsonNode v2 = assessment(prot, "RECAPTCHA_V2");
        assertEquals("LOW", v2.get("confidence").asText());
        assertEquals("POSSIBLE", v2.get("status").asText());
        assertTrue(v2.get("limitations").toString().contains("produto incerto"));
        assertEquals("MEDIUM", assessment(prot, "DATADOME").get("confidence").asText());
        JsonNode ak = assessment(prot, "BOT_MANAGER");
        assertEquals("LOW", ak.get("confidence").asText(), "assinatura de terceiros com teto LOW");
        for (JsonNode a : prot.get("assessments")) {
            assertEquals("TECHNOLOGY_DETECTED", a.get("challengeState").asText(), "tecnologia presente ≠ desafio: " + a.get("product"));
        }
        assertTrue(d.at("/readiness/obstacles").toString().contains("ANTIBOT_TECHNOLOGY"));
    }

    @Test
    @DisplayName("P003-009 — LAB-P08 proteção dentro de iframe: localização na hierarquia (documento, cadeia de frames e trajetória)")
    void insideIframe() {
        JsonNode d = fixture("p08-in-iframe", 2);
        JsonNode a = assessment(d.get("protection"), "TURNSTILE");
        JsonNode loc = a.get("location");
        assertTrue(loc.get("insideFrame").asBoolean());
        assertEquals(List.of("Pagamento", "Confirmação"), M.convertValue(loc.get("frameChain"), List.class));
        assertTrue(loc.get("inspectable").asBoolean());
        assertEquals(List.of("MAIN_DOCUMENT", "IFRAME", "IFRAME", "ELEMENT"), stepTypes(loc.get("accessPath")));
        assertEquals(loc.get("documentId").asText(), loc.at("/accessPath/steps/3/documentId").asText());
        assertEquals("HIGH", a.get("confidence").asText());
    }

    @Test
    @DisplayName("P003-010 — LAB-P09 proteção dinâmica: surge por delta (sem recaptura) e o desaparecimento NÃO é conclusão")
    void dynamic() {
        Page p = page("p09-dynamic", 0);
        assertEquals("NOT_OBSERVED", diagnose(p).at("/protection/status").asText());
        int full = session(p).get("fullCaptures").asInt();
        act(p, "exibir");
        BrowserLab.waitUntil(Duration.ofSeconds(15), () -> assessment(diagnose(p).get("protection"), "HCAPTCHA") != null, "widget dinâmico observado");
        assertEquals("HIGH", assessment(diagnose(p).get("protection"), "HCAPTCHA").get("confidence").asText());
        act(p, "ocultar");
        BrowserLab.waitUntil(Duration.ofSeconds(15), () -> assessment(diagnose(p).get("protection"), "HCAPTCHA") == null, "widget removido");
        JsonNode d = diagnose(p);
        evidence("P003-010-dynamic-after-removal.json", d.get("protection"));
        assertEquals("NOT_OBSERVED", d.at("/protection/status").asText());
        assertFalse(d.get("protection").toString().contains("CHALLENGE_COMPLETED"), "desaparecimento nunca vira conclusão");
        assertEquals(full, session(p).get("fullCaptures").asInt(), "observado por deltas");
    }

    @Test
    @DisplayName("P003-011 — LAB-P10 metadados insuficientes: POSSIBLE/LOW com estado UNKNOWN; frame sem permissão declarado")
    void insufficient() {
        JsonNode d = fixture("p10-insufficient", 1);
        JsonNode a = assessment(d.get("protection"), "WAF_CAPTCHA");
        assertEquals("POSSIBLE", a.get("status").asText());
        assertEquals("LOW", a.get("confidence").asText());
        assertEquals("UNKNOWN", a.get("challengeState").asText());
        assertEquals("INSUFFICIENT_EVIDENCE", d.at("/protection/status").asText());
        assertTrue(d.at("/protection/limitations").toString().contains("sem inspeção de conteúdo"));
        String obstacles = d.at("/readiness/obstacles").toString();
        assertTrue(obstacles.contains("UNKNOWN_PROTECTION") && obstacles.contains("INACCESSIBLE_FRAME"), obstacles);
        assertFalse(obstacles.contains("HUMAN_VERIFICATION"), "evidência fraca não vira exigência de verificação humana");
        assertTrue(d.at("/readiness/recommendations").toString().contains("MANUAL_VALIDATION"));
        assertEquals("LOW", d.at("/readiness/confidence").asText());
    }

    @Test
    @DisplayName("P003-012 — Tecnologia ≠ desafio ≠ bloqueio: LAB-P07 × LAB-P04 × LAB-P11 (§18)")
    void technologyChallengeBlock() {
        JsonNode tech = fixture("p07-multiple", 0);
        JsonNode challenge = fixture("p04-challenge-page", 0);
        JsonNode block = fixture("p11-block-page", 0);
        assertEquals("TECHNOLOGY_DETECTED", assessment(tech.get("protection"), "DATADOME").get("challengeState").asText());
        assertEquals("CHALLENGE_ACTIVE", assessment(challenge.get("protection"), "CHALLENGE_PAGE").get("challengeState").asText());
        JsonNode b = assessment(block.get("protection"), "BLOCK_PAGE");
        assertEquals("ACCESS_BLOCKED", b.get("challengeState").asText());
        assertTrue(block.at("/readiness/obstacles").toString().contains("ACCESS_BLOCKED"));
        assertEquals("REQUIRES_HUMAN_INTERVENTION", block.at("/readiness/status").asText());
        assertEquals("REQUIRES_SPECIAL_HANDLING", tech.at("/readiness/status").asText(), "tecnologia presente não exige intervenção humana");
        evidence("P003-012-technology-challenge-block.json", Map.of("technology", tech.at("/readiness/status"),
                "challenge", challenge.at("/readiness/status"), "block", block.at("/readiness/status")));
    }

    @Test
    @DisplayName("P003-013 — Detecção passiva: nenhuma requisição a fornecedores, nenhuma função da página chamada, nenhum valor sensível exportado")
    void passive() {
        Page p = page("p01-recaptcha", 1);
        diagnose(p);
        lab.call("protection.assess", p);
        JsonNode map = exportMap(p);
        Object hosts = lab.js(p, "return performance.getEntriesByType('resource').map(e => new URL(e.name).hostname)");
        for (Object h : (List<?>) hosts) {
            assertTrue(List.of("127.0.0.1", "localhost").contains(String.valueOf(h)), "requisição externa: " + h);
        }
        assertEquals(0L, ((Number) lab.js(p, "return window.__chamadasIsca")).longValue(), "nenhuma função da página executada");
        String json = map.toString();
        assertFalse(json.contains("LAB-FICTICIA"), "chave de site (mesmo fictícia) nunca exportada");
        assertTrue(json.contains("[redacted]"));
        assertTrue(json.contains("k=REDACTED"), "nomes de parâmetros mantidos, valores redigidos");
        evidence("P003-013-pagemap-protection.json", map.get("protection"));
    }

    @Test
    @DisplayName("P003-014 — Recomendações: nenhuma sugere evasão; tipos dentro do conjunto permitido (§15) em todas as fixtures")
    void recommendations() {
        Map<String, Object> all = new LinkedHashMap<>();
        List<String> allowed = List.of("HUMAN_INTERVENTION", "AUTHORIZED_API", "ENVIRONMENT_CONFIGURATION", "MANUAL_VALIDATION", "UNKNOWN_PROTECTION",
                "NO_ACTION_REQUIRED");
        for (String f : List.of("p01-recaptcha", "p02-hcaptcha", "p03-turnstile", "p04-challenge-page", "p05-unknown", "p06-false-positive",
                "p07-multiple", "p09-dynamic", "p11-block-page")) {
            Page p = page(f, -1);
            sleep(500);
            JsonNode r = diagnose(p).get("readiness");
            all.put(f, r.get("recommendations"));
            assertNoEvasion(r);
            for (JsonNode x : r.get("recommendations")) {
                assertTrue(allowed.contains(x.get("type").asText()), x.toString());
            }
            deactivate(p);
        }
        evidence("P003-014-recommendations.json", all);
    }

    @Test
    @DisplayName("P003-015 — Catálogo versionado e determinismo: mesma entrada ⇒ mesmo diagnóstico; versão e base de cada assinatura expostas")
    void catalogAndDeterminism() {
        Page p = page("p07-multiple", 0);
        JsonNode a = lab.call("protection.assess", p);
        JsonNode b = lab.call("protection.assess", p);
        assertEquals("2026.10.1", a.get("catalogVersion").asText());
        assertEquals(a.toString(), b.toString(), "determinístico");
        for (JsonNode x : a.get("assessments")) {
            assertTrue(x.get("signatureVersion").isTextual() && x.get("basis").isTextual(), x.toString());
        }
    }
}
