package com.nekkora.extension.host;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nekkora.extension.core.protection.SignatureCatalog;
import com.nekkora.extension.host.json.CatalogLoader;
import com.nekkora.extension.host.persistence.DataDirectory;
import com.nekkora.extension.host.protocol.ChunkCodec;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Comandos CHROME-003 do host: metadados de frames, grafo, trajetória, proteções e prontidão. */
class FrameCommandsTest {

    private final ObjectMapper m = new ObjectMapper();
    private final AtomicInteger ids = new AtomicInteger();
    @TempDir
    Path data;
    private NativeHost host;
    private String snapshot;
    private final JsonSchema protocol = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(SchemaLocation.of("classpath:contracts/native-protocol.schema.json"));

    @BeforeEach
    void setUp() throws Exception {
        host = new NativeHost(new DataDirectory(data));
        snapshot = new String(getClass().getResourceAsStream("/snapshots/duplicated-tables.json").readAllBytes(), StandardCharsets.UTF_8);
    }

    private JsonNode call(String type, String payload) throws Exception {
        ObjectNode req = m.createObjectNode().put("protocol", ChunkCodec.PROTOCOL).put("id", "f" + ids.incrementAndGet()).put("type", type);
        req.set("payload", m.readTree(payload));
        assertTrue(protocol.validate(req).isEmpty(), "requisição " + type + ": " + protocol.validate(req));
        List<String> frames = host.processor().process(req.toString());
        ChunkCodec c = new ChunkCodec(m);
        String full = frames.size() == 1 ? frames.get(0) : null;
        for (String f : frames) {
            assertTrue(protocol.validate(m.readTree(f)).isEmpty(), "resposta " + type);
            if (frames.size() > 1) {
                String r = c.accept(m.readTree(f), 0);
                if (r != null) {
                    full = r;
                }
            }
        }
        return m.readTree(full);
    }

    @Test
    void catalogLoadsFromTheJarAndRejectsInvalidCatalogs() throws Exception {
        SignatureCatalog c = CatalogLoader.load();
        assertTrue(c.signatures().size() >= 11);
        assertThrows(IllegalStateException.class, () -> CatalogLoader.parse(
                "{\"catalogVersion\":\"x\",\"signatures\":[{\"id\":\"a\",\"provider\":\"P\",\"product\":\"Q\",\"category\":\"WAF\",\"basis\":\"THIRD_PARTY_OBSERVATION\",\"maxConfidence\":\"HIGH\",\"indicators\":[{\"id\":\"i\",\"kind\":\"TEXT\",\"token\":\"x\",\"strength\":\"WEAK\",\"signal\":\"MENTION\"}]}]}"
                        .getBytes(StandardCharsets.UTF_8)), "assinatura de terceiros com teto HIGH é recusada");
    }

    @Test
    void diagnosticsCommandsWorkOnTheLatestCapture() throws Exception {
        call("snapshot.submit", "{\"tabKey\":\"t1\",\"snapshot\":" + snapshot + "}");
        JsonNode meta = call("capture.frames.metadata", "{\"tabKey\":\"t1\",\"frames\":[{\"browserFrameId\":0,\"browserDocumentId\":\"DOC1\",\"parentBrowserFrameId\":-1,\"url\":\"http://127.0.0.1:8765/x\",\"mapping\":\"TOP\",\"inspection\":\"TOP\"}]}");
        assertTrue(meta.get("ok").asBoolean(), meta.toString());
        JsonNode graph = call("frames.graph", "{\"tabKey\":\"t1\"}").get("payload");
        assertEquals("DOC1", graph.at("/documents/0/browserDocumentId").asText(), "documentId do Chrome associado ao documento principal");
        JsonNode path = call("element.accessPath", "{\"tabKey\":\"t1\",\"nodeId\":\"n16\"}").get("payload");
        assertEquals("MAIN_DOCUMENT", path.at("/steps/0/type").asText());
        assertEquals("ELEMENT", path.at("/steps/1/type").asText());
        JsonNode prot = call("protection.assess", "{\"tabKey\":\"t1\"}").get("payload");
        assertEquals("NOT_OBSERVED", prot.get("status").asText());
        assertFalse(prot.get("limitations").isEmpty());
        JsonNode diag = call("page.diagnose", "{\"tabKey\":\"t1\"}").get("payload");
        assertTrue(diag.at("/readiness/status").isTextual());
        assertTrue(diag.has("counts"));
        JsonNode inspect = call("element.inspect", "{\"tabKey\":\"t1\",\"nodeId\":\"n16\"}").get("payload");
        assertEquals("doc:f0", inspect.get("documentId").asText());
        assertEquals("MAIN_DOCUMENT", inspect.at("/accessPath/steps/0/type").asText());
        assertEquals("UNKNOWN_NODE", call("element.accessPath", "{\"tabKey\":\"t1\",\"nodeId\":\"nao\"}").at("/error/code").asText());
    }
}
