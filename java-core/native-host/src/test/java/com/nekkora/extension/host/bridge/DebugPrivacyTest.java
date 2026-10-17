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

/**
 * STUDIO-009: a barreira do debug guiado no host. Um service worker defeituoso (ou uma página hostil) que ponha texto da
 * página no resumo do elemento, nos candidatos ou no elemento escolhido não consegue fazê-lo chegar ao Studio.
 */
class DebugPrivacyTest {

    private static final Path FIXTURES = Path.of("..", "..", "contracts", "studio-bridge", "fixtures", "valid");
    private final ObjectMapper m = new ObjectMapper();

    private ObjectNode fixture(String name) throws Exception {
        return (ObjectNode) m.readTree(Files.readString(FIXTURES.resolve(name + ".json")));
    }

    @Test
    void inspecaoComTextoDaPaginaNoResumoENosCandidatos() throws Exception {
        ObjectNode resp = fixture("s009-response-debug-inspect-resolved");
        ObjectNode p = (ObjectNode) resp.get("payload");
        ((ObjectNode) p.get("element")).put("name", "Ana Souza 123.456.789-09");
        ((ObjectNode) p.get("element")).put("tag", "x-cliente-ana");
        ((ObjectNode) p.get("candidates").get(0)).put("name", "ana.souza@exemplo.com");
        DebugPrivacy.reapply("studio.debug.inspect", p);
        assertEquals("", p.at("/element/name").asText(), "nome fora do vocabulário: vazio");
        assertEquals("custom-element", p.at("/element/tag").asText(), "tag fora do catálogo: marcador neutro");
        assertEquals("", p.at("/candidates/0/name").asText());
        assertFalse(p.toString().contains("Ana") || p.toString().contains("ana.souza"), p.toString());
        assertTrue(BridgeContract.valid(resp), "continua no contrato depois da barreira: " + BridgeContract.problemSummary(resp));
    }

    @Test
    void nomeDoVocabularioPassaInteiro() throws Exception {
        ObjectNode p = (ObjectNode) fixture("s009-response-debug-inspect-ambiguous").get("payload");
        DebugPrivacy.reapply("studio.debug.inspect", p);
        assertEquals("Consultar", p.at("/candidates/0/name").asText());
    }

    @Test
    void elementoEscolhidoPassaPelaBarreiraDeEtapa() throws Exception {
        ObjectNode ev = fixture("s009-event-debug-picked");
        ObjectNode p = (ObjectNode) ev.get("payload");
        ObjectNode e = (ObjectNode) p.get("element");
        e.put("name", "Pedido de Ana Souza");
        e.put("htmlId", "cliente-ana-souza-123");
        ((ObjectNode) e.get("locators").get(0)).put("css", "[data-testid=\"cliente-ana-souza\"]");
        DebugPrivacy.reapply("studio.debug.picked", p);
        JsonNode out = p.get("element");
        assertEquals("", out.get("name").asText());
        assertTrue(out.get("htmlId").isNull(), "identificador fora do vocabulário");
        assertEquals(0, out.get("locators").size(), "localizador com literal fora da política é descartado, nunca reescrito");
        assertFalse(p.toString().toLowerCase().contains("ana"), p.toString());
        ev.put("seq", 1);
        assertTrue(BridgeContract.valid(ev), BridgeContract.problemSummary(ev).toString());
    }
}
