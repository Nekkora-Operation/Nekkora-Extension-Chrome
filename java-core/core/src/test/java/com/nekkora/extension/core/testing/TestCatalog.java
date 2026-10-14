package com.nekkora.extension.core.testing;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekkora.extension.core.protection.SignatureCatalog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Lê o catálogo versionado real ({@code contracts/protection-signatures.json}) para os testes do núcleo. */
public final class TestCatalog {

    private static SignatureCatalog cached;

    private TestCatalog() {
    }

    public static synchronized SignatureCatalog load() {
        if (cached == null) {
            try {
                Path p = Path.of("..", "..", "contracts", "protection-signatures.json");
                ObjectMapper m = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
                JsonNode root = m.readTree(Files.readAllBytes(p));
                cached = new SignatureCatalog(root.path("catalogVersion").asText(), root.path("reviewedAt").asText(),
                        m.convertValue(root.path("genericIndicators"), m.getTypeFactory().constructCollectionType(List.class, SignatureCatalog.Indicator.class)),
                        m.convertValue(root.path("signatures"), m.getTypeFactory().constructCollectionType(List.class, SignatureCatalog.Signature.class)));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        return cached;
    }
}
