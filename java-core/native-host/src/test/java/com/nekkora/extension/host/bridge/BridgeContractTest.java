package com.nekkora.extension.host.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Contrato nekkora-bridge/1: fixtures compartilhadas com o Studio e as extensões aditivas do nekkora-nm/1. */
class BridgeContractTest {

    private final ObjectMapper m = new ObjectMapper();
    private static final Path FIXTURES = Paths.get("..", "..", "contracts", "studio-bridge", "fixtures");

    private List<Path> files(String kind) throws Exception {
        try (Stream<Path> s = Files.list(FIXTURES.resolve(kind))) {
            return s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
    }

    @Test
    void fixturesValidasPassamEInvalidasFalham() throws Exception {
        List<Path> valid = files("valid");
        List<Path> invalid = files("invalid");
        assertTrue(valid.size() >= 20, "fixtures válidas: " + valid.size());
        assertTrue(invalid.size() >= 10, "fixtures inválidas: " + invalid.size());
        for (Path p : valid) {
            JsonNode n = m.readTree(p.toFile());
            assertTrue(BridgeContract.valid(n), p.getFileName() + ": " + BridgeContract.problems(n));
        }
        for (Path p : invalid) {
            assertFalse(BridgeContract.valid(m.readTree(p.toFile())), p.getFileName() + " deveria ser recusada");
        }
    }

    @Test
    void listaDoHostCorrespondeAoContrato() throws Exception {
        JsonNode schema = m.readTree(FIXTURES.getParent().getParent().resolve("studio-bridge.schema.json").toFile());
        java.util.Set<String> commands = new java.util.TreeSet<>();
        schema.at("/$defs/CommandType/enum").forEach(n -> commands.add(n.asText()));
        java.util.Set<String> host = new java.util.TreeSet<>(BridgeContract.LOCAL_COMMANDS);
        host.addAll(BridgeContract.FORWARDED_COMMANDS);
        assertEquals(commands, host, "todo comando do contrato tem destino e nenhum outro é aceito");
        java.util.Set<String> events = new java.util.TreeSet<>();
        schema.at("/$defs/EventType/enum").forEach(n -> {
            if (n.asText().startsWith("studio.recording.")) {
                events.add(n.asText());
            }
        });
        assertEquals(events, new java.util.TreeSet<>(BridgeContract.FORWARDED_EVENTS));
    }

    @Test
    void mensagensStudioDoNativeMessagingSaoAditivas() throws Exception {
        JsonSchema nm = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(SchemaLocation.of("classpath:contracts/native-protocol.schema.json"));
        String[] ok = {
            "{\"protocol\":\"nekkora-nm/1\",\"type\":\"studio.command\",\"id\":\"sc-1\",\"command\":\"studio.recording.pause\",\"payload\":{},\"controller\":{\"instanceId\":\"studio-a\"}}",
            "{\"protocol\":\"nekkora-nm/1\",\"type\":\"studio.reply\",\"id\":\"sc-1\",\"ok\":false,\"payload\":null,\"error\":{\"code\":\"TAB_NOT_FOUND\",\"message\":\"x\"}}",
            "{\"protocol\":\"nekkora-nm/1\",\"type\":\"studio.event\",\"event\":\"studio.recording.paused\",\"payload\":{},\"controller\":{\"instanceId\":\"studio-a\"}}",
            "{\"protocol\":\"nekkora-nm/1\",\"type\":\"studio.link\",\"state\":\"CONNECTED\",\"detail\":null,\"controller\":{\"instanceId\":\"studio-a\"}}",
            "{\"protocol\":\"nekkora-nm/1\",\"id\":\"x1\",\"type\":\"studio.link.configure\",\"payload\":{\"enabled\":true}}",
            "{\"protocol\":\"nekkora-nm/1\",\"id\":\"x2\",\"type\":\"host.hold\",\"payload\":{\"ms\":10}}",
            // mensagens anteriores continuam válidas
            "{\"protocol\":\"nekkora-nm/1\",\"id\":\"x3\",\"type\":\"host.ping\",\"payload\":{}}",
        };
        for (String s : ok) {
            assertTrue(nm.validate(m.readTree(s)).isEmpty(), s + " → " + nm.validate(m.readTree(s)));
        }
        String[] bad = {
            "{\"protocol\":\"nekkora-nm/1\",\"type\":\"studio.command\",\"id\":\"sc-1\",\"command\":\"page.capture\",\"payload\":{},\"controller\":{\"instanceId\":\"a\"}}",
            "{\"protocol\":\"nekkora-nm/1\",\"type\":\"studio.command\",\"id\":\"sc-1\",\"command\":\"studio.recording.pause\",\"payload\":{}}",
            "{\"protocol\":\"nekkora-nm/1\",\"type\":\"studio.event\",\"event\":\"export.session\",\"payload\":{},\"controller\":{\"instanceId\":\"a\"}}",
        };
        for (String s : bad) {
            assertFalse(nm.validate(m.readTree(s)).isEmpty(), s + " deveria ser recusada");
        }
    }
}
