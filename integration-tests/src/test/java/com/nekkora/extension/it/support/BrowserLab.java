package com.nekkora.extension.it.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekkora.extension.host.tools.ExtensionId;
import com.nekkora.extension.lab.LabServer;
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
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Ambiente real de homologação: servidor do laboratório + host Java registrado + Chrome for Testing
 * com a extensão carregada + página de harness da extensão. Uma única instância por execução.
 */
public final class BrowserLab {

    public static final int PORT = Integer.getInteger("nekkora.lab.port", 18765);
    private static BrowserLab instance;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path repo;
    private final Path evidence;
    private final Path hostData;
    private LabServer server;
    private ChromeDriver driver;
    private String harness;
    private String extensionId;
    private ChromeForTesting.Installation chrome;

    /** Aba do laboratório aberta no Chrome. */
    public record Page(String handle, int tabId, String url) {
    }

    public static class CallException extends RuntimeException {
        public final String code;

        CallException(String code, String message) {
            super(code + ": " + message);
            this.code = code;
        }
    }

    private BrowserLab(Path repo) {
        this.repo = repo;
        this.evidence = repo.resolve("reports").resolve("evidence");
        this.hostData = repo.resolve("integration-tests").resolve("target").resolve("host-data");
    }

    public static synchronized BrowserLab get() {
        if (instance == null) {
            Path repo = Paths.get(System.getProperty("nekkora.repo", "..")).toAbsolutePath().normalize();
            instance = new BrowserLab(repo);
            try {
                instance.start();
            } catch (Exception e) {
                instance = null;
                throw new IllegalStateException("Falha ao preparar o ambiente de homologação: " + e.getMessage(), e);
            }
            Runtime.getRuntime().addShutdownHook(new Thread(instance::stop));
        }
        return instance;
    }

    private void start() throws Exception {
        Files.createDirectories(evidence);
        deleteRecursively(hostData);
        Files.createDirectories(hostData);
        if (!Boolean.getBoolean("nekkora.skipHostInstall")) {
            HostInstaller.install(repo);
        }
        server = new LabServer(PORT, repo.resolve("test-lab").resolve("pages"));
        server.start();
        chrome = ChromeForTesting.ensure(repo.resolve(".tools").resolve("chrome-for-testing"));
        Path ext = repo.resolve("chrome-extension");
        extensionId = ExtensionId.fromManifest(ext.resolve("manifest.json"));
        Path profile = Files.createTempDirectory("nekkora-cft-profile");

        ChromeDriverService service = new ChromeDriverService.Builder()
                .usingDriverExecutable(chrome.chromedriver().toFile())
                .withEnvironment(Map.of("NEKKORA_DATA_DIR", hostData.toString()))
                .usingAnyFreePort()
                .build();
        ChromeOptions o = new ChromeOptions();
        o.setBinary(chrome.chrome().toFile());
        o.addArguments("--load-extension=" + ext, "--disable-extensions-except=" + ext, "--window-size=1366,900",
                "--no-first-run", "--no-default-browser-check", "--disable-search-engine-choice-screen",
                "--user-data-dir=" + profile, "--lang=pt-BR",
                // Harness e página ficam em JANELAS separadas: sem estas opções o Chrome trataria a janela
                // coberta como oculta (sem requestAnimationFrame e com timers estrangulados).
                "--disable-backgrounding-occluded-windows", "--disable-renderer-backgrounding",
                "--disable-background-timer-throttling", "--disable-features=CalculateNativeWinOcclusion");
        if (Boolean.getBoolean("nekkora.headless")) {
            o.addArguments("--headless=new");
        }
        driver = new ChromeDriver(service, o);
        driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(180));
        driver.get("chrome-extension://" + extensionId + "/src/ui/harness.html");
        harness = driver.getWindowHandle();
        new WebDriverWait(driver, Duration.ofSeconds(20)).until(d -> ((JavascriptExecutor) d)
                .executeScript("return !!(window.nekkora && window.nekkora.call)"));
    }

    public void stop() {
        try {
            if (driver != null) {
                driver.quit();
            }
        } catch (RuntimeException ignored) {
            // encerramento
        }
        if (server != null) {
            server.stop();
        }
        driver = null;
        server = null;
    }

    public WebDriver driver() {
        return driver;
    }

    public String chromeVersion() {
        return chrome.version();
    }

    public String extensionId() {
        return extensionId;
    }

    public Path repo() {
        return repo;
    }

    public Path hostData() {
        return hostData;
    }

    public String url(String path) {
        return "http://127.0.0.1:" + PORT + path;
    }

    // ------------------------------------------------------------------ comandos da extensão

    /** Executa um comando do service worker (via página de harness) e devolve {ok, result|error}. */
    public JsonNode callRaw(String cmd, Map<String, ?> args) {
        driver.switchTo().window(harness);
        Object r = ((JavascriptExecutor) driver).executeAsyncScript(
                "const done = arguments[arguments.length - 1];"
                        + "window.nekkora.call(arguments[0], arguments[1]).then(done)"
                        + ".catch(e => done({ok:false, error:{code:'HARNESS', message:String(e)}}));",
                cmd, args == null ? Map.of() : args);
        return mapper.valueToTree(r);
    }

    public JsonNode call(String cmd, Map<String, ?> args) {
        JsonNode r = callRaw(cmd, args);
        if (r == null || !r.path("ok").asBoolean(false)) {
            throw new CallException(r == null ? "NULL" : r.at("/error/code").asText(), r == null ? "sem resposta" : r.at("/error/message").asText());
        }
        return r.get("result");
    }

    public JsonNode call(String cmd, Page page, Object... kv) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("tabId", page.tabId());
        for (int i = 0; i + 1 < kv.length; i += 2) {
            args.put((String) kv[i], kv[i + 1]);
        }
        return call(cmd, args);
    }

    public JsonNode events(String name) {
        driver.switchTo().window(harness);
        return mapper.valueToTree(((JavascriptExecutor) driver).executeScript("return window.nekkora.events(arguments[0]);", name));
    }

    public void clearEvents() {
        driver.switchTo().window(harness);
        ((JavascriptExecutor) driver).executeScript("window.nekkora.clearEvents();");
    }

    // ------------------------------------------------------------------ páginas

    public Page open(String path) {
        String url = url(path);
        driver.switchTo().newWindow(WindowType.WINDOW);
        driver.get(url);
        String handle = driver.getWindowHandle();
        String current = driver.getCurrentUrl();
        new WebDriverWait(driver, Duration.ofSeconds(15)).until(d -> "complete".equals(((JavascriptExecutor) d).executeScript("return document.readyState")));
        driver.switchTo().window(harness);
        Object tab = ((JavascriptExecutor) driver).executeAsyncScript(
                "const done = arguments[arguments.length - 1]; window.nekkora.tabIdFor(arguments[0]).then(done);", current);
        if (tab == null) {
            throw new IllegalStateException("Aba não encontrada para " + current);
        }
        Page p = new Page(handle, ((Number) tab).intValue(), current);
        waitUntil(Duration.ofSeconds(15), () -> {
            JsonNode d = callRaw("page.diagnostics", Map.of("tabId", p.tabId()));
            return d.path("ok").asBoolean(false);
        }, "content script pronto em " + current);
        return p;
    }

    /** Abre uma página interna da extensão (ex.: painel) em nova janela e devolve o handle. */
    public String openExtensionPage(String pathAndQuery) {
        driver.switchTo().newWindow(WindowType.WINDOW);
        driver.get("chrome-extension://" + extensionId + "/" + pathAndQuery);
        return driver.getWindowHandle();
    }

    public void closeWindow(String handle) {
        try {
            driver.switchTo().window(handle);
            driver.close();
        } catch (RuntimeException ignored) {
            // já fechada
        }
        driver.switchTo().window(harness);
    }

    public Path screenshotWindow(String handle, String name) throws IOException {
        driver.switchTo().window(handle);
        Path out = evidence.resolve(name + ".png");
        Files.write(out, ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES));
        return out;
    }

    public void focus(Page p) {
        driver.switchTo().window(p.handle());
    }

    public void close(Page p) {
        try {
            driver.switchTo().window(p.handle());
            driver.close();
        } catch (RuntimeException ignored) {
            // aba já fechada
        }
        driver.switchTo().window(harness);
    }

    /** Clique REAL de ponteiro (eventos confiáveis via WebDriver/CDP), não element.click(). */
    public void click(Page p, By by) {
        focus(p);
        WebElement el = driver.findElement(by);
        new Actions(driver).scrollToElement(el).moveToElement(el).click().perform();
    }

    public Object js(Page p, String script, Object... args) {
        focus(p);
        return ((JavascriptExecutor) driver).executeScript(script, args);
    }

    public Path screenshot(Page p, String name) throws IOException {
        focus(p);
        byte[] png = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES);
        Path out = evidence.resolve(name + ".png");
        Files.write(out, png);
        return out;
    }

    public Path writeEvidence(String name, String content) throws IOException {
        Path out = evidence.resolve(name);
        Files.writeString(out, content, StandardCharsets.UTF_8);
        return out;
    }

    public static void waitUntil(Duration timeout, Supplier<Boolean> condition, String what) {
        long end = System.nanoTime() + timeout.toNanos();
        RuntimeException last = null;
        while (System.nanoTime() < end) {
            try {
                if (Boolean.TRUE.equals(condition.get())) {
                    return;
                }
            } catch (RuntimeException e) {
                last = e;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("Tempo esgotado aguardando: " + what + (last == null ? "" : " (último erro: " + last.getMessage() + ")"));
    }

    private static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) {
            return;
        }
        try (Stream<Path> s = Files.walk(p)) {
            s.sorted(Comparator.reverseOrder()).forEach(x -> x.toFile().delete());
        }
    }
}
