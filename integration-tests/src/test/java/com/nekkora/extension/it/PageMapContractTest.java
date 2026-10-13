package com.nekkora.extension.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nekkora.extension.host.NativeHost;
import com.nekkora.extension.host.persistence.DataDirectory;
import com.nekkora.extension.it.support.Contracts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contrato sem navegador (executa no build padrão): o host, alimentado por um snapshot válido,
 * produz PageMap, identidades, resoluções e envelopes conformes aos JSON Schemas versionados.
 */
class PageMapContractTest {

    private final ObjectMapper m = new ObjectMapper();
    @TempDir
    Path data;

    private JsonNode call(NativeHost host, String type, String payload) throws Exception {
        ObjectNode req = m.createObjectNode().put("protocol", "nekkora-nm/1").put("id", type).put("type", type);
        req.set("payload", m.readTree(payload));
        List<String> frames = host.processor().process(req.toString());
        JsonNode resp = m.readTree(frames.get(0));
        Contracts.assertValid("native-protocol.schema.json", resp);
        Contracts.assertValid("native-protocol.schema.json", req);
        return resp;
    }

    @Test
    @DisplayName("C-6 — PageMap, identidade e resolução conformes ao contrato 0.1.0 (sem navegador)")
    void contract() throws Exception {
        Path repo = Contracts.dir().getParent();
        String snapshot = Files.readString(repo.resolve("java-core/native-host/src/test/resources/snapshots/duplicated-tables.json"), StandardCharsets.UTF_8);
        Contracts.assertValid("raw-snapshot.schema.json", m.readTree(snapshot));
        NativeHost host = new NativeHost(new DataDirectory(data));
        assertTrue(call(host, "snapshot.submit", "{\"tabKey\":\"t\",\"snapshot\":" + snapshot + "}").get("ok").asBoolean());
        String target = call(host, "target.register", "{\"tabKey\":\"t\",\"nodeId\":\"n16\"}").at("/payload/targetId").asText();
        call(host, "target.resolve", "{\"tabKey\":\"t\",\"targetId\":\"" + target + "\"}");
        JsonNode export = call(host, "pagemap.export", "{\"tabKey\":\"t\"}").at("/payload/pageMap");
        Contracts.assertValid("page-map.schema.json", export);
        assertFalse(export.get("targets").isEmpty());
        assertFalse(export.get("resolutions").isEmpty());
        JsonNode error = call(host, "target.resolve", "{\"tabKey\":\"t\"}");
        assertFalse(error.get("ok").asBoolean());
    }

    @Test
    @DisplayName("C-7 — O schema rejeita PageMaps inválidos")
    void schemaRejectsInvalid() throws Exception {
        ObjectNode bad = m.createObjectNode().put("schema", "nekkora.pagemap").put("version", "9.9.9");
        assertFalse(Contracts.validate("page-map.schema.json", bad).isEmpty());
        ObjectNode badChunk = m.createObjectNode().put("protocol", "nekkora-nm/1").put("type", "chunk").put("index", -1);
        assertFalse(Contracts.validate("native-protocol.schema.json", badChunk).isEmpty());
    }
}
