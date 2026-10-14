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

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NEKKORA-REAL-001 — homologação em PÁGINA REAL autorizada (Wikipedia, autorizada pela usuária para o teste).
 * Suíte opcional ({@code scripts/run-tests.ps1 -Suite real}): depende de internet e do conteúdo atual do site.
 * Somente leitura e páginas públicas; uma visita por página; nenhuma conta, formulário enviado ou desafio executado.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Real001LiveWebIT extends FrameIT {

    static final String ARTICLE = BrowserLab.REAL_ORIGIN + "/wiki/Brazil";
    static final String CREATE_ACCOUNT = BrowserLab.REAL_ORIGIN + "/wiki/Special:CreateAccount";
    static final String UNAUTHORIZED = "https://pt.wikipedia.org/wiki/Brasil";
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}");

    private JsonNode access(Page p) {
        return lab.call("access.describe", p);
    }

    @Test
    @Order(1)
    @DisplayName("RW-01 — Wikipedia autorizada: navegar não captura; ativar inventaria, classifica, localiza e exporta sem dados sensíveis")
    void authorizedRealPage() {
        Page p = openUrl(ARTICLE);
        JsonNode acc = access(p);
        assertEquals("AUTHORIZED", acc.get("kind").asText(), acc.toString());
        assertEquals(0, diagnostics(p).get("observers").asInt(), "nenhuma captura antes da ativação");
        activate(p);
        JsonNode s = session(p);
        assertTrue(s.get("nodes").asInt() > 500, "página real inventariada: " + s.get("nodes"));
        JsonNode heading = select(p, "#firstHeading");
        JsonNode search = select(p, "#searchInput");
        assertLiveAgreement(heading);
        assertLiveAgreement(search);
        JsonNode export = lab.call("pagemap.export", p, "returnPageMap", true);
        JsonNode map = export.get("pageMap");
        if (map == null || map.isNull()) {
            // PageMap acima do limite de mensagens: o arquivo persistido (exportação explícita) é a fonte completa.
            assertEquals("TOO_LARGE_FOR_MESSAGE", export.get("pageMapOmitted").asText());
            try {
                map = M.readTree(java.nio.file.Path.of(export.get("path").asText()).toFile());
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
        String json = map.toString();
        Matcher m = EMAIL.matcher(json);
        String emailFound = m.find() ? m.group() : null;
        JsonNode g = graph(p);
        JsonNode d = diagnose(p);
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("access", acc);
        ev.put("session", s);
        ev.put("heading", heading.at("/inspection/classification"));
        ev.put("headingLocators", heading.at("/inspection/locators"));
        ev.put("search", search.at("/inspection/classification"));
        ev.put("searchLocators", search.at("/inspection/locators"));
        ev.put("elements", map.get("elements").size());
        ev.put("exportBytes", export.get("bytes"));
        ev.put("pageMapOmitted", export.path("pageMapOmitted").asText(null));
        ev.put("documentUrl", map.at("/document/url"));
        ev.put("frameGraphCounts", g.get("counts"));
        ev.put("protection", d.get("protection"));
        ev.put("readiness", d.get("readiness"));
        ev.put("emailPatternInExport", emailFound);
        evidence("RW-01-wikipedia-article.json", ev);
        screenshot(p, "RW-01-wikipedia-article");
        assertEquals("HEADING", heading.at("/inspection/classification/kind").asText(), heading.at("/inspection/classification").toString());
        assertTrue(map.get("elements").size() > 500);
        assertEquals(null, emailFound, "nenhum e-mail no PageMap exportado");
        assertFalse(map.at("/document/url").asText().contains("?"), "URL do artigo sem consulta: " + map.at("/document/url"));
        if ("NOT_OBSERVED".equals(d.at("/protection/status").asText())) {
            assertTrue(d.at("/protection/limitations").toString().contains("não comprova ausência"), "nunca afirma ausência de proteção");
        }
    }

    @Test
    @Order(2)
    @DisplayName("RW-02 — Outro subdomínio da Wikipedia sem autorização: indisponível com erro explícito, sem content script")
    void unauthorizedRealPage() {
        Page p = openWithoutContent(UNAUTHORIZED);
        JsonNode acc = access(p);
        evidence("RW-02-wikipedia-unauthorized.json", acc);
        assertEquals("PERMISSION_REQUIRED", acc.get("reason").asText(), acc.toString());
        assertEquals("https://pt.wikipedia.org/*", acc.get("pattern").asText());
        assertEquals("ORIGIN_NOT_ALLOWED", assertThrows(BrowserLab.CallException.class, () -> lab.call("inspection.activate", p)).code);
        assertFalse(lab.callRaw("page.diagnostics", Map.of("tabId", p.tabId())).path("ok").asBoolean());
    }

    @Test
    @Order(3)
    @DisplayName("RW-03 — Atualização dinâmica real (cabeçalho fixo ao rolar) aplicada por deltas, sem recaptura completa")
    void dynamicUpdate() {
        Page p = openUrl(ARTICLE);
        // Consumidor sem inspetor visual: a rolagem é feita pelo teste (como um usuário), a extensão só observa.
        lab.call("session.acquire", p, "consumer", "rw-observer");
        waitSessionActive(p);
        int full = session(p).get("fullCaptures").asInt();
        int deltas = session(p).get("deltasApplied").asInt();
        lab.js(p, "window.scrollTo(0, document.body.scrollHeight / 3)");
        sleep(1500);
        lab.js(p, "window.scrollTo(0, document.body.scrollHeight / 2)");
        JsonNode sync = sync(p);
        JsonNode s = session(p);
        evidence("RW-03-wikipedia-dynamic.json", Map.of("sync", sync, "session", s, "deltasBefore", deltas));
        assertEquals("SYNCHRONIZED", sync.get("syncState").asText());
        assertEquals(full, s.get("fullCaptures").asInt(), "sem recaptura completa");
        lab.call("session.release", p, "consumer", "rw-observer");
    }

    @Test
    @Order(4)
    @DisplayName("RW-04 — Criação de conta (possível verificação humana): diagnóstico passivo; se servida por outra origem, recusa explícita")
    void protectionObservation() {
        Page p = openWithoutContent(CREATE_ACCOUNT);
        if (!p.url().startsWith(BrowserLab.REAL_ORIGIN + "/")) {
            // A criação de conta é servida por OUTRA origem (login central da Wikimedia): a autorização de
            // en.wikipedia.org não se estende a ela — nenhum content script, nenhuma inspeção, erro explícito.
            JsonNode acc = access(p);
            evidence("RW-04-wikipedia-create-account-redirect.json", Map.of("finalOrigin", acc.path("origin").asText(), "access", acc));
            screenshot(p, "RW-04-wikipedia-create-account");
            assertEquals("PERMISSION_REQUIRED", acc.get("reason").asText(), acc.toString());
            assertEquals("ORIGIN_NOT_ALLOWED", assertThrows(BrowserLab.CallException.class, () -> lab.call("inspection.activate", p)).code);
            return;
        }
        BrowserLab.waitUntil(Duration.ofSeconds(20), () -> lab.callRaw("page.diagnostics", Map.of("tabId", p.tabId())).path("ok").asBoolean(false),
                "content script pronto");
        activate(p);
        JsonNode d = diagnose(p);
        evidence("RW-04-wikipedia-create-account-protection.json", Map.of("protection", d.get("protection"), "readiness", d.get("readiness"),
                "frameGraphCounts", graph(p).get("counts")));
        screenshot(p, "RW-04-wikipedia-create-account");
        String text = d.toString().toLowerCase();
        assertFalse(text.contains("bypass") || text.contains("contornar"), "nenhuma recomendação de contorno");
        if ("NOT_OBSERVED".equals(d.at("/protection/status").asText())) {
            assertTrue(d.at("/protection/limitations").toString().contains("não comprova ausência"));
        }
    }

    @Test
    @Order(5)
    @DisplayName("RW-05 — Desativação/reativação e revogação de acesso na página real")
    void deactivateAndRevoke() {
        Page p = openUrl(ARTICLE);
        activate(p);
        assertEquals("INACTIVE", deactivate(p).get("state").asText());
        assertEquals(0, diagnostics(p).get("observers").asInt());
        activate(p);
        try {
            lab.siteAccess(BrowserLab.REAL_ORIGIN + "/*", false);
            BrowserLab.waitUntil(Duration.ofSeconds(15), () -> "INACTIVE".equals(inspection(p).get("state").asText()), "inspeção encerrada");
            evidence("RW-05-wikipedia-revocation.json", Map.of("inspection", inspection(p), "lastEnded", lastEnded(p), "access", access(p)));
            assertEquals("PERMISSION_REVOKED", lastEnded(p).get("reason").asText());
            assertEquals("PERMISSION_REQUIRED", access(p).get("reason").asText());
        } finally {
            lab.siteAccess(BrowserLab.REAL_ORIGIN + "/*", true);
        }
    }
}
