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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STUDIO-009 (guided-debug/1): {@code debug.resolve} e {@code debug.node.state} — reencontro SOMENTE LEITURA por identidade
 * registrada ou por descritor; verificações em códigos; nenhuma frase livre do resolvedor na saída; o isolamento por aba
 * de {@code target.resolve} continua intacto.
 */
class DebugCommandsTest {

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
        JsonNode resp = m.readTree(frames.get(0));
        assertTrue(protocol.validate(resp).isEmpty(), "resposta " + type);
        return resp;
    }

    private JsonNode ok(String type, String payload) throws Exception {
        JsonNode r = call(type, payload);
        assertTrue(r.get("ok").asBoolean(), r.toString());
        return r.get("payload");
    }

    private void submit(String tab, String snapshot) throws Exception {
        ok("snapshot.submit", "{\"tabKey\":\"" + tab + "\",\"snapshot\":" + resource(snapshot) + "}");
    }

    private static String descriptor(String kind, String tag, String name, String htmlId, String css, String accessType) {
        return "{\"kind\":\"" + kind + "\",\"tag\":\"" + tag + "\",\"name\":\"" + name + "\",\"htmlId\":" + (htmlId == null ? "null" : "\"" + htmlId + "\"")
                + ",\"htmlName\":null,\"locators\":[" + (css == null ? "" : "{\"strategy\":\"ID\",\"css\":\"" + css + "\",\"xpath\":null,\"description\":\"\"}")
                + "],\"accessPath\":[{\"type\":\"MAIN_DOCUMENT\",\"locator\":null}" + (accessType == null ? "" : ",{\"type\":\"" + accessType + "\",\"locator\":null}")
                + "]}";
    }

    @Test
    void identidadeRegistradaResolveSemAtualizarORegistro() throws Exception {
        submit("t1", "login-form.json");
        String targetId = ok("recorder.element.describe", "{\"tabKey\":\"t1\",\"nodeId\":\"n8\"}").get("targetId").asText();
        JsonNode before = ok("target.get", "{\"targetId\":\"" + targetId + "\"}");
        submit("t1", "login-form.json"); // nova captura da mesma página
        JsonNode r = ok("debug.resolve", "{\"tabKey\":\"t1\",\"targetId\":\"" + targetId + "\",\"actionType\":\"CLICK\",\"descriptor\":"
                + descriptor("BUTTON", "button", "Entrar", "entrar", "#entrar", null) + "}");
        assertEquals("TARGET_ID", r.get("identitySource").asText());
        assertEquals("RESOLVED", r.get("status").asText());
        assertEquals("n8", r.get("resolvedNodeId").asText());
        assertEquals("PASS", r.at("/checks/identity").asText());
        assertEquals("PASS", r.at("/checks/unique").asText());
        assertEquals("PASS", r.at("/checks/visible").asText());
        assertEquals("PASS", r.at("/checks/enabled").asText());
        assertEquals("NOT_APPLICABLE", r.at("/checks/editable").asText());
        assertEquals("NOT_APPLICABLE", r.at("/checks/kindMatchesAction").asText());
        JsonNode after = ok("target.get", "{\"targetId\":\"" + targetId + "\"}");
        assertEquals(before.get("lastCaptureId"), after.get("lastCaptureId"), "somente leitura: o registro não foi atualizado");
        assertEquals(before.toString(), after.toString());
        assertFalse(r.toString().contains("candidato(s) avaliado(s)") || r.toString().contains("Resultado:"), "nenhuma frase livre do resolvedor sai");
    }

    @Test
    void outraAbaResolveNoDebugMasTargetResolveContinuaIsolado() throws Exception {
        submit("t1", "login-form.json");
        String targetId = ok("recorder.element.describe", "{\"tabKey\":\"t1\",\"nodeId\":\"n8\"}").get("targetId").asText();
        submit("t2", "login-form.json"); // ex.: Chrome reiniciado — outra aba, mesmo laboratório
        JsonNode isolated = call("target.resolve", "{\"tabKey\":\"t2\",\"targetId\":\"" + targetId + "\"}");
        assertFalse(isolated.get("ok").asBoolean());
        assertEquals("TARGET_NOT_OWNED", isolated.at("/error/code").asText(), "isolamento da CHROME-002 intacto");
        JsonNode r = ok("debug.resolve", "{\"tabKey\":\"t2\",\"targetId\":\"" + targetId + "\",\"actionType\":\"CLICK\",\"descriptor\":"
                + descriptor("BUTTON", "button", "Entrar", "entrar", "#entrar", null) + "}");
        assertEquals("RESOLVED", r.get("status").asText(), "debug: reencontro somente leitura na aba da sessão (D-S009-03)");
        JsonNode again = call("target.resolve", "{\"tabKey\":\"t2\",\"targetId\":\"" + targetId + "\"}");
        assertEquals("TARGET_NOT_OWNED", again.at("/error/code").asText(), "o debug não transferiu o dono");
    }

    @Test
    void descritorResolveComFonteDeclaradaEDesconhecidoNoRegistroCaiNoDescritor() throws Exception {
        submit("t1", "login-form.json");
        JsonNode r = ok("debug.resolve", "{\"tabKey\":\"t1\",\"targetId\":\"tgt-inexistente\",\"actionType\":\"CLICK\",\"descriptor\":"
                + descriptor("BUTTON", "button", "Entrar", "entrar", "#entrar", null) + "}");
        assertEquals("DESCRIPTOR", r.get("identitySource").asText());
        assertTrue(r.get("reasons").toString().contains("TARGET_UNKNOWN_IN_REGISTRY") && r.get("reasons").toString().contains("DESCRIPTOR_ONLY"));
        assertEquals("RESOLVED", r.get("status").asText(), r.toString());
        assertEquals("n8", r.get("resolvedNodeId").asText());
        assertTrue(ok("target.list", "{}").isEmpty(), "a identidade transitória nunca é registrada");
    }

    @Test
    void sosiasSemDiferencaPeloDescritorNuncaViramOPrimeiro() throws Exception {
        submit("t1", "duplicated-tables.json");
        JsonNode r = ok("debug.resolve", "{\"tabKey\":\"t1\",\"actionType\":\"CLICK\",\"descriptor\":"
                + descriptor("BUTTON", "button", "Excluir", "excluir", "#excluir", null) + "}");
        assertNotEquals("RESOLVED", r.get("status").asText(), r.toString());
        assertTrue(List.of("AMBIGUOUS", "INSUFFICIENT_EVIDENCE").contains(r.get("status").asText()), r.toString());
        assertEquals("FAIL", r.at("/checks/identity").asText());
        assertTrue(r.get("candidates").size() >= 2, "candidatos oferecidos para exame: " + r.get("candidates"));
        assertTrue(r.get("subjectNodeId").isNull(), "sem sujeito único: nenhuma verificação de estado finge um alvo");
        // a identidade REGISTRADA (com o contexto da tabela) distingue: é a prova mais forte
        String targetId = ok("recorder.element.describe", "{\"tabKey\":\"t1\",\"nodeId\":\"n16\"}").get("targetId").asText();
        JsonNode reg = ok("debug.resolve", "{\"tabKey\":\"t1\",\"targetId\":\"" + targetId + "\",\"actionType\":\"CLICK\",\"descriptor\":"
                + descriptor("BUTTON", "button", "Excluir", "excluir", "#excluir", null) + "}");
        assertEquals("RESOLVED", reg.get("status").asText(), reg.toString());
        assertEquals("n16", reg.get("resolvedNodeId").asText());
        assertTrue(reg.get("reasons").toString().contains("LOOKALIKES_PRESENT"));
    }

    @Test
    void negativosDescritor() throws Exception {
        submit("t1", "login-form.json");
        JsonNode inexistente = ok("debug.resolve", "{\"tabKey\":\"t1\",\"actionType\":\"FILL\",\"descriptor\":"
                + descriptor("TEXT_INPUT", "input", "Inexistente", "nao-existe", "#nao-existe", null) + "}");
        assertEquals("NOT_FOUND", inexistente.get("status").asText(), inexistente.toString());
        assertEquals("UNKNOWN", inexistente.at("/checks/visible").asText());
        JsonNode frame = ok("debug.resolve", "{\"tabKey\":\"t1\",\"actionType\":\"CLICK\",\"descriptor\":"
                + descriptor("BUTTON", "button", "Entrar", "entrar", "#entrar", "IFRAME") + "}");
        assertEquals("SCOPE_UNAVAILABLE", frame.get("status").asText());
        assertTrue(frame.get("reasons").toString().contains("DESCRIPTOR_CANNOT_CROSS_SCOPE"));
        JsonNode botao = ok("debug.resolve", "{\"tabKey\":\"t1\",\"actionType\":\"FILL\",\"descriptor\":"
                + descriptor("BUTTON", "button", "Entrar", "entrar", "#entrar", null) + "}");
        assertEquals("FAIL", botao.at("/checks/kindMatchesAction").asText(), "preencher um botão");
        assertEquals("FAIL", botao.at("/checks/editable").asText());
        assertTrue(botao.get("reasons").toString().contains("KIND_UNUSUAL_FOR_ACTION"));
    }

    @Test
    void estadoDeNoEscolhido() throws Exception {
        submit("t1", "login-form.json");
        JsonNode senha = ok("debug.node.state", "{\"tabKey\":\"t1\",\"nodeId\":\"n7\",\"actionType\":\"FILL\"}");
        assertEquals("PASSWORD_INPUT", senha.get("kind").asText());
        assertEquals("PASS", senha.at("/checks/editable").asText());
        assertEquals("PASS", senha.at("/checks/kindMatchesAction").asText());
        assertEquals("NOT_APPLICABLE", senha.at("/checks/identity").asText(), "o estado de um nó não afirma identidade");
        JsonNode r = call("debug.node.state", "{\"tabKey\":\"t1\",\"nodeId\":\"n999\",\"actionType\":\"CLICK\"}");
        assertEquals("UNKNOWN_NODE", r.at("/error/code").asText());
    }
}
