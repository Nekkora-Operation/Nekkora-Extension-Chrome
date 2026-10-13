package com.nekkora.extension.lab;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Servidor do laboratório de simulação. Escuta SOMENTE em 127.0.0.1 (o mesmo processo também é
 * alcançável como {@code localhost}, o que fornece uma segunda ORIGEM para testes de iframe
 * cross-origin sem infraestrutura adicional).
 *
 * <p>Rotas:
 * <ul>
 *   <li>{@code /lab/...} — páginas estáticas de {@code test-lab/pages};</li>
 *   <li>{@code /clientes} — laboratório de pesquisa e resultados;</li>
 *   <li>{@code /lab/spa/*} — aplicação SPA (fallback para spa/app.html);</li>
 *   <li>{@code /api/clientes}, {@code /api/cidades} — API JSON de teste;</li>
 *   <li>{@code /generated/large?n=N} — página com N elementos para medição de desempenho;</li>
 *   <li>{@code /health} — verificação de disponibilidade.</li>
 * </ul>
 */
public final class LabServer {

    private final HttpServer server;
    private final Path root;
    private final LabApi api = new LabApi();

    public LabServer(int port, Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 64);
        this.server.setExecutor(Executors.newFixedThreadPool(8));
        this.server.createContext("/", this::handle);
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            URI uri = ex.getRequestURI();
            String path = URLDecoder.decode(uri.getRawPath(), StandardCharsets.UTF_8);
            Map<String, String> query = query(uri.getRawQuery());
            if (!"GET".equals(ex.getRequestMethod()) && !"HEAD".equals(ex.getRequestMethod())) {
                send(ex, 405, "text/plain", "Somente GET");
                return;
            }
            if (path.equals("/health")) {
                send(ex, 200, "text/plain", "ok");
            } else if (path.equals("/") || path.equals("/lab") || path.equals("/lab/")) {
                redirect(ex, "/lab/index.html");
            } else if (path.equals("/clientes")) {
                file(ex, "scenarios/search/clientes.html");
            } else if (path.startsWith("/lab/spa/") && !path.contains(".")) {
                file(ex, "spa/app.html");
            } else if (path.startsWith("/lab/")) {
                file(ex, path.substring("/lab/".length()));
            } else if (path.startsWith("/api/")) {
                LabApi.Response r = api.handle(path, query);
                if (r.delayMs() > 0) {
                    Thread.sleep(r.delayMs());
                }
                send(ex, r.status(), "application/json; charset=utf-8", r.body());
            } else if (path.equals("/generated/large")) {
                int n = Math.max(10, Math.min(parseInt(query.get("n"), 1000), 20_000));
                send(ex, 200, "text/html; charset=utf-8", LargePageGenerator.generate(n));
            } else {
                send(ex, 404, "text/plain; charset=utf-8", "Não encontrado: " + path);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            send(ex, 500, "text/plain", "interrompido");
        } catch (RuntimeException e) {
            send(ex, 500, "text/plain; charset=utf-8", "Erro: " + e.getMessage());
        } finally {
            ex.close();
        }
    }

    private void file(HttpExchange ex, String relative) throws IOException {
        Path p = root.resolve(relative).normalize();
        if (!p.startsWith(root) || !Files.isRegularFile(p)) {
            send(ex, 404, "text/plain; charset=utf-8", "Não encontrado: " + relative);
            return;
        }
        send(ex, 200, contentType(p), Files.readAllBytes(p));
    }

    private static String contentType(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        if (n.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (n.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (n.endsWith(".js")) {
            return "text/javascript; charset=utf-8";
        }
        if (n.endsWith(".json")) {
            return "application/json; charset=utf-8";
        }
        if (n.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (n.endsWith(".png")) {
            return "image/png";
        }
        return "application/octet-stream";
    }

    private static void redirect(HttpExchange ex, String to) throws IOException {
        ex.getResponseHeaders().add("Location", to);
        ex.sendResponseHeaders(302, -1);
    }

    private static void send(HttpExchange ex, int status, String type, String body) throws IOException {
        send(ex, status, type, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().add("Content-Type", type);
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        boolean head = "HEAD".equals(ex.getRequestMethod());
        ex.sendResponseHeaders(status, head ? -1 : body.length);
        if (!head) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        }
    }

    static Map<String, String> query(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            out.put(k, v);
        }
        return out;
    }

    static int parseInt(String s, int def) {
        try {
            return s == null ? def : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** Uso: {@code java -jar nekkora-lab-server.jar [porta] [diretório de páginas]}. */
    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8765;
        Path pages = args.length > 1 ? Paths.get(args[1]) : locatePages();
        LabServer s = new LabServer(port, pages);
        s.start();
        System.out.println("Nekkora Test Lab em http://127.0.0.1:" + s.port() + "/lab/index.html (páginas: " + pages.toAbsolutePath() + ")");
    }

    static Path locatePages() {
        for (Path p = Paths.get("").toAbsolutePath(); p != null; p = p.getParent()) {
            Path candidate = p.resolve("test-lab").resolve("pages");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Diretório test-lab/pages não encontrado; informe-o como segundo argumento");
    }
}
