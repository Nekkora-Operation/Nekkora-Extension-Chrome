package com.nekkora.extension.host.commands;

import com.fasterxml.jackson.databind.JsonNode;

/** Leitura validada de campos da carga. */
final class Payloads {

    private Payloads() {
    }

    static String required(JsonNode p, String field) {
        JsonNode v = p.get(field);
        if (v == null || v.isNull() || v.asText().isBlank()) {
            throw new CommandException("BAD_REQUEST", "Campo obrigatório ausente: " + field);
        }
        return v.asText();
    }

    static String optional(JsonNode p, String field) {
        JsonNode v = p.get(field);
        return v == null || v.isNull() || v.asText().isBlank() ? null : v.asText();
    }

    static JsonNode node(JsonNode p, String field) {
        JsonNode v = p.get(field);
        if (v == null || v.isNull()) {
            throw new CommandException("BAD_REQUEST", "Campo obrigatório ausente: " + field);
        }
        return v;
    }

    static boolean flag(JsonNode p, String field, boolean def) {
        JsonNode v = p.get(field);
        return v == null || v.isNull() ? def : v.asBoolean(def);
    }
}
