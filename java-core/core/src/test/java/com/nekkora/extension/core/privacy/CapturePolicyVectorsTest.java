package com.nekkora.extension.core.privacy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * CHROME-SECURITY-001 — SEC001-066/067: a implementação Java da política {@code nekkora-privacy/2} produz exatamente os
 * resultados dos vetores compartilhados com a extensão ({@code contracts/privacy/privacy-v2-vectors.json}).
 */
class CapturePolicyVectorsTest {

    private static JsonNode read(String file) throws IOException {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("contracts/privacy/" + file))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("contracts/privacy/" + file + " não encontrado");
        }
        return new ObjectMapper().readTree(dir.resolve("contracts/privacy/" + file).toFile());
    }

    private static String text(JsonNode n) {
        return n == null || n.isNull() ? null : n.asText();
    }

    @Test
    @DisplayName("SEC001-066/067 — vetores compartilhados: texto de interface, identificadores, classes, URLs, expressões, atributos, portadores")
    void sharedVectors() throws IOException {
        JsonNode v = read("privacy-v2-vectors.json");
        assertEquals(CapturePrivacyPolicy.VERSION, v.path("policy").asText());
        for (JsonNode c : v.get("interfaceText")) {
            assertEquals(text(c.get("out")), CapturePrivacyPolicy.interfaceText(text(c.get("in"))), "interfaceText " + c.get("in"));
        }
        for (JsonNode c : v.get("identifiers")) {
            assertEquals(text(c.get("out")), CapturePrivacyPolicy.identifier(text(c.get("in"))), "identifier " + c.get("in"));
        }
        for (JsonNode c : v.get("classes")) {
            assertEquals(text(c.get("out")), CapturePrivacyPolicy.classes(text(c.get("in"))), "classes " + c.get("in"));
        }
        for (JsonNode c : v.get("urls")) {
            assertEquals(text(c.get("out")), CapturePrivacyPolicy.urlTemplate(text(c.get("in"))), "urlTemplate " + c.get("in"));
        }
        for (JsonNode c : v.get("expressions")) {
            assertEquals(c.get("out").asBoolean(), CapturePrivacyPolicy.expressionSafe(text(c.get("in"))), "expressionSafe " + c.get("in"));
        }
        for (JsonNode c : v.get("attributes")) {
            assertEquals(text(c.get("out")), CapturePrivacyPolicy.attribute(c.get("name").asText(), text(c.get("value")), text(c.get("tag")),
                    text(c.get("type"))), "attribute " + c.get("name") + "=" + c.get("value"));
        }
        for (JsonNode c : v.get("carriers")) {
            assertEquals(c.get("out").asBoolean(), CapturePrivacyPolicy.isCarrier(text(c.get("tag")), text(c.get("role")),
                    c.get("hasHref").asBoolean()), "isCarrier " + c.get("tag") + "/" + c.get("role"));
        }
    }

    @Test
    @DisplayName("SEC001-066 — o vocabulário empacotado no motor é a fonte única, fechada e sem os sobrenomes excluídos")
    void vocabularyIsTheSingleSource() throws IOException {
        JsonNode src = read("ui-vocabulary.json");
        Set<String> terms = new HashSet<>();
        src.get("terms").forEach(t -> terms.add(t.asText()));
        assertEquals(src.path("version").asText(), CapturePrivacyPolicy.vocabularyVersion());
        assertEquals(terms.size(), CapturePrivacyPolicy.vocabularySize());
        for (JsonNode name : src.get("excludedOnPurpose")) {
            assertNull(CapturePrivacyPolicy.interfaceText(name.asText()), "sobrenome comum fica fora: " + name);
        }
        assertFalse(CapturePrivacyPolicy.vocabularyOnly("Quixadá"));
        CapturePrivacyPolicy.vocabularyOnly("Quixadá Quixadá");
        assertEquals(terms.size(), CapturePrivacyPolicy.vocabularySize(), "sem expansão em execução");
    }
}
