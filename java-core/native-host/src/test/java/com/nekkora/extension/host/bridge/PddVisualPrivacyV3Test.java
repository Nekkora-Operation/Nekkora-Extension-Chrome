package com.nekkora.extension.host.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NEKKORA-STUDIO-013: terceira barreira da política {@code nekkora-visual/3} (descrição inteiramente sintética, sem imagem).
 * O host refaz a validação com as regras Java: um rótulo com texto fora do vocabulário (nome, documento, termo de negócio),
 * um campo a mais (valor, placeholder), uma barra TEXT não quantizada ou com texto, ordem trocada, geometria fracionária
 * ou fora do viewport, ou um {@code png} anexado derrubam o evento: falha sem conteúdo.
 */
class PddVisualPrivacyV3Test {
    private final ObjectMapper m = new ObjectMapper();

    private ObjectNode captured() {
        ObjectNode p = m.createObjectNode();
        p.put("captureId", "c1");
        p.put("status", "CAPTURED");
        p.put("policy", "nekkora-visual/3");
        p.put("observedAt", "2026-10-19T12:00:00Z");
        ObjectNode vp = p.putObject("viewport");
        vp.put("width", 400);
        vp.put("height", 300);
        vp.put("dpr", 1.25);
        ArrayNode r = p.putArray("regions");
        r.addObject().put("kind", "SURFACE").put("x", 0).put("y", 0).put("width", 400).put("height", 40).put("tone", "DARK");
        r.addObject().put("kind", "MEDIA").put("x", 4).put("y", 4).put("width", 32).put("height", 32);
        r.addObject().put("kind", "FRAME").put("x", 300).put("y", 200).put("width", 90).put("height", 90);
        r.addObject().put("kind", "CONTROL").put("x", 20).put("y", 200).put("width", 80).put("height", 28);
        r.addObject().put("kind", "FIELD").put("x", 20).put("y", 100).put("width", 200).put("height", 24);
        r.addObject().put("kind", "TEXT").put("x", 20).put("y", 60).put("width", 128).put("height", 8).put("ink", "DARK");
        label(r.addObject(), "Senha");
        label(r.addObject(), "Entrar").put("align", "CENTER").put("ink", "LIGHT").put("weight", "BOLD");
        p.putArray("annotations");
        p.putArray("limitations").add("TABLE_CONTENT_HIDDEN");
        return p;
    }

    private static ObjectNode label(ObjectNode o, String text) {
        return o.put("kind", "LABEL").put("x", 20).put("y", 80).put("width", 80).put("height", 16).put("text", text).put("size", 14).put("weight", "NORMAL")
                .put("ink", "DARK").put("align", "START");
    }

    private void assertRejected(String why, Consumer<ObjectNode> tamper) {
        ObjectNode p = captured();
        tamper.accept(p);
        JsonNode out = PddVisualPrivacy.reapply(p);
        assertEquals("FAILED", out.path("status").asText(), why);
        assertEquals("SANITIZATION_CHECK_FAILED", out.path("code").asText(), why);
        assertEquals(4, out.size(), why + ": só captureId, status, code e observedAt");
        assertFalse(out.toString().contains("Maria") || out.toString().contains("segredo") || out.toString().contains("123.456"), why);
    }

    private static ObjectNode region(ObjectNode p, int i) {
        return (ObjectNode) p.path("regions").get(i);
    }

    @Test
    void descricaoConformePassaIntactaEValidaNoContrato() {
        ObjectNode p = captured();
        assertSame(p, PddVisualPrivacy.reapply(p));
        ObjectNode ev = m.createObjectNode();
        ev.put("protocol", "nekkora-bridge/1");
        ev.put("kind", "event");
        ev.put("type", "studio.pdd.captured");
        ev.put("seq", 1);
        ev.set("payload", p);
        assertTrue(BridgeContract.problems(ev).isEmpty(), BridgeContract.problems(ev).toString());
    }

    @Test
    void rotuloComTextoForaDoVocabularioReprova() {
        assertRejected("nome de pessoa", p -> region(p, 6).put("text", "Maria Souza"));
        assertRejected("CPF sintético", p -> region(p, 6).put("text", "123.456.789-09"));
        assertRejected("termo de negócio", p -> region(p, 6).put("text", "Entrar paciente"));
        assertRejected("forma não limpa", p -> region(p, 6).put("text", " Senha"));
        assertRejected("texto vazio", p -> region(p, 6).put("text", ""));
        assertRejected("texto não textual", p -> region(p, 6).put("text", 42));
    }

    @Test
    void campoForaDoContratoReprova() {
        assertRejected("valor de campo", p -> region(p, 4).put("value", "segredo-sintetico"));
        assertRejected("placeholder", p -> region(p, 4).put("placeholder", "CPF"));
        assertRejected("barra com texto", p -> region(p, 5).put("text", "Senha"));
        assertRejected("cor real", p -> region(p, 0).put("color", "#ff0000"));
        assertRejected("imagem anexada", p -> p.put("png", "QUFB"));
        assertRejected("url", p -> p.put("url", "https://app.exemplo.test/pacientes"));
    }

    @Test
    void geometriaOrdemTetosEEnumeracoesReprovam() {
        assertRejected("barra não quantizada", p -> region(p, 5).put("width", 130));
        assertRejected("ordem trocada", p -> ((ArrayNode) p.path("regions")).insert(0, region(p, 6).deepCopy()));
        assertRejected("tipo de pixels da v2", p -> region(p, 1).put("kind", "CARRIER"));
        assertRejected("fracionário", p -> region(p, 4).put("x", 20.5));
        assertRejected("fora do viewport", p -> region(p, 4).put("x", 300));
        assertRejected("tom inválido", p -> region(p, 0).put("tone", "RED"));
        assertRejected("tinta inválida", p -> region(p, 6).put("ink", "#000"));
        assertRejected("tamanho absurdo", p -> region(p, 6).put("size", 200));
        assertRejected("viewport com rolagem", p -> ((ObjectNode) p.path("viewport")).put("scrollY", 10));
        assertRejected("dpr inválido", p -> ((ObjectNode) p.path("viewport")).put("dpr", 0));
        assertRejected("rótulos demais", p -> {
            ArrayNode r = (ArrayNode) p.path("regions");
            for (int i = 0; i < 96; i++) label(r.addObject(), "Salvar");
        });
    }
}
