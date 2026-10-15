package com.nekkora.extension.host.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** STUDIO-003 (S003-037): terceira barreira — o host reaplica a política aos textos e URLs de cada etapa. */
class StepPrivacyTest {

    private final ObjectMapper m = new ObjectMapper();

    private ObjectNode step() throws Exception {
        Path f = Path.of("../../contracts/studio-bridge/fixtures/valid/s003-event-step-click.json");
        return (ObjectNode) m.readTree(Files.readString(f)).get("payload");
    }

    @Test
    void textosUrlsEIdsComDadosSensiveisSaoReaplicadosNoHost() throws Exception {
        ObjectNode p = step();
        ((ObjectNode) p.get("element")).put("name", "Enviar para ana.souza@exemplo.com");
        ((ObjectNode) p.get("element")).put("htmlId", "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U");
        ((ObjectNode) p.get("context")).put("url", "http://127.0.0.1:18765/lab/studio003/login.html?token=abc123def456ghi789jkl&cpf=123.456.789-09");
        ((ObjectNode) p.get("context")).put("title", "Pedido de 123.456.789-09");
        StepPrivacy.reapply(p);
        String json = p.toString();
        assertFalse(json.contains("ana.souza@exemplo.com"), json);
        assertFalse(json.contains("abc123def456ghi789jkl"), json);
        assertFalse(json.contains("123.456.789-09"), json);
        assertTrue(p.at("/element/htmlId").isNull(), "id com aparência de segredo não sai");
        assertTrue(BridgeContract.valid(m.createObjectNode().put("protocol", "nekkora-bridge/1").put("kind", "event")
                .put("type", "studio.recording.step").put("seq", 1).set("payload", p)), "continua no contrato depois da reaplicação");
    }

    @Test
    void elementoSensivelNuncaLevaIdNemName() throws Exception {
        ObjectNode p = step();
        ObjectNode e = (ObjectNode) p.get("element");
        e.put("sensitive", true);
        e.put("htmlId", "senha");
        e.put("htmlName", "senha");
        StepPrivacy.reapply(p);
        assertTrue(e.get("htmlId").isNull());
        assertTrue(e.get("htmlName").isNull());
    }

    @Test
    void textoSemDadoSensivelPermaneceIgual() throws Exception {
        // a fixture representa uma etapa já produzida sob nekkora-privacy/2 (nomes do vocabulário, URL como modelo)
        ObjectNode p = step();
        String before = p.toString();
        StepPrivacy.reapply(p);
        JsonNode after = p;
        assertEquals(m.readTree(before), after, "a política é idempotente em dados que já a respeitam");
    }

    @Test
    void sec001MetadadosDaPaginaNaoAtravessamOHostMesmoQueAExtensaoFalhe() throws Exception {
        // CHROME-SECURITY-001 (SEC001-002/018/041/042/043/047/049): extensão antiga ou com defeito envia texto da página
        ObjectNode p = step();
        ObjectNode e = (ObjectNode) p.get("element");
        e.put("name", "Consultar cliente Bartolomeu Quixadá");
        e.put("htmlId", "cliente-anacleto-prudencio");
        e.put("htmlName", "usuario_hermengarda");
        ((ObjectNode) e.get("accessPath").get(0)).put("label", "Ficha de Lucíola Assumpção");
        ((ObjectNode) e.get("context").get(0)).put("name", "Cadastro de Nicanor Esperança");
        com.fasterxml.jackson.databind.node.ArrayNode ls = (com.fasterxml.jackson.databind.node.ArrayNode) e.get("locators");
        ((ObjectNode) ls.get(1)).put("xpath", "//button[text()='Consultar João Oliveira']");
        ((ObjectNode) p.get("parameters")).putObject("option").put("label", "Zéfiro Quintanilha (contrato 7731)").put("value", "t1");
        ((ObjectNode) p.get("context")).put("url", "http://127.0.0.1:18765/clientes/joao-oliveira?cliente=Ataulfo#ficha");
        ((ObjectNode) p.get("context")).put("title", "Ficha de Belmira Saraiva");
        StepPrivacy.reapply(p);
        String json = p.toString();
        for (String s : new String[]{"Quixad", "anacleto", "hermengarda", "Assump", "Esperan", "Oliveira", "oliveira", "Quintanilha", "Ataulfo",
                "Saraiva", "7731"}) {
            assertFalse(json.contains(s), s + " em " + json);
        }
        assertEquals("", e.get("name").asText());
        assertTrue(e.get("htmlId").isNull() && e.get("htmlName").isNull());
        assertEquals(1, e.get("locators").size(), "o localizador com texto da página foi descartado; o seguro ficou");
        assertTrue(p.at("/parameters/option").isNull());
        assertTrue(p.get("notes").toString().contains("LOCATORS_WITHHELD") && p.get("notes").toString().contains("OPTION_WITHHELD"), p.get("notes").toString());
        assertEquals("http://127.0.0.1:18765/clientes/{…}?cliente={…}", p.at("/context/url").asText());
        assertTrue(p.at("/context/title").isNull());
        assertTrue(BridgeContract.valid(m.createObjectNode().put("protocol", "nekkora-bridge/1").put("kind", "event")
                .put("type", "studio.recording.step").put("seq", 1).set("payload", p)), "continua no contrato depois da reaplicação");
    }
}
