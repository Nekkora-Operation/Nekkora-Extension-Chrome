package com.nekkora.extension.host.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * STUDIO-010 — TERCEIRA barreira da política visual {@code nekkora-visual/1} (o host não confia na extensão).
 *
 * <p>Para {@code studio.pdd.captured} com imagem: decodifica o PNG, confere política, dimensões e tetos das regiões e
 * REFAZ a verificação de pixels — fora dos retângulos de portadores, só as três cores sintéticas (opaco, preenchimento e
 * borda de campo), totalmente opacas. Qualquer divergência substitui o evento por uma falha
 * ({@code SANITIZATION_CHECK_FAILED}) sem imagem: nada que não passe na verificação chega ao Studio. A imagem nunca é
 * gravada nem registrada aqui.
 */
final class PddVisualPrivacy {
    static final String POLICY = "nekkora-visual/1";
    static final int OPAQUE = 0xD5D8DD;
    static final int FIELD_FILL = 0xFFFFFF;
    static final int FIELD_BORDER = 0x8A9099;
    static final int MAX_PNG_BYTES = 150_000;
    static final int MAX_REGIONS = 64;
    static final int CARRIER_MAX_CSS_HEIGHT = 64;
    static final double CARRIER_MAX_WIDTH_FRACTION = 0.6;
    static final double CARRIERS_MAX_AREA_FRACTION = 0.2;

    private PddVisualPrivacy() {
    }

    /** Devolve o payload a repassar: o original, se conforme; senão uma falha sem imagem. */
    static JsonNode reapply(JsonNode payload) {
        if (!(payload instanceof ObjectNode p) || !"CAPTURED".equals(p.path("status").asText())) {
            return payload;
        }
        try {
            check(p);
            return p;
        } catch (RuntimeException e) {
            ObjectNode f = JsonNodeFactory.instance.objectNode();
            f.put("captureId", p.path("captureId").asText(""));
            f.put("status", "FAILED");
            f.put("code", "SANITIZATION_CHECK_FAILED");
            f.put("observedAt", p.path("observedAt").asText(""));
            return f;
        }
    }

    /** Lança se a imagem não estiver conforme. */
    static void check(ObjectNode p) {
        if (!POLICY.equals(p.path("policy").asText())) throw new IllegalArgumentException("política");
        byte[] png = Base64.getDecoder().decode(p.path("png").asText(""));
        if (png.length == 0 || png.length > MAX_PNG_BYTES) throw new IllegalArgumentException("tamanho");
        BufferedImage img;
        try {
            img = ImageIO.read(new ByteArrayInputStream(png));
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("png");
        }
        if (img == null) throw new IllegalArgumentException("png");
        int w = p.path("width").asInt(-1);
        int h = p.path("height").asInt(-1);
        double dpr = p.path("devicePixelRatio").asDouble(0);
        if (img.getWidth() != w || img.getHeight() != h || !(dpr > 0 && dpr <= 4)) throw new IllegalArgumentException("dimensões");
        List<int[]> carriers = new ArrayList<>();
        long carrierArea = 0;
        int fields = 0;
        for (JsonNode r : p.path("regions")) {
            int x = r.path("x").asInt(-1), y = r.path("y").asInt(-1), rw = r.path("width").asInt(0), rh = r.path("height").asInt(0);
            if (x < 0 || y < 0 || rw <= 0 || rh <= 0 || x + rw > w || y + rh > h) throw new IllegalArgumentException("região");
            String kind = r.path("kind").asText();
            if ("CARRIER".equals(kind)) {
                if (rh > Math.ceil(CARRIER_MAX_CSS_HEIGHT * dpr) || rw > Math.ceil(w * CARRIER_MAX_WIDTH_FRACTION)) throw new IllegalArgumentException("portador");
                carriers.add(new int[] {x, y, x + rw, y + rh});
                carrierArea += (long) rw * rh;
            } else if ("FIELD".equals(kind)) {
                fields++;
            } else {
                throw new IllegalArgumentException("tipo");
            }
        }
        if (carriers.size() > MAX_REGIONS || fields > MAX_REGIONS || carrierArea > (long) (w * (long) h * CARRIERS_MAX_AREA_FRACTION)) {
            throw new IllegalArgumentException("regiões");
        }
        if (violations(img, carriers) != 0) throw new IllegalArgumentException("pixels");
    }

    /** Pixels fora dos portadores que não são uma das três cores sintéticas opacas. */
    static long violations(BufferedImage img, List<int[]> carriers) {
        int w = img.getWidth();
        int h = img.getHeight();
        boolean[] inCarrier = new boolean[w * h];
        for (int[] c : carriers) {
            for (int y = c[1]; y < c[3]; y++) {
                for (int x = c[0]; x < c[2]; x++) inCarrier[y * w + x] = true;
            }
        }
        boolean alpha = img.getColorModel().hasAlpha();
        long bad = 0;
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            img.getRGB(0, y, w, 1, row, 0, w);
            for (int x = 0; x < w; x++) {
                if (inCarrier[y * w + x]) continue;
                int argb = row[x];
                int rgb = argb & 0xFFFFFF;
                if ((alpha && (argb >>> 24) != 0xFF) || (rgb != OPAQUE && rgb != FIELD_FILL && rgb != FIELD_BORDER)) bad++;
            }
        }
        return bad;
    }
}
