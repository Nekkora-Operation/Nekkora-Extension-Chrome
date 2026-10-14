package com.nekkora.extension.host.json;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekkora.extension.core.protection.SignatureCatalog;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Carrega o catálogo versionado de assinaturas de proteção ({@code contracts/protection-signatures.json},
 * empacotado no JAR) e o valida antes do uso. Um catálogo inválido impede o diagnóstico (falha explícita).
 */
public final class CatalogLoader {

    public static final String RESOURCE = "contracts/protection-signatures.json";

    private CatalogLoader() {
    }

    public static SignatureCatalog load() {
        try (InputStream in = CatalogLoader.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Catálogo de assinaturas ausente no classpath: " + RESOURCE);
            }
            return parse(in.readAllBytes());
        } catch (IOException e) {
            throw new IllegalStateException("Catálogo de assinaturas ilegível: " + e.getMessage(), e);
        }
    }

    public static SignatureCatalog parse(byte[] json) throws IOException {
        ObjectMapper m = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        JsonNode root = m.readTree(json);
        SignatureCatalog c = new SignatureCatalog(root.path("catalogVersion").asText(null), root.path("reviewedAt").asText(null),
                m.convertValue(root.path("genericIndicators"), m.getTypeFactory().constructCollectionType(List.class, SignatureCatalog.Indicator.class)),
                m.convertValue(root.path("signatures"), m.getTypeFactory().constructCollectionType(List.class, SignatureCatalog.Signature.class)));
        List<String> problems = c.validate();
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Catálogo de assinaturas inválido: " + problems);
        }
        return c;
    }
}
