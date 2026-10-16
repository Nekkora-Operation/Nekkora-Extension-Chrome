package com.nekkora.extension.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.nekkora.extension.it.support.BrowserLab.Page;
import com.nekkora.extension.it.support.FrameIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cenários integrados da CHROME-003 (§29). Cada cenário verifica, na mesma página: captura, hierarquia,
 * identificação, família, localizadores, trajetória, reencontro, diagnóstico, evidências e limitações.
 */
class Chrome003IntegratedIT extends FrameIT {

    /** Expectativas de um cenário. */
    private record Expect(String kind, String name, List<String> steps, int documents, String readiness, String protection, boolean resolvable) {
        Expect(String kind, String name, List<String> steps, int documents, String readiness, String protection) {
            this(kind, name, steps, documents, readiness, protection, true);
        }
    }

    private Map<String, Object> run(String id, Page p, String nodeId, Expect e) {
        Map<String, Object> out = new LinkedHashMap<>();
        // 1. captura
        JsonNode s = session(p);
        assertEquals("ACTIVE", s.get("status").asText());
        assertTrue(s.get("nodes").asInt() > 0);
        out.put("captura", Map.of("nodes", s.get("nodes"), "fullCaptures", s.get("fullCaptures"), "deltas", s.get("deltasApplied")));
        // 2. hierarquia
        JsonNode g = graph(p);
        assertEquals(e.documents(), g.at("/counts/documents").asInt(), titles(g).toString());
        out.put("hierarquia", Map.of("counts", g.get("counts"), "maxDepth", g.get("maxDepth"), "frames", titles(g)));
        // 3. identificação e 4. família
        JsonNode sel = selectNode(p, nodeId);
        JsonNode c = sel.at("/inspection/classification");
        assertEquals(e.kind(), c.get("kind").asText(), c.toString());
        // CHROME-SECURITY-001: o nome é o resultado EXATO da política sobre o texto da página
        assertEquals(com.nekkora.extension.it.support.Policy.name(e.name()), c.at("/name/name").asText());
        JsonNode fam = sel.at("/inspection/family");
        assertNotNull(fam);
        out.put("identificacao", Map.of("kind", c.get("kind"), "name", c.at("/name/name"), "targetId", sel.at("/identity/targetId"),
                "frameChain", sel.at("/identity/frameChain")));
        out.put("familia", Map.of("form", fam.path("form"), "region", fam.path("region"), "scope", fam.path("scopeChain")));
        // 5. localizadores (escopados ao documento do elemento)
        JsonNode primary = sel.at("/inspection/locators/primary");
        assertEquals(1, primary.get("matchCount").asInt(), primary.toString());
        out.put("localizadores", Map.of("primary", primary, "verification", sel.get("verification")));
        // 6. trajetória
        JsonNode path = sel.at("/inspection/accessPath");
        assertEquals(e.steps(), stepTypes(path));
        assertTrue(path.get("accessible").asBoolean());
        out.put("trajetoria", path);
        // 7. reencontro
        JsonNode r = resolve(p, sel.at("/identity/targetId").asText());
        if (e.resolvable()) {
            assertEquals("RESOLVED", r.get("status").asText(), r.toString());
            assertEquals(nodeId, r.get("resolvedNodeId").asText());
        } else {
            // o que distinguia o alvo era só texto da página: ambiguidade DECLARADA, nunca outro elemento escolhido
            assertTrue(java.util.Set.of("AMBIGUOUS", "INSUFFICIENT_EVIDENCE").contains(r.get("status").asText()), r.toString());
            assertTrue(r.path("resolvedNodeId").isNull() || nodeId.equals(r.path("resolvedNodeId").asText()), r.toString());
        }
        out.put("reencontro", Map.of("status", r.get("status"), "score", r.get("score"), "evidence", r.get("evidence")));
        // 8. diagnóstico
        JsonNode d = diagnose(p);
        assertEquals(e.readiness(), d.at("/readiness/status").asText(), d.at("/readiness").toString());
        assertEquals(e.protection(), d.at("/protection/status").asText());
        out.put("diagnostico", Map.of("protection", d.get("protection"), "readiness", d.get("readiness"), "timings", d.get("timings")));
        // 9. evidências e 10. limitações
        assertFalse(d.at("/readiness/basis").isEmpty() && d.at("/readiness/obstacles").isEmpty(), "diagnóstico sem base");
        assertFalse(d.at("/readiness/limitations").isEmpty(), "limitações sempre declaradas");
        out.put("limitacoes", d.at("/readiness/limitations"));
        evidence(id + "-integrated.json", out);
        return out;
    }

    @Test
    @DisplayName("I003-01 — Financeiro → Pagamentos → Shadow DOM → Confirmar pagamento")
    void financeiroPagamentos() {
        Page p = openActive(FRAMES, 9);
        String id = idIn(p, "button[type=submit]", "FRAME", "#f-financeiro", "FRAME", "#f-pagamentos", "SHADOW_HOST", "nk-pagamento");
        run("I003-01", p, id, new Expect("BUTTON", "Confirmar pagamento", List.of("MAIN_DOCUMENT", "IFRAME", "IFRAME", "SHADOW_ROOT", "ELEMENT"),
                10, "REQUIRES_SPECIAL_HANDLING", "NOT_OBSERVED"));
    }

    @Test
    @DisplayName("I003-02 — Filiais idênticas: Aprovar da Filial Sul")
    void filiais() {
        Page p = openActive(FRAMES, 9);
        // CHROME-SECURITY-001: as filiais só se distinguiam por "Filial Sul"/"#filial-sul" (fora do vocabulário)
        run("I003-02", p, idIn(p, "button", "FRAME", "#filial-sul iframe"), new Expect("BUTTON", "Aprovar",
                List.of("MAIN_DOCUMENT", "IFRAME", "ELEMENT"), 10, "REQUIRES_SPECIAL_HANDLING", "NOT_OBSERVED", false));
    }

    @Test
    @DisplayName("I003-03 — Shadow DOM contendo iframe: Enviar mensagem do widget de suporte")
    void widgetSuporte() {
        Page p = openActive(FRAMES, 9);
        run("I003-03", p, idIn(p, "#enviar", "SHADOW_HOST", "nk-suporte", "FRAME", "#f-widget"), new Expect("BUTTON", "Enviar mensagem",
                List.of("MAIN_DOCUMENT", "SHADOW_ROOT", "IFRAME", "ELEMENT"), 10, "REQUIRES_SPECIAL_HANDLING", "NOT_OBSERVED"));
    }

    @Test
    @DisplayName("I003-04 — Outra origem permitida: Relatórios → Gráficos → Shadow DOM → Exportar gráfico")
    void relatorios() {
        Page p = openActive(ORIGINS, 5);
        JsonNode g = waitGraph(p, x -> onlyChildFrame(x, frameTitled(p, x, "Relatórios")) != null
                && "INSPECTED".equals(onlyChildFrame(x, frameTitled(p, x, "Relatórios")).get("status").asText()), "Gráficos");
        String id = onlyShadowButton(exportMap(p), onlyChildFrame(g, requireFrame(p, g, "Relatórios")).get("frameId").asText());
        assertNotNull(id);
        // CHROME-SECURITY-001: o único traço próprio do botão era o nome "Exportar gráfico" (fora do vocabulário)
        run("I003-04", p, id, new Expect("BUTTON", "Exportar gráfico", List.of("MAIN_DOCUMENT", "IFRAME", "IFRAME", "SHADOW_ROOT", "ELEMENT"),
                6, "REQUIRES_SPECIAL_HANDLING", "NOT_OBSERVED", false));
    }

    @Test
    @DisplayName("I003-05 — SPA: Salvar do iframe Cadastro após re-renderização (frame reconstruído)")
    void spa() {
        Page p = openActive(DYNAMIC, 5);
        String before = requireFrame(p, graph(p), "Cadastro").get("hostNodeId").asText();
        act(p, "spa");
        waitGraph(p, x -> frameTitled(p, x, "Cadastro") != null && !before.equals(frameTitled(p, x, "Cadastro").get("hostNodeId").asText())
                && "INSPECTED".equals(frameTitled(p, x, "Cadastro").get("status").asText()), "Cadastro recriado");
        run("I003-05", p, idIn(p, "button[type=submit]", "FRAME", "#spa-root iframe"), new Expect("BUTTON", "Salvar",
                List.of("MAIN_DOCUMENT", "IFRAME", "ELEMENT"), 6, "MANAGEABLE", "NOT_OBSERVED"));
    }

    @Test
    @DisplayName("I003-06 — about:blank preenchido pelo pai: Guardar nota")
    void aboutBlank() {
        Page p = openActive(DYNAMIC, 5);
        run("I003-06", p, idIn(p, "button", "FRAME", "#f-branco"), new Expect("BUTTON", "Guardar nota",
                List.of("MAIN_DOCUMENT", "IFRAME", "ELEMENT"), 6, "MANAGEABLE", "NOT_OBSERVED", false)); // nome fora do vocabulário, sem id
    }

    @Test
    @DisplayName("I003-07 — Formulário com reCAPTCHA (simulado): Enviar; verificação humana diagnosticada")
    void recaptchaForm() {
        Page p = openActive(PROTECTION + "p01-recaptcha.html", 1);
        Map<String, Object> out = run("I003-07", p, nodeIdOf(p, "#contato button[type=submit]"), new Expect("BUTTON", "Enviar",
                List.of("MAIN_DOCUMENT", "ELEMENT"), 2, "REQUIRES_SPECIAL_HANDLING", "OBSERVED"));
        assertTrue(out.get("diagnostico").toString().contains("HUMAN_VERIFICATION"));
    }

    @Test
    @DisplayName("I003-08 — Turnstile dentro de dois níveis de iframe: Confirmar pagamento no mesmo documento da proteção")
    void turnstileNested() {
        Page p = openActive(PROTECTION + "p08-in-iframe.html", 2);
        String id = idIn(p, "button[type=submit]", "FRAME", "#f-pagamento", "FRAME", "#f-confirmacao");
        run("I003-08", p, id, new Expect("BUTTON", "Confirmar pagamento", List.of("MAIN_DOCUMENT", "IFRAME", "IFRAME", "ELEMENT"),
                3, "REQUIRES_SPECIAL_HANDLING", "OBSERVED"));
        JsonNode loc = assessment(diagnose(p).get("protection"), "TURNSTILE").get("location");
        assertEquals(accessPath(p, id).at("/steps/3/documentId").asText(), loc.get("documentId").asText(), "proteção e alvo no mesmo documento");
    }

    @Test
    @DisplayName("I003-09 — Página de desafio (simulada): caixa de verificação identificada; intervenção humana recomendada")
    void challengePage() {
        Page p = openActive(PROTECTION + "p04-challenge-page.html", 0);
        Map<String, Object> out = run("I003-09", p, nodeIdOf(p, "#caixa"), new Expect("CHECKBOX", "Verify you are human",
                List.of("MAIN_DOCUMENT", "ELEMENT"), 1, "REQUIRES_HUMAN_INTERVENTION", "OBSERVED", false)); // nome e id "caixa" retidos
        assertTrue(out.get("diagnostico").toString().contains("HUMAN_INTERVENTION"));
        assertEquals(0L, ((Number) lab.js(p, "return document.getElementById('caixa').checked ? 1 : 0")).longValue(), "nenhum desafio executado");
    }

    @Test
    @DisplayName("I003-10 — 10 iframes com profundidade 3: Confirmar 7 no frame mais profundo")
    void generatedDeep() {
        Page p = openActive("/generated/frames?count=10&depth=3", 30);
        String id = idIn(p, "button", "FRAME", "section[aria-label='Bloco 7'] iframe", "FRAME", "iframe", "FRAME", "iframe");
        run("I003-10", p, id, new Expect("BUTTON", "Confirmar 7", List.of("MAIN_DOCUMENT", "IFRAME", "IFRAME", "IFRAME", "ELEMENT"),
                31, "REQUIRES_SPECIAL_HANDLING", "NOT_OBSERVED"));
    }
}
