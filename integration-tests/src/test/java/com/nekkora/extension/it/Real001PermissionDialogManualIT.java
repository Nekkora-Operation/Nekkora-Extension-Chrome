package com.nekkora.extension.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nekkora.extension.host.tools.ExtensionId;
import com.nekkora.extension.it.support.BrowserLab;
import com.nekkora.extension.it.support.ChromeForTesting;
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
import org.openqa.selenium.WindowType;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;

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
 * NEKKORA-REAL-001 — verificação ASSISTIDA do diálogo nativo de permissão no Google Chrome CONVENCIONAL, com a
 * extensão PUBLICADA (nenhuma origem concedida na instalação). A automação apenas clica nos botões do painel; a
 * aprovação no diálogo do Chrome é feita por uma PESSOA (nunca automatizada). Fora da suíte padrão (*ManualIT):
 * {@code scripts/run-tests.ps1 -Suite integration -Test Real001PermissionDialogManualIT}.
 */
class Real001PermissionDialogManualIT {

    private static final ObjectMapper M = new ObjectMapper();
    private static final int DEBUG_PORT = Integer.getInteger("nekkora.manual.debugPort", 9334);
    private static final Duration APPROVAL = Duration.ofSeconds(Integer.getInteger("nekkora.manual.approvalSeconds", 180));
    private static final String SITE = "https://en.wikipedia.org/wiki/Brazil";
    private static final String SITE_PATTERN = "https://en.wikipedia.org/*";
    private static final String OTHER = "https://pt.wikipedia.org/wiki/Brasil";
    private static final List<String> BROAD = List.of("https://*/*", "http://*/*");
    private static Path repo;
    private static Path profile;
    private static Process chrome;
    private static WebDriver driver;
    private static String harness;
    private static String extensionId;
    private static final ObjectNode EVIDENCE = M.createObjectNode();

    @BeforeAll
    static void start() throws Exception {
        Path exe = Paths.get(System.getProperty("nekkora.branded.chrome", "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe"));
        Assumptions.assumeTrue(Files.isRegularFile(exe), "Google Chrome convencional não instalado: " + exe);
        repo = Paths.get(System.getProperty("nekkora.repo", "..")).toAbsolutePath().normalize();
        Path ext = repo.resolve("chrome-extension"); // extensão PUBLICADA: optional_host_permissions, nada pré-concedido
        extensionId = ExtensionId.fromManifest(ext.resolve("manifest.json"));
        Process inst = new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                repo.resolve("native-host/scripts/install-host.ps1").toString(), "-SkipBuild").redirectErrorStream(true).start();
        String instOut = new String(inst.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, inst.waitFor(), instOut);
        profile = Files.createTempDirectory("nekkora-manual-profile");
        Path hostData = repo.resolve("integration-tests").resolve("target").resolve("host-data-manual");
        Files.createDirectories(hostData);
        ProcessBuilder pb = new ProcessBuilder(exe.toString(), "--remote-debugging-port=" + DEBUG_PORT, "--enable-unsafe-extension-debugging",
                "--user-data-dir=" + profile, "--no-first-run", "--no-default-browser-check", "--disable-search-engine-choice-screen",
                "--window-size=1366,900", "--lang=pt-BR", "about:blank");
        pb.environment().put("NEKKORA_DATA_DIR", hostData.toString());
        chrome = pb.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        EVIDENCE.put("browser", waitDevTools());
        assertEquals(extensionId, loadUnpacked(ext));
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
        return r.at("/result/id").asText();
    }

    @AfterAll
    static void stop() throws Exception {
        try {
            if (repo != null && !EVIDENCE.isEmpty()) {
                Files.writeString(repo.resolve("reports/evidence/R001-12-permission-dialog-manual.json"),
                        M.writerWithDefaultPrettyPrinter().writeValueAsString(EVIDENCE), StandardCharsets.UTF_8);
            }
        } finally {
            if (driver != null) {
                try {
                    driver.quit();
                } catch (RuntimeException ignored) {
                    // anexado
                }
            }
            if (chrome != null) {
                chrome.descendants().forEach(ProcessHandle::destroy);
                chrome.destroy();
                chrome.waitFor(10, TimeUnit.SECONDS);
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

    private static JsonNode callRaw(String cmd, Map<String, ?> args) {
        driver.switchTo().window(harness);
        Object r = ((JavascriptExecutor) driver).executeAsyncScript("const done = arguments[arguments.length - 1];"
                + "window.nekkora.call(arguments[0], arguments[1]).then(done).catch(e => done({ok:false, error:{code:'HARNESS', message:String(e)}}));", cmd, args);
        return M.valueToTree(r);
    }

    private static JsonNode call(String cmd, Map<String, ?> args) {
        JsonNode n = callRaw(cmd, args);
        if (!n.path("ok").asBoolean()) {
            throw new AssertionError(cmd + " → " + n);
        }
        return n.get("result");
    }

    /** Consulta a permissão na janela ATUAL (sem trocar de janela: trocar traria outra janela à frente do diálogo). */
    private static boolean containsHere(List<String> origins) {
        return Boolean.TRUE.equals(((JavascriptExecutor) driver).executeAsyncScript(
                "const done = arguments[arguments.length - 1]; chrome.permissions.contains({origins: arguments[0]}).then(done, () => done(false));", origins));
    }

    private static boolean contains(List<String> origins) {
        driver.switchTo().window(harness);
        return Boolean.TRUE.equals(((JavascriptExecutor) driver).executeAsyncScript(
                "const done = arguments[arguments.length - 1]; chrome.permissions.contains({origins: arguments[0]}).then(done, () => done(false));", origins));
    }

    private static int openTab(String url) {
        driver.switchTo().newWindow(WindowType.WINDOW);
        driver.get(url);
        BrowserLab.waitUntil(Duration.ofSeconds(30), () -> "complete".equals(((JavascriptExecutor) driver).executeScript("return document.readyState")), "página carregada");
        driver.switchTo().window(harness);
        return ((Number) ((JavascriptExecutor) driver).executeAsyncScript(
                "const done = arguments[arguments.length - 1]; window.nekkora.newestTabId().then(done);")).intValue();
    }

    private static String panel;

    private static void showPanel(int tabId) {
        if (panel == null) {
            driver.switchTo().newWindow(WindowType.WINDOW);
            panel = driver.getWindowHandle();
        }
        driver.switchTo().window(panel);
        driver.get("chrome-extension://" + extensionId + "/src/ui/panel.html?tabId=" + tabId);
        BrowserLab.waitUntil(Duration.ofSeconds(15), () -> !driver.findElement(By.id("acc-state")).getText().contains("verificando"), "painel pronto");
    }

    private static String panelState() {
        driver.switchTo().window(panel);
        return driver.findElement(By.id("acc-state")).getText();
    }

    private static void clickPanel(String id) {
        driver.switchTo().window(panel);
        driver.manage().window().maximize(); // o diálogo do Chrome fica preso a esta janela: ela precisa estar à frente
        driver.findElement(By.id(id)).click(); // clique real (gesto do usuário exigido pelo Chrome)
    }

    private static void shot(String name) throws IOException {
        driver.switchTo().window(panel);
        Files.write(repo.resolve("reports/evidence/" + name + ".png"), ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES));
    }

    /** Aguarda a APROVAÇÃO HUMANA no diálogo do Chrome (a automação nunca clica no diálogo). */
    private static void awaitHumanApproval(List<String> origins, String what) throws IOException {
        System.out.println(">>> DIÁLOGO DO CHROME ABERTO (" + what + "): aprove em até " + APPROVAL.toSeconds() + " s <<<");
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // Diagnóstico: o painel informa recusa imediata do Chrome (sem diálogo) na área de mensagens.
        driver.switchTo().window(panel);
        String msg = driver.findElement(By.id("message")).isDisplayed() ? driver.findElement(By.id("message")).getText() : "";
        System.out.println(">>> painel após o clique: '" + msg + "' <<<");
        EVIDENCE.put(what + "_panelMessageAfterClick", msg);
        shot("R001-12-after-click-" + what);
        long t0 = System.nanoTime();
        driver.switchTo().window(panel);
        BrowserLab.waitUntil(APPROVAL, () -> containsHere(origins), "aprovação humana no diálogo do Chrome: " + what);
        EVIDENCE.put(what.replace(' ', '_') + "_approvalSeconds", Math.round((System.nanoTime() - t0) / 1e8) / 10.0);
    }

    @Test
    @DisplayName("R001-12 — Diálogo nativo (Chrome convencional, extensão publicada): autorizar site, revogar, autorização ampla e revogar")
    void permissionDialog() throws Exception {
        assertFalse(contains(List.of(SITE_PATTERN)), "nada concedido na instalação");
        int tab = openTab(SITE);
        showPanel(tab);
        EVIDENCE.put("beforeGrant", panelState());
        assertEquals("Não autorizado", panelState());
        shot("R001-12-panel-not-authorized");

        // 1. Autorizar este site → diálogo do Chrome → aprovação humana
        clickPanel("btn-authorize");
        awaitHumanApproval(List.of(SITE_PATTERN), "site");
        BrowserLab.waitUntil(Duration.ofSeconds(10), () -> panelState().startsWith("Autorizado"), "painel atualizado");
        EVIDENCE.put("afterGrant", panelState());
        shot("R001-12-panel-site-authorized");
        assertEquals(List.of(SITE_PATTERN).toString(), call("access.describe", Map.of("tabId", tab)).get("authorized").toString().replace("\"", ""));
        JsonNode on = call("inspection.activate", Map.of("tabId", tab)); // aba aberta ANTES da autorização: injeção nos frames que faltam
        BrowserLab.waitUntil(Duration.ofSeconds(30), () -> "ACTIVE".equals(call("session.status", Map.of("tabId", tab)).at("/session/status").asText()), "sessão ativa");
        JsonNode sel = call("page.select", Map.of("tabId", tab, "css", "#firstHeading"));
        EVIDENCE.put("selection", sel.at("/inspection/classification/kind").asText() + " " + sel.at("/inspection/classification/name/name").asText());
        assertEquals("HEADING", sel.at("/inspection/classification/kind").asText());
        assertTrue(on.get("active").asBoolean() || "ACTIVATING".equals(on.get("state").asText()) || "ACTIVE".equals(on.get("state").asText()));

        // 2. Revogar acesso a este site (painel)
        clickPanel("btn-revoke");
        BrowserLab.waitUntil(Duration.ofSeconds(15), () -> !contains(List.of(SITE_PATTERN)), "permissão removida");
        BrowserLab.waitUntil(Duration.ofSeconds(15), () -> "INACTIVE".equals(call("inspection.state", Map.of("tabId", tab)).get("state").asText()), "inspeção encerrada");
        JsonNode st = call("inspection.state", Map.of("tabId", tab));
        EVIDENCE.set("afterRevoke", st.get("error"));
        assertEquals("PERMISSION_REVOKED", st.at("/error/code").asText());
        BrowserLab.waitUntil(Duration.ofSeconds(10), () -> "Não autorizado".equals(panelState()), "painel atualizado");
        shot("R001-12-panel-site-revoked");

        // 3. Autorização ampla (segunda confirmação no painel) → diálogo → aprovação humana
        int other = openTab(OTHER);
        showPanel(other);
        clickPanel("btn-authorize-all");
        driver.switchTo().window(panel);
        assertTrue(driver.findElement(By.id("acc-confirm-all")).isDisplayed(), "segunda confirmação exibida antes do diálogo");
        assertFalse(contains(BROAD), "nada concedido só com o primeiro clique");
        shot("R001-12-panel-broad-confirm");
        clickPanel("btn-confirm-all");
        awaitHumanApproval(BROAD, "broad");
        BrowserLab.waitUntil(Duration.ofSeconds(10), () -> panelState().contains("ampla"), "painel atualizado");
        EVIDENCE.put("afterBroad", panelState());
        shot("R001-12-panel-broad-authorized");
        call("inspection.activate", Map.of("tabId", other));
        BrowserLab.waitUntil(Duration.ofSeconds(30), () -> "ACTIVE".equals(call("session.status", Map.of("tabId", other)).at("/session/status").asText()), "sessão ativa (ampla)");

        // 4. Revogar autorização ampla (remove o par https + http)
        clickPanel("btn-revoke");
        BrowserLab.waitUntil(Duration.ofSeconds(15), () -> !contains(List.of("https://*/*")) && !contains(List.of("http://*/*")), "autorização ampla removida");
        BrowserLab.waitUntil(Duration.ofSeconds(15), () -> "INACTIVE".equals(call("inspection.state", Map.of("tabId", other)).get("state").asText()), "inspeção encerrada (ampla)");
        EVIDENCE.put("afterBroadRevoke", panelState());
        shot("R001-12-panel-broad-revoked");
        assertEquals("Não autorizado", panelState());
    }
}
