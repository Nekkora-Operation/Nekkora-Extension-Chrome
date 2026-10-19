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
 * NEKKORA-STUDIO-013 (revisão): terceira barreira da {@code nekkora-visual/4} (print real com o texto de dados mascarado).
 * O host repinta as máscaras: um pixel da página DENTRO de uma máscara, uma máscara declarada e não pintada, tipo fora da
 * v4, campo a mais ou imagem grande demais derrubam o evento.
 */
class PddVisualPrivacyV4Test {
    private static final int W = 240;
    private static final int H = 120;
    private static final int SENTINEL = 0xE6007E;
    private final ObjectMapper m = new ObjectMapper();

    private static int[][] masks() {
        return new int[][] {{0, 140, 10, 90, 60}, {1, 10, 80, 100, 20}, {2, 10, 10, 90, 14}}; // BLOCK, FIELD, TEXT (tipo, x, y, w, h)
    }

    /** Tela "real" toda em sentinela com as máscaras pintadas por cima, como o service worker faz. */
    private static BufferedImage paint(boolean skipText) {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) img.setRGB(x, y, SENTINEL);
        for (int[] r : masks()) {
            if (skipText && r[0] == 2) continue;
            int fill = r[0] == 0 ? PddVisualPrivacy.V4_BLOCK_FILL : r[0] == 1 ? PddVisualPrivacy.FIELD_FILL : PddVisualPrivacy.V4_BARS.get("DARK");
            int border = r[0] == 0 ? PddVisualPrivacy.V4_BLOCK_BORDER : r[0] == 1 ? PddVisualPrivacy.FIELD_BORDER : fill;
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
        p.put("captureId", "c4").put("status", "CAPTURED").put("policy", "nekkora-visual/4").put("observedAt", "2026-10-19T12:00:00Z").put("width", W)
                .put("height", H).put("devicePixelRatio", 1).put("png", Base64.getEncoder().encodeToString(out.toByteArray())).put("sha256", "sha256:" + "b".repeat(64));
        ArrayNode r = p.putArray("regions");
        String[] kinds = {"BLOCK", "FIELD", "TEXT"};
        for (int[] x : masks()) {
            ObjectNode o = r.addObject().put("kind", kinds[x[0]]).put("x", x[1]).put("y", x[2]).put("width", x[3]).put("height", x[4]);
            if (x[0] == 2) o.put("ink", "DARK");
        }
        p.putArray("annotations");
        p.putArray("limitations");
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
    void printComMascarasPintadasPassaEValidaNoContrato() throws Exception {
        ObjectNode p = captured(paint(false));
        assertSame(p, PddVisualPrivacy.reapply(p));
        ObjectNode ev = m.createObjectNode().put("protocol", "nekkora-bridge/1").put("kind", "event").put("type", "studio.pdd.captured").put("seq", 1);
        ev.set("payload", p);
        assertTrue(BridgeContract.problems(ev).isEmpty(), BridgeContract.problems(ev).toString());
        assertTrue(BridgeFrames.largeAllowed("event", "studio.pdd.captured", "nekkora-visual/4"));
        assertTrue(!BridgeFrames.largeAllowed("event", "studio.pdd.captured", "nekkora-visual/2"), "só a v4 passa do teto");
        assertTrue(!BridgeFrames.largeAllowed("response", "browser.tabs.list", "nekkora-visual/4"));
    }

    @Test
    void pixelDaPaginaDentroDeMascaraOuMascaraNaoPintadaReprova() throws Exception {
        BufferedImage leak = paint(false);
        leak.setRGB(160, 40, SENTINEL); // dentro do BLOCK
        assertRejected("pixel real dentro da máscara", leak, p -> { });
        assertRejected("linha de texto declarada e não coberta", paint(true), p -> { });
    }

    @Test
    void contratoDaV4Fechado() throws Exception {
        BufferedImage ok = paint(false);
        assertRejected("tipo de pixels da v2", ok, p -> ((ArrayNode) p.path("regions")).addObject().put("kind", "CARRIER").put("x", 1).put("y", 1)
                .put("width", 5).put("height", 5));
        assertRejected("máscara de texto levando o texto", ok, p -> ((ObjectNode) p.path("regions").get(2)).put("text", "Ana Souza"));
        assertRejected("tinta em BLOCK", ok, p -> ((ObjectNode) p.path("regions").get(0)).put("ink", "DARK"));
        assertRejected("ordem trocada", ok, p -> ((ArrayNode) p.path("regions")).insert(0, p.path("regions").get(2).deepCopy()));
        assertRejected("url anexada", ok, p -> p.put("url", "https://app.exemplo.test/pacientes"));
        assertRejected("fora da imagem", ok, p -> ((ObjectNode) p.path("regions").get(1)).put("x", 200));
    }
}
