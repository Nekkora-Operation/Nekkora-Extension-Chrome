package com.nekkora.extension.lab;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LabServerTest {

    @TempDir
    static Path pages;
    static LabServer server;
    static final HttpClient HTTP = HttpClient.newHttpClient();

    @BeforeAll
    static void start() throws Exception {
        Files.writeString(pages.resolve("index.html"), "<h1>Lab</h1>");
        Files.createDirectories(pages.resolve("scenarios/search"));
        Files.writeString(pages.resolve("scenarios/search/clientes.html"), "<h1>Clientes</h1>");
        Files.createDirectories(pages.resolve("spa"));
        Files.writeString(pages.resolve("spa/app.html"), "<h1>SPA</h1>");
        server = new LabServer(0, pages);
        server.start();
    }

    @AfterAll
    static void stop() {
        server.stop();
    }

    static HttpResponse<String> get(String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void routes() throws Exception {
        assertEquals("ok", get("/health").body());
        assertTrue(get("/lab/index.html").body().contains("Lab"));
        assertTrue(get("/clientes?nome=ana&status=ativo").body().contains("Clientes"));
        assertTrue(get("/lab/spa/pedidos").body().contains("SPA"));
        assertEquals(404, get("/lab/../../etc/passwd").statusCode());
        assertEquals(404, get("/lab/inexistente.html").statusCode());
    }

    @Test
    void api() throws Exception {
        String r = get("/api/clientes?nome=ana&status=ativo&size=10").body();
        assertTrue(r.contains("\"total\":3"), r); // Ana, Ana Paula e Mariana (busca por "contém")
    }

    @Test
    void largePage() throws Exception {
        String html = get("/generated/large?n=1000").body();
        int tags = html.split("<[a-z]").length - 1;
        assertTrue(tags > 900 && tags < 1100, "elementos gerados: " + tags);
    }
}
