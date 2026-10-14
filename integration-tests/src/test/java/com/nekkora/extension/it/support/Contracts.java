package com.nekkora.extension.it.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.stream.Collectors;

/** Validação contra os JSON Schemas versionados em {@code contracts/}. */
public final class Contracts {

    private Contracts() {
    }

    public static Path dir() {
        return Paths.get(System.getProperty("nekkora.repo", "..")).toAbsolutePath().normalize().resolve("contracts");
    }

    public static JsonSchema schema(String file) throws IOException {
        try (InputStream in = Files.newInputStream(dir().resolve(file))) {
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(in);
        }
    }

    /** Lança AssertionError listando as violações (até 20). */
    public static void assertValid(String file, JsonNode doc) throws IOException {
        Set<ValidationMessage> errors = schema(file).validate(doc);
        if (!errors.isEmpty()) {
            throw new AssertionError(file + ": " + errors.size() + " violação(ões):\n" + errors.stream().limit(20)
                    .map(ValidationMessage::getMessage).collect(Collectors.joining("\n")));
        }
    }

    public static Set<ValidationMessage> validate(String file, JsonNode doc) throws IOException {
        return schema(file).validate(doc);
    }

    /** Valida contra UMA definição ({@code $defs/<def>}) do arquivo de contrato. */
    public static void assertValidDef(String file, String def, JsonNode doc) throws IOException {
        com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode root = (com.fasterxml.jackson.databind.node.ObjectNode) m.readTree(dir().resolve(file).toFile());
        root.remove("oneOf");
        root.put("$ref", "#/$defs/" + def);
        Set<ValidationMessage> errors = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(root).validate(doc);
        if (!errors.isEmpty()) {
            throw new AssertionError(file + "#" + def + ": " + errors.size() + " violação(ões):\n" + errors.stream().limit(20)
                    .map(ValidationMessage::getMessage).collect(Collectors.joining("\n")) + "\ndocumento: "
                    + doc.toString().substring(0, Math.min(800, doc.toString().length())));
        }
    }
}
