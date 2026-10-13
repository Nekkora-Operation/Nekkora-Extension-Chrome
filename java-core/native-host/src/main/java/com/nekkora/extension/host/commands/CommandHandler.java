package com.nekkora.extension.host.commands;

import com.fasterxml.jackson.databind.JsonNode;

/** Trata um tipo de requisição do protocolo. */
@FunctionalInterface
public interface CommandHandler {

    /**
     * @param payload carga da requisição (nunca {@code null}; objeto vazio se ausente)
     * @return objeto serializável (record, mapa ou {@link JsonNode}) que vira a carga da resposta
     */
    Object handle(JsonNode payload) throws Exception;
}
