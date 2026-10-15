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
import org.openqa.selenium.Keys;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STUDIO-003 no Chrome real: interações de verdade (eventos confiáveis via WebDriver/CDP) viram etapas
 * studio.recording.step que chegam ao "Studio" de laboratório pela cadeia completa service worker ⇄ Native Host ⇄
 * Bridge. Todo evento recebido é validado contra o contrato (StudioBridgeLab.contractViolations).
 */
class Studio003InteractionsIT extends SessionIT {

    private static final String LOGIN = "/lab/studio003/login.html";
    private static final String CONSULTA = "/lab/studio003/consulta.html";
    private static final String SPA = "/lab/studio003/spa.html";
    private static final String FRAMES = "/lab/studio003/frames.html";
    private static final String USER = "ana.teste";
    private static final String PASSWORD = "S3nh@Secreta!42";
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
        JsonNode r = studio.call("browser.tabs.list", null);
        String title = (String) lab.js(p, "return document.title");
        String host = java.net.URI.create(p.url()).getHost();
        for (JsonNode t : r.at("/payload/tabs")) {
            if (host.equals(t.path("host").asText()) && title.equals(t.path("title").asText())) {
                return t.path("tabRef").asText();
            }
        }
        throw new AssertionError("aba não listada: " + p.url() + " em " + r);
    }

    private JsonNode start(String rid, Page p, String mode, String route) {
        ObjectNode pl = studio.obj().put("recordingId", rid).put("operationId", id("op")).put("tabRef", refOf(p)).put("mode", mode).put("leaseMs", 60000);
        pl.putArray("capabilities").add("steps/1");
        pl.put("routeRef", route);
        JsonNode r = studio.call("studio.recording.start", pl);
        assertTrue(r.path("ok").asBoolean(), r.toString());
        BrowserLab.waitUntil(Duration.ofSeconds(15), () -> lab.call("page.diagnostics", p).at("/recorder/active").asBoolean(false),
                "sensor de interações ligado no documento");
        return r;
    }

    private JsonNode op(String type, String rid) {
        return studio.call(type, studio.obj().put("recordingId", rid).put("operationId", id("op")));
    }

    /** Etapas recebidas desta gravação, uma por sequência (reemissões não duplicam), em ordem de sequência. */
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

    private static List<String> summary(List<JsonNode> steps) {
        List<String> out = new ArrayList<>();
        for (JsonNode s : steps) {
            out.add(s.path("sequence").asInt() + ":" + s.path("action").asText() + ":" + s.at("/element/kind").asText() + ":" + s.at("/element/name").asText());
        }
        return out;
    }

    private JsonNode ackAll(String rid) {
        int last = steps(rid).stream().mapToInt(s -> s.path("sequence").asInt()).max().orElse(0);
        JsonNode r = studio.call("studio.recording.steps.ack", studio.obj().put("recordingId", rid).put("upToSequence", last));
        assertTrue(r.path("ok").asBoolean(), r.toString());
        return r;
    }

    private void click(Page p, By by) {
        lab.click(p, by);
    }

    private void type(Page p, By by, String text) {
        lab.focus(p);
        WebElement el = lab.driver().findElement(by);
        new Actions(lab.driver()).moveToElement(el).click().perform();
        el.sendKeys(text);
    }

    private String storageDump() {
        return String.valueOf(harnessScript("const done = arguments[arguments.length - 1]; chrome.storage.session.get(null).then((x) => done(JSON.stringify(x)));"));
    }

    private void assertNoSecretAnywhere(String... secrets) throws IOException {
        String events = studio.allEvents.toString();
        String storage = storageDump();
        String panelLog = String.valueOf(lab.call("events.since", Map.of("since", 0)));
        List<String> files = new ArrayList<>();
        if (Files.isDirectory(lab.hostData())) {
            try (Stream<Path> s = Files.walk(lab.hostData())) {
                s.filter(Files::isRegularFile).filter(f -> f.toString().endsWith(".log") || f.toString().endsWith(".json") || f.toString().endsWith(".txt"))
                        .forEach(f -> {
                            try {
                                files.add(Files.readString(f));
                            } catch (IOException e) {
                                // arquivo em uso/binário
                            }
                        });
            }
        }
        for (String secret : secrets) {
            assertFalse(events.contains(secret), "valor digitado nas etapas enviadas ao Studio");
            assertFalse(storage.contains(secret), "valor digitado no armazenamento temporário da extensão");
            int at = panelLog.indexOf(secret);
            assertFalse(at >= 0, "valor digitado no log de eventos do service worker: '" + secret + "' em …"
                    + (at < 0 ? "" : panelLog.substring(Math.max(0, at - 300), Math.min(panelLog.length(), at + 80))));
            assertTrue(files.stream().noneMatch(f -> f.contains(secret)), "valor digitado em arquivos do host");
        }
    }

    // ------------------------------------------------------------------------------------------ cenários

    @Test
    @DisplayName("S003-010/013/014/015/020/026/028/037/045 — Cenário 1 (login): Preencher Usuário, Preencher Senha, Clicar em Entrar")
    void s003Login() throws Exception {
        Page p = open(LOGIN);
        String rid = id("rec");
        long t0 = System.currentTimeMillis();
        start(rid, p, "CAPTURE_AND_INTERACT", "route-login");
        type(p, By.id("usuario"), USER);
        type(p, By.id("senha"), PASSWORD);
        click(p, By.id("entrar"));
        List<JsonNode> s = awaitSteps(rid, 3);
        assertEquals(List.of("1:FILL:TEXT_INPUT:Usuário", "2:FILL:PASSWORD_INPUT:Senha", "3:CLICK:BUTTON:Entrar"), summary(s));
        for (JsonNode e : s) {
            assertEquals("route-login", e.path("routeRef").asText());
            assertEquals("INTERACTION_OBSERVED", e.path("observation").asText());
            assertFalse(e.at("/parameters/valueCaptured").asBoolean(true));
            assertEquals("RESOLVED", e.at("/element/resolution").asText(), e.toString());
            assertTrue(e.toString().length() < 4096, "evento compacto (sem PageMap): " + e.toString().length());
        }
        assertTrue(s.get(0).path("events").asInt() >= USER.length(), "digitação consolidada numa etapa: " + s.get(0).path("events"));
        assertTrue(s.get(1).at("/element/sensitive").asBoolean());
        assertTrue(s.get(1).at("/element/htmlId").isNull(), "campo sensível sem id HTML");
        assertEquals("usuario", s.get(0).at("/element/htmlId").asText());
        JsonNode click = s.get(2);
        assertTrue(click.path("evidence").toString().contains("FORM_SUBMITTED"), "submit como evidência do clique: " + click.path("evidence"));
        int pref = click.at("/element/preferredLocator").asInt();
        assertTrue(pref >= 0, "localizador validado: " + click.at("/element/locators"));
        assertTrue(List.of("LIVE", "SNAPSHOT").contains(click.at("/element/locators/" + pref + "/validation").asText()));
        assertEquals("MAIN_DOCUMENT", click.at("/element/accessPath/0/type").asText());
        assertTrue(click.at("/element/correlation/targetId").asText().startsWith("tgt-"), "identidade lógica do motor");
        assertEquals(1, s.stream().filter(e -> "CLICK".equals(e.path("action").asText())).count(), "submit não duplica o clique");
        BrowserLab.waitUntil(Duration.ofSeconds(15), () -> String.valueOf(lab.js(p, "return location.pathname")).endsWith("consulta.html"),
                "a página seguiu para a consulta (interação encaminhada)");
        ackAll(rid);
        JsonNode fin = op("studio.recording.finish", rid);
        assertEquals("COMPLETED", fin.at("/payload/recording/state").asText(), fin.toString());
        assertEquals("AVAILABLE", fin.at("/payload/summary/actionCapture").asText(), fin.at("/payload/summary").toString());
        assertEquals(3, fin.at("/payload/summary/steps/acknowledged").asInt());
        assertFalse(fin.at("/payload/summary/pageMapExported").asBoolean());
        JsonNode rec = lab.call("page.diagnostics", p).path("recorder");
        assertFalse(rec.path("active").asBoolean(true), "S003-002: finalizar desliga o sensor: " + rec);
        assertEquals(0, rec.path("listeners").asInt(-1), "S003-002: nenhum ouvinte do Recorder depois de finalizar");
        assertNoSecretAnywhere(PASSWORD, USER);
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("steps", summary(s));
        ev.put("click", click);
        ev.put("summary", fin.at("/payload/summary"));
        ev.put("elapsedMs", System.currentTimeMillis() - t0);
        lab.writeEvidence("S003-login-steps.json", M.writerWithDefaultPrettyPrinter().writeValueAsString(ev));
    }

    @Test
    @DisplayName("S003-007/008/009/011/012/016/017/018/019/027/043 — Cenário 2 (consulta): select, radio, checkbox, textarea, contenteditable, link, role=button, ambíguo, programático, combobox, Enter")
    void s003Consulta() throws Exception {
        Page p = open(CONSULTA);
        String rid = id("rec");
        start(rid, p, "CAPTURE_AND_INTERACT", "route-consulta");
        WebDriver d = lab.driver();
        lab.focus(p);
        d.findElement(By.id("estado")).sendKeys("São");                     // seleção por teclado (eventos confiáveis)
        click(p, By.id("pesquisar"));
        click(p, By.id("pf"));
        click(p, By.cssSelector("label[for='ativos']"));                     // marca pela label
        click(p, By.id("ativos"));                                           // desmarca no controle
        type(p, By.id("obs"), "Cliente preferencial");
        type(p, By.id("nota"), "Retornar amanhã");
        click(p, By.id("menu-clientes"));
        click(p, By.id("exportar"));
        click(p, By.id("detalhes"));
        click(p, By.id("texto-solto"));
        click(p, By.id("automatico"));
        click(p, By.id("cidade"));
        click(p, By.id("op-campinas"));
        type(p, By.id("pesquisa"), "abc");
        d.findElement(By.id("pesquisa")).sendKeys(Keys.ENTER);
        awaitSteps(rid, 15);
        Thread.sleep(1500); // nada chega atrasado (ex.: clique sintético do envio implícito por Enter)
        List<JsonNode> s = steps(rid);
        List<String> got = new ArrayList<>();
        for (JsonNode e : s) {
            got.add(e.path("action").asText() + ":" + e.at("/element/htmlId").asText(e.at("/element/name").asText()));
        }
        assertEquals(List.of("SELECT_OPTION:estado", "CLICK:pesquisar", "CHECK:pf", "CHECK:ativos", "UNCHECK:ativos", "FILL:obs", "FILL:nota",
                "CLICK:menu-clientes", "CLICK:exportar", "UNKNOWN:detalhes", "CLICK:automatico", "CLICK:cidade", "SELECT_OPTION:cidade",
                "FILL:pesquisa", "PRESS_KEY:pesquisa"), got, "ordem e tipos: " + summary(s));
        assertEquals("São Paulo", s.get(0).at("/parameters/option/label").asText());
        assertEquals("SP", s.get(0).at("/parameters/option/value").asText());
        assertEquals("LINK", s.get(7).at("/element/kind").asText());
        assertTrue(s.get(9).path("notes").toString().contains("AMBIGUOUS_TARGET"), "ambíguo declarado: " + s.get(9));
        assertEquals("Campinas", s.get(12).at("/parameters/option/label").asText(), "combobox personalizado com relação comprovada");
        assertEquals("Enter", s.get(14).at("/parameters/key").asText());
        assertTrue(s.get(14).path("evidence").toString().contains("FORM_SUBMITTED"), "Enter enviou o formulário (sem clique inventado)");
        assertEquals(15, s.size(), "eventos programáticos (preencher/marcar/clicar pelo script) não viraram etapas");
        ackAll(rid);
        JsonNode fin = op("studio.recording.finish", rid);
        JsonNode discarded = fin.at("/payload/summary/steps/discarded");
        assertTrue(discarded.path("UNTRUSTED").asInt() >= 3, "eventos não confiáveis contados: " + discarded);
        assertTrue(discarded.path("NON_INTERACTIVE").asInt() >= 1, "texto sem interação ignorado e contado: " + discarded);
        assertNoSecretAnywhere("Cliente preferencial", "Retornar amanhã", "preenchido pelo script");
        lab.writeEvidence("S003-consulta-steps.json", M.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("steps", summary(s), "summary", fin.at("/payload/summary"))));
    }

    @Test
    @DisplayName("S003-003/005 — CAPTURE_ONLY não executa a ação (mouse e teclado); a alternância passa a executar")
    void s003CaptureOnly() throws Exception {
        Page p = open(CONSULTA);
        String rid = id("rec");
        start(rid, p, "CAPTURE_ONLY", "route-x");
        click(p, By.id("adicionar"));
        lab.driver().findElement(By.id("adicionar")).sendKeys(Keys.ENTER);
        click(p, By.id("estado"));
        click(p, By.id("pesquisa"));
        List<JsonNode> s = awaitSteps(rid, 4);
        assertEquals("0", lab.js(p, "return document.getElementById('linhas').textContent"), "a ação normal do botão NÃO aconteceu");
        assertEquals(List.of("CLICK", "CLICK", "SELECT_OPTION", "FILL"), s.stream().map(e -> e.path("action").asText()).toList(), String.valueOf(summary(s)));
        assertTrue(s.stream().allMatch(e -> "PROPOSED".equals(e.path("observation").asText()) && "CAPTURE_ONLY".equals(e.path("mode").asText())));
        assertTrue(s.get(1).path("evidence").toString().contains("VIA_KEYBOARD"), "seleção por teclado");
        assertTrue(s.get(2).path("notes").toString().contains("OPTION_NOT_CHOSEN"));
        JsonNode m = studio.call("studio.recording.mode", studio.obj().put("recordingId", rid).put("operationId", id("op")).put("mode", "CAPTURE_AND_INTERACT"));
        assertEquals("CAPTURE_AND_INTERACT", m.at("/payload/recording/mode").asText());
        click(p, By.id("adicionar"));
        List<JsonNode> after = awaitSteps(rid, 5);
        assertEquals("1", lab.js(p, "return document.getElementById('linhas').textContent"), "depois da alternância a página funciona normalmente");
        assertEquals("INTERACTION_OBSERVED", after.get(4).path("observation").asText());
        ackAll(rid);
        op("studio.recording.finish", rid);
    }

    @Test
    @DisplayName("S003-023/024/025 — Cenário 4: iframe autorizado e Shadow DOM com trajetória; iframe sem permissão não é capturado")
    void s003FramesShadow() throws Exception {
        Page p = open(FRAMES + "?externo=" + java.net.URLEncoder.encode(lab.externalUrl(BrowserLab.SITE_X, "/lab/studio003/frame-form.html"),
                java.nio.charset.StandardCharsets.UTF_8));
        String rid = id("rec");
        start(rid, p, "CAPTURE_AND_INTERACT", "route-f");
        WebDriver d = lab.driver();
        lab.focus(p);
        d.switchTo().frame("cadastro");
        WebElement nome = d.findElement(By.id("nome"));
        new Actions(d).moveToElement(nome).click().perform();
        nome.sendKeys("Maria Teste");
        new Actions(d).moveToElement(d.findElement(By.id("salvar"))).click().perform();
        d.switchTo().defaultContent();
        d.switchTo().frame("externo"); // origem SEM permissão: o Chrome não injeta o content script
        new Actions(d).moveToElement(d.findElement(By.id("nome"))).click().perform();
        d.findElement(By.id("nome")).sendKeys("fora do escopo");
        new Actions(d).moveToElement(d.findElement(By.id("salvar"))).click().perform();
        d.switchTo().defaultContent();
        WebElement shadowUser = d.findElement(By.id("componente")).getShadowRoot().findElement(By.cssSelector("#usuario"));
        new Actions(d).moveToElement(shadowUser).click().perform();
        shadowUser.sendKeys("comp");
        WebElement icon = d.findElement(By.id("componente")).getShadowRoot().findElement(By.cssSelector("#entrar .icone"));
        new Actions(d).moveToElement(icon).click().perform();
        List<JsonNode> s = awaitSteps(rid, 4);
        Thread.sleep(800);
        s = steps(rid);
        assertEquals(List.of("FILL", "CLICK", "FILL", "CLICK"), s.stream().map(e -> e.path("action").asText()).toList(), String.valueOf(summary(s)));
        assertTrue(s.get(0).at("/element/accessPath").toString().contains("IFRAME"), "trajetória entra no iframe: " + s.get(0).at("/element/accessPath"));
        assertEquals("Nome", s.get(0).at("/element/name").asText());
        assertTrue(s.get(2).at("/element/accessPath").toString().contains("SHADOW_ROOT"), "trajetória entra no shadow root: " + s.get(2).at("/element/accessPath"));
        assertEquals("BUTTON", s.get(3).at("/element/kind").asText(), "clique no ícone identifica o botão");
        assertTrue(s.get(3).at("/element/name").asText().contains("Entrar"));
        assertFalse(studio.allEvents.toString().contains("fora do escopo"), "nada do iframe sem permissão");
        JsonNode status = studio.call("studio.recording.status", studio.obj().put("recordingId", rid));
        assertEquals("PARTIAL", status.at("/payload/recordings/0/coverage/state").asText(), "cobertura parcial declarada: " + status);
        ackAll(rid);
        JsonNode fin = op("studio.recording.finish", rid);
        assertEquals("PARTIAL", fin.at("/payload/summary/actionCapture").asText());
        assertTrue(fin.at("/payload/summary/limitations").toString().contains("PARTIAL_COVERAGE"));
        lab.writeEvidence("S003-frames-shadow-steps.json", M.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("steps", s)));
    }

    @Test
    @DisplayName("S003-022 — Cenário 5 (SPA): ações antes e depois da troca de rota, em ordem, sem etapa NAVIGATE")
    void s003Spa() throws Exception {
        Page p = open(SPA);
        String rid = id("rec");
        start(rid, p, "CAPTURE_AND_INTERACT", "route-spa");
        click(p, By.id("atualizar"));
        click(p, By.id("rota-clientes"));
        type(p, By.id("filtro"), "silva");
        click(p, By.id("filtrar"));
        List<JsonNode> s = awaitSteps(rid, 4);
        assertEquals(List.of("CLICK:Atualizar painel", "CLICK:Clientes", "FILL:Filtro", "CLICK:Filtrar"),
                s.stream().map(e -> e.path("action").asText() + ":" + e.at("/element/name").asText()).toList(), String.valueOf(summary(s)));
        assertEquals("SAME_DOCUMENT", s.get(1).at("/context/after/navigation").asText(), "troca de rota como contexto da etapa: " + s.get(1).path("context"));
        waitText(lab.driver(), "contagem", "3 clientes");
        ackAll(rid);
        op("studio.recording.finish", rid);
    }

    @Test
    @DisplayName("S003-033 — Cenário 6: troca de sessão consolida o preenchimento aberto na rota anterior (nada atrasado na sessão nova)")
    void s003Sessions() throws Exception {
        Page p = open(CONSULTA);
        String rid = id("rec");
        start(rid, p, "CAPTURE_AND_INTERACT", "route-login");
        type(p, By.id("pesquisa"), "cliente"); // preenchimento ainda aberto quando a sessão muda
        JsonNode route = studio.call("studio.recording.route", studio.obj().put("recordingId", rid).put("operationId", id("op")).put("routeRef", "route-consulta"));
        assertTrue(route.path("ok").asBoolean(), route.toString());
        click(p, By.id("pesquisar"));
        List<JsonNode> s = awaitSteps(rid, 2);
        assertEquals("FILL", s.get(0).path("action").asText());
        assertEquals("route-login", s.get(0).path("routeRef").asText(), "consolidado na rota anterior");
        assertEquals(s.get(0).path("sequence").asInt(), route.at("/payload/boundarySequence").asInt(), "fronteira exata");
        assertEquals("route-consulta", s.get(1).path("routeRef").asText());
        ackAll(rid);
        op("studio.recording.finish", rid);
    }

    @Test
    @DisplayName("S003-029/030 — reconexão: etapas não confirmadas são reemitidas em ordem e não duplicam")
    void s003Reconnect() throws Exception {
        Page p = open(CONSULTA);
        String rid = id("rec");
        start(rid, p, "CAPTURE_AND_INTERACT", "route-r");
        click(p, By.id("exportar"));
        click(p, By.id("adicionar"));
        awaitSteps(rid, 2);
        studio.dropConnection();
        lab.call("studio.link.set", Map.of("enabled", true));
        studio.awaitConnected(Duration.ofSeconds(30));
        JsonNode st = studio.call("studio.recording.status", studio.obj().put("recordingId", rid));
        assertEquals(2, st.at("/payload/recordings/0/steps/lastSequence").asInt(), st.toString());
        assertEquals(2, st.at("/payload/recordings/0/steps/pending").asInt(), "retidas até a confirmação");
        int before = (int) studio.allEvents.stream().filter(e -> "studio.recording.step".equals(e.path("type").asText())).count();
        JsonNode sync = studio.call("studio.recording.steps.sync", studio.obj().put("recordingId", rid).put("afterSequence", 0));
        assertEquals(2, sync.at("/payload/resent").asInt(), sync.toString());
        BrowserLab.waitUntil(Duration.ofSeconds(10), () -> studio.allEvents.stream().filter(e -> "studio.recording.step".equals(e.path("type").asText())).count() >= before + 2,
                "reemissões recebidas");
        assertEquals(2, steps(rid).size(), "deduplicação por sequência: nenhuma etapa duplicada");
        assertTrue(studio.allEvents.stream().anyMatch(e -> e.at("/payload/notes").toString().contains("RESENT")));
        ackAll(rid);
        JsonNode st2 = studio.call("studio.recording.status", studio.obj().put("recordingId", rid));
        assertEquals(0, st2.at("/payload/recordings/0/steps/pending").asInt());
        op("studio.recording.finish", rid);
    }

    @Test
    @DisplayName("S003-031/032 — pausa: interações ignoradas (e a página funciona); retomada sem duplicar")
    void s003Pause() throws Exception {
        Page p = open(CONSULTA);
        String rid = id("rec");
        start(rid, p, "CAPTURE_AND_INTERACT", "route-p");
        click(p, By.id("exportar"));
        awaitSteps(rid, 1);
        op("studio.recording.pause", rid);
        assertFalse(lab.call("page.diagnostics", p).at("/recorder/active").asBoolean(true), "sensores desligados na pausa");
        click(p, By.id("adicionar"));
        assertEquals("1", lab.js(p, "return document.getElementById('linhas').textContent"));
        op("studio.recording.resume", rid);
        BrowserLab.waitUntil(Duration.ofSeconds(15), () -> lab.call("page.diagnostics", p).at("/recorder/active").asBoolean(false), "sensores religados");
        click(p, By.id("detalhes"));
        List<JsonNode> s = awaitSteps(rid, 2);
        Thread.sleep(600);
        assertEquals(List.of("CLICK:exportar", "UNKNOWN:detalhes"), steps(rid).stream()
                .map(e -> e.path("action").asText() + ":" + e.at("/element/htmlId").asText(e.at("/element/name").asText())).toList(), String.valueOf(summary(s)));
        ackAll(rid);
        op("studio.recording.finish", rid);
    }

    @Test
    @DisplayName("S003-044 — inspeção manual independente: suspensa enquanto o Recorder grava, volta igual depois")
    void s003Inspector() throws Exception {
        Page p = open(CONSULTA);
        activate(p);
        assertEquals("INSPECT", lab.call("page.diagnostics", p).path("inspectorMode").asText());
        String rid = id("rec");
        start(rid, p, "CAPTURE_AND_INTERACT", "route-i");
        assertEquals("SUSPENDED", lab.call("page.diagnostics", p).path("inspectorMode").asText(), "um único interceptador por clique");
        click(p, By.id("adicionar"));
        awaitSteps(rid, 1);
        assertEquals("1", lab.js(p, "return document.getElementById('linhas').textContent"), "o clique não foi capturado pela inspeção");
        ackAll(rid);
        op("studio.recording.finish", rid);
        BrowserLab.waitUntil(Duration.ofSeconds(10), () -> "INSPECT".equals(lab.call("page.diagnostics", p).path("inspectorMode").asText()), "inspeção restaurada");
        assertTrue(inspection(p).path("active").asBoolean(), "consumidor da inspeção preservado");
        click(p, By.id("exportar"));
        BrowserLab.waitUntil(Duration.ofSeconds(15), () -> !inspection(p).path("selected").isNull() && !inspection(p).path("selected").isMissingNode(),
                "seleção pela inspeção funcionando de novo");
        deactivate(p);
    }

    @Test
    @DisplayName("S003-038 — cancelar: a extensão descarta as etapas retidas desta gravação")
    void s003Cancel() throws Exception {
        Page p = open(CONSULTA);
        String rid = id("rec");
        start(rid, p, "CAPTURE_AND_INTERACT", "route-c");
        click(p, By.id("exportar"));
        awaitSteps(rid, 1);
        JsonNode c = op("studio.recording.cancel", rid);
        assertEquals("CANCELLED", c.at("/payload/recording/state").asText(), c.toString());
        assertEquals(1, c.at("/payload/summary/steps/produced").asInt());
        assertEquals(0, c.at("/payload/summary/steps/unacknowledged").asInt(), "nada retido de uma gravação cancelada");
        assertFalse(lab.call("page.diagnostics", p).at("/recorder/active").asBoolean(true));
    }

    @Test
    @DisplayName("S003-040 — permissão revogada: sensores parados, gravação DEGRADED, nenhuma etapa da origem revogada")
    void s003PermissionRevoked() throws Exception {
        Page p = openUrl(lab.externalUrl(BrowserLab.SITE_A, LOGIN));
        String rid = id("rec");
        start(rid, p, "CAPTURE_AND_INTERACT", "route-rv");
        click(p, By.id("ajuda"));
        List<JsonNode> s = awaitSteps(rid, 1);
        assertEquals("BUTTON", s.get(0).at("/element/kind").asText());
        assertEquals("Ajuda", s.get(0).at("/element/name").asText(), "clique no ícone identifica o botão");
        String pattern = "http://" + BrowserLab.SITE_A + "/*";
        try {
            lab.siteAccess(pattern, false);
            BrowserLab.waitUntil(Duration.ofSeconds(20), () -> {
                JsonNode st = studio.call("studio.recording.status", studio.obj().put("recordingId", rid));
                return "DEGRADED".equals(st.at("/payload/recordings/0/state").asText());
            }, "gravação DEGRADED após a revogação");
            lab.focus(p);
            new Actions(lab.driver()).moveToElement(lab.driver().findElement(By.id("entrar"))).click().perform();
            Thread.sleep(800);
            assertEquals(1, steps(rid).size(), "nenhuma etapa depois da revogação");
        } finally {
            lab.siteAccess(pattern, true);
        }
        ackAll(rid);
        op("studio.recording.cancel", rid);
    }

    @Test
    @DisplayName("S003-046/CA-020 — Studio da STUDIO-002 (sem steps/1): nenhum sensor, nenhuma etapa, resumo NOT_AVAILABLE")
    void s003Compat() throws Exception {
        Page p = open(CONSULTA);
        String rid = id("rec");
        ObjectNode pl = studio.obj().put("recordingId", rid).put("operationId", id("op")).put("tabRef", refOf(p)).put("mode", "CAPTURE_AND_INTERACT").put("leaseMs", 30000);
        assertTrue(studio.call("studio.recording.start", pl).path("ok").asBoolean());
        waitSessionActive(p);
        assertFalse(lab.call("page.diagnostics", p).at("/recorder/active").asBoolean(true), "sem a capacidade, nenhum sensor");
        click(p, By.id("adicionar"));
        Thread.sleep(800);
        assertEquals(0, steps(rid).size());
        JsonNode fin = op("studio.recording.finish", rid);
        assertEquals("NOT_AVAILABLE", fin.at("/payload/summary/actionCapture").asText());
        assertTrue(fin.at("/payload/summary/steps").isMissingNode(), "resumo idêntico ao da STUDIO-002");
    }

    @Test
    @DisplayName("S003-039 — Studio perdido: a concessão expira, os sensores param e nada continua sendo registrado")
    void s003StudioLost() throws Exception {
        Page p = open(CONSULTA);
        String rid = id("rec");
        ObjectNode pl = studio.obj().put("recordingId", rid).put("operationId", id("op")).put("tabRef", refOf(p)).put("mode", "CAPTURE_AND_INTERACT").put("leaseMs", 5000);
        pl.putArray("capabilities").add("steps/1");
        pl.put("routeRef", "route-l");
        assertTrue(studio.call("studio.recording.start", pl).path("ok").asBoolean());
        BrowserLab.waitUntil(Duration.ofSeconds(15), () -> lab.call("page.diagnostics", p).at("/recorder/active").asBoolean(false), "sensor ligado");
        studio.dropConnection();
        BrowserLab.waitUntil(Duration.ofSeconds(20), () -> {
            for (JsonNode r : lab.call("studio.recordings", Map.of())) {
                if (rid.equals(r.path("recordingId").asText())) {
                    return "EXPIRED".equals(r.path("state").asText());
                }
            }
            return false;
        }, "concessão expirada");
        assertFalse(lab.call("page.diagnostics", p).at("/recorder/active").asBoolean(true), "sensores desligados na expiração");
        click(p, By.id("adicionar"));
        assertEquals("1", lab.js(p, "return document.getElementById('linhas').textContent"), "a página segue normal");
    }

    @Test
    @DisplayName("S003-032 (desempenho) — digitação rápida, cliques consecutivos: latências e tamanho dos eventos")
    void s003Performance() throws Exception {
        Page p = open(CONSULTA);
        String rid = id("rec");
        start(rid, p, "CAPTURE_AND_INTERACT", "route-perf");
        String text = "abcdefghij".repeat(30);
        long typeStart = System.nanoTime();
        type(p, By.id("obs"), text);
        double typingMs = (System.nanoTime() - typeStart) / 1e6;
        List<Double> clickLatency = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            int expected = steps(rid).size() + (i == 0 ? 2 : 1); // o primeiro clique também consolida o preenchimento
            long t0 = System.nanoTime();
            click(p, By.id("adicionar"));
            BrowserLab.waitUntil(Duration.ofSeconds(10), () -> steps(rid).size() >= expected, "etapa do clique " + expected);
            clickLatency.add((System.nanoTime() - t0) / 1e6);
            if (i % 5 == 4) {
                ackAll(rid);
            }
        }
        List<JsonNode> s = awaitSteps(rid, 21);
        assertEquals("FILL", s.get(0).path("action").asText());
        assertTrue(s.get(0).path("events").asInt() >= text.length(), "300 caracteres consolidados em UMA etapa: " + s.get(0).path("events"));
        assertEquals(20, s.stream().filter(e -> "CLICK".equals(e.path("action").asText())).count());
        assertEquals("20", lab.js(p, "return document.getElementById('linhas').textContent"), "nenhum clique perdido pela página");
        ackAll(rid);
        JsonNode metrics = lab.call("studio.steps.metrics", Map.of("recordingId", rid));
        List<Integer> sizes = s.stream().map(e -> e.toString().length()).toList();
        clickLatency.sort(Double::compare);
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("typedCharacters", text.length());
        ev.put("typingMs", Math.round(typingMs));
        ev.put("clicks", 20);
        ev.put("clickToStepMs", Map.of("p50", Math.round(clickLatency.get(10)), "p95", Math.round(clickLatency.get(18)), "max", Math.round(clickLatency.get(19))));
        ev.put("eventBytes", Map.of("max", sizes.stream().mapToInt(Integer::intValue).max().orElse(0), "avg", (int) sizes.stream().mapToInt(Integer::intValue).average().orElse(0)));
        ev.put("pipeline", metrics);
        lab.writeEvidence("S003-performance.json", M.writerWithDefaultPrettyPrinter().writeValueAsString(ev));
        assertTrue(clickLatency.get(18) < 3000, "p95 do clique até a etapa no Studio: " + clickLatency);
        op("studio.recording.finish", rid);
    }
}
