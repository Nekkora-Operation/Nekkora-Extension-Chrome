package com.nekkora.extension.core.privacy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekkora.extension.core.snapshot.DocumentInfo;
import com.nekkora.extension.core.snapshot.FrameInfo;
import com.nekkora.extension.core.snapshot.ObservedRequest;
import com.nekkora.extension.core.snapshot.RawNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Segunda barreira de privacidade (NEKKORA-REAL-001): paridade com a política do navegador e aplicação no snapshot. */
class PrivacyTest {

    private static JsonNode vectors() throws IOException {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("contracts/privacy/privacy-vectors.json"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("contracts/privacy/privacy-vectors.json não encontrado");
        }
        return new ObjectMapper().readTree(dir.resolve("contracts/privacy/privacy-vectors.json").toFile());
    }

    @Test
    void javaPolicyMatchesTheBrowserPolicyVectors() throws IOException {
        JsonNode v = vectors();
        assertEquals(Privacy.VERSION, v.get("policy").asText());
        for (JsonNode c : v.get("urls")) {
            assertEquals(c.get("out").asText(), Privacy.sanitizeUrl(c.get("in").asText()), "URL: " + c.get("in").asText());
            assertEquals(c.get("out").asText(), Privacy.sanitizeUrl(c.get("out").asText()), "idempotente: " + c.get("out").asText());
        }
        for (JsonNode c : v.get("texts")) {
            assertEquals(c.get("out").asText(), Privacy.maskText(c.get("in").asText()), "texto: " + c.get("in").asText());
            assertEquals(c.get("out").asText(), Privacy.maskText(c.get("out").asText()));
        }
        for (JsonNode c : v.get("attributes")) {
            String name = c.get("name").asText();
            assertEquals(c.get("out").asText(), AttributePrivacy.sanitize(name, c.get("in").asText()), "atributo " + name + "=" + c.get("in").asText());
            assertEquals(c.get("out").asText(), AttributePrivacy.sanitize(name, c.get("out").asText()), "idempotente: " + name);
        }
    }

    @Test
    void snapshotRecordsApplyThePolicyOnConstruction() {
        DocumentInfo d = new DocumentInfo("https://app.test/conta/98765432?token=abc&aba=dados", "Caixa de ana@x.com", "complete", null, "INITIAL", "ps1");
        assertEquals("https://app.test/conta/:redacted?token=REDACTED&aba=dados", d.url());
        assertEquals("Caixa de [email]", d.title());
        FrameInfo f = new FrameInfo("f1", "f0", "n1", "https://app.test/frame?session=xyz", true, null);
        assertEquals("https://app.test/frame?session=REDACTED", f.url(), "document.frames[].url não guarda mais a URL completa");
        assertEquals("/api/busca?q=REDACTED&page=1", new ObservedRequest("/api/busca?q=ana@x.com&page=1", "fetch", 1, 1).url());
        RawNode n = new RawNode("n2", null, List.of(), "a", "f0", null,
                Map.of("href", "/reset?code=abc123", "title", "Enviar para ana@x.com", "id", "link", "data-user", "aB3dE5fG7hJ9kL1mN3pQ5rS7"),
                "CPF 123.456.789-09", null, null, List.of(), null, null);
        assertEquals("/reset?code=REDACTED", n.attr("href"));
        assertEquals("Enviar para [email]", n.attr("title"));
        assertEquals("[redacted]", n.attr("data-user"));
        assertEquals("link", n.attr("id"));
        assertEquals("CPF [cpf]", n.ownText());
    }

    @Test
    void customAttributesWithSensitiveNamesUrlsOrPairsAreRedacted() { // revisão do PR #3, P1
        assertEquals("[redacted]", AttributePrivacy.sanitize("data-password", "hunter2"), "segredo curto em atributo de nome sensível");
        assertEquals("https://example.test/reset?token=REDACTED", AttributePrivacy.sanitize("data-reset-url", "https://example.test/reset?token=abc123"));
        assertEquals("{\"endpoint\":\"https://a.test/?apiKey=REDACTED\",\"password\":\"REDACTED\",\"pin\":REDACTED,\"theme\":\"dark\"}",
                AttributePrivacy.sanitize("data-config", "{\"endpoint\":\"https://a.test/?apiKey=K\",\"password\":\"hunter2\",\"pin\":1234,\"theme\":\"dark\"}"));
        assertEquals("[redacted]", AttributePrivacy.sanitize("x-session-token", "abc"), "atributo personalizado fora de data-*");
        assertEquals("open", AttributePrivacy.sanitize("data-state", "open"), "atributo genérico de componente preservado");
        assertEquals("principal", AttributePrivacy.sanitize("data-contact", "principal"));
        RawNode n = new RawNode("n9", null, List.of(), "div", "f0", null,
                Map.of("data-password", "hunter2", "data-reset-url", "/reset?token=abc123", "data-row", "7"), "", null, null, List.of(), null, null);
        assertEquals("[redacted]", n.attr("data-password"));
        assertEquals("/reset?token=REDACTED", n.attr("data-reset-url"));
        assertEquals("7", n.attr("data-row"));
    }

    @Test
    void untouchedAttributesKeepTheSameMapInstance() {
        Map<String, String> attrs = Map.of("id", "btn", "href", "/clientes?nome=ana", "class", "x");
        assertSame(attrs, AttributePrivacy.sanitize(attrs));
    }

    @Test
    void maskedValuesAreNotLocatorMaterialAndLabOriginsAreRecognized() {
        assertTrue(Privacy.isMasked("/reset?code=REDACTED"));
        assertTrue(Privacy.isMasked("Enviar para [email]"));
        assertFalse(Privacy.isMasked("Enviar"));
        assertTrue(Privacy.isLabOrigin("http://127.0.0.1:18765"));
        assertTrue(Privacy.isLabOrigin("http://localhost:8766/x"));
        assertFalse(Privacy.isLabOrigin("https://en.wikipedia.org"));
        assertFalse(Privacy.isLabOrigin(null));
    }
}
