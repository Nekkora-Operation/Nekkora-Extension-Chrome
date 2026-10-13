package com.nekkora.extension.it.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Obtém o Chrome for Testing e o ChromeDriver correspondente em {@code .tools/} (uma vez).
 *
 * <p>Motivo: o Google Chrome de marca ignora {@code --load-extension} desde a versão 137; o Chrome for
 * Testing mantém o suporte e tem versão fixa e reprodutível. Versão: {@code -Dnekkora.cft.version}
 * ou o canal Stable da lista "last-known-good" do projeto Chrome for Testing.
 */
public final class ChromeForTesting {

    private static final String KNOWN_GOOD = "https://googlechromelabs.github.io/chrome-for-testing/last-known-good-versions-with-downloads.json";
    private static final String BY_VERSION = "https://googlechromelabs.github.io/chrome-for-testing/known-good-versions-with-downloads.json";

    public record Installation(String version, Path chrome, Path chromedriver) {
    }

    private ChromeForTesting() {
    }

    public static synchronized Installation ensure(Path toolsDir) throws IOException, InterruptedException {
        String platform = platform();
        String requested = System.getProperty("nekkora.cft.version", "");
        Path marker = toolsDir.resolve("cft-version.txt");
        if (requested.isBlank() && Files.exists(marker)) {
            requested = Files.readString(marker).trim();
        }
        if (!requested.isBlank()) {
            Installation existing = locate(toolsDir.resolve(requested), platform, requested);
            if (existing != null) {
                return existing;
            }
        }
        ObjectMapper m = new ObjectMapper();
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        JsonNode downloads;
        String version;
        if (requested.isBlank()) {
            JsonNode stable = m.readTree(get(http, KNOWN_GOOD)).at("/channels/Stable");
            version = stable.get("version").asText();
            downloads = stable.get("downloads");
        } else {
            version = requested;
            downloads = null;
            for (JsonNode v : m.readTree(get(http, BY_VERSION)).get("versions")) {
                if (v.get("version").asText().equals(version)) {
                    downloads = v.get("downloads");
                }
            }
            if (downloads == null) {
                throw new IOException("Versão do Chrome for Testing não encontrada: " + version);
            }
        }
        Path dir = toolsDir.resolve(version);
        Files.createDirectories(dir);
        for (String product : new String[]{"chrome", "chromedriver"}) {
            String url = null;
            for (JsonNode d : downloads.get(product)) {
                if (d.get("platform").asText().equals(platform)) {
                    url = d.get("url").asText();
                }
            }
            if (url == null) {
                throw new IOException("Sem download de " + product + " para " + platform);
            }
            Path zip = dir.resolve(product + ".zip");
            if (!Files.exists(zip)) {
                HttpResponse<InputStream> r = http.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofInputStream());
                if (r.statusCode() != 200) {
                    throw new IOException("Falha ao baixar " + url + ": HTTP " + r.statusCode());
                }
                Path tmp = dir.resolve(product + ".zip.part");
                Files.copy(r.body(), tmp, StandardCopyOption.REPLACE_EXISTING);
                Files.move(tmp, zip, StandardCopyOption.REPLACE_EXISTING);
            }
            unzip(zip, dir);
        }
        Files.writeString(marker, version);
        Installation inst = locate(dir, platform, version);
        if (inst == null) {
            throw new IOException("Chrome for Testing extraído, mas executáveis não encontrados em " + dir);
        }
        return inst;
    }

    private static Installation locate(Path dir, String platform, String version) {
        boolean win = platform.startsWith("win");
        Path chrome = switch (platform) {
            case "mac-arm64", "mac-x64" -> dir.resolve("chrome-" + platform).resolve("Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing");
            default -> dir.resolve("chrome-" + platform).resolve(win ? "chrome.exe" : "chrome");
        };
        Path driver = dir.resolve("chromedriver-" + platform).resolve(win ? "chromedriver.exe" : "chromedriver");
        if (Files.isRegularFile(chrome) && Files.isRegularFile(driver)) {
            chrome.toFile().setExecutable(true);
            driver.toFile().setExecutable(true);
            return new Installation(version, chrome, driver);
        }
        return null;
    }

    private static String get(HttpClient http, String url) throws IOException, InterruptedException {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) {
            throw new IOException("HTTP " + r.statusCode() + " em " + url);
        }
        return r.body();
    }

    private static void unzip(Path zip, Path dest) throws IOException {
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                Path out = dest.resolve(e.getName()).normalize();
                if (!out.startsWith(dest)) {
                    throw new IOException("Entrada de zip fora do destino: " + e.getName());
                }
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    if (!Files.exists(out)) {
                        Files.copy(in, out);
                    }
                }
            }
        }
    }

    static String platform() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return arch.contains("64") ? "win64" : "win32";
        }
        if (os.contains("mac")) {
            return arch.contains("aarch64") || arch.contains("arm") ? "mac-arm64" : "mac-x64";
        }
        return "linux64";
    }
}
