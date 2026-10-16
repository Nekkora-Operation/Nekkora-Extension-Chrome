package com.nekkora.extension.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nekkora.extension.host.tools.ExtensionId;
import com.nekkora.extension.it.support.BrowserLab;
import com.nekkora.extension.it.support.ChromeForTesting;
import com.nekkora.extension.it.support.Contracts;
import com.nekkora.extension.lab.LabServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.WindowType;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.interactions.Actions;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CHROME-002 §30 — homologação no Google Chrome CONVENCIONAL (de marca), não no Chrome for Testing.
 *
 * <p>O Chrome de marca ≥ 137 ignora {@code --load-extension}; a extensão é carregada SEM COMPACTAÇÃO pelo
 * método oficial do DevTools Protocol {@code Extensions.loadUnpacked} (exige
 * {@code --enable-unsafe-extension-debugging}). O perfil é temporário e descartado; somente páginas do
 * laboratório local são abertas. O ChromeDriver se anexa ao navegador já iniciado ({@code debuggerAddress}).
 */
class BrandedChromeIT {

    private static final ObjectMapper M = new ObjectMapper();
    private static final int PORT = Integer.getInteger("nekkora.branded.labPort", 18865);
    private static final int DEBUG_PORT = Integer.getInteger("nekkora.branded.debugPort", 9333);
    private static Path repo;
    private static Path profile;
    private static Path hostData;
    private static Process chrome;
    private static LabServer server;
    private static WebDriver driver;
    private static String harness;
    private static String extensionId;
    private static final ObjectNode EVIDENCE = M.createObjectNode();

    @BeforeAll
    static void start() throws Exception {
        Path exe = Paths.get(System.getProperty("nekkora.branded.chrome", "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe"));
        Assumptions.assumeTrue(Files.isRegularFile(exe), "Google Chrome convencional não instalado: " + exe);
        repo = Paths.get(System.getProperty("nekkora.repo", "..")).toAbsolutePath().normalize();
        Path ext = repo.resolve("chrome-extension");
        extensionId = ExtensionId.fromManifest(ext.resolve("manifest.json"));
        // Host instalado pelo script oficial (registra também HKCU\Software\Google\Chrome).
        Process inst = new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                repo.resolve("native-host/scripts/install-host.ps1").toString(), "-SkipBuild").redirectErrorStream(true).start();
        String instOut = new String(inst.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, inst.waitFor(), instOut);
        server = new LabServer(PORT, repo.resolve("test-lab").resolve("pages"));
        server.start();
        profile = Files.createTempDirectory("nekkora-branded-profile");
        hostData = repo.resolve("integration-tests").resolve("target").resolve("host-data-branded");
        Files.createDirectories(hostData);
        ProcessBuilder pb = new ProcessBuilder(exe.toString(), "--remote-debugging-port=" + DEBUG_PORT, "--enable-unsafe-extension-debugging",
                "--user-data-dir=" + profile, "--no-first-run", "--no-default-browser-check", "--disable-search-engine-choice-screen",
                "--window-size=1366,900", "--lang=pt-BR", "--disable-backgrounding-occluded-windows", "--disable-renderer-backgrounding",
                "--disable-background-timer-throttling", "--disable-features=CalculateNativeWinOcclusion", "about:blank");
        pb.environment().put("NEKKORA_DATA_DIR", hostData.toString());
        chrome = pb.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        String version = waitDevTools();
        EVIDENCE.put("browser", version);
        String loaded = loadUnpacked(ext);
        assertEquals(extensionId, loaded, "id fixo da extensão (chave no manifest)");
        ChromeForTesting.Installation cft = ChromeForTesting.ensure(repo.resolve(".tools").resolve("chrome-for-testing"));
        ChromeDriverService service = new ChromeDriverService.Builder().usingDriverExecutable(cft.chromedriver().toFile()).usingAnyFreePort().build();
        ChromeOptions o = new ChromeOptions();
        o.setExperimentalOption("debuggerAddress", "127.0.0.1:" + DEBUG_PORT);
        driver = new ChromeDriver(service, o);
        driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(120));
        driver.get("chrome-extension://" + extensionId + "/src/ui/harness.html");
        harness = driver.getWindowHandle();
        BrowserLab.waitUntil(Duration.ofSeconds(20), () -> Boolean.TRUE.equals(((JavascriptExecutor) driver).executeScript("return !!(window.nekkora && window.nekkora.call)")), "harness");
    }

    private static String waitDevTools() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        long end = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < end) {
            try {
                HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + DEBUG_PORT + "/json/version")).build(), HttpResponse.BodyHandlers.ofString());
                return M.readTree(r.body()).get("Browser").asText();
            } catch (IOException e) {
                Thread.sleep(200);
            }
        }
        throw new IllegalStateException("DevTools do Chrome não respondeu");
    }

    /** CDP Extensions.loadUnpacked pelo WebSocket do navegador (sem cabeçalho Origin). */
    private static String loadUnpacked(Path ext) throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        String ws = M.readTree(http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + DEBUG_PORT + "/json/version")).build(),
                HttpResponse.BodyHandlers.ofString()).body()).get("webSocketDebuggerUrl").asText();
        CompletableFuture<String> reply = new CompletableFuture<>();
        StringBuilder buf = new StringBuilder();
        WebSocket socket = http.newWebSocketBuilder().buildAsync(URI.create(ws), new WebSocket.Listener() {
            @Override
            public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
                buf.append(data);
                if (last) {
                    reply.complete(buf.toString());
                }
                w.request(1);
                return null;
            }
        }).get(10, TimeUnit.SECONDS);
        ObjectNode msg = M.createObjectNode().put("id", 1).put("method", "Extensions.loadUnpacked");
        msg.putObject("params").put("path", ext.toString());
        socket.sendText(msg.toString(), true).get(10, TimeUnit.SECONDS);
        JsonNode r = M.readTree(reply.get(30, TimeUnit.SECONDS));
        socket.sendClose(WebSocket.NORMAL_CLOSURE, "ok");
        if (r.has("error")) {
            throw new IllegalStateException("Extensions.loadUnpacked: " + r.get("error"));
        }
        EVIDENCE.put("loadUnpacked", r.toString());
        return r.at("/result/id").asText();
    }

    @AfterAll
    static void stop() throws Exception {
        try {
            if (repo != null && !EVIDENCE.isEmpty()) {
                Files.writeString(repo.resolve("reports/evidence/C002-branded-chrome.json"), M.writerWithDefaultPrettyPrinter().writeValueAsString(EVIDENCE), StandardCharsets.UTF_8);
            }
        } finally {
            if (driver != null) {
                try {
                    driver.quit();
                } catch (RuntimeException ignored) {
                    // anexado: o navegador é encerrado abaixo
                }
            }
            if (chrome != null) {
                chrome.descendants().forEach(ProcessHandle::destroy);
                chrome.destroy();
                chrome.waitFor(10, TimeUnit.SECONDS);
            }
            if (server != null) {
                server.stop();
            }
            if (profile != null) {
                try (Stream<Path> s = Files.walk(profile)) {
                    s.sorted(Comparator.reverseOrder()).forEach(x -> x.toFile().delete());
                } catch (IOException ignored) {
                    // perfil temporário
                }
            }
        }
    }

    // ------------------------------------------------------------------------------- utilitários

    private static JsonNode call(String cmd, Map<String, ?> args) {
        driver.switchTo().window(harness);
        Object r = ((JavascriptExecutor) driver).executeAsyncScript("const done = arguments[arguments.length - 1];"
                + "window.nekkora.call(arguments[0], arguments[1]).then(done).catch(e => done({ok:false, error:{code:'HARNESS', message:String(e)}}));", cmd, args);
        JsonNode n = M.valueToTree(r);
        if (!n.path("ok").asBoolean()) {
            throw new AssertionError(cmd + " → " + n);
        }
        return n.get("result");
    }

    private static Object harnessAsync(String script, Object... args) {
        driver.switchTo().window(harness);
        return ((JavascriptExecutor) driver).executeAsyncScript("const done = arguments[arguments.length - 1];" + script, args);
    }

    @Test
    @DisplayName("C002-HOM — Chrome convencional (§30): host, extensão sem compactação, painel nativo, captura automática, seleção, destaque, exportação e liberação")
    void brandedChromeHomologation() throws Exception {
        // 1. Motor Java via Native Messaging no Chrome de marca.
        JsonNode ping = call("host.ping", Map.of());
        assertTrue(ping.at("/ping/java").asText().startsWith("21"), ping.toString());
        EVIDENCE.set("hostPing", ping.get("ping"));
        // 2. Página do laboratório local.
        driver.switchTo().newWindow(WindowType.WINDOW);
        String url = "http://127.0.0.1:" + PORT + "/lab/chrome002/continuous.html";
        driver.get(url);
        String page = driver.getWindowHandle();
        Number tab = (Number) harnessAsync("window.nekkora.tabIdFor(arguments[0]).then(done);", url);
        int tabId = tab.intValue();
        BrowserLab.waitUntil(Duration.ofSeconds(15), () -> {
            try {
                call("page.diagnostics", Map.of("tabId", tabId));
                return true;
            } catch (AssertionError e) {
                return false;
            }
        }, "content script no Chrome convencional");
        // 3. Painel lateral NATIVO (gesto: clique real no harness).
        driver.switchTo().window(harness);
        WebElement spTab = driver.findElement(By.id("sp-tab"));
        spTab.clear();
        spTab.sendKeys(String.valueOf(tabId));
        driver.findElement(By.id("sp-open")).click();
        BrowserLab.waitUntil(Duration.ofSeconds(10), () -> Boolean.TRUE.equals(((JavascriptExecutor) driver)
                .executeScript("return !!(window.nekkoraSidePanel && window.nekkoraSidePanel.opened)")), "side panel aberto");
        JsonNode contexts = M.valueToTree(harnessAsync("window.nekkora.contexts(['SIDE_PANEL']).then(done);"));
        assertEquals(1, contexts.size(), contexts.toString());
        EVIDENCE.set("sidePanelContexts", contexts);
        BrowserLab.waitUntil(Duration.ofSeconds(10), () -> M.valueToTree(harnessAsync("window.nekkora.panel('panel.snapshot').then(done);")).path("host").asText().contains("Conectado"), "painel nativo conectado");
        // 4. Ativação pelo botão do painel nativo → captura automática.
        harnessAsync("window.nekkora.panel('panel.click', {id: 'btn-inspect'}).then(done);");
        BrowserLab.waitUntil(Duration.ofSeconds(30), () -> "ACTIVE".equals(call("session.status", Map.of("tabId", tabId)).at("/session/status").asText()), "captura automática");
        JsonNode s = call("session.status", Map.of("tabId", tabId)).get("session");
        assertEquals("COOPERATIVE", s.at("/initialCapture/mode").asText());
        assertEquals("ACTIVE", call("inspection.state", Map.of("tabId", tabId)).get("state").asText());
        // 5. Seleção por clique real e destaque.
        driver.switchTo().window(page);
        WebElement aprovar = driver.findElement(By.id("aprovar"));
        new Actions(driver).moveToElement(aprovar).click().perform();
        BrowserLab.waitUntil(Duration.ofSeconds(10), () -> call("page.diagnostics", Map.of("tabId", tabId)).at("/highlight/boxes/selection/visible").asBoolean(false), "destaque da seleção");
        BrowserLab.waitUntil(Duration.ofSeconds(10), () -> M.valueToTree(harnessAsync("window.nekkora.panel('panel.snapshot').then(done);"))
                .path("element").asText().contains("Aprovar pedido"), "elemento selecionado exibido no painel nativo");
        EVIDENCE.set("panelAfterSelection", M.valueToTree(harnessAsync("window.nekkora.panel('panel.snapshot').then(done);")));
        // 6. Atualizações incrementais.
        driver.switchTo().window(page);
        ((JavascriptExecutor) driver).executeScript("document.getElementById('adicionar').onclick()");
        JsonNode sync = call("session.synchronize", Map.of("tabId", tabId));
        assertEquals("SYNCHRONIZED", sync.get("syncState").asText());
        assertTrue(call("session.status", Map.of("tabId", tabId)).at("/session/deltasApplied").asInt() >= 1);
        driver.switchTo().window(page);
        Files.write(repo.resolve("reports/evidence/C002-030B-branded-page.png"), ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES));
        // 7. Exportação DEV (manual) — contrato válido, estado incremental incluído.
        JsonNode map = call("pagemap.export", Map.of("tabId", tabId, "returnPageMap", true)).get("pageMap");
        Contracts.assertValid("page-map.schema.json", map);
        // CHROME-SECURITY-001: o item inserido está no mapa (mesma quantidade de itens que a página real); o texto dele não
        driver.switchTo().window(page);
        long liveItems = ((Number) ((JavascriptExecutor) driver).executeScript("return document.querySelectorAll('li').length")).longValue();
        assertEquals(liveItems, com.nekkora.extension.it.support.Policy.countTag(map, "li"), "estado incremental incluído");
        assertFalse(map.toString().contains("Tarefa 3"), "texto do item retido");
        // 8. Desativação pelo painel nativo e liberação de recursos.
        harnessAsync("window.nekkora.panel('panel.click', {id: 'btn-inspect'}).then(done);");
        BrowserLab.waitUntil(Duration.ofSeconds(10), () -> "INACTIVE".equals(call("inspection.state", Map.of("tabId", tabId)).get("state").asText()), "desativado");
        JsonNode d = call("page.diagnostics", Map.of("tabId", tabId));
        assertEquals(0, d.get("listeners").asInt());
        assertEquals(0, d.get("observers").asInt());
        JsonNode mem = call("host.memory", Map.of("gc", true));
        assertEquals(0, mem.at("/retainedCaptures/tab-" + tabId).asInt(), "capturas liberadas no motor");
        EVIDENCE.set("session", s);
        EVIDENCE.put("result", "APROVADO");
        List<String> handles = List.copyOf(driver.getWindowHandles());
        EVIDENCE.put("windows", handles.size());
    }
}
