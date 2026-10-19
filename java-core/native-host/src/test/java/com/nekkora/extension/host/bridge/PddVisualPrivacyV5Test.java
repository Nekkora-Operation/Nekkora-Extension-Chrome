package com.nekkora.extension.host.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.function.Consumer;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Revisão da usuária (19/10/2026): terceira barreira da {@code nekkora-visual/5} (print integral). A tela passa como
 * está; o host repinta as máscaras de senha (FIELD) e de frame (BLOCK). Um pixel da página dentro delas, máscara não
 * pintada, máscara de texto (que só existe na v4) ou campo a mais derrubam o evento.
 */
class PddVisualPrivacyV5Test {
    private static final int W = 240;
    private static final int H = 120;
    private static final int SENTINEL = 0xE6007E;
    private final ObjectMapper m = new ObjectMapper();

    private static int[][] masks() {
        return new int[][] {{0, 140, 10, 90, 60}, {1, 10, 80, 100, 20}}; // BLOCK (frame), FIELD (senha): tipo, x, y, w, h
    }

    /** Tela "real" toda em sentinela (os dados ficam) com as máscaras pintadas por cima, como o service worker faz. */
    private static BufferedImage paint(boolean skipField) {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) img.setRGB(x, y, SENTINEL);
        for (int[] r : masks()) {
            if (skipField && r[0] == 1) continue;
            int fill = r[0] == 0 ? PddVisualPrivacy.V4_BLOCK_FILL : PddVisualPrivacy.FIELD_FILL;
            int border = r[0] == 0 ? PddVisualPrivacy.V4_BLOCK_BORDER : PddVisualPrivacy.FIELD_BORDER;
            for (int y = r[2]; y < r[2] + r[4]; y++) {
                for (int x = r[1]; x < r[1] + r[3]; x++) {
                    img.setRGB(x, y, y == r[2] || y == r[2] + r[4] - 1 || x == r[1] || x == r[1] + r[3] - 1 ? border : fill);
                }
            }
        }
        return img;
    }

    private ObjectNode captured(BufferedImage img) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        ObjectNode p = m.createObjectNode();
        p.put("captureId", "c5").put("status", "CAPTURED").put("policy", "nekkora-visual/5").put("observedAt", "2026-10-19T12:00:00Z").put("width", W)
                .put("height", H).put("devicePixelRatio", 1).put("png", Base64.getEncoder().encodeToString(out.toByteArray())).put("sha256", "sha256:" + "c".repeat(64));
        ArrayNode r = p.putArray("regions");
        String[] kinds = {"BLOCK", "FIELD"};
        for (int[] x : masks()) {
            r.addObject().put("kind", kinds[x[0]]).put("x", x[1]).put("y", x[2]).put("width", x[3]).put("height", x[4]);
        }
        p.putArray("annotations");
        p.putArray("limitations").add("FRAMES_HIDDEN");
        return p;
    }

    private void assertRejected(String why, BufferedImage img, Consumer<ObjectNode> tamper) throws Exception {
        ObjectNode p = captured(img);
        tamper.accept(p);
        JsonNode out = PddVisualPrivacy.reapply(p);
        assertEquals("SANITIZATION_CHECK_FAILED", out.path("code").asText(), why);
        assertEquals(4, out.size(), why);
    }

    @Test
    void printIntegralComSenhaCobertaPassaEValidaNoContrato() throws Exception {
        ObjectNode p = captured(paint(false));
        assertSame(p, PddVisualPrivacy.reapply(p));
        ObjectNode ev = m.createObjectNode().put("protocol", "nekkora-bridge/1").put("kind", "event").put("type", "studio.pdd.captured").put("seq", 1);
        ev.set("payload", p);
        assertTrue(BridgeContract.problems(ev).isEmpty(), BridgeContract.problems(ev).toString());
        assertTrue(BridgeFrames.largeAllowed("event", "studio.pdd.captured", "nekkora-visual/5"));
        assertTrue(!BridgeFrames.largeAllowed("event", "studio.pdd.captured", "nekkora-visual/3"), "a v3 não tem imagem: nunca passa do teto");
    }

    @Test
    void senhaNaoCobertaOuPixelDaPaginaNaMascaraReprova() throws Exception {
        assertRejected("campo de senha declarado e não coberto", paint(true), p -> { });
        BufferedImage leak = paint(false);
        leak.setRGB(40, 90, SENTINEL); // dentro da máscara da senha
        assertRejected("pixel real dentro da máscara da senha", leak, p -> { });
    }

    @Test
    void mascaraDeTextoPintadaCertaAindaReprovaNaV5() throws Exception {
        // a faixa TEXT pintada exatamente como a v4 pinta: só o TIPO (que não existe na v5) reprova
        BufferedImage img = paint(false);
        int bar = PddVisualPrivacy.V4_BARS.get("DARK");
        for (int y = 30; y < 44; y++) for (int x = 10; x < 100; x++) img.setRGB(x, y, bar);
        assertRejected("máscara de texto pintada na v5", img, p -> ((ArrayNode) p.path("regions")).addObject().put("kind", "TEXT").put("x", 10).put("y", 30)
                .put("width", 90).put("height", 14).put("ink", "DARK"));
        ObjectNode v4 = captured(img);
        v4.put("policy", "nekkora-visual/4");
        ((ArrayNode) v4.path("regions")).addObject().put("kind", "TEXT").put("x", 10).put("y", 30).put("width", 90).put("height", 14).put("ink", "DARK");
        assertSame(v4, PddVisualPrivacy.reapply(v4), "a mesma imagem é válida como v4: a recusa da v5 é pelo tipo");
    }

    @Test
    void contratoDaV5Fechado() throws Exception {
        BufferedImage ok = paint(false);
        assertRejected("máscara de texto (só existe na v4)", ok, p -> ((ArrayNode) p.path("regions")).addObject().put("kind", "TEXT").put("x", 1).put("y", 1)
                .put("width", 5).put("height", 5).put("ink", "DARK"));
        assertRejected("tipo de pixels da v2", ok, p -> ((ArrayNode) p.path("regions")).addObject().put("kind", "CARRIER").put("x", 1).put("y", 1)
                .put("width", 5).put("height", 5));
        assertRejected("ordem trocada", ok, p -> ((ArrayNode) p.path("regions")).insert(0, p.path("regions").get(1).deepCopy()));
        assertRejected("url anexada", ok, p -> p.put("url", "https://app.exemplo.test/pacientes"));
        assertRejected("fora da imagem", ok, p -> ((ObjectNode) p.path("regions").get(1)).put("x", 200));
    }
}
