package com.nekkora.extension.host.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STUDIO-010: terceira barreira da política visual. Um service worker defeituoso (ou adulterado) que deixe passar um
 * único pixel da página fora dos portadores, ou declare um "portador" do tamanho da tela, não consegue fazer a imagem
 * chegar ao Studio: o evento vira falha sem imagem.
 */
class PddVisualPrivacyTest {
    private static final int W = 200;
    private static final int H = 100;
    private final ObjectMapper m = new ObjectMapper();

    /** Imagem conforme: opaca, um portador (pixels "reais" quaisquer) e um campo sintético. */
    private static BufferedImage conforming() {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) img.setRGB(x, y, PddVisualPrivacy.OPAQUE);
        for (int y = 10; y < 30; y++) for (int x = 10; x < 50; x++) img.setRGB(x, y, 0x0AC81E); // conteúdo real do rótulo
        for (int y = 10; y < 30; y++) {
            for (int x = 60; x < 140; x++) {
                boolean edge = y == 10 || y == 29 || x == 60 || x == 139;
                img.setRGB(x, y, edge ? PddVisualPrivacy.FIELD_BORDER : PddVisualPrivacy.FIELD_FILL);
            }
        }
        return img;
    }

    private ObjectNode payload(BufferedImage img, int carrierW, int carrierH) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        ObjectNode p = m.createObjectNode();
        p.put("captureId", "c1").put("status", "CAPTURED").put("policy", "nekkora-visual/1").put("observedAt", "2026-10-17T12:00:00Z");
        p.put("width", W).put("height", H).put("devicePixelRatio", 1.0);
        p.put("png", Base64.getEncoder().encodeToString(out.toByteArray()));
        p.put("sha256", "sha256:" + "a".repeat(64));
        ArrayNode regions = p.putArray("regions");
        regions.addObject().put("kind", "CARRIER").put("x", 10).put("y", 10).put("width", carrierW).put("height", carrierH);
        regions.addObject().put("kind", "FIELD").put("x", 60).put("y", 10).put("width", 80).put("height", 20);
        p.putArray("annotations").addObject().put("stepRef", "step-1").put("status", "PLACED").put("x", 0.3).put("y", 0.1).put("width", 0.4)
                .put("height", 0.2).put("reason", "");
        p.putArray("limitations");
        return p;
    }

    private ObjectNode event(JsonNode payload) {
        ObjectNode ev = m.createObjectNode();
        ev.put("protocol", "nekkora-bridge/1").put("kind", "event").put("type", "studio.pdd.captured").put("seq", 1);
        ev.set("payload", payload);
        return ev;
    }

    @Test
    void imagemConformePassaSemAlteracaoEDentroDoContrato() throws Exception {
        ObjectNode p = payload(conforming(), 40, 20);
        JsonNode out = PddVisualPrivacy.reapply(p);
        assertSame(p, out);
        assertTrue(BridgeContract.valid(event(out)), String.valueOf(BridgeContract.problemSummary(event(out))));
    }

    @Test
    void umPixelDaPaginaForaDosPortadoresViraFalhaSemImagem() throws Exception {
        BufferedImage img = conforming();
        img.setRGB(100, 70, 0x0AC81E); // vazamento: 1 pixel
        JsonNode out = PddVisualPrivacy.reapply(payload(img, 40, 20));
        assertEquals("FAILED", out.path("status").asText());
        assertEquals("SANITIZATION_CHECK_FAILED", out.path("code").asText());
        assertFalse(out.has("png"), "nenhuma imagem segue adiante");
        assertTrue(BridgeContract.valid(event(out)), String.valueOf(BridgeContract.problemSummary(event(out))));
    }

    @Test
    void campoComPixelDeValorViraFalha() throws Exception {
        BufferedImage img = conforming();
        img.setRGB(80, 20, 0x111111); // "texto" dentro do campo sintético
        assertEquals("FAILED", PddVisualPrivacy.reapply(payload(img, 40, 20)).path("status").asText());
    }

    @Test
    void portadorDoTamanhoDaTelaOuForaDaImagemViraFalha() throws Exception {
        assertEquals("FAILED", PddVisualPrivacy.reapply(payload(conforming(), 40, 90)).path("status").asText(), "portador alto demais");
        assertEquals("FAILED", PddVisualPrivacy.reapply(payload(conforming(), 400, 20)).path("status").asText(), "fora da imagem");
    }

    @Test
    void dimensoesQueNaoBatemPoliticaDesconhecidaEPngInvalidoViramFalha() throws Exception {
        ObjectNode p = payload(conforming(), 40, 20);
        p.put("width", 201);
        assertEquals("FAILED", PddVisualPrivacy.reapply(p).path("status").asText());
        ObjectNode q = payload(conforming(), 40, 20);
        q.put("policy", "nekkora-visual/2");
        assertEquals("FAILED", PddVisualPrivacy.reapply(q).path("status").asText());
        ObjectNode r = payload(conforming(), 40, 20);
        r.put("png", Base64.getEncoder().encodeToString(new byte[] {1, 2, 3}));
        assertEquals("FAILED", PddVisualPrivacy.reapply(r).path("status").asText());
    }

    @Test
    void falhaDaExtensaoPassaComoEsta() {
        ObjectNode f = m.createObjectNode().put("captureId", "c1").put("status", "FAILED").put("code", "TAB_MISMATCH").put("observedAt", "x");
        assertSame(f, PddVisualPrivacy.reapply(f));
    }
}
