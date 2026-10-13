package com.nekkora.extension.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.nekkora.extension.it.support.BrowserLab;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fase 02 — comunicação real extensão ↔ Native Messaging ↔ host Java 21. */
class HostCommunicationIT {

    static BrowserLab lab;

    @BeforeAll
    static void setUp() {
        lab = BrowserLab.get();
    }

    @Test
    @DisplayName("C-1 — Extensão conecta ao host Java via Native Messaging")
    void ping() {
        JsonNode r = lab.call("host.ping", Map.of());
        assertEquals("nekkora-nm/1", r.at("/ping/protocol").asText());
        assertTrue(r.at("/ping/java").asText().startsWith("21"), "host deve rodar em Java 21: " + r.at("/ping/java"));
        assertTrue(r.at("/client/connected").asBoolean());
        assertEquals(lab.hostData().toString(), r.at("/ping/dataDir").asText(), "dados isolados do usuário durante os testes");
    }

    @Test
    @DisplayName("C-2 — Resposta grande (5 M caracteres) é fragmentada pelo host e remontada pela extensão")
    void largeResponse() {
        JsonNode r = lab.call("host.request", Map.of("type", "host.generate", "payload", Map.of("chars", 5_000_000)));
        assertEquals(5_000_000, r.at("/payload/length").asInt());
        assertTrue(lab.call("host.status", Map.of()).path("chunksIn").asInt() >= 40, "resposta acima de 1 MiB exige fragmentos");
    }

    @Test
    @DisplayName("C-3 — Requisição grande (3 M caracteres) é fragmentada pela extensão e remontada pelo host")
    void largeRequest() {
        String data = "ação-😀-".repeat(400_000);
        JsonNode r = lab.call("host.request", Map.of("type", "host.echo", "payload", Map.of("data", data), "forceChunkChars", 500_000));
        assertEquals(data.length(), r.at("/payload/length").asInt());
        assertEquals(data, r.at("/payload/data").asText(), "conteúdo íntegro, inclusive pares substitutos");
    }

    @Test
    @DisplayName("C-4 — Erros do host chegam à extensão com código estável")
    void errors() {
        JsonNode fail = lab.callRaw("host.request", Map.of("type", "host.fail", "payload", Map.of()));
        assertEquals("TEST_FAILURE", fail.at("/error/code").asText());
        JsonNode unknown = lab.callRaw("host.request", Map.of("type", "nao.existe", "payload", Map.of()));
        assertEquals("UNKNOWN_COMMAND", unknown.at("/error/code").asText());
    }

    @Test
    @DisplayName("C-5 — Após desconexão, a extensão reconecta e o host volta a responder")
    void reconnect() {
        int before = lab.call("host.status", Map.of()).path("connects").asInt();
        lab.call("host.disconnect", Map.of());
        JsonNode r = lab.call("host.ping", Map.of());
        assertTrue(r.at("/client/connected").asBoolean());
        assertEquals(before + 1, r.at("/client/connects").asInt(), "nova conexão nativa (novo processo Java)");
    }
}
