package com.nekkora.extension.host.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nekkora.extension.core.locator.query.Predicate;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Mapeamento JSON do contrato. As anotações de polimorfismo ficam em mix-ins aqui na infraestrutura,
 * para que o domínio ({@code nekkora-extension-core}) não dependa do Jackson.
 */
public final class JsonCodec {

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = Predicate.AttrEquals.class, name = "ATTR_EQUALS"),
            @JsonSubTypes.Type(value = Predicate.AttrPresent.class, name = "ATTR_PRESENT"),
            @JsonSubTypes.Type(value = Predicate.TextEquals.class, name = "TEXT_EQUALS"),
            @JsonSubTypes.Type(value = Predicate.HasChildText.class, name = "HAS_CHILD_TEXT"),
            @JsonSubTypes.Type(value = Predicate.NthOfType.class, name = "NTH_OF_TYPE"),
            @JsonSubTypes.Type(value = Predicate.LabelTextEquals.class, name = "LABEL_TEXT_EQUALS"),
            @JsonSubTypes.Type(value = Predicate.RoleIs.class, name = "ROLE_IS"),
            @JsonSubTypes.Type(value = Predicate.NameEquals.class, name = "NAME_EQUALS")
    })
    abstract static class PredicateMixin {
    }

    private final ObjectMapper mapper;

    public JsonCodec() {
        this.mapper = new ObjectMapper()
                .addMixIn(Predicate.class, PredicateMixin.class)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
                .setSerializationInclusion(JsonInclude.Include.ALWAYS);
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    public String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String writePretty(Object value) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public JsonNode tree(Object value) {
        return mapper.valueToTree(value);
    }

    public JsonNode parse(String json) throws IOException {
        return mapper.readTree(json);
    }

    public <T> T read(JsonNode node, Class<T> type) throws IOException {
        return mapper.treeToValue(node, type);
    }

    public <T> T read(String json, Class<T> type) throws IOException {
        return mapper.readValue(json, type);
    }
}
