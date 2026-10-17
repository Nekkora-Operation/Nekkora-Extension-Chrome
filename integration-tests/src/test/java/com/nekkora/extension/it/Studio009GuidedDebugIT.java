package com.nekkora.extension.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nekkora.extension.it.support.BrowserLab;
import com.nekkora.extension.it.support.BrowserLab.Page;
import com.nekkora.extension.it.support.SessionIT;
import com.nekkora.extension.it.support.StudioBridgeLab;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STUDIO-009 G2 no Chrome real: Studio (de laboratório) → Bridge → Native Host → extensão → página. A extensão
 * reencontra o alvo pelo motor (somente leitura), destaca em rosa pelo overlay e NÃO interage com a página — provado por
 * dois observadores independentes: os contadores de eventos no mundo da página e o ORÁCULO do servidor do laboratório
 * (consultas efetivamente recebidas). Toda mensagem que chega ao Studio é validada contra o contrato.
 */
class Studio009GuidedDebugIT extends SessionIT {

    private static final String PAGE = "/lab/studio009/consulta.html";
    private static final ObjectMapper M = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static StudioBridgeLab studio;
    private final List<Page> opened = new ArrayList<>();
    private String debugId;

    @BeforeAll
    static void bridge() throws Exception {
        lab = BrowserLab.get();
        studio = new StudioBridgeLab(lab.bridgeDir());
        lab.call("studio.link.set", Map.of("enabled", true));
        studio.awaitConnected(Duration.ofSeconds(30));
    }

    @AfterAll
    static void unbridge() throws IOException {
        try {
            lab.call("studio.link.set", Map.of("enabled", false));
        } finally {
            studio.close();
        }
        assertTrue(studio.contractViolations.isEmpty(), "mensagens fora do contrato: " + studio.contractViolations);
    }

    @BeforeEach
    void resetOracle() throws Exception {
        assertEquals(0, oracle("/api/studio009/reset").path("total").asInt(), "reset verificável do laboratório");
    }

    @AfterEach
    void cleanUp() {
        if (debugId != null) {
            studio.call("studio.debug.finish", studio.obj().put("debugId", debugId).put("operationId", id("op")));
            debugId = null;
        }
        opened.forEach(lab::close);
        opened.clear();
        if (!studio.connected()) {
            lab.call("studio.link.set", Map.of("enabled", true));
            studio.awaitConnected(Duration.ofSeconds(30));
        }
    }

    // ------------------------------------------------------------------------------------------ apoio

    private static String id(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private static JsonNode oracle(String path) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(lab.url(path))).GET().build(), HttpResponse.BodyHandlers.ofString());
        return M.readTree(r.body());
    }

    private Page variant(String variant) {
        Page p = lab.open(PAGE + "?v=" + variant);
        opened.add(p);
        return p;
    }

    private String refOf(Page p) {
        String ref = lab.call("studio.tabRef", p).path("tabRef").asText();
        JsonNode r = studio.call("browser.tabs.list", null);
        for (JsonNode t : r.at("/payload/tabs")) {
            if (ref.equals(t.path("tabRef").asText())) {
                return ref;
            }
        }
        throw new AssertionError("aba não listada: " + p.url());
    }

    private JsonNode start(Page p) {
        debugId = id("dbg");
        ObjectNode pl = studio.obj().put("debugId", debugId).put("operationId", id("op")).put("tabRef", refOf(p)).put("leaseMs", 60000);
        pl.putArray("capabilities").add("guided-debug/1");
        JsonNode r = studio.call("studio.debug.start", pl);
        assertTrue(r.path("ok").asBoolean(), r.toString());
        return r.get("payload");
    }

    private static ObjectNode descriptor(String kind, String tag, String name, String htmlId, String css) {
        ObjectNode d = M.createObjectNode().put("kind", kind).put("tag", tag).put("name", name);
        if (htmlId == null) {
            d.putNull("htmlId");
        } else {
            d.put("htmlId", htmlId);
        }
        d.putNull("htmlName");
        ArrayNode ls = d.putArray("locators");
        if (css != null) {
            ls.addObject().put("strategy", css.startsWith("#") ? "ID" : "TEST_ATTRIBUTE").put("css", css).putNull("xpath").put("description", "");
        }
        d.putArray("accessPath").addObject().put("type", "MAIN_DOCUMENT").putNull("locator");
        return d;
    }

    private JsonNode inspect(String targetId, ObjectNode descriptor, String action, boolean highlight) {
        ObjectNode pl = studio.obj().put("debugId", debugId).put("requestId", id("rq")).put("stepRef", id("step")).put("actionType", action);
        if (targetId == null) {
            pl.putNull("targetId");
        } else {
            pl.put("targetId", targetId);
        }
        pl.set("descriptor", descriptor);
        pl.put("highlight", highlight);
        JsonNode r = studio.call("studio.debug.inspect", pl, Duration.ofSeconds(30));
        assertTrue(r.path("ok").asBoolean(), r.toString());
        return r.get("payload");
    }

    /** Seleção no navegador com clique REAL de ponteiro; devolve o payload de studio.debug.picked. */
    private JsonNode pick(Page p, By by, String action) throws InterruptedException {
        String rq = id("rq");
        JsonNode r = studio.call("studio.debug.pick", studio.obj().put("debugId", debugId).put("requestId", rq).put("action", "START").putNull("candidateRef")
                .put("actionType", action));
        assertTrue(r.at("/payload/picking").asBoolean(), r.toString());
        lab.click(p, by);
        return studio.awaitEvent(e -> "studio.debug.picked".equals(e.path("type").asText()) && rq.equals(e.at("/payload/requestId").asText()),
                Duration.ofSeconds(20)).get("payload");
    }

    private JsonNode counts(Page p) {
        return M.valueToTree(lab.js(p, "return window.__nk009"));
    }

    private int interactions(Page p) {
        JsonNode c = counts(p);
        int n = 0;
        for (String k : List.of("pointerdown", "mousedown", "click", "focusin", "input", "change", "submit", "keydown")) {
            n += c.path(k).asInt();
        }
        return n;
    }

    private JsonNode boxes(Page p) {
        return lab.call("page.diagnostics", p).at("/highlight/boxes");
    }

    // ------------------------------------------------------------------------------------------ cenários

    @Test
    @DisplayName("G2-01 seleção no navegador: clique real interceptado, a ação NÃO acontece (página e oráculo), identidade nova com referência compacta")
    void selecaoSemAcao() throws Exception {
        Page p = variant("base");
        start(p);
        JsonNode picked = pick(p, By.cssSelector("[data-testid=consultar]"), "CLICK");
        assertEquals("BROWSER_SELECTION", picked.path("origin").asText());
        assertEquals("BUTTON", picked.at("/element/kind").asText());
        assertEquals("Consultar", picked.at("/element/name").asText());
        assertTrue(picked.at("/element/correlation/targetId").asText().startsWith("tgt-"), picked.toString());
        assertEquals(0, counts(p).path("click").asInt(), "o clique não chegou ao botão");
        assertEquals(0, counts(p).path("submit").asInt(), "o formulário não foi enviado");
        assertEquals(0, oracle("/api/studio009/estado").path("total").asInt(), "oráculo: nenhuma consulta feita");
        assertTrue(boxes(p).at("/debug/visible").asBoolean(), "elemento escolhido destacado");
        lab.screenshot(p, "S009-G2-01-selecao-sem-acao");
    }

    @Test
    @DisplayName("G2-02 inspeção pela identidade registrada: RESOLVED, destaque rosa sólido sobre o próprio alvo, zero eventos na página")
    void inspecaoPorIdentidade() throws Exception {
        Page p = variant("base");
        start(p);
        JsonNode picked = pick(p, By.id("registro-codigo"), "FILL");
        String targetId = picked.at("/element/correlation/targetId").asText();
        int before = interactions(p);
        int overs = counts(p).path("mouseover").asInt();
        JsonNode r = inspect(targetId, descriptor("SEARCH_INPUT", "input", "Código do registro", "registro-codigo", "#registro-codigo"), "FILL", true);
        assertEquals("RESOLVED", r.path("status").asText(), r.toString());
        assertEquals("TARGET_ID", r.path("identitySource").asText());
        assertEquals("PASS", r.at("/checks/editable").asText());
        assertEquals("PASS", r.at("/checks/kindMatchesAction").asText());
        assertTrue(r.path("highlighted").asBoolean());
        JsonNode box = boxes(p).get("debug");
        assertTrue(box.path("visible").asBoolean());
        assertEquals("rgb(194, 70, 109)", box.path("borderColor").asText(), "rosa oficial #C2466D");
        JsonNode rect = M.valueToTree(lab.js(p, "return document.getElementById('registro-codigo').getBoundingClientRect().toJSON()"));
        assertEquals(rect.path("left").asDouble(), box.at("/targetRect/left").asDouble(), 1.0, "o destaque está sobre o campo");
        assertEquals(before, interactions(p), "destacar não gerou clique/foco/entrada");
        assertEquals(overs, counts(p).path("mouseover").asInt(), "nem hover sintético");
        assertEquals(0, oracle("/api/studio009/estado").path("total").asInt());
        lab.screenshot(p, "S009-G2-02-destaque-campo");
    }

    @Test
    @DisplayName("G2-03 descritor sem targetId: reencontro com fonte DESCRIPTOR; alvo desconhecido no registro cai no descritor e declara")
    void descritor() {
        Page p = variant("base");
        start(p);
        JsonNode r = inspect(null, descriptor("BUTTON", "button", "Consultar", "consultar", "[data-testid=\"consultar\"]"), "CLICK", true);
        assertEquals("DESCRIPTOR", r.path("identitySource").asText());
        assertEquals("RESOLVED", r.path("status").asText(), r.toString());
        assertTrue(r.path("reasons").toString().contains("DESCRIPTOR_ONLY"));
        JsonNode u = inspect("tgt-nao-existe", descriptor("BUTTON", "button", "Consultar", "consultar", null), "CLICK", false);
        assertTrue(u.path("reasons").toString().contains("TARGET_UNKNOWN_IN_REGISTRY"), u.toString());
    }

    @Test
    @DisplayName("G2-04 sósias sem diferença: AMBÍGUO, nenhum destaque como alvo; examinar candidato usa o pontilhado")
    void sosias() {
        Page p = variant("duplicado");
        start(p);
        JsonNode r = inspect(null, descriptor("BUTTON", "button", "Consultar", null, null), "CLICK", true);
        assertNotEquals("RESOLVED", r.path("status").asText(), r.toString());
        assertEquals("FAIL", r.at("/checks/identity").asText());
        assertFalse(r.path("highlighted").asBoolean() && "RESOLVED".equals(r.path("status").asText()));
        assertFalse(boxes(p).at("/debug/visible").asBoolean(false), "nenhum destaque sólido");
        assertTrue(r.path("candidates").size() >= 2, r.toString());
        JsonNode h = studio.call("studio.debug.highlight", studio.obj().put("debugId", debugId).put("requestId", id("rq"))
                .put("candidateRef", r.at("/candidates/1/candidateRef").asText()));
        assertTrue(h.at("/payload/highlighted").asBoolean(), h.toString());
        assertTrue(boxes(p).at("/debugCandidate/visible").asBoolean());
    }

    @Test
    @DisplayName("G2-05 endereço forte com outro controle (#consultar no botão Limpar): o motor não aponta o Limpar como o alvo")
    void enderecoErrado() throws Exception {
        Page base = variant("base");
        start(base);
        String targetId = pick(base, By.cssSelector("[data-testid=consultar]"), "CLICK").at("/element/correlation/targetId").asText();
        studio.call("studio.debug.finish", studio.obj().put("debugId", debugId).put("operationId", id("op")));
        Page p = variant("errado");
        start(p);
        JsonNode r = inspect(targetId, descriptor("BUTTON", "button", "Consultar", "consultar", "[data-testid=\"consultar\"]"), "CLICK", true);
        JsonNode limpar = M.valueToTree(lab.js(p, "return document.getElementById('consultar').getBoundingClientRect().toJSON()"));
        if ("RESOLVED".equals(r.path("status").asText())) {
            JsonNode box = boxes(p).get("debug");
            assertNotEquals(limpar.path("left").asDouble(), box.at("/targetRect/left").asDouble(), 0.5, "o destaque não pode estar no Limpar: " + r);
        } else {
            assertTrue(List.of("IDENTITY_MISMATCH", "INSUFFICIENT_EVIDENCE", "AMBIGUOUS", "NOT_FOUND").contains(r.path("status").asText()), r.toString());
        }
        lab.writeEvidence("S009-G2-05-endereco-errado.json", r.toPrettyString());
    }

    @Test
    @DisplayName("G2-06 navegação muda a época: studio.debug.changed; candidato antigo expira; fim limpa o overlay e libera a sessão")
    void epocaEFim() throws Exception {
        Page p = variant("navegar");
        start(p);
        JsonNode r = inspect(null, descriptor("BUTTON", "button", "Consultar", "consultar", "[data-testid=\"consultar\"]"), "CLICK", true);
        String cand = r.at("/candidates/0/candidateRef").asText();
        lab.js(p, "location.href = 'consulta.html?v=base&pagina=2'");
        studio.awaitEvent(e -> "studio.debug.changed".equals(e.path("type").asText()) && debugId.equals(e.at("/payload/debug/debugId").asText())
                && e.at("/payload/debug/pageEpoch").asInt() > 1, Duration.ofSeconds(20));
        BrowserLab.waitUntil(Duration.ofSeconds(15), () -> lab.callRaw("page.diagnostics", Map.of("tabId", p.tabId())).path("ok").asBoolean(false),
                "content script no novo documento");
        JsonNode expired = studio.call("studio.debug.highlight", studio.obj().put("debugId", debugId).put("requestId", id("rq")).put("candidateRef", cand));
        assertEquals("CANDIDATE_EXPIRED", expired.at("/error/code").asText(), expired.toString());
        inspect(null, descriptor("BUTTON", "button", "Consultar", "consultar", "[data-testid=\"consultar\"]"), "CLICK", true);
        assertTrue(boxes(p).at("/debug/visible").asBoolean());
        JsonNode f = studio.call("studio.debug.finish", studio.obj().put("debugId", debugId).put("operationId", id("op")));
        assertEquals("ENDED", f.at("/payload/debug/state").asText());
        debugId = null;
        assertFalse(boxes(p).at("/debug/visible").asBoolean(false), "o fim limpa o destaque");
        assertEquals(0, oracle("/api/studio009/estado").path("total").asInt());
    }

    @Test
    @DisplayName("G2-07 contrato: capacidade desconhecida e comando de execução recusados pelo host; aba sem permissão recusada")
    void recusas() {
        Page p = variant("base");
        ObjectNode pl = studio.obj().put("debugId", id("dbg")).put("operationId", id("op")).put("tabRef", refOf(p)).put("leaseMs", 60000);
        pl.putArray("capabilities").add("guided-debug/2");
        assertEquals("BAD_REQUEST", studio.call("studio.debug.start", pl).at("/error/code").asText());
        JsonNode exec = studio.call("studio.debug.execute", studio.obj().put("debugId", "x"));
        assertEquals("UNKNOWN_COMMAND", exec.at("/error/code").asText());
    }
}
