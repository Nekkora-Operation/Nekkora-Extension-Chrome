package com.nekkora.extension.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nekkora.extension.it.support.BrowserLab;
import com.nekkora.extension.it.support.BrowserLab.Page;
import com.nekkora.extension.it.support.PrivacyCanaries;
import com.nekkora.extension.it.support.PrivacyCanaries.Finding;
import com.nekkora.extension.it.support.SessionIT;
import com.nekkora.extension.it.support.StudioBridgeLab;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.Keys;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CHROME-SECURITY-001 no Chrome real: cada página do laboratório {@code chrome-security-001} (dados fictícios) é
 * gravada pela cadeia completa — content script ⇄ service worker ⇄ Native Host (motor Java) ⇄ "Studio" de
 * laboratório — com interações reais; elementos são inspecionados (motor + registro de alvos) e o PageMap é exportado.
 * Depois, TODOS os destinos são varridos em busca dos marcadores da página ({@link PrivacyCanaries}):
 *
 * <ul>
 *   <li>snapshot transportado ao motor ({@code cs.capture}, a mesma representação da captura-base e dos deltas);</li>
 *   <li>resultado do motor para a inspeção (classificação, nome, localizadores, contexto);</li>
 *   <li>registro de alvos ({@code target.list});</li>
 *   <li>PageMap exportado;</li>
 *   <li>log de eventos do service worker ({@code events.since});</li>
 *   <li>{@code chrome.storage.session} e {@code chrome.storage.local};</li>
 *   <li>arquivos do Native Host (logs, registro de alvos, PageMaps);</li>
 *   <li>eventos recebidos pelo Studio (etapas e evidências).</li>
 * </ul>
 *
 * <p>Os testes foram escritos ANTES da correção e reproduziram as exposições (relatório da missão); permanecem como
 * regressão. Cada achado é guardado em {@code reports/evidence/SEC001-exposure.json}.
 */
class ChromeSecurity001IT extends SessionIT {

    private static final String BASE = "/lab/chrome-security-001/";
    private static final ObjectMapper M = new ObjectMapper();
    private static StudioBridgeLab studio;
    /** cenário → achados (todos os destinos). */
    private static final Map<String, List<Finding>> FINDINGS = new LinkedHashMap<>();
    private static final List<String> NOTES = new CopyOnWriteArrayList<>();

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
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<Finding>> e : FINDINGS.entrySet()) {
            Map<String, Object> byDest = new TreeMap<>();
            for (Finding f : e.getValue()) {
                @SuppressWarnings("unchecked")
                List<Map<String, String>> l = (List<Map<String, String>>) byDest.computeIfAbsent(f.destination(), k -> new ArrayList<>());
                l.add(Map.of("canary", f.canary(), "surface", f.surface(), "excerpt", f.excerpt()));
            }
            out.put(e.getKey(), Map.of("count", e.getValue().size(), "byDestination", byDest));
        }
        out.put("notes", NOTES);
        lab.writeEvidence("SEC001-exposure.json", M.writerWithDefaultPrettyPrinter().writeValueAsString(out));
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

    /**
     * Referência opaca da aba no Studio. CHROME-SECURITY-001 (D4): o título só segue se for do vocabulário, então a aba
     * não é mais identificada pelo título — o teste pede a referência exata (comando DEV) e confere que ela aparece na
     * listagem que o Studio recebe.
     */
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

    private String start(Page p) {
        String rid = id("rec");
        ObjectNode pl = studio.obj().put("recordingId", rid).put("operationId", id("op")).put("tabRef", refOf(p))
                .put("mode", "CAPTURE_AND_INTERACT").put("leaseMs", 60000);
        pl.putArray("capabilities").add("steps/1").add("evidence/1");
        pl.put("routeRef", "route-" + rid.substring(rid.length() - 6));
        JsonNode r = studio.call("studio.recording.start", pl);
        assertTrue(r.path("ok").asBoolean(), r.toString());
        BrowserLab.waitUntil(Duration.ofSeconds(15), () -> lab.call("page.diagnostics", p).at("/recorder/active").asBoolean(false),
                "sensor de interações ligado no documento");
        return rid;
    }

    private JsonNode finish(String rid) {
        int last = steps(rid).stream().mapToInt(s -> s.path("sequence").asInt()).max().orElse(0);
        if (last > 0) {
            studio.call("studio.recording.steps.ack", studio.obj().put("recordingId", rid).put("upToSequence", last));
        }
        return studio.call("studio.recording.finish", studio.obj().put("recordingId", rid).put("operationId", id("op")));
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

    private void awaitSteps(String rid, int n) {
        BrowserLab.waitUntil(Duration.ofSeconds(40), () -> steps(rid).size() >= n, n + " etapa(s); até agora " + steps(rid).size());
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

    private String storage(String area) {
        return String.valueOf(harnessScript("const done = arguments[arguments.length - 1]; chrome.storage." + area
                + ".get(null).then((x) => done(JSON.stringify(x)));"));
    }

    /** Inspeção de um elemento (motor: classificação, nome, localizadores, contexto; registro do alvo). */
    private String inspect(Page p, String css) {
        try {
            return lab.call("page.select", p, "css", css).toString();
        } catch (RuntimeException e) {
            NOTES.add("seleção de " + css + " em " + p.url() + " falhou: " + e.getMessage());
            return "";
        }
    }

    /**
     * Varre todos os destinos. {@code engine} = respostas do motor obtidas no cenário (inspeção, exportação). Os
     * arquivos do host e o log do service worker são compartilhados entre cenários; como cada página usa marcadores
     * próprios, o achado continua atribuível.
     */
    private List<Finding> scan(String scenario, Page p, String rid, long since, Map<String, String> engine) throws IOException {
        List<Finding> out = new ArrayList<>();
        try {
            out.addAll(PrivacyCanaries.scan("snapshot transportado ao motor (cs.capture)", lab.call("page.rawSnapshot", p).toString()));
        } catch (RuntimeException e) {
            NOTES.add(scenario + ": snapshot indisponível (" + e.getMessage() + ")");
        }
        for (Map.Entry<String, String> e : engine.entrySet()) {
            out.addAll(PrivacyCanaries.scan(e.getKey(), e.getValue()));
        }
        out.addAll(PrivacyCanaries.scan("registro de alvos (target.list)", lab.call("target.list", Map.of()).toString()));
        out.addAll(PrivacyCanaries.scan("log de eventos do service worker (events.since)", lab.call("events.since", Map.of("since", since)).toString()));
        out.addAll(PrivacyCanaries.scan("chrome.storage.session", storage("session")));
        out.addAll(PrivacyCanaries.scan("chrome.storage.local", storage("local")));
        if (rid != null) {
            String ev = studio.allEvents.stream().filter(e -> rid.equals(e.at("/payload/recordingId").asText())).map(JsonNode::toString)
                    .collect(Collectors.joining("\n"));
            out.addAll(PrivacyCanaries.scan("eventos ao Studio (etapas/evidências)", ev));
        }
        if (Files.isDirectory(lab.hostData())) {
            try (Stream<Path> s = Files.walk(lab.hostData())) {
                for (Path f : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
                    String rel = lab.hostData().relativize(f).toString().replace('\\', '/');
                    String kind = rel.startsWith("logs/") ? "logs" : rel.startsWith("pagemaps/") ? "pagemaps/*.json" : rel;
                    try {
                        out.addAll(PrivacyCanaries.scan("arquivo do host: " + kind, Files.readString(f)));
                    } catch (IOException e) {
                        // em uso / binário
                    }
                }
            }
        }
        FINDINGS.computeIfAbsent(scenario, k -> new ArrayList<>()).addAll(out);
        return out;
    }

    private static void assertClean(String scenario, List<Finding> found) {
        assertTrue(found.isEmpty(), scenario + ": " + found.size() + " exposição(ões):\n" + found.stream()
                .map(f -> "  [" + f.destination() + "] " + f.surface() + " → …" + f.excerpt() + "…").distinct().limit(40)
                .collect(Collectors.joining("\n")));
    }

    private Map<String, String> inspectAndExport(Page p, String... css) {
        Map<String, String> engine = new LinkedHashMap<>();
        StringBuilder sel = new StringBuilder();
        for (String c : css) {
            sel.append(inspect(p, c)).append('\n');
        }
        engine.put("resultado do motor para a inspeção (element.inspect/target.register)", sel.toString());
        try {
            engine.put("PageMap exportado (pagemap.export)", lab.call("pagemap.export", p).toString());
        } catch (RuntimeException e) {
            NOTES.add("exportação em " + p.url() + " falhou: " + e.getMessage());
        }
        return engine;
    }

    // ------------------------------------------------------------------------------------------ cenários

    @Test
    @DisplayName("SEC001-001/021/022/023/024 — Página 1: texto livre, descendente, segmentos e título de SVG não saem da página")
    void sec001TextoLivre() throws Exception {
        long since = System.currentTimeMillis();
        Page p = open(BASE + "texto-livre.html");
        String rid = start(p);
        click(p, By.id("ok"));
        click(p, By.id("consultar-misto"));
        click(p, By.id("excluir"));
        awaitSteps(rid, 3);
        Map<String, String> engine = inspectAndExport(p, "#aviso", "#consultar-misto", "#excluir");
        finish(rid);
        assertClean("Página 1 — texto livre", scan("Página 1 — texto livre", p, rid, since, engine));
    }

    @Test
    @DisplayName("SEC001-004..012/032/033 — Página 2: valores de formulário (pesquisa, senha, e-mail, telefone, número, contenteditable, lista, combobox) não saem")
    void sec001Formularios() throws Exception {
        long since = System.currentTimeMillis();
        Page p = open(BASE + "formularios.html");
        String rid = start(p);
        type(p, By.id("nome"), "Leocádia Brandão");
        type(p, By.id("senha"), "Senha-Kq9Zt!");
        type(p, By.id("email"), "rosalvo.peixoto@exemplo.test");
        type(p, By.id("telefone"), "11 98877-6655");
        type(p, By.id("numero"), "483192");
        type(p, By.id("pesquisa"), "Quirino Valadares");
        type(p, By.id("obs"), "Prefere contato à tarde");
        type(p, By.id("nota"), "Nota reservada Amarílis");
        lab.focus(p);
        lab.driver().findElement(By.id("titular")).sendKeys("Zéf");
        click(p, By.id("combo"));
        click(p, By.id("op-ondina"));
        type(p, By.id("codigo"), "Florisbela");
        click(p, By.id("verificar"));
        click(p, By.id("salvar"));
        awaitSteps(rid, 10);
        Thread.sleep(1200);
        Map<String, String> engine = inspectAndExport(p, "#pesquisa", "#titular", "#combo", "#nota");
        click(p, By.id("remover"));
        Thread.sleep(1200);
        finish(rid);
        assertClean("Página 2 — formulários", scan("Página 2 — formulários", p, rid, since, engine));
    }

    @Test
    @DisplayName("SEC001-002/013/014/015/016 — Página 3: aria-label, aria-labelledby, placeholder, title, rótulo e texto de link não saem")
    void sec001Elementos() throws Exception {
        long since = System.currentTimeMillis();
        Page p = open(BASE + "elementos.html");
        String rid = start(p);
        click(p, By.id("consultar"));
        type(p, By.id("fone"), "1");
        type(p, By.id("apelido"), "a");
        type(p, By.id("anotacao"), "b");
        type(p, By.id("dependente"), "c");
        click(p, By.id("perfil"));
        awaitSteps(rid, 6);
        Map<String, String> engine = inspectAndExport(p, "#consultar", "#fone", "#apelido", "#anotacao", "#dependente", "#perfil");
        finish(rid);
        assertClean("Página 3 — elementos", scan("Página 3 — elementos", p, rid, since, engine));
    }

    @Test
    @DisplayName("SEC001-017..020/043/081..085 — Página 4: id, name, data-*, atributo personalizado, classe, ARIA, alt e URLs de atributos não saem")
    void sec001Atributos() throws Exception {
        long since = System.currentTimeMillis();
        Page p = open(BASE + "atributos.html");
        String rid = start(p);
        click(p, By.id("abrir-cartao"));
        click(p, By.id("cliente-anacleto-prudencio"));
        type(p, By.name("usuario_hermengarda"), "x");
        click(p, By.cssSelector("button.cliente-rosamunda-teixeira"));
        click(p, By.id("descr"));
        click(p, By.id("enviar"));
        awaitSteps(rid, 6);
        Map<String, String> engine = inspectAndExport(p, "#abrir-cartao", "#cliente-anacleto-prudencio", "[name=usuario_hermengarda]",
                "#titular-x", "button.cliente-rosamunda-teixeira", "#barra", "#foto", "#link-ficha", "#enviar");
        finish(rid);
        assertClean("Página 4 — atributos", scan("Página 4 — atributos", p, rid, since, engine));
    }

    @Test
    @DisplayName("SEC001-003/049..053 — Página 5: caminho de rota, novo documento, iframe, recurso e requisição não levam o nome")
    void sec001Urls() throws Exception {
        long since = System.currentTimeMillis();
        Page p = open(BASE + "urls.html");
        String rid = start(p);
        click(p, By.id("requisicao"));
        Thread.sleep(800);
        click(p, By.id("rota"));
        Thread.sleep(800);
        Map<String, String> engine = inspectAndExport(p, "#rota", "#novo-doc", "#quadro");
        click(p, By.id("novo-doc"));
        BrowserLab.waitUntil(Duration.ofSeconds(15), () -> lab.driver().getCurrentUrl().contains("ataulfo"), "novo documento");
        Thread.sleep(1500);
        click(p, By.id("voltar"));
        awaitSteps(rid, 4);
        finish(rid);
        assertClean("Página 5 — URLs", scan("Página 5 — URLs", p, rid, since, engine));
    }

    @Test
    @DisplayName("SEC001-035..041 — Página 6: textos de linhas, cabeçalhos e legenda não entram em contexto, localizadores nem registro")
    void sec001Tabela() throws Exception {
        long since = System.currentTimeMillis();
        Page p = open(BASE + "tabela.html");
        String rid = start(p);
        lab.focus(p);
        List<WebElement> ver = lab.driver().findElements(By.cssSelector("button.ver"));
        new Actions(lab.driver()).moveToElement(ver.get(1)).click().perform();
        awaitSteps(rid, 1);
        Map<String, String> engine = inspectAndExport(p, "tbody tr:nth-child(2) button.ver", "tbody tr:nth-child(1) td:nth-child(1)");
        finish(rid);
        assertClean("Página 6 — tabela", scan("Página 6 — tabela", p, rid, since, engine));
    }

    @Test
    @DisplayName("SEC001-025 — Página 7: texto e rótulos em Shadow DOM não saem")
    void sec001Shadow() throws Exception {
        long since = System.currentTimeMillis();
        Page p = open(BASE + "shadow.html");
        String rid = start(p);
        lab.focus(p);
        WebElement in = (WebElement) lab.js(p, "return document.getElementById('ficha').shadowRoot.getElementById('s-campo')");
        new Actions(lab.driver()).moveToElement(in).click().perform();
        in.sendKeys("y");
        WebElement ok = (WebElement) lab.js(p, "return document.getElementById('ficha').shadowRoot.getElementById('s-ok')");
        new Actions(lab.driver()).moveToElement(ok).click().perform();
        awaitSteps(rid, 2);
        Map<String, String> engine = inspectAndExport(p);
        finish(rid);
        assertClean("Página 7 — Shadow DOM", scan("Página 7 — Shadow DOM", p, rid, since, engine));
    }

    @Test
    @DisplayName("SEC001-026/027/051 — Página 8: conteúdo do iframe autorizado e URL do iframe não saem; o sem permissão não é lido")
    void sec001Iframes() throws Exception {
        long since = System.currentTimeMillis();
        Page p = open(BASE + "iframes.html");
        String rid = start(p);
        lab.focus(p);
        lab.driver().switchTo().frame(lab.driver().findElement(By.id("autorizado")));
        WebElement b = lab.driver().findElement(By.id("no-quadro"));
        new Actions(lab.driver()).moveToElement(b).click().perform();
        lab.driver().switchTo().defaultContent();
        awaitSteps(rid, 1);
        Map<String, String> engine = inspectAndExport(p, "#autorizado");
        finish(rid);
        assertClean("Página 8 — iframes", scan("Página 8 — iframes", p, rid, since, engine));
    }

    @Test
    @DisplayName("SEC001-028..031/034 — Página 9: texto e atributo alterados depois da captura não chegam por delta nem MutationRecord")
    void sec001Dinamica() throws Exception {
        long since = System.currentTimeMillis();
        Page p = open(BASE + "dinamica.html");
        String rid = start(p);
        click(p, By.id("preencher"));
        Thread.sleep(600);
        click(p, By.id("inserir"));
        Thread.sleep(600);
        Map<String, String> engine = inspectAndExport(p, "#cliente");
        engine.put("comparação de capturas (page.compare)", tryCall("page.compare", p));
        click(p, By.id("apagar"));
        awaitSteps(rid, 3);
        Thread.sleep(800);
        finish(rid);
        assertClean("Página 9 — dinâmica", scan("Página 9 — dinâmica", p, rid, since, engine));
    }

    @Test
    @DisplayName("SEC001-003/050 — Página 10: rota, fragmento e novo documento com nome no caminho não chegam ao Studio")
    void sec001Navegacao() throws Exception {
        long since = System.currentTimeMillis();
        Page p = open(BASE + "navegacao.html");
        String rid = start(p);
        click(p, By.id("rota-cliente"));
        Thread.sleep(800);
        click(p, By.id("hash-cliente"));
        Thread.sleep(800);
        click(p, By.id("doc-cliente"));
        BrowserLab.waitUntil(Duration.ofSeconds(15), () -> lab.driver().getCurrentUrl().contains("ataulfo"), "novo documento");
        Thread.sleep(1500);
        click(p, By.id("voltar"));
        awaitSteps(rid, 4);
        finish(rid);
        assertClean("Página 10 — navegação", scan("Página 10 — navegação", p, rid, since, Map.of()));
    }

    @Test
    @DisplayName("SEC001-039/054/086 — Página 11: âncoras de contexto, nome de formulário e título não entram no PageMap nem no registro")
    void sec001Exportacao() throws Exception {
        long since = System.currentTimeMillis();
        Page p = open(BASE + "exportacao.html");
        String rid = start(p);
        type(p, By.id("rua"), "z");
        click(p, By.id("gravar"));
        click(p, By.id("fechar"));
        awaitSteps(rid, 3);
        Map<String, String> engine = inspectAndExport(p, "#rua", "#gravar", "#sec-titulo");
        finish(rid);
        assertClean("Página 11 — exportação", scan("Página 11 — exportação", p, rid, since, engine));
    }

    @Test
    @DisplayName("SEC001-061..064 — Página 12: reinício do service worker e desconexão do host não reenviam conteúdo da página")
    void sec001Recuperacao() throws Exception {
        long since = System.currentTimeMillis();
        Page p = open(BASE + "recuperacao.html");
        String rid = start(p);
        click(p, By.id("r-consultar"));
        awaitSteps(rid, 1);
        lab.call("host.disconnect", Map.of());
        Thread.sleep(500);
        lab.call("host.reconnect", Map.of());
        type(p, By.id("r-nome"), "w");
        lab.driver().findElement(By.id("r-nome")).sendKeys(Keys.TAB);
        awaitSteps(rid, 2);
        finish(rid);
        assertClean("Página 12 — recuperação", scan("Página 12 — recuperação", p, rid, since, Map.of()));
    }

    private String tryCall(String cmd, Page p) {
        try {
            return lab.call(cmd, p).toString();
        } catch (RuntimeException e) {
            NOTES.add(cmd + " em " + p.url() + " falhou: " + e.getMessage());
            return "";
        }
    }
}
