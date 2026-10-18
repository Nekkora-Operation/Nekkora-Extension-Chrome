package com.nekkora.extension.host.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STUDIO-012 PDD-01: terceira barreira da política {@code nekkora-visual/2}. O host repinta a tela esperada a partir das
 * regiões declaradas: um pixel real fora de um portador, uma região declarada e não desenhada, um tom trocado, a cor de
 * ocultação da v1, a ordem trocada ou um portador sobre um frame derrubam o evento (falha sem imagem).
 */
class PddVisualPrivacyV2Test {
    private static final int W = 240;
    private static final int H = 120;
    private final ObjectMapper m = new ObjectMapper();

    /** Região declarada: tipo, tom (só SURFACE), retângulo. */
    record R(String kind, String tone, int x, int y, int w, int h) {}

    /** Login sintético: cartão claro, cabeçalho escuro, logotipo (mídia), rótulo, campo e botão-portador. */
    private static List<R> login() {
        List<R> r = new ArrayList<>();
        r.add(new R("SURFACE", "DARK", 0, 0, 240, 24));
        r.add(new R("SURFACE", "LIGHT", 40, 30, 160, 80));
        r.add(new R("MEDIA", null, 4, 4, 16, 16));
        r.add(new R("FIELD", null, 60, 60, 120, 18));
        r.add(new R("CARRIER", null, 60, 40, 40, 16)); // "Usuário"
        r.add(new R("CARRIER", null, 140, 84, 40, 20)); // botão "Entrar" (input submit)
        return r;
    }

    /** Pinta como o service worker: fundo, sintéticas na ordem e "pixels reais" (verdes) nos portadores. */
    private static BufferedImage paint(List<R> regions) {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) img.setRGB(x, y, PddVisualPrivacy.V2_PAGE);
        for (R r : regions) {
            for (int y = r.y(); y < r.y() + r.h(); y++) {
                for (int x = r.x(); x < r.x() + r.w(); x++) {
                    if ("CARRIER".equals(r.kind())) {
                        img.setRGB(x, y, 0x0AC81E);
                        continue;
                    }
                    int[] fb = PddVisualPrivacy.v2Paint(r.kind(), r.tone());
                    boolean edge = y == r.y() || y == r.y() + r.h() - 1 || x == r.x() || x == r.x() + r.w() - 1;
                    img.setRGB(x, y, edge ? fb[1] : fb[0]);
                }
            }
        }
        return img;
    }

    private ObjectNode payload(BufferedImage img, List<R> regions) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        ObjectNode p = m.createObjectNode();
        p.put("captureId", "c1").put("status", "CAPTURED").put("policy", "nekkora-visual/2").put("observedAt", "2026-10-18T12:00:00Z");
        p.put("width", W).put("height", H).put("devicePixelRatio", 1.0);
        p.put("png", Base64.getEncoder().encodeToString(out.toByteArray()));
        p.put("sha256", "sha256:" + "a".repeat(64));
        ArrayNode a = p.putArray("regions");
        for (R r : regions) {
            ObjectNode o = a.addObject().put("kind", r.kind()).put("x", r.x()).put("y", r.y()).put("width", r.w()).put("height", r.h());
            if (r.tone() != null) o.put("tone", r.tone());
        }
        p.putArray("annotations").addObject().put("stepRef", "step-1").put("status", "PLACED").put("x", 0.25).put("y", 0.5).put("width", 0.5)
                .put("height", 0.15).put("reason", "");
        p.putArray("limitations");
        return p;
    }

    private ObjectNode event(JsonNode payload) {
        ObjectNode ev = m.createObjectNode();
        ev.put("protocol", "nekkora-bridge/1").put("kind", "event").put("type", "studio.pdd.captured").put("seq", 1);
        ev.set("payload", payload);
        return ev;
    }

    private void assertRejected(JsonNode out) {
        assertEquals("FAILED", out.path("status").asText());
        assertEquals("SANITIZATION_CHECK_FAILED", out.path("code").asText());
        assertFalse(out.has("png"), "a falha não leva imagem");
        assertTrue(BridgeContract.valid(event(out)), String.valueOf(BridgeContract.problemSummary(event(out))));
    }

    @Test
    void representacaoConformePassaSemAlteracaoEDentroDoContrato() throws Exception {
        ObjectNode p = payload(paint(login()), login());
        JsonNode out = PddVisualPrivacy.reapply(p);
        assertSame(p, out);
        assertTrue(BridgeContract.valid(event(out)), String.valueOf(BridgeContract.problemSummary(event(out))));
    }

    @Test
    void umPixelRealDentroDeUmaSuperficieViraFalha() throws Exception {
        BufferedImage img = paint(login());
        img.setRGB(120, 100, 0x0AC81E); // vazamento dentro do cartão
        assertRejected(PddVisualPrivacy.reapply(payload(img, login())));
    }

    @Test
    void regiaoDeclaradaENaoDesenhadaViraFalha() throws Exception {
        List<R> drawn = new ArrayList<>(login());
        drawn.remove(1); // o cartão não foi pintado, mas é declarado
        assertRejected(PddVisualPrivacy.reapply(payload(paint(drawn), login())));
    }

    @Test
    void tomTrocadoViraFalha() throws Exception {
        List<R> declared = new ArrayList<>(login());
        declared.set(0, new R("SURFACE", "LIGHT", 0, 0, 240, 24)); // pintado DARK, declarado LIGHT
        assertRejected(PddVisualPrivacy.reapply(payload(paint(login()), declared)));
    }

    @Test
    void corDeOcultacaoDaV1NaoValeNaV2() throws Exception {
        BufferedImage img = paint(login());
        for (int x = 0; x < W; x++) img.setRGB(x, H - 1, PddVisualPrivacy.OPAQUE);
        assertRejected(PddVisualPrivacy.reapply(payload(img, login())));
    }

    @Test
    void ordemTrocadaOuSuperficieSemTomViraFalha() throws Exception {
        List<R> swapped = new ArrayList<>(login());
        R field = swapped.remove(3);
        swapped.add(0, field); // campo antes das superfícies
        assertRejected(PddVisualPrivacy.reapply(payload(paint(swapped), swapped)));
        List<R> noTone = new ArrayList<>(login());
        noTone.set(1, new R("SURFACE", null, 40, 30, 160, 80));
        assertRejected(PddVisualPrivacy.reapply(payload(paint(login()), noTone)));
    }

    @Test
    void portadorSobreFrameOuMidiaViraFalha() throws Exception {
        List<R> r = new ArrayList<>(login());
        r.add(3, new R("FRAME", null, 130, 80, 60, 30)); // o botão-portador fica em cima do frame
        assertRejected(PddVisualPrivacy.reapply(payload(paint(r), r)));
        List<R> media = new ArrayList<>(login());
        media.set(2, new R("MEDIA", null, 50, 36, 30, 30)); // logotipo debaixo do rótulo
        assertRejected(PddVisualPrivacy.reapply(payload(paint(media), media)));
    }

    @Test
    void v1ContinuaIgual() throws Exception {
        // a política anterior segue com a regra dela: opaco + portador + campo
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) img.setRGB(x, y, PddVisualPrivacy.OPAQUE);
        ObjectNode p = payload(img, List.of());
        p.put("policy", "nekkora-visual/1");
        assertSame(p, PddVisualPrivacy.reapply(p));
        p.put("policy", "nekkora-visual/9");
        assertRejected(PddVisualPrivacy.reapply(p));
    }
}
