package com.nekkora.extension.host.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.util.List;
import java.util.Set;

/**
 * Validação do PageMap contra {@code contracts/page-map.schema.json} (empacotado no JAR). Para não
 * materializar o PageMap inteiro como árvore, o documento é validado em duas partes equivalentes ao
 * schema completo: o envelope (com {@code elements} vazio) e cada {@code ElementDescriptor} individualmente.
 */
public final class PageMapSchema {

    private static final String LOCATION = "classpath:contracts/page-map.schema.json";

    private final JsonSchema full;
    private final JsonSchema element;

    public PageMapSchema() {
        JsonSchemaFactory f = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        this.full = f.getSchema(SchemaLocation.of(LOCATION));
        this.element = f.getSchema(SchemaLocation.of(LOCATION + "#/$defs/ElementDescriptor"));
    }

    public List<String> validateEnvelope(JsonNode pageMapWithoutElements) {
        return messages(full.validate(pageMapWithoutElements));
    }

    public List<String> validateElement(JsonNode element) {
        return messages(this.element.validate(element));
    }

    private static List<String> messages(Set<ValidationMessage> errors) {
        return errors.stream().limit(20).map(ValidationMessage::getMessage).toList();
    }
}
