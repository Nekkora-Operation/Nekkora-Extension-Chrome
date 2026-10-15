package com.nekkora.extension.host;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STUDIO-003: referência COMPACTA de elemento para as etapas gravadas (recorder.element.describe/verify) — o motor
 * semântico continua sendo a fonte; nada de PageMap; privacidade aplicada no host.
 */
class RecorderCommandsTest {

    private final ObjectMapper m = new ObjectMapper();
    private final AtomicInteger ids = new AtomicInteger();
    @TempDir
    Path data;
    private NativeHost host;
    private final JsonSchema protocol = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(SchemaLocation.of("classpath:contracts/native-protocol.schema.json"));

    @BeforeEach
    void setUp() {
        host = new NativeHost(new DataDirectory(data));
    }

    private String resource(String name) throws Exception {
        return new String(getClass().getResourceAsStream("/snapshots/" + name).readAllBytes(), StandardCharsets.UTF_8);
    }

    private JsonNode call(String type, String payload) throws Exception {
        ObjectNode req = m.createObjectNode().put("protocol", ChunkCodec.PROTOCOL).put("id", "r" + ids.incrementAndGet()).put("type", type);
        req.set("payload", m.readTree(payload));
        assertTrue(protocol.validate(req).isEmpty(), "requisição " + type + " no contrato nekkora-nm/1: " + protocol.validate(req));
        List<String> frames = host.processor().process(req.toString());
        assertEquals(1, frames.size(), "resposta compacta: sem fragmentação");
        JsonNode resp = m.readTree(frames.get(0));
        assertTrue(protocol.validate(resp).isEmpty(), "resposta " + type);
        return resp;
    }

    @Test
    void describeDevolveReferenciaCompactaComLocalizadorUnicoETrajetoria() throws Exception {
        call("snapshot.submit", "{\"tabKey\":\"t1\",\"snapshot\":" + resource("login-form.json") + "}");
        JsonNode r = call("recorder.element.describe", "{\"tabKey\":\"t1\",\"nodeId\":\"n8\"}");
        assertTrue(r.get("ok").asBoolean(), r.toString());
        JsonNode p = r.get("payload");
        assertEquals("RESOLVED", p.get("resolution").asText());
        assertEquals("BUTTON", p.get("kind").asText());
        assertEquals("Entrar", p.get("name").asText());
        assertEquals("button", p.get("tag").asText());
        assertEquals("entrar", p.get("htmlId").asText());
        assertTrue(p.get("targetId").asText().startsWith("tgt-"), "identidade lógica registrada");
        int pref = p.get("preferredLocator").asInt();
        assertTrue(pref >= 0, "localizador único preferencial: " + p.get("locators"));
        JsonNode best = p.get("locators").get(pref);
        assertEquals(1, best.get("matchCount").asInt());
        assertTrue(best.get("matchesTarget").asBoolean());
        assertTrue(p.get("locators").size() <= 5);
        assertEquals("MAIN_DOCUMENT", p.at("/accessPath/0/type").asText(), "trajetória documental");
        assertTrue(p.get("context").toString().contains("IN_FORM"), "âncora de formulário: " + p.get("context"));
        String json = p.toString();
        assertTrue(json.length() < 16_000, "compacto (" + json.length() + " bytes): nunca um PageMap");
        assertFalse(p.has("elements") || p.has("nodes") || p.has("page"), "sem PageMap/snapshot");
    }

    @Test
    void campoSensivelNaoExpoeIdNemNameEDadoPessoalEMascarado() throws Exception {
        call("snapshot.submit", "{\"tabKey\":\"t1\",\"snapshot\":" + resource("login-form.json") + "}");
        JsonNode senha = call("recorder.element.describe", "{\"tabKey\":\"t1\",\"nodeId\":\"n7\"}").get("payload");
        assertEquals("PASSWORD_INPUT", senha.get("kind").asText());
        assertTrue(senha.get("sensitive").asBoolean());
        assertTrue(senha.get("htmlId").isNull(), "campo sensível: id não sai");
        assertTrue(senha.get("htmlName").isNull());
        assertEquals("Senha", senha.get("name").asText());
        JsonNode contato = call("recorder.element.describe", "{\"tabKey\":\"t1\",\"nodeId\":\"n10\"}").get("payload");
        assertFalse(contato.toString().contains("ana.souza@exemplo.com"), "e-mail no nome acessível mascarado: " + contato.get("name"));
    }

    @Test
    void elementosAmbiguosSaoDeclaradosSemIdentidadeInventada() throws Exception {
        call("snapshot.submit", "{\"tabKey\":\"t1\",\"snapshot\":" + resource("duplicated-tables.json") + "}");
        JsonNode p = call("recorder.element.describe", "{\"tabKey\":\"t1\",\"nodeId\":\"n9\"}").get("payload");
        assertTrue(p.get("lookalikes").asInt() >= 1, "elemento parecido declarado");
        for (JsonNode l : p.get("locators")) {
            if ("ID".equals(l.get("strategy").asText())) {
                assertTrue(l.get("matchCount").asInt() > 1, "id duplicado não é tratado como único");
            }
        }
        int pref = p.get("preferredLocator").asInt();
        if (pref >= 0) {
            assertEquals(1, p.get("locators").get(pref).get("matchCount").asInt(), "preferencial só se for único");
        } else {
            assertTrue(p.get("notes").toString().contains("NO_UNIQUE_LOCATOR"));
        }
        assertTrue(p.get("context").toString().contains("IN_ROW"), "contexto da linha preservado: " + p.get("context"));
    }

    @Test
    void noInexistenteRecusadoSemReferenciaInventada() throws Exception {
        call("snapshot.submit", "{\"tabKey\":\"t1\",\"snapshot\":" + resource("login-form.json") + "}");
        JsonNode r = call("recorder.element.describe", "{\"tabKey\":\"t1\",\"nodeId\":\"n999\"}");
        assertFalse(r.get("ok").asBoolean());
        assertEquals("UNKNOWN_NODE", r.at("/error/code").asText());
    }

    @Test
    void verifyMarcaSomenteLocalizadoresUnicosConfirmadosAoVivo() throws Exception {
        call("snapshot.submit", "{\"tabKey\":\"t1\",\"snapshot\":" + resource("login-form.json") + "}");
        JsonNode p = call("recorder.element.describe", "{\"tabKey\":\"t1\",\"nodeId\":\"n8\"}").get("payload");
        String semantic = p.at("/verification/primary/semantic").asText();
        assertFalse(semantic.isBlank(), "conjunto para verificação ao vivo devolvido ao service worker");
        JsonNode ok = call("recorder.element.verify", "{\"tabKey\":\"t1\",\"nodeId\":\"n8\",\"checks\":[{\"semantic\":" + m.writeValueAsString(semantic)
                + ",\"cssCount\":1,\"xpathCount\":1,\"cssMatchesTarget\":true,\"xpathMatchesTarget\":true}]}").get("payload");
        assertTrue(ok.at("/checks/0/liveUnique").asBoolean(), ok.toString());
        JsonNode bad = call("recorder.element.verify", "{\"tabKey\":\"t1\",\"nodeId\":\"n8\",\"checks\":[{\"semantic\":" + m.writeValueAsString(semantic)
                + ",\"cssCount\":2,\"xpathCount\":2,\"cssMatchesTarget\":true,\"xpathMatchesTarget\":true}]}").get("payload");
        assertFalse(bad.at("/checks/0/liveUnique").asBoolean(), "dois elementos na DOM real: não é validado");
    }
}
