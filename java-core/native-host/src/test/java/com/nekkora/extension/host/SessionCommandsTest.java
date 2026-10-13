package com.nekkora.extension.host;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nekkora.extension.host.persistence.DataDirectory;
import com.nekkora.extension.host.protocol.ChunkCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionCommandsTest {

    private final ObjectMapper m = new ObjectMapper();
    private final AtomicInteger ids = new AtomicInteger();
    @TempDir
    Path data;
    private NativeHost host;
    private String snapshot;

    @BeforeEach
    void setUp() throws Exception {
        host = new NativeHost(new DataDirectory(data));
        snapshot = new String(getClass().getResourceAsStream("/snapshots/duplicated-tables.json").readAllBytes(), StandardCharsets.UTF_8);
    }

    private JsonNode call(String type, String payload) throws Exception {
        ObjectNode req = m.createObjectNode().put("protocol", ChunkCodec.PROTOCOL).put("id", "r" + ids.incrementAndGet()).put("type", type);
        req.set("payload", m.readTree(payload));
        List<String> frames = host.processor().process(req.toString());
        if (frames.size() == 1) {
            return m.readTree(frames.get(0));
        }
        ChunkCodec c = new ChunkCodec(m);
        String full = null;
        for (String f : frames) {
            String r = c.accept(m.readTree(f), 0);
            if (r != null) {
                full = r;
            }
        }
        return m.readTree(full);
    }

    private String delta(int epoch, long seq, String pageSession, String text) throws Exception {
        ObjectNode d = m.createObjectNode();
        d.putNull("baseCaptureId");
        d.put("captureId", "cap-d" + seq + "-" + epoch);
        d.put("capturedAt", "t");
        ObjectNode doc = (ObjectNode) m.readTree(snapshot).get("document").deepCopy();
        doc.put("pageSessionId", pageSession);
        d.set("document", doc);
        ArrayNode up = d.putArray("upserts");
        ObjectNode n7 = (ObjectNode) m.readTree(snapshot).get("nodes").get(6).deepCopy(); // td "Ana"
        n7.put("ownText", text);
        up.add(n7);
        d.putArray("removed");
        d.put("rawMutationCount", 1).put("batchCount", 1);
        d.put("sessionId", "s1").put("source", "top").put("epoch", epoch).put("seq", seq);
        return d.toString();
    }

    @Test
    void sequencedDeltasAreValidated() throws Exception {
        JsonNode start = call("capture.session.start", "{\"sessionId\":\"s1\",\"tabKey\":\"t1\",\"snapshot\":" + snapshot + "}");
        assertTrue(start.get("ok").asBoolean(), start.toString());
        assertEquals(1, start.at("/payload/session/epoch").asInt());

        JsonNode ok = call("capture.session.delta", "{\"sessionId\":\"s1\",\"delta\":" + delta(1, 1, "ps-fixture-1", "Ana Souza") + "}");
        assertTrue(ok.get("ok").asBoolean(), ok.toString());
        assertEquals(1, ok.at("/payload/session/deltasApplied").asInt());

        assertEquals("DELTA_STALE", call("capture.session.delta", "{\"sessionId\":\"s1\",\"delta\":" + delta(1, 1, "ps-fixture-1", "x") + "}").at("/error/code").asText());
        assertEquals("DELTA_GAP", call("capture.session.delta", "{\"sessionId\":\"s1\",\"delta\":" + delta(1, 3, "ps-fixture-1", "x") + "}").at("/error/code").asText());
        assertEquals("DELTA_STALE", call("capture.session.delta", "{\"sessionId\":\"s1\",\"delta\":" + delta(0, 2, "ps-fixture-1", "x") + "}").at("/error/code").asText());
        assertEquals("DOCUMENT_MISMATCH", call("capture.session.delta", "{\"sessionId\":\"s1\",\"delta\":" + delta(1, 2, "ps-outro", "x") + "}").at("/error/code").asText());
        assertEquals("SESSION_UNKNOWN", call("capture.session.delta", "{\"sessionId\":\"nao\",\"delta\":" + delta(1, 2, "ps-fixture-1", "x") + "}").at("/error/code").asText());

        JsonNode status = call("capture.session.status", "{\"sessionId\":\"s1\"}");
        assertEquals(4, status.at("/payload/deltasRejected").asInt(), "rejeições não avançam a versão confirmada");
        assertEquals(1, status.at("/payload/lastSeq/top").asInt());

        // Nova base (ressincronização) incrementa a época e reinicia as sequências.
        JsonNode rebase = call("capture.session.start", "{\"sessionId\":\"s1\",\"tabKey\":\"t1\",\"snapshot\":" + snapshot + "}");
        assertEquals(2, rebase.at("/payload/session/epoch").asInt());
        assertTrue(call("capture.session.delta", "{\"sessionId\":\"s1\",\"delta\":" + delta(2, 1, "ps-fixture-1", "y") + "}").get("ok").asBoolean());
    }

    @Test
    void exportIsAtomicValidatedAndIdempotent() throws Exception {
        call("capture.session.start", "{\"sessionId\":\"s1\",\"tabKey\":\"t1\",\"snapshot\":" + snapshot + "}");
        call("target.register", "{\"tabKey\":\"t1\",\"nodeId\":\"n16\"}");
        JsonNode r = call("export.session", "{\"sessionId\":\"s1\",\"operationId\":\"rec-1\",\"reason\":\"RECORDING_FINISHED\",\"syncState\":\"SYNCHRONIZED\"}");
        assertTrue(r.get("ok").asBoolean(), r.toString());
        JsonNode e = r.at("/payload/export");
        assertEquals("EXPORTED", e.get("status").asText(), e.toString());
        assertEquals("0.1.0", e.get("schemaVersion").asText());
        assertTrue(e.at("/validation/valid").asBoolean());
        assertTrue(e.get("complete").asBoolean());
        Path file = Path.of(e.get("path").asText());
        byte[] bytes = Files.readAllBytes(file);
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), e.get("sha256").asText());
        assertEquals(bytes.length, e.get("bytes").asLong());
        JsonNode map = m.readTree(bytes);
        assertEquals("nekkora.pagemap", map.get("schema").asText());
        assertEquals(1, map.get("targets").size());
        assertEquals(e.get("elementCount").asInt(), map.get("elements").size(), "contagem declarada = elementos no arquivo");

        JsonNode again = call("export.session", "{\"sessionId\":\"s1\",\"operationId\":\"rec-1\",\"reason\":\"RECORDING_FINISHED\",\"syncState\":\"SYNCHRONIZED\"}");
        assertTrue(again.at("/payload/export/duplicate").asBoolean());
        assertEquals(e.get("sha256").asText(), again.at("/payload/export/sha256").asText());

        // Finalizações simultâneas da mesma operação: uma exportação, mesmo resultado.
        CompletableFuture<JsonNode> a = CompletableFuture.supplyAsync(() -> safe("rec-2"));
        CompletableFuture<JsonNode> b = CompletableFuture.supplyAsync(() -> safe("rec-2"));
        JsonNode ra = a.get().at("/payload/export");
        JsonNode rb = b.get().at("/payload/export");
        assertEquals(ra.get("sha256").asText(), rb.get("sha256").asText());
        assertTrue(ra.get("duplicate").asBoolean() ^ rb.get("duplicate").asBoolean(), "exatamente uma das duas é a exportação original");
        try (Stream<Path> files = Files.list(file.getParent())) {
            assertTrue(files.noneMatch(f -> f.toString().endsWith(".tmp")), "nenhum arquivo parcial");
        }
    }

    private JsonNode safe(String op) {
        try {
            return call("export.session", "{\"sessionId\":\"s1\",\"operationId\":\"" + op + "\",\"syncState\":\"SYNCHRONIZED\"}");
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void exportFailureIsExplicitAndRetryable() throws Exception {
        call("capture.session.start", "{\"sessionId\":\"s1\",\"tabKey\":\"t1\",\"snapshot\":" + snapshot + "}");
        Files.writeString(data.resolve("pagemaps"), "bloqueio: arquivo no lugar do diretório");
        JsonNode r = call("export.session", "{\"sessionId\":\"s1\",\"operationId\":\"op\",\"syncState\":\"SYNCHRONIZED\"}");
        assertEquals("FAILED", r.at("/payload/export/status").asText());
        assertEquals("EXPORT_WRITE_FAILED", r.at("/payload/export/error/code").asText());
        assertTrue(r.at("/payload/export/path").isNull());
        Files.delete(data.resolve("pagemaps"));
        JsonNode retry = call("export.session", "{\"sessionId\":\"s1\",\"operationId\":\"op\",\"syncState\":\"SYNCHRONIZED\"}");
        assertEquals("EXPORTED", retry.at("/payload/export/status").asText(), "falha não é memorizada: nova tentativa exporta");
        assertFalse(retry.at("/payload/export/duplicate").asBoolean());
    }

    @Test
    void streamedExportMatchesLegacyExport() throws Exception {
        call("capture.session.start", "{\"sessionId\":\"s1\",\"tabKey\":\"t1\",\"snapshot\":" + snapshot + "}");
        JsonNode streamed = m.readTree(Path.of(call("export.session", "{\"sessionId\":\"s1\",\"operationId\":\"x\"}").at("/payload/export/path").asText()).toFile());
        JsonNode legacy = call("pagemap.export", "{\"tabKey\":\"t1\",\"persist\":false}").at("/payload/pageMap");
        for (String field : List.of("schema", "version", "snapshot", "document", "coverage", "elements", "relations", "optionSets", "targets")) {
            assertEquals(legacy.get(field), streamed.get(field), "campo " + field + " idêntico");
        }
    }

    @Test
    void frameDeltasRequireAttachment() throws Exception {
        call("capture.session.start", "{\"sessionId\":\"s1\",\"tabKey\":\"t1\",\"snapshot\":" + snapshot + "}");
        ObjectNode d = (ObjectNode) m.readTree(delta(1, 1, "ps-do-frame", "x"));
        d.put("source", "x7");
        d.putArray("upserts");
        JsonNode r = call("capture.session.delta", "{\"sessionId\":\"s1\",\"delta\":" + d + "}");
        assertEquals("FRAME_NOT_ATTACHED", r.at("/error/code").asText(), "delta de frame sem anexação prévia é recusado");
    }

    @Test
    void newCommandsConformToTheNativeProtocol() throws Exception {
        com.networknt.schema.JsonSchema protocol = com.networknt.schema.JsonSchemaFactory.getInstance(com.networknt.schema.SpecVersion.VersionFlag.V202012)
                .getSchema(com.networknt.schema.SchemaLocation.of("classpath:contracts/native-protocol.schema.json"));
        List<String[]> calls = List.of(
                new String[]{"capture.session.start", "{\"sessionId\":\"s1\",\"tabKey\":\"t1\",\"snapshot\":" + snapshot + "}"},
                new String[]{"capture.session.delta", "{\"sessionId\":\"s1\",\"delta\":" + delta(1, 1, "ps-fixture-1", "Ana") + "}"},
                new String[]{"capture.session.delta", "{\"sessionId\":\"s1\",\"delta\":" + delta(1, 5, "ps-fixture-1", "Ana") + "}"},
                new String[]{"capture.pin", "{\"tabKey\":\"t1\"}"},
                new String[]{"capture.session.status", "{\"sessionId\":\"s1\"}"},
                new String[]{"capture.session.list", "{}"},
                new String[]{"export.session", "{\"sessionId\":\"s1\",\"operationId\":\"p\"}"},
                new String[]{"host.memory", "{}"},
                new String[]{"capture.session.stop", "{\"sessionId\":\"s1\"}"});
        for (String[] c : calls) {
            ObjectNode req = m.createObjectNode().put("protocol", ChunkCodec.PROTOCOL).put("id", "p" + ids.incrementAndGet()).put("type", c[0]);
            req.set("payload", m.readTree(c[1]));
            assertTrue(protocol.validate(req).isEmpty(), "requisição " + c[0] + ": " + protocol.validate(req));
            for (String frame : host.processor().process(req.toString())) {
                JsonNode f = m.readTree(frame);
                assertTrue(protocol.validate(f).isEmpty(), "resposta " + c[0] + ": " + protocol.validate(f));
            }
        }
    }

    @Test
    void stopReleasesResources() throws Exception {
        call("capture.session.start", "{\"sessionId\":\"s1\",\"tabKey\":\"t1\",\"snapshot\":" + snapshot + "}");
        assertEquals(1, call("host.memory", "{}").at("/payload/retainedCaptures/t1").asInt());
        JsonNode stop = call("capture.session.stop", "{\"sessionId\":\"s1\",\"reason\":\"INSPECTION_DEACTIVATED\"}");
        assertEquals("STOPPED", stop.at("/payload/status").asText());
        JsonNode mem = call("host.memory", "{\"gc\":true}");
        assertEquals(0, mem.at("/payload/retainedCaptures/t1").asInt());
        assertEquals(0, mem.at("/payload/captureSessions").asInt());
        assertEquals("SESSION_UNKNOWN", call("capture.session.status", "{\"sessionId\":\"s1\"}").at("/error/code").asText());
    }
}
