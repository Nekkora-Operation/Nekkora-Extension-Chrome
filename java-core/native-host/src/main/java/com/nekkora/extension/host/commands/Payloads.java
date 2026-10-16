package com.nekkora.extension.host.commands;

import com.fasterxml.jackson.databind.JsonNode;

/** Leitura validada de campos da carga. */
final class Payloads {

    /**
     * Campos que são IDENTIFICADORES técnicos (gerados pela extensão/motor). CHROME-SECURITY-001: têm forma fechada; um
     * valor fora dela é recusado sem eco — as mensagens de erro citam identificadores, então um texto da página posto num
     * desses campos não pode voltar numa resposta ou num log.
     */
    private static final java.util.Set<String> IDENTIFIERS = java.util.Set.of("tabKey", "captureId", "baseCaptureId", "beforeCaptureId",
            "afterCaptureId", "nodeId", "hostNodeId", "targetId", "sessionId", "operationId", "source");
    private static final java.util.regex.Pattern IDENTIFIER = java.util.regex.Pattern.compile("[A-Za-z0-9:._+-]{1,160}"); // "+": captura com frame anexado (cap-…+x12)

    private Payloads() {
    }

    static String required(JsonNode p, String field) {
        JsonNode v = p.get(field);
        if (v == null || v.isNull() || v.asText().isBlank()) {
            throw new CommandException("BAD_REQUEST", "Campo obrigatório ausente: " + field);
        }
        return checked(field, v.asText());
    }

    static String optional(JsonNode p, String field) {
        JsonNode v = p.get(field);
        return v == null || v.isNull() || v.asText().isBlank() ? null : checked(field, v.asText());
    }

    private static String checked(String field, String value) {
        if (IDENTIFIERS.contains(field) && !IDENTIFIER.matcher(value).matches()) {
            throw new CommandException("BAD_REQUEST", "Identificador fora do formato: " + field);
        }
        return value;
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
