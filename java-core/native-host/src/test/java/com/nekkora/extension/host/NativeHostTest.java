package com.nekkora.extension.host;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nekkora.extension.core.identity.TargetIdentity;
import com.nekkora.extension.host.json.JsonCodec;
import com.nekkora.extension.host.persistence.DataDirectory;
import com.nekkora.extension.host.protocol.ChunkCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeHostTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger ids = new AtomicInteger();
    @TempDir
    Path dataDir;
    private NativeHost host;
    private String snapshot;

    @BeforeEach
    void setUp() throws Exception {
        host = new NativeHost(new DataDirectory(dataDir));
        snapshot = new String(getClass().getResourceAsStream("/snapshots/duplicated-tables.json").readAllBytes(), StandardCharsets.UTF_8);
    }

    private JsonNode call(String type, String payloadJson) throws Exception {
        ObjectNode req = mapper.createObjectNode();
        req.put("protocol", ChunkCodec.PROTOCOL);
        req.put("id", "r" + ids.incrementAndGet());
        req.put("type", type);
        req.set("payload", mapper.readTree(payloadJson));
        List<String> frames = host.processor().process(req.toString());
        return reassemble(frames);
    }

    private JsonNode reassemble(List<String> frames) throws Exception {
        if (frames.size() == 1) {
            return mapper.readTree(frames.get(0));
        }
        ChunkCodec c = new ChunkCodec(mapper);
        String full = null;
        for (String f : frames) {
            String r = c.accept(mapper.readTree(f), 0);
            if (r != null) {
                full = r;
            }
        }
        return mapper.readTree(full);
    }

    @Test
    void pingAndErrors() throws Exception {
        JsonNode ping = call("host.ping", "{}");
        assertTrue(ping.get("ok").asBoolean());
        assertEquals("nekkora-nm/1", ping.at("/payload/protocol").asText());
        assertEquals("r1", ping.get("id").asText(), "correlação requisição/resposta");

        JsonNode unknown = call("nao.existe", "{}");
        assertFalse(unknown.get("ok").asBoolean());
        assertEquals("UNKNOWN_COMMAND", unknown.at("/error/code").asText());

        JsonNode fail = call("host.fail", "{}");
        assertEquals("TEST_FAILURE", fail.at("/error/code").asText());

        JsonNode badProtocol = mapper.readTree(host.processor().process("{\"protocol\":\"x\",\"id\":\"1\",\"type\":\"host.ping\"}").get(0));
        assertEquals("UNSUPPORTED_PROTOCOL", badProtocol.at("/error/code").asText());

        JsonNode badJson = mapper.readTree(host.processor().process("{nao é json").get(0));
        assertEquals("BAD_JSON", badJson.at("/error/code").asText());

        JsonNode missing = call("target.resolve", "{\"tabKey\":\"t1\"}");
        assertEquals("BAD_REQUEST", missing.at("/error/code").asText());
    }

    @Test
    void largeOutboundResponseIsChunked() throws Exception {
        ObjectNode req = mapper.createObjectNode();
        req.put("protocol", ChunkCodec.PROTOCOL).put("id", "big").put("type", "host.generate");
        req.putObject("payload").put("chars", 5_000_000);
        List<String> frames = host.processor().process(req.toString());
        assertTrue(frames.size() > 1, "resposta de 5M caracteres deve ser fragmentada");
        JsonNode resp = reassemble(frames);
        assertEquals(5_000_000, resp.at("/payload/length").asInt());
        assertEquals("big", resp.get("id").asText());
    }

    @Test
    void chunkedInboundRequestIsProcessed() throws Exception {
        ObjectNode req = mapper.createObjectNode();
        req.put("protocol", ChunkCodec.PROTOCOL).put("id", "in").put("type", "host.echo");
        req.putObject("payload").put("data", "é".repeat(2_000_000));
        List<String> chunks = new ChunkCodec(mapper).split(req.toString());
        assertTrue(chunks.size() > 1);
        List<String> out = List.of();
        for (String c : chunks) {
            out = host.processor().process(c);
            if (c != chunks.get(chunks.size() - 1)) {
                assertTrue(out.isEmpty(), "fragmentos intermediários não geram resposta");
            }
        }
        JsonNode resp = reassemble(out);
        assertEquals(2_000_000, resp.at("/payload/length").asInt());
    }

    @Test
    void engineRoundTripOverProtocol() throws Exception {
        JsonNode submit = call("snapshot.submit", "{\"tabKey\":\"t1\",\"snapshot\":" + snapshot + "}");
        assertTrue(submit.get("ok").asBoolean(), submit.toString());
        assertEquals(16, submit.at("/payload/nodes").asInt());
        // CHROME-SECURITY-001: o caminho chega como MODELO; segmentos fora do vocabulário não viram "tela" inventada
        assertTrue(submit.at("/payload/navigation/inferred/screen").isNull(), submit.at("/payload/navigation").toString());
        assertFalse(submit.toString().contains("duplicated"), "segmento do caminho não atravessou");

        JsonNode inspect = call("element.inspect", "{\"tabKey\":\"t1\",\"nodeId\":\"n16\"}");
        assertTrue(inspect.get("ok").asBoolean(), inspect.toString());
        assertEquals("BUTTON", inspect.at("/payload/classification/kind").asText());
        assertEquals("Excluir", inspect.at("/payload/classification/name/name").asText(), "nome de controle do vocabulário continua");
        assertEquals("clientes-inativos", inspect.at("/payload/family/table/idAttr").asText(), "id do desenvolvedor feito de termos do vocabulário");
        // a CHAVE da linha (data-client=202, identificador de negócio) e os textos das células não atravessam
        assertEquals(0, inspect.at("/payload/family/row/rowKey").size(), inspect.at("/payload/family/row").toString());
        assertEquals(0, inspect.at("/payload/family/row/cellTexts").size());
        assertFalse(inspect.toString().contains("202"), "valor da chave de linha retido");
        // sem a chave da linha, o localizador estável e único vem da estrutura com ids do vocabulário (não é inventado)
        assertEquals("CONTEXTUAL", inspect.at("/payload/locators/primary/strategy").asText());
        assertEquals(1, inspect.at("/payload/locators/primary/matchCount").asInt());
        assertTrue(inspect.at("/payload/locators/primary/matchesTarget").asBoolean());
        for (String f : new String[]{"css", "xpath", "semantic"}) {
            assertTrue(com.nekkora.extension.core.privacy.CapturePrivacyPolicy.expressionSafe(inspect.at("/payload/locators/primary/" + f).asText()), f);
        }
        assertEquals("ATTR_EQUALS", inspect.at("/payload/locators/primary/query/steps/0/predicates/0/type").asText());
        assertEquals(1, inspect.at("/payload/lookalikes").size());

        JsonNode reg = call("target.register", "{\"tabKey\":\"t1\",\"nodeId\":\"n16\"}");
        assertTrue(reg.get("ok").asBoolean(), reg.toString());
        String targetId = reg.at("/payload/targetId").asText();

        JsonNode res = call("target.resolve", "{\"tabKey\":\"t1\",\"targetId\":\"" + targetId + "\"}");
        assertTrue(res.get("ok").asBoolean(), res.toString());
        assertEquals("RESOLVED", res.at("/payload/result/status").asText());
        assertEquals("n16", res.at("/payload/result/resolvedNodeId").asText());

        // A identidade persistida é relida (polimorfismo de predicados preservado).
        Path registry = dataDir.resolve("targets").resolve("registry.json");
        assertTrue(Files.exists(registry));
        NativeHost restarted = new NativeHost(new DataDirectory(dataDir));
        TargetIdentity reloaded = restarted.session().targets().get(targetId).orElseThrow();
        assertNotNull(reloaded.locators().primary().query());
        assertEquals("CONTEXTUAL", reloaded.locators().primary().strategy().name());

        JsonNode verify = call("locators.verify", "{\"tabKey\":\"t1\",\"nodeId\":\"n16\",\"checks\":[{\"semantic\":"
                + mapper.writeValueAsString(inspect.at("/payload/locators/primary/semantic").asText())
                + ",\"cssCount\":1,\"xpathCount\":1,\"cssMatchesTarget\":true,\"xpathMatchesTarget\":true}]}");
        assertTrue(verify.at("/payload/allAgree").asBoolean(), verify.toString());

        JsonNode export = call("pagemap.export", "{\"tabKey\":\"t1\"}");
        assertTrue(export.get("ok").asBoolean(), export.toString());
        assertEquals("nekkora.pagemap", export.at("/payload/pageMap/schema").asText());
        assertTrue(Files.exists(Path.of(export.at("/payload/path").asText())));
        assertEquals(1, export.at("/payload/pageMap/targets").size());
        assertTrue(export.at("/payload/pageMap/evidence").size() >= 3);
    }

    @Test
    void jsonCodecRoundTripsSnapshot() throws Exception {
        JsonCodec codec = new JsonCodec();
        var snap = codec.read(snapshot, com.nekkora.extension.core.snapshot.RawSnapshot.class);
        assertEquals(16, snap.nodes().size());
        assertEquals("ps-fixture-1", snap.document().pageSessionId());
        var again = codec.read(codec.write(snap), com.nekkora.extension.core.snapshot.RawSnapshot.class);
        assertEquals(snap, again);
    }
}
