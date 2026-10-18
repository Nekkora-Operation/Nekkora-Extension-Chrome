package com.nekkora.extension.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nekkora.extension.it.support.BrowserLab;
import com.nekkora.extension.it.support.BrowserLab.Page;
import com.nekkora.extension.it.support.SessionIT;
import com.nekkora.extension.it.support.StudioBridgeLab;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NEKKORA-STUDIO-012 no Chrome real: a estrutura do caso de reprodução (login com campos descendentes e id/name técnicos,
 * portal com iframes de menu e de conteúdo que TROCAM DE DOCUMENTO, lista de nível entre 15 sósias) gravada COM
 * interação pela cadeia completa service worker ⇄ Native Host ⇄ Bridge. O estado final é conferido por um oráculo
 * independente da gravação (a própria página, lida pelo WebDriver).
 */
class Studio012RecorderIT extends SessionIT {

    private static final String LOGIN = "/lab/studio012/login.html";
    private static final String USER = "auditor.teste";
    private static final String PASSWORD = "Fict1c1a#012";
    private static final ObjectMapper M = new ObjectMapper();
    private static StudioBridgeLab studio;

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

    @AfterEach
    void reconnectIfNeeded() {
        lab.driver().switchTo().defaultContent();
        if (!studio.connected()) {
            lab.call("studio.link.set", Map.of("enabled", true));
            studio.awaitConnected(Duration.ofSeconds(30));
        }
    }

    // ------------------------------------------------------------------------------------------ apoio

    private static String id(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private String refOf(Page p) {
        String ref = lab.call("studio.tabRef", p).path("tabRef").asText();
        JsonNode r = studio.call("browser.tabs.list", null);
        for (JsonNode t : r.at("/payload/tabs")) {
            if (ref.equals(t.path("tabRef").asText())) {
                return ref;
            }
        }
        throw new AssertionError("aba não listada: " + p.url() + " em " + r);
    }

    private void start(String rid, Page p, String mode, String route) {
        ObjectNode pl = studio.obj().put("recordingId", rid).put("operationId", id("op")).put("tabRef", refOf(p)).put("mode", mode).put("leaseMs", 120000);
        pl.putArray("capabilities").add("steps/1");
        pl.put("routeRef", route);
        JsonNode r = studio.call("studio.recording.start", pl);
        assertTrue(r.path("ok").asBoolean(), r.toString());
        BrowserLab.waitUntil(Duration.ofSeconds(15), () -> lab.call("page.diagnostics", p).at("/recorder/active").asBoolean(false),
                "sensor de interações ligado no documento");
    }

    private JsonNode op(String type, String rid) {
        return studio.call(type, studio.obj().put("recordingId", rid).put("operationId", id("op")));
    }

    private List<JsonNode> steps(String rid) {
        TreeMap<Integer, JsonNode> bySeq = new TreeMap<>();
        for (JsonNode e : studio.allEvents) {
            if ("studio.recording.step".equals(e.path("type").asText()) && rid.equals(e.at("/payload/recordingId").asText())) {
                bySeq.putIfAbsent(e.at("/payload/sequence").asInt(), e.get("payload"));
            }
        }
        return new ArrayList<>(bySeq.values());
    }

    private List<JsonNode> awaitSteps(String rid, int n) {
        BrowserLab.waitUntil(Duration.ofSeconds(30), () -> steps(rid).size() >= n, n + " etapa(s) recebida(s); até agora: " + summary(steps(rid)));
        return steps(rid);
    }

    /** "ação:tipo:estratégia preferida:frames na trajetória" de cada etapa. */
    private static List<String> summary(List<JsonNode> steps) {
        List<String> out = new ArrayList<>();
        for (JsonNode s : steps) {
            out.add(s.path("action").asText() + ":" + s.at("/element/kind").asText() + ":" + preferred(s) + ":" + frames(s));
        }
        return out;
    }

    static String preferred(JsonNode s) {
        int pref = s.at("/element/preferredLocator").asInt(-1);
        return pref < 0 ? "-" : s.at("/element/locators/" + pref + "/strategy").asText();
    }

    static long frames(JsonNode s) {
        long n = 0;
        for (JsonNode a : s.at("/element/accessPath")) {
            n += "IFRAME".equals(a.path("type").asText()) ? 1 : 0;
        }
        return n;
    }

    private void ackAll(String rid) {
        int last = steps(rid).stream().mapToInt(s -> s.path("sequence").asInt()).max().orElse(0);
        JsonNode r = studio.call("studio.recording.steps.ack", studio.obj().put("recordingId", rid).put("upToSequence", last));
        assertTrue(r.path("ok").asBoolean(), r.toString());
    }

    private static void type(WebDriver d, WebElement el, String text) {
        new Actions(d).moveToElement(el).click().perform();
        el.sendKeys(text);
    }

    private static Page page;

    private static void clickIn(WebDriver d, String frame, By by) {
        lab.focus(page); // as chamadas ao harness mudam a janela corrente do WebDriver
        d.switchTo().defaultContent();
        if (frame != null) {
            d.switchTo().frame(frame);
        }
        new Actions(d).moveToElement(d.findElement(by)).click().perform();
    }

    private static Object jsIn(WebDriver d, String frame, String script) {
        lab.focus(page);
        d.switchTo().defaultContent();
        if (frame != null) {
            d.switchTo().frame(frame);
        }
        return ((JavascriptExecutor) d).executeScript(script);
    }

    // ------------------------------------------------------------------------------------------ cenários

    @Test
    @DisplayName("S012-G1 — login (descendentes, id/name técnicos) → menu que recarrega o iframe → tela no outro iframe → nível → Gravar, COM interação")
    void s012FluxoCompletoComInteracao() throws Exception {
        Page p = open(LOGIN);
        page = p;
        String rid = id("rec");
        start(rid, p, "CAPTURE_AND_INTERACT", "route-s012");
        WebDriver d = lab.driver();
        lab.focus(p);
        type(d, d.findElement(By.id("dsLogin")), USER);
        type(d, d.findElement(By.id("dsSenhaTemp")), PASSWORD);
        clickIn(d, null, By.id("Button_DoLogin"));
        BrowserLab.waitUntil(Duration.ofSeconds(15), () -> String.valueOf(lab.js(p, "return location.pathname")).endsWith("portal.html"), "portal aberto");
        BrowserLab.waitUntil(Duration.ofSeconds(15), () -> lab.call("page.diagnostics", p).at("/recorder/active").asBoolean(false), "sensor no portal");
        Thread.sleep(800); // frames carregados e inventariados
        clickIn(d, "menu", By.linkText("Auditoria"));                                 // recarrega o frame do menu
        BrowserLab.waitUntil(Duration.ofSeconds(15), () -> String.valueOf(jsIn(d, "menu", "return location.search")).contains("auditoria"), "seção aberta");
        Thread.sleep(800);
        clickIn(d, "menu", By.linkText("Solicitação em estudo"));                     // carrega a tela no frame de conteúdo
        BrowserLab.waitUntil(Duration.ofSeconds(15), () -> String.valueOf(jsIn(d, "conteudo", "return location.pathname")).endsWith("solicitacao.html"),
                "tela da solicitação no frame de conteúdo");
        Thread.sleep(800);
        lab.focus(p);
        d.switchTo().defaultContent();
        d.switchTo().frame("conteudo");
        WebElement nivel = d.findElements(By.tagName("select")).get(6);
        new Actions(d).moveToElement(nivel).click().perform();
        nivel.sendKeys("Auditoria C");                                                 // seleção por teclado (eventos confiáveis)
        clickIn(d, "conteudo", By.name("Button_DoGravar"));
        List<JsonNode> s = awaitSteps(rid, 7);
        Thread.sleep(1200);
        s = steps(rid);
        // oráculo independente da gravação: o nível ficou gravado na página
        assertEquals("Auditoria Carefy", jsIn(d, "conteudo", "return sessionStorage.getItem('nk012.nivel')"), "estado final na página");
        d.switchTo().defaultContent();
        List<String> got = summary(s);
        assertEquals(List.of("FILL", "FILL", "CLICK", "CLICK", "CLICK", "SELECT_OPTION", "CLICK"), s.stream().map(e -> e.path("action").asText()).toList(),
                "sequência com interação: " + got);
        // login: o campo DESCENDENTE certo (não o envoltório), id/name técnicos aceitos pela lista aprovada → ID preferido
        assertEquals("TEXT_INPUT", s.get(0).at("/element/kind").asText(), got.toString());
        assertEquals("dsLogin", s.get(0).at("/element/htmlId").asText(), s.get(0).at("/element").toString());
        assertEquals("ID", preferred(s.get(0)), "id estável e único preferido ao XPath: " + s.get(0).at("/element/locators"));
        assertEquals("PASSWORD_INPUT", s.get(1).at("/element/kind").asText());
        assertTrue(s.get(1).at("/element/sensitive").asBoolean());
        assertTrue(s.get(1).at("/element/htmlId").isNull(), "campo sensível: id não vai no descritor");
        assertTrue(List.of("ID", "NAME").contains(preferred(s.get(1))), "senha localizada pelo id/name, não pela posição: " + s.get(1).at("/element/locators"));
        assertEquals("ID", preferred(s.get(2)), "botão por id: " + got);
        // menu e tela: dentro dos iframes, nos documentos NOVOS de cada frame
        assertEquals(1, frames(s.get(3)), "Auditoria no frame do menu: " + s.get(3).at("/element/accessPath"));
        assertEquals("LINK", s.get(4).at("/element/kind").asText());
        assertEquals(1, frames(s.get(4)), "Solicitação em estudo no frame do menu RECARREGADO: " + s.get(4).at("/element/accessPath"));
        assertEquals(1, frames(s.get(5)), "nível no frame de conteúdo carregado depois: " + s.get(5).at("/element/accessPath"));
        assertTrue(s.get(5).path("notes").toString().contains("OPTION_WITHHELD"), "opção identificada e retida: " + s.get(5));
        assertTrue(s.get(5).at("/parameters/option").isNull());
        for (int k : new int[]{3, 4}) {
            assertFalse(s.get(k).path("notes").toString().contains("REVEALED_BY_HOVER"), "seção aberta por recarga, não por hover: " + s.get(k));
        }
        for (JsonNode e : s) {
            assertEquals("INTERACTION_OBSERVED", e.path("observation").asText(), "modo com interação: " + e);
        }
        assertFalse(studio.allEvents.toString().contains("Carefy"), "o rótulo da opção não chega ao Studio");
        ackAll(rid);
        JsonNode fin = op("studio.recording.finish", rid);
        assertEquals("COMPLETED", fin.at("/payload/recording/state").asText(), fin.toString());
        String all = studio.allEvents.toString();
        assertFalse(all.contains(PASSWORD) || all.contains(USER), "valores digitados não atravessam");
        // Estrutura do caso real: a lista não tem id/name/rótulo e há 15 sósias — o debug NÃO comprova a identidade e,
        // portanto, não confere a opção (falha fechada; a posição não é identidade)
        Map<String, JsonNode> checks = optionChecks(p, s.get(5), "Auditoria Carefy");
        assertEquals("AMBIGUOUS", checks.get("status:Auditoria Carefy").asText(), checks.toString());
        assertTrue(checks.get("Auditoria Carefy").isNull(), "sem identidade comprovada, nenhuma opção é conferida: " + checks);
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("steps", got);
        ev.put("summary", fin.at("/payload/summary"));
        ev.put("optionChecksSemIdentidade", checks);
        lab.writeEvidence("S012-fluxo-com-interacao.json", M.writerWithDefaultPrettyPrinter().writeValueAsString(ev));
    }

    /** Inspeciona a etapa de seleção no debug guiado com cada opção configurada; devolve option e status por opção. */
    private Map<String, JsonNode> optionChecks(Page p, JsonNode sel, String... options) {
        String debugId = id("dbg");
        ObjectNode ds = studio.obj().put("debugId", debugId).put("operationId", id("op")).put("tabRef", refOf(p)).put("leaseMs", 60000);
        ds.putArray("capabilities").add("guided-debug/1");
        assertTrue(studio.call("studio.debug.start", ds).path("ok").asBoolean());
        Map<String, JsonNode> out = new LinkedHashMap<>();
        for (String opt : options) {
            ObjectNode pl = studio.obj().put("debugId", debugId).put("requestId", id("rq")).put("stepRef", id("step")).put("actionType", "SELECT_OPTION")
                    .put("targetId", sel.at("/element/correlation/targetId").asText()).put("highlight", false).put("expectedOption", opt);
            pl.set("descriptor", debugDescriptor(sel.get("element")));
            JsonNode r = studio.call("studio.debug.inspect", pl, Duration.ofSeconds(30));
            assertTrue(r.path("ok").asBoolean(), r.toString());
            out.put(opt, r.at("/payload/option"));
            out.put("status:" + opt, r.at("/payload/status"));
        }
        studio.call("studio.debug.finish", studio.obj().put("debugId", debugId).put("operationId", id("op")));
        return out;
    }

    @Test
    @DisplayName("S012-G4 — nível Auditoria Carefy: lista COM identificador → debug confirma a opção selecionada (só contagem e sim/não saem)")
    void s012ConfirmacaoDoNivel() throws Exception {
        Page p = open("/lab/studio012/solicitacao.html?variante=nome");
        page = p;
        String rid = id("rec");
        start(rid, p, "CAPTURE_AND_INTERACT", "route-s012-nivel");
        WebDriver d = lab.driver();
        lab.focus(p);
        WebElement nivel = d.findElement(By.name("ddlTipo"));
        new Actions(d).moveToElement(nivel).click().perform();
        nivel.sendKeys("Auditoria C");
        nivel.sendKeys(org.openqa.selenium.Keys.TAB); // fecha a lista: a escolha é confirmada (change)
        List<JsonNode> s = awaitSteps(rid, 1);
        Thread.sleep(800);
        s = steps(rid);
        assertEquals("SELECT_OPTION", s.get(0).path("action").asText(), summary(s).toString());
        assertEquals("NAME", preferred(s.get(0)), "lista pelo name técnico aprovado: " + s.get(0).at("/element/locators"));
        ackAll(rid);
        op("studio.recording.finish", rid);
        Map<String, JsonNode> checks = optionChecks(p, s.get(0), "Auditoria Carefy", "Auditoria médica", "Opção inexistente");
        assertEquals("RESOLVED", checks.get("status:Auditoria Carefy").asText(), checks.toString());
        assertEquals(1, checks.get("Auditoria Carefy").path("matches").asInt(), checks.toString());
        assertTrue(checks.get("Auditoria Carefy").path("selected").asBoolean(), "nível efetivo confirmado: " + checks);
        assertEquals(1, checks.get("Auditoria médica").path("matches").asInt());
        assertFalse(checks.get("Auditoria médica").path("selected").asBoolean(), "outra opção existe mas não está selecionada");
        assertEquals(0, checks.get("Opção inexistente").path("matches").asInt(), "opção que não existe na lista");
        // oráculo independente: a própria página
        assertEquals("Auditoria Carefy", String.valueOf(lab.js(p, "return document.querySelector('[name=ddlTipo]').selectedOptions[0].textContent")));
        // o rótulo só existe nos PEDIDOS do Studio; nada vindo da extensão o contém
        for (JsonNode e : studio.allEvents) {
            if (!"request".equals(e.path("kind").asText())) {
                assertFalse(e.toString().contains("Carefy"), "rótulo da opção vindo da extensão: " + e);
            }
        }
        lab.writeEvidence("S012-confirmacao-nivel.json", M.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("steps", summary(s), "optionChecks", checks)));
    }

    /** Descritor do debug a partir do elemento da etapa gravada (o que o Studio enviaria). */
    private static ObjectNode debugDescriptor(JsonNode e) {
        ObjectNode d = M.createObjectNode().put("kind", e.path("kind").asText()).put("tag", e.path("tag").asText()).put("name", e.path("name").asText(""));
        d.set("htmlId", e.path("htmlId"));
        d.set("htmlName", e.path("htmlName"));
        var ls = d.putArray("locators");
        for (JsonNode l : e.path("locators")) {
            ls.addObject().put("strategy", l.path("strategy").asText()).set("css", l.path("css"));
            ((ObjectNode) ls.get(ls.size() - 1)).set("xpath", l.path("xpath"));
            ((ObjectNode) ls.get(ls.size() - 1)).put("description", l.path("description").asText(""));
        }
        var ap = d.putArray("accessPath");
        for (JsonNode a : e.path("accessPath")) {
            ap.addObject().put("type", a.path("type").asText()).set("locator", a.path("locator"));
        }
        return d;
    }

    private static JsonNode locatorOf(JsonNode step, String strategy) {
        for (JsonNode l : step.at("/element/locators")) {
            if (strategy.equals(l.path("strategy").asText())) {
                return l;
            }
        }
        return null;
    }

    @Test
    @DisplayName("S012-G2 — sósias: name repetido, id duplicado, id gerado, id fora das listas, só rótulo, evento delegado (falha fechada)")
    void s012Sosias() throws Exception {
        Page p = open("/lab/studio012/sosias.html");
        page = p;
        String rid = id("rec");
        start(rid, p, "CAPTURE_AND_INTERACT", "route-s012-sosias");
        WebDriver d = lab.driver();
        lab.focus(p);
        type(d, d.findElement(By.id("dsCampoA")), "um");
        type(d, d.findElements(By.id("txtDuplicado")).get(0), "dois");
        type(d, d.findElement(By.cssSelector("input[id^=txtNome]")), "tres");
        type(d, d.findElement(By.id("zqxCampo")), "quatro");
        type(d, d.findElement(By.cssSelector("input.semIdentificador")), "cinco");
        new Actions(d).moveToElement(d.findElement(By.cssSelector("#delegado .item"))).click().perform();
        BrowserLab.waitUntil(Duration.ofSeconds(10), () -> "Linha acionada.".equals(String.valueOf(lab.js(p, "return document.getElementById('saida').textContent"))),
                "o evento delegado funcionou na página");
        List<JsonNode> s = awaitSteps(rid, 5);
        Thread.sleep(1200);
        s = steps(rid);
        String got = summary(s).toString();
        assertEquals(List.of("FILL", "FILL", "FILL", "FILL", "FILL"), s.stream().map(e -> e.path("action").asText()).toList(),
                "clique num filho de ancestral com evento delegado não vira etapa inventada: " + got);
        // name repetido: o id único vence; NAME, se oferecido, não é único
        assertEquals("ID", preferred(s.get(0)), got);
        JsonNode byName = locatorOf(s.get(0), "NAME");
        assertTrue(byName == null || byName.path("matchCount").asInt() != 1, "name repetido não identifica: " + byName);
        // id duplicado: nunca o ID como preferido
        assertFalse("ID".equals(preferred(s.get(1))), "id duplicado não é preferido: " + s.get(1).at("/element/locators"));
        JsonNode byId = locatorOf(s.get(1), "ID");
        assertTrue(byId == null || byId.path("matchCount").asInt() != 1, "id duplicado não identifica: " + byId);
        // id/name gerados (sufixo numérico longo) e fora das listas: retidos, com o motivo
        for (int k : new int[]{2, 3}) {
            assertTrue(s.get(k).at("/element/htmlId").isNull() && s.get(k).at("/element/htmlName").isNull(), s.get(k).at("/element").toString());
            assertTrue(s.get(k).path("notes").toString().contains("IDENTIFIER_WITHHELD"), "motivo declarado: " + s.get(k).path("notes"));
            assertFalse(List.of("ID", "NAME").contains(preferred(s.get(k))), "valor retido não vira localizador: " + s.get(k).at("/element/locators"));
        }
        // só rótulo: localizado pelo rótulo (vocabulário), não pela posição
        assertTrue(List.of("LABEL", "ROLE_NAME").contains(preferred(s.get(4))), "campo pelo rótulo: " + s.get(4).at("/element/locators"));
        assertFalse(s.get(4).path("notes").toString().contains("IDENTIFIER_WITHHELD"), "sem id/name não há retenção a declarar");
        ackAll(rid);
        JsonNode fin = op("studio.recording.finish", rid);
        assertTrue(fin.at("/payload/summary/steps/discarded/NON_INTERACTIVE").asInt() >= 1, "clique sem semântica contado: " + fin.at("/payload/summary/steps"));
        String all = studio.allEvents.toString();
        for (String v : new String[]{"um\"", "dois", "tres", "quatro", "cinco", "zqxCampo", "txtNome"}) {
            assertFalse(all.contains(v), "nada digitado nem identificador retido atravessa: " + v);
        }
        lab.writeEvidence("S012-sosias.json", M.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("steps", summary(s))));
    }

    @Test
    @DisplayName("S012-G1 — submenu que só abre com o ponteiro (CSS :hover) no iframe: clique registrado com a limitação REVEALED_BY_HOVER, sem ação inventada")
    void s012SubmenuPorHover() throws Exception {
        Page p = open("/lab/studio012/portal.html");
        page = p;
        String rid = id("rec");
        start(rid, p, "CAPTURE_AND_INTERACT", "route-s012-hover");
        Thread.sleep(800);
        WebDriver d = lab.driver();
        lab.focus(p);
        d.switchTo().defaultContent();
        d.switchTo().frame("menu");
        new Actions(d).moveToElement(d.findElement(By.linkText("Cadastros"))).pause(Duration.ofMillis(300)).perform(); // o gesto real: passar…
        WebElement prestadores = d.findElement(By.linkText("Prestadores")); // só visível com o ponteiro em "Cadastros"
        new Actions(d).moveToElement(prestadores).click().perform();                                                      // …e clicar
        BrowserLab.waitUntil(Duration.ofSeconds(15), () -> String.valueOf(jsIn(d, "conteudo", "return location.search")).contains("cadastro"),
                "tela de cadastro aberta no frame de conteúdo");
        List<JsonNode> s = awaitSteps(rid, 1);
        Thread.sleep(800);
        s = steps(rid);
        d.switchTo().defaultContent();
        assertEquals(List.of("CLICK"), s.stream().map(e -> e.path("action").asText()).toList(), "só o clique observado (passar o mouse não vira etapa): " + summary(s));
        assertEquals("LINK", s.get(0).at("/element/kind").asText());
        assertEquals(1, frames(s.get(0)), s.get(0).at("/element/accessPath").toString());
        assertTrue(s.get(0).path("notes").toString().contains("REVEALED_BY_HOVER"), "limitação do hover declarada: " + s.get(0));
        ackAll(rid);
        op("studio.recording.finish", rid);
        lab.writeEvidence("S012-submenu-hover.json", M.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("steps", summary(s), "notes", s.get(0).path("notes"))));
    }
}
