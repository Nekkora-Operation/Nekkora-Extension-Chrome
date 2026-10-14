package com.nekkora.extension.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.nekkora.extension.it.support.BrowserLab;
import com.nekkora.extension.it.support.BrowserLab.Page;
import com.nekkora.extension.it.support.FrameIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NEKKORA-REAL-001 — páginas http/https EXTERNAS autorizadas, sem depender do laboratório 127.0.0.1/localhost.
 * Origens "externas" de teste ({@code site-a/site-b/site-x.nekkora.test}) resolvidas para 127.0.0.1 pelo Chrome:
 * determinísticas e sem tráfego para fora da máquina. A homologação em página real (Wikipedia) está em
 * {@link Real001LiveWebIT} (suíte opcional, depende de internet).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Real001PilotIT extends FrameIT {

    static final String SITE = "/lab/real001/site.html";
    /** URL do documento com segredos FICTÍCIOS em consulta e fragmento. */
    static final String SITE_QUERY = SITE + "?session=SESS-7f3a9c2e1b44&token=TKN-SECRETO-123&aba=inicio#access_token=AT-SECRETO-456";
    /** Valores plantados na página que NUNCA podem aparecer em dados enviados, mantidos ou exportados. */
    static final List<String> PLANTED = List.of("SESS-7f3a9c2e1b44", "TKN-SECRETO-123", "AT-SECRETO-456", "CODE-SECRETO-789", "SIG-SECRETA-0a1b2c",
            "DTK-0a1b2c3d4e5f6a7b8c9d0e1f", "KEY-SECRETA-321", "SID-SECRETO-654", "REF-SECRETO-111", "ana.silva", "exemplo.com", "123.456.789-09",
            "98765-4321", "4111 1111 1111 1111", "550e8400-e29b-41d4-a716-446655440000",
            // revisão do PR #3 (P1): atributos personalizados
            "hunter2", "RST-SECRETO-222", "CFG-SECRETA-333", "PIN-SECRETO-444", "XST-SECRETO-555");

    private String siteA() {
        return lab.externalUrl(BrowserLab.SITE_A, SITE_QUERY);
    }

    private static String pattern(String host) {
        return "http://" + host + "/*";
    }

    private Page openSiteActive() {
        Page p = openUrl(siteA());
        activate(p);
        waitGraph(p, g -> {
            JsonNode b = frameTitled(g, "Parceiro autorizado");
            return b != null && "INSPECTED".equals(b.path("status").asText()) && frameTitled(g, "Parceiro sem autorização") != null;
        }, "iframes do parceiro inventariados");
        return p;
    }

    private JsonNode access(Page p) {
        return lab.call("access.describe", p);
    }

    private String nodeByName(Page p, String accessibleName) {
        for (JsonNode e : exportMap(p).get("elements")) {
            if (accessibleName.equals(e.at("/text/accessibleName").asText())) {
                return e.get("nodeId").asText();
            }
        }
        throw new AssertionError("elemento '" + accessibleName + "' não exportado");
    }

    private static List<String> leaks(String text) {
        List<String> out = new ArrayList<>();
        for (String s : PLANTED) {
            if (text.contains(s)) {
                out.add(s);
            }
        }
        return out;
    }

    @Test
    @Order(1)
    @DisplayName("R001-01 — Página externa autorizada: acesso AUTHORIZED, navegar não captura; ativar inspeciona com as garantias anteriores")
    void authorizedExternalPage() {
        Page p = openUrl(siteA());
        JsonNode acc = access(p);
        assertEquals(true, acc.get("allowed").asBoolean(), acc.toString());
        assertEquals("AUTHORIZED", acc.get("kind").asText());
        assertEquals("http://" + BrowserLab.SITE_A + ":" + BrowserLab.PORT, acc.get("origin").asText());
        assertTrue(acc.at("/registration/matches").toString().contains(pattern(BrowserLab.SITE_A)), "content scripts registrados para a origem autorizada");
        assertTrue(acc.at("/registration/excludeMatches").toString().contains("127.0.0.1"), "laboratório excluído do registro (sem injeção dupla)");
        // Navegar para o site NÃO inicia captura: content script presente, ocioso.
        JsonNode d = diagnostics(p);
        assertEquals(0, d.get("observers").asInt(), "nenhum observador antes da ativação");
        assertEquals(0, d.get("listeners").asInt());
        assertTrue(session(p).isMissingNode() || session(p).isNull(), "sem sessão de captura antes da ativação");
        assertFalse(d.get("url").asText().contains("TKN-SECRETO"), "URL do content script já sanitizada: " + d.get("url"));

        activate(p);
        JsonNode sel = select(p, "#salvar");
        evidence("R001-01-authorized-selection.json", Map.of("access", acc, "selection", sel.get("inspection")));
        assertEquals("BUTTON", sel.at("/inspection/classification/kind").asText());
        assertEquals("Salvar preferências", sel.at("/inspection/classification/name/name").asText());
        assertLiveAgreement(sel);
        JsonNode s = session(p);
        assertEquals("ACTIVE", s.get("status").asText());
        assertFalse(s.get("url").asText().contains("SECRETO"), "URL da sessão sanitizada: " + s.get("url"));
        assertTrue(s.get("url").asText().contains("aba=inicio"), "parâmetros não sensíveis preservados para a análise");
        screenshot(p, "R001-01-authorized-page");
    }

    @Test
    @Order(2)
    @DisplayName("R001-02 — Página externa sem autorização, página interna e esquema não suportado: indisponíveis com erro explícito")
    void unauthorizedPages() throws IOException {
        Page x = openWithoutContent(lab.externalUrl(BrowserLab.SITE_X, SITE));
        JsonNode acc = access(x);
        assertEquals("PERMISSION_REQUIRED", acc.get("reason").asText(), acc.toString());
        assertEquals("http://" + BrowserLab.SITE_X + "/*", acc.get("pattern").asText(), "padrão por site a solicitar ao usuário");
        BrowserLab.CallException e = assertThrows(BrowserLab.CallException.class, () -> lab.call("inspection.activate", x));
        assertEquals("ORIGIN_NOT_ALLOWED", e.code);
        assertTrue(e.getMessage().contains("não está autorizado"), e.getMessage());
        assertEquals("ERROR", inspection(x).get("state").asText());
        assertFalse(inspection(x).get("active").asBoolean(), "nunca indica inspeção ativa sem permissão");
        assertFalse(lab.callRaw("page.diagnostics", Map.of("tabId", x.tabId())).path("ok").asBoolean(), "nenhum content script na origem sem permissão");

        Page internal = openWithoutContent("chrome://version");
        assertEquals("BROWSER_INTERNAL", access(internal).get("reason").asText());
        assertEquals("ORIGIN_NOT_ALLOWED", assertThrows(BrowserLab.CallException.class, () -> lab.call("inspection.activate", internal)).code);

        Path file = Files.createTempFile("nekkora-real001-", ".html");
        Files.writeString(file, "<!doctype html><title>arquivo local</title><p>arquivo</p>", StandardCharsets.UTF_8);
        Page local = openWithoutContent(file.toUri().toString());
        assertEquals("UNSUPPORTED_SCHEME", access(local).get("reason").asText());
        evidence("R001-02-unauthorized.json", Map.of("siteX", acc, "chromeInternal", access(internal), "file", access(local),
                "activationError", Map.of("code", e.code, "message", e.getMessage())));
    }

    @Test
    @Order(3)
    @DisplayName("R001-03 — Iframe de outra origem SEM permissão: PERMISSION_DENIED, conteúdo não inventariado, permissão não ampliada")
    void frameWithoutPermission() {
        Page p = openSiteActive();
        JsonNode g = graph(p);
        JsonNode x = requireFrame(g, "Parceiro sem autorização");
        evidence("R001-03-frame-without-permission.json", x);
        assertEquals("PERMISSION_DENIED", x.get("status").asText(), x.toString());
        assertEquals(0, x.path("elementCount").asInt());
        assertEquals("NO_PERMISSION", x.at("/browser/inspection").asText());
        JsonNode acc = access(p);
        assertFalse(acc.get("authorized").toString().contains(BrowserLab.SITE_X), "a autorização da página não se estende ao frame");
        Page probe = openWithoutContent(lab.externalUrl(BrowserLab.SITE_X, "/lab/real001/partner.html"));
        assertEquals("PERMISSION_REQUIRED", access(probe).get("reason").asText());
    }

    @Test
    @Order(4)
    @DisplayName("R001-04 — Iframe de outra origem AUTORIZADA: anexado pelo próprio content script, trajetória e reencontro")
    void frameWithPermission() {
        Page p = openSiteActive();
        JsonNode b = requireFrame(graph(p), "Parceiro autorizado");
        assertEquals("ATTACHED", b.at("/browser/inspection").asText(), b.toString());
        assertFalse(b.toString().contains("SID-SECRETO"), "URL do frame sanitizada no grafo");
        String nodeId = nodeByName(p, "Aceitar oferta");
        JsonNode sel = selectNode(p, nodeId);
        JsonNode path = sel.at("/inspection/accessPath");
        evidence("R001-04-authorized-frame-path.json", Map.of("frame", b, "path", path));
        assertEquals(List.of("MAIN_DOCUMENT", "IFRAME", "SHADOW_ROOT", "ELEMENT"), stepTypes(path));
        JsonNode r = resolve(p, sel.at("/identity/targetId").asText());
        assertEquals("RESOLVED", r.get("status").asText(), r.toString());
    }

    @Test
    @Order(5)
    @DisplayName("R001-05 — Navegação SPA e atualização dinâmica no site externo: deltas, sem recaptura completa, rota sanitizada")
    void spaAndDynamicUpdate() {
        Page p = openSiteActive();
        int full = session(p).get("fullCaptures").asInt();
        lab.clearEvents();
        lab.js(p, "window.irPara('pedidos')");
        lab.js(p, "window.adicionarPedido()");
        BrowserLab.waitUntil(Duration.ofSeconds(15), () -> lab.events("navigation").size() > 0, "evento de navegação SPA");
        JsonNode sel = select(p, "#pedidos tbody tr:nth-child(3) button");
        assertEquals("Ver A-102", sel.at("/inspection/classification/name/name").asText());
        JsonNode s = session(p);
        assertEquals(full, s.get("fullCaptures").asInt(), "SPA e mutação aplicadas por deltas");
        assertTrue(s.get("deltasApplied").asInt() > 0);
        JsonNode nav = lab.events("navigation").get(0).get("data");
        evidence("R001-05-spa.json", Map.of("navigation", nav, "session", s));
        assertEquals("SPA_ROUTE", nav.get("kind").asText());
        assertTrue(nav.get("url").asText().contains("ref=REDACTED"), "rota com parâmetro sensível redigido: " + nav.get("url"));
        assertTrue(leaks(nav.toString()).isEmpty());
    }

    @Test
    @Order(6)
    @DisplayName("R001-06 — Shadow DOM aberto em página externa: elemento inventariado com trajetória SHADOW_ROOT")
    void openShadowDom() {
        Page p = openSiteActive();
        JsonNode sel = lab.call("page.select", p, "css", "#upgrade", "scope", Map.of("chain", List.of(Map.of("kind", "SHADOW_HOST", "css", "#card"))));
        assertEquals("Mudar plano", sel.at("/inspection/classification/name/name").asText());
        assertEquals(List.of("MAIN_DOCUMENT", "SHADOW_ROOT", "ELEMENT"), stepTypes(sel.at("/inspection/accessPath")));
        evidence("R001-06-shadow.json", sel.at("/inspection/accessPath"));
    }

    @Test
    @Order(7)
    @DisplayName("R001-07 — Tecnologia de proteção observável em origem externa autorizada: diagnóstico passivo preservado")
    void protectionOnExternalOrigin() {
        Page p = openUrl(lab.externalUrl(BrowserLab.SITE_A, PROTECTION + "p01-recaptcha.html"));
        activate(p);
        JsonNode d = diagnose(p);
        JsonNode a = assessment(d.get("protection"), "RECAPTCHA_V2");
        assertNotNull(a, d.get("protection").toString());
        assertEquals("DETECTED", a.get("status").asText());
        assertEquals("CHALLENGE_PRESENT", a.get("challengeState").asText());
        assertTrue(a.get("limitations").toString().contains("simulação do laboratório"));
        assertFalse(d.toString().toLowerCase().contains("bypass"), "nenhuma recomendação de contorno");
        Page fp = openUrl(lab.externalUrl(BrowserLab.SITE_A, PROTECTION + "p06-false-positive.html"));
        activate(fp);
        JsonNode none = diagnose(fp);
        assertEquals("NOT_OBSERVED", none.at("/protection/status").asText());
        assertTrue(none.at("/protection/limitations").toString().contains("não comprova ausência"));
        evidence("R001-07-protection-external.json", Map.of("recaptcha", a, "falsePositive", none.get("protection")));
    }

    @Test
    @Order(8)
    @DisplayName("R001-08 — Desativação e reativação no site externo: ouvintes/observadores removidos; nova sessão ao reativar")
    void deactivateReactivate() {
        Page p = openSiteActive();
        String first = session(p).get("sessionId").asText();
        JsonNode off = deactivate(p);
        assertEquals("INACTIVE", off.get("state").asText());
        JsonNode d = diagnostics(p);
        assertEquals(0, d.get("listeners").asInt());
        assertEquals(0, d.get("observers").asInt());
        activate(p);
        assertFalse(first.equals(session(p).get("sessionId").asText()), "nova sessão");
        assertEquals("BUTTON", select(p, "#salvar").at("/inspection/classification/kind").asText());
    }

    @Test
    @Order(9)
    @DisplayName("R001-09 — Revogação de permissão durante a sessão (página e frame): sessão encerrada, nada presumido; reconcessão restabelece")
    void revocationDuringSession() {
        Page p = openSiteActive();
        try {
            // Frame: retirar o acesso ao parceiro durante a sessão ⇒ frame deixa de ser inspecionado (sem presunção).
            lab.siteAccess(pattern(BrowserLab.SITE_B), false);
            JsonNode g = waitGraph(p, x -> {
                JsonNode b = frameTitled(x, "Parceiro autorizado");
                return b != null && "PERMISSION_DENIED".equals(b.path("status").asText());
            }, "frame revogado declarado sem permissão");
            assertEquals("ACTIVE", session(p).get("status").asText(), "a página continua autorizada");
            // Página: retirar o acesso ao site ⇒ inspeção e sessão encerradas com motivo explícito.
            lab.siteAccess(pattern(BrowserLab.SITE_A), false);
            BrowserLab.waitUntil(Duration.ofSeconds(15), () -> "INACTIVE".equals(inspection(p).get("state").asText()), "inspeção encerrada");
            JsonNode st = inspection(p);
            JsonNode ended = lastEnded(p);
            evidence("R001-09-revocation.json", Map.of("frameAfterRevoke", requireFrame(g, "Parceiro autorizado"), "inspection", st, "lastEnded", ended,
                    "access", access(p)));
            assertEquals("PERMISSION_REVOKED", st.at("/error/code").asText(), st.toString());
            assertEquals("PERMISSION_REVOKED", ended.get("reason").asText());
            assertTrue(session(p).isMissingNode() || session(p).isNull());
            assertEquals("PERMISSION_REQUIRED", access(p).get("reason").asText());
            BrowserLab.CallException e = assertThrows(BrowserLab.CallException.class, () -> lab.call("inspection.activate", p));
            assertEquals("ORIGIN_NOT_ALLOWED", e.code);
            assertEquals("OFF", diagnostics(p).at("/tracker/mode").asText(), "rastreador desligado na página revogada");
        } finally {
            lab.siteAccess(pattern(BrowserLab.SITE_A), true);
            lab.siteAccess(pattern(BrowserLab.SITE_B), true);
        }
        // Reconcessão (pelo mesmo controle do Chrome): ativar volta a funcionar.
        activate(p);
        assertEquals("ACTIVE", session(p).get("status").asText());
    }

    @Test
    @Order(10)
    @DisplayName("R001-10 — Exportação e verificação de ausência de informações sensíveis (PageMap, sessão, eventos, disco do motor)")
    void exportWithoutSensitiveData() throws IOException {
        Page p = openSiteActive();
        select(p, "#salvar");
        JsonNode export = lab.call("pagemap.export", p, "returnPageMap", true);
        JsonNode map = export.get("pageMap");
        String json = map.toString();
        Map<String, Object> found = new LinkedHashMap<>();
        found.put("pageMap", leaks(json));
        found.put("session", leaks(session(p).toString()));
        found.put("events", leaks(lab.events(null).toString()));
        found.put("frameGraph", leaks(graph(p).toString()));
        // Snapshot bruto (o que o content script envia ao motor) também sem os valores.
        JsonNode raw = lab.call("page.rawSnapshot", p);
        found.put("rawSnapshot", leaks(raw.toString()));
        // O log do motor fica bloqueado enquanto o processo do host está aberto: fecha a aba (sem sessão ativa não há
        // reconexão automática), encerra o host e lê TUDO o que foi gravado em disco.
        lab.close(p);
        StringBuilder disk = new StringBuilder();
        StringBuilder automatic = new StringBuilder(); // tudo o que o motor gravou SEM ser a exportação explícita
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (true) {
            lab.call("host.disconnect", Map.of());
            sleep(500);
            disk.setLength(0);
            automatic.setLength(0);
            try (Stream<Path> files = Files.walk(lab.hostData())) {
                for (Path f : (Iterable<Path>) files::iterator) {
                    if (Files.isRegularFile(f) && Files.size(f) < 50_000_000) {
                        String content = Files.readString(f, StandardCharsets.UTF_8);
                        disk.append(content);
                        if (!lab.hostData().relativize(f).startsWith("pagemaps")) {
                            automatic.append(content);
                        }
                    }
                }
                break;
            } catch (IOException e) {
                if (System.nanoTime() > deadline) {
                    throw e;
                }
            }
        }
        found.put("hostDataDir", leaks(disk.toString()));
        lab.call("host.ping", Map.of()); // reabre o motor para os testes seguintes
        // Marcas da política presentes: os dados existem, mas redigidos/mascarados.
        Map<String, Object> marks = new LinkedHashMap<>();
        marks.put("documentUrl", map.at("/document/url").asText());
        marks.put("frames", map.at("/document/frames"));
        marks.put("requests", map.at("/document/requests").isMissingNode() ? map.at("/snapshot") : map.at("/document/requests"));
        evidence("R001-10-sensitive-data-check.json", Map.of("leaks", found, "marks", marks, "exportPath", export.path("path").asText(),
                "planted", PLANTED));
        for (Map.Entry<String, Object> e : found.entrySet()) {
            assertEquals(List.of(), e.getValue(), "dados sensíveis em " + e.getKey());
        }
        assertTrue(json.contains("[email]") && json.contains("[cpf]") && json.contains("[telefone]") && json.contains("[cartão]"), "textos mascarados");
        assertTrue(json.contains("token=REDACTED") && json.contains("code=REDACTED"), "parâmetros sensíveis redigidos");
        assertTrue(json.contains(":redacted"), "segmento de caminho com identificador mascarado");
        JsonNode config = null;
        for (JsonNode e : map.get("elements")) {
            if ("config".equals(e.at("/attributes/id").asText())) {
                config = e.get("attributes");
            }
        }
        if (config != null) { // elemento exportado: atributos redigidos, atributo neutro preservado
            assertEquals("[redacted]", config.path("data-password").asText());
            assertEquals("escuro", config.path("data-tema").asText());
        }
        assertEquals(List.of(), found.get("rawSnapshot"), "dados sensíveis no snapshot bruto");
        boolean sawConfig = false;
        for (JsonNode n : raw.get("nodes")) {
            if ("config".equals(n.at("/attributes/id").asText())) {
                sawConfig = true;
                assertEquals("[redacted]", n.at("/attributes/data-password").asText());
                assertEquals("https://example.test/reset?token=REDACTED", n.at("/attributes/data-reset-url").asText());
                assertTrue(n.at("/attributes/data-config").asText().contains("apiKey=REDACTED") && n.at("/attributes/data-config").asText().contains("\"pin\":\"REDACTED\""),
                        n.at("/attributes/data-config").asText());
                assertEquals("[redacted]", n.at("/attributes/x-session-token").asText());
                assertEquals("escuro", n.at("/attributes/data-tema").asText());
            }
        }
        assertTrue(sawConfig, "elemento #config capturado");
        for (JsonNode f : map.at("/document/frames")) {
            assertFalse(f.path("url").asText().contains("SID-SECRETO"), "document.frames[].url sem a URL completa: " + f);
        }
        assertFalse(automatic.toString().contains(BrowserLab.SITE_A), "nada do site externo persistido automaticamente no disco do motor (além da exportação explícita)"
                + " — exportação: " + export.path("path").asText());
    }

    @Test
    @Order(11)
    @DisplayName("R001-11 — Página grande externa: rolagem e classe trocada no body (padrão da Wikipedia) sem recaptura completa; visibilidade correta")
    void largePageScrollAndBodyClass() {
        Page p = openUrl(lab.externalUrl(BrowserLab.SITE_A, "/generated/large?n=8000"));
        activate(p);
        lab.js(p, "const st = document.createElement('style'); st.textContent = 'body.nk-fixo #nk-alvo { display: none }'; document.head.append(st);"
                + "const b = document.createElement('button'); b.id = 'nk-alvo'; b.textContent = 'Alvo'; document.body.prepend(b);");
        sync(p);
        assertTrue(hasCondition(select(p, "#nk-alvo"), "VISIBLE"), "alvo visível antes");
        int full = session(p).get("fullCaptures").asInt();
        lab.js(p, "window.scrollTo(0, document.body.scrollHeight / 2)");
        sleep(800);
        lab.js(p, "document.body.classList.add('nk-fixo')");
        JsonNode st = sync(p);
        JsonNode s = session(p);
        JsonNode d = diagnostics(p);
        JsonNode sel = select(p, "#nk-alvo");
        evidence("R001-11-large-page-scroll.json", Map.of("sync", st, "session", s, "tracker", d.get("tracker"),
                "targetConditions", sel.at("/inspection/classification/conditions")));
        assertEquals("SYNCHRONIZED", st.get("syncState").asText());
        assertEquals(full, s.get("fullCaptures").asInt(), "rolagem + classe no body por deltas, sem recaptura completa");
        assertTrue(d.at("/tracker/geometryUpdates").asInt() > 1000, "geometria enviada em atualizações compactas: " + d.get("tracker"));
        assertTrue(d.at("/tracker/largeSubtreeScans").asInt() >= 1);
        assertTrue(hasCondition(sel, "HIDDEN") && !hasCondition(sel, "VISIBLE"),
                "descendente oculto pela classe do body é atualizado: " + sel.at("/inspection/classification/conditions"));
    }
}
