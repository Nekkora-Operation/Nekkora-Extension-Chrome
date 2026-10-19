package com.nekkora.extension.host.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nekkora.extension.core.privacy.CapturePrivacyPolicy;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * STUDIO-010 — TERCEIRA barreira da política visual {@code nekkora-visual/1} (o host não confia na extensão).
 *
 * <p>Para {@code studio.pdd.captured} com imagem: decodifica o PNG, confere política, dimensões e tetos das regiões e
 * REFAZ a verificação de pixels — fora dos retângulos de portadores, só as três cores sintéticas (opaco, preenchimento e
 * borda de campo), totalmente opacas. Qualquer divergência substitui o evento por uma falha
 * ({@code SANITIZATION_CHECK_FAILED}) sem imagem: nada que não passe na verificação chega ao Studio. A imagem nunca é
 * gravada nem registrada aqui.
 *
 * <p>STUDIO-012 PDD-01 — {@code nekkora-visual/2} (representação documental): o host REPINTA a tela esperada a partir das
 * regiões declaradas (paleta fechada, na ordem de pintura) e exige que cada pixel fora dos portadores seja exatamente a
 * cor esperada naquele ponto; portador sobre campo, frame ou mídia e ordem trocada também reprovam.
 *
 * <p>NEKKORA-STUDIO-013 — {@code nekkora-visual/3} (inteiramente sintética): não há imagem. O host refaz a validação da
 * DESCRIÇÃO com as próprias regras: tipos e campos fechados por tipo, ordem de pintura, tetos, geometria inteira dentro do
 * viewport, barras TEXT quantizadas e, em cada LABEL, o texto INTEIRO aprovado pelo vocabulário controlado
 * ({@link CapturePrivacyPolicy#interfaceText}, a mesma política Java das etapas). Um payload v3 com {@code png} reprova.
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
    static final String POLICY_V2 = "nekkora-visual/2";
    static final int V2_PAGE = 0xFFFFFF;
    static final Map<String, Integer> V2_TONES = Map.of("LIGHT", 0xF4F6F8, "MID", 0xE6EAEE, "DARK", 0x5B6675);
    static final int V2_SURFACE_BORDER = 0xC9D1D9;
    static final int V2_MEDIA_FILL = 0xEDEFF2;
    static final int V2_FRAME_FILL = 0xF4F6F8;
    static final int V2_CONTROL_FILL = 0xE6EAEE;
    /** Ordem de pintura da v2 (portadores por cima). */
    static final List<String> V2_KINDS = List.of("SURFACE", "MEDIA", "FRAME", "CONTROL", "FIELD", "CARRIER");
    static final Map<String, Integer> V2_MAX = Map.of("SURFACE", 48, "MEDIA", 16, "FRAME", 16, "CONTROL", 32, "FIELD", MAX_REGIONS, "CARRIER", MAX_REGIONS);

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
        String policy = p.path("policy").asText();
        if (POLICY_V3.equals(policy)) {
            checkV3(p);
            return;
        }
        if (POLICY_V2.equals(policy)) {
            checkV2(p);
            return;
        }
        if (!POLICY.equals(policy)) throw new IllegalArgumentException("política");
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

    /** v2: decodifica, confere geometria, ordem, tetos e sobreposições, e compara cada pixel com a tela esperada. */
    static void checkV2(ObjectNode p) {
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
        List<V2Region> regions = new ArrayList<>();
        Map<String, Integer> count = new HashMap<>();
        int rank = 0;
        long carrierArea = 0;
        for (JsonNode r : p.path("regions")) {
            String kind = r.path("kind").asText();
            int k = V2_KINDS.indexOf(kind);
            if (k < 0 || k < rank) throw new IllegalArgumentException("tipo/ordem");
            rank = k;
            if (count.merge(kind, 1, Integer::sum) > V2_MAX.get(kind)) throw new IllegalArgumentException("regiões");
            int x = r.path("x").asInt(-1), y = r.path("y").asInt(-1), rw = r.path("width").asInt(0), rh = r.path("height").asInt(0);
            if (x < 0 || y < 0 || rw <= 0 || rh <= 0 || x + rw > w || y + rh > h) throw new IllegalArgumentException("região");
            String tone = r.path("tone").isTextual() ? r.path("tone").asText() : null;
            if ("SURFACE".equals(kind) != (tone != null) || (tone != null && !V2_TONES.containsKey(tone))) throw new IllegalArgumentException("tom");
            if ("CARRIER".equals(kind)) {
                if (rh > Math.ceil(CARRIER_MAX_CSS_HEIGHT * dpr) || rw > Math.ceil(w * CARRIER_MAX_WIDTH_FRACTION)) throw new IllegalArgumentException("portador");
                carrierArea += (long) rw * rh;
            }
            regions.add(new V2Region(kind, tone, x, y, x + rw, y + rh));
        }
        if (carrierArea > (long) (w * (long) h * CARRIERS_MAX_AREA_FRACTION)) throw new IllegalArgumentException("regiões");
        for (V2Region c : regions) {
            if (!"CARRIER".equals(c.kind())) continue;
            for (V2Region f : regions) {
                boolean content = "FIELD".equals(f.kind()) || "FRAME".equals(f.kind()) || "MEDIA".equals(f.kind());
                if (content && c.x() < f.x2() && f.x() < c.x2() && c.y() < f.y2() && f.y() < c.y2()) throw new IllegalArgumentException("sobreposição");
            }
        }
        if (violationsV2(img, regions) != 0) throw new IllegalArgumentException("pixels");
    }

    static final String POLICY_V3 = "nekkora-visual/3";
    static final List<String> V3_KINDS = List.of("SURFACE", "MEDIA", "FRAME", "CONTROL", "FIELD", "TEXT", "LABEL");
    static final Map<String, Integer> V3_MAX = Map.of("SURFACE", 48, "MEDIA", 24, "FRAME", 16, "CONTROL", 48, "FIELD", 96, "TEXT", 240, "LABEL", 96);
    static final Map<String, List<String>> V3_FIELDS = Map.of(
            "SURFACE", List.of("kind", "x", "y", "width", "height", "tone"),
            "MEDIA", List.of("kind", "x", "y", "width", "height"),
            "FRAME", List.of("kind", "x", "y", "width", "height"),
            "CONTROL", List.of("kind", "x", "y", "width", "height"),
            "FIELD", List.of("kind", "x", "y", "width", "height"),
            "TEXT", List.of("kind", "x", "y", "width", "height", "ink"),
            "LABEL", List.of("kind", "x", "y", "width", "height", "text", "size", "weight", "ink", "align"));
    static final List<String> V3_PAYLOAD = List.of("captureId", "status", "policy", "observedAt", "viewport", "regions", "annotations", "limitations");
    static final int V3_TEXT_QUANTUM = 16;

    /** v3: só a descrição — nenhuma imagem; texto de rótulo revalidado pelo vocabulário. */
    static void checkV3(ObjectNode p) {
        p.fieldNames().forEachRemaining(f -> {
            if (!V3_PAYLOAD.contains(f)) throw new IllegalArgumentException("campo");
        });
        JsonNode vp = p.path("viewport");
        if (!vp.isObject() || vp.size() != 3) throw new IllegalArgumentException("viewport");
        int vw = intOf(vp.path("width"));
        int vh = intOf(vp.path("height"));
        double dpr = vp.path("dpr").isNumber() ? vp.path("dpr").asDouble() : 0;
        if (vw < 1 || vh < 1 || vw > 8192 || vh > 8192 || !(dpr > 0 && dpr <= 4)) throw new IllegalArgumentException("viewport");
        if (!p.path("regions").isArray()) throw new IllegalArgumentException("regiões");
        Map<String, Integer> count = new HashMap<>();
        int rank = 0;
        for (JsonNode r : p.path("regions")) {
            if (!r.isObject()) throw new IllegalArgumentException("região");
            String kind = r.path("kind").asText();
            int k = V3_KINDS.indexOf(kind);
            if (k < 0 || k < rank) throw new IllegalArgumentException("tipo/ordem");
            rank = k;
            List<String> allowed = V3_FIELDS.get(kind);
            r.fieldNames().forEachRemaining(f -> {
                if (!allowed.contains(f)) throw new IllegalArgumentException("campo da região");
            });
            if (count.merge(kind, 1, Integer::sum) > V3_MAX.get(kind)) throw new IllegalArgumentException("regiões");
            int x = intOf(r.path("x")), y = intOf(r.path("y")), rw = intOf(r.path("width")), rh = intOf(r.path("height"));
            if (x < 0 || y < 0 || rw < 1 || rh < 1 || (long) x + rw > vw || (long) y + rh > vh) throw new IllegalArgumentException("região");
            switch (kind) {
                case "SURFACE" -> {
                    if (!V2_TONES.containsKey(r.path("tone").asText())) throw new IllegalArgumentException("tom");
                }
                case "TEXT" -> {
                    if (!inks(r.path("ink").asText()) || rw % V3_TEXT_QUANTUM != 0) throw new IllegalArgumentException("barra");
                }
                case "LABEL" -> {
                    JsonNode t = r.path("text");
                    String text = t.isTextual() ? t.asText() : null;
                    if (text == null || text.isEmpty() || !text.equals(CapturePrivacyPolicy.interfaceText(text))) throw new IllegalArgumentException("rótulo");
                    int size = intOf(r.path("size"));
                    if (size < 8 || size > 40 || !inks(r.path("ink").asText()) || !List.of("NORMAL", "BOLD").contains(r.path("weight").asText())
                            || !List.of("START", "CENTER").contains(r.path("align").asText())) {
                        throw new IllegalArgumentException("rótulo");
                    }
                }
                default -> {
                }
            }
        }
    }

    private static boolean inks(String v) {
        return "DARK".equals(v) || "LIGHT".equals(v);
    }

    /** Inteiro exato (sem fração, sem texto); -1 se não for. */
    private static int intOf(JsonNode n) {
        return n.isIntegralNumber() && n.canConvertToInt() ? n.asInt() : -1;
    }

    /** Região da v2 em px do dispositivo (x2/y2 exclusivos). */
    record V2Region(String kind, String tone, int x, int y, int x2, int y2) {}

    /** Pixels fora dos portadores diferentes da tela esperada (repintada na ordem declarada). */
    static long violationsV2(BufferedImage img, List<V2Region> regions) {
        int w = img.getWidth();
        int h = img.getHeight();
        int[] expected = new int[w * h];
        Arrays.fill(expected, V2_PAGE);
        boolean[] inCarrier = new boolean[w * h];
        for (V2Region r : regions) {
            if ("CARRIER".equals(r.kind())) {
                for (int y = r.y(); y < r.y2(); y++) Arrays.fill(inCarrier, y * w + r.x(), y * w + r.x2(), true);
                continue;
            }
            int[] fb = v2Paint(r.kind(), r.tone());
            for (int y = r.y(); y < r.y2(); y++) {
                for (int x = r.x(); x < r.x2(); x++) {
                    boolean edge = y == r.y() || y == r.y2() - 1 || x == r.x() || x == r.x2() - 1;
                    expected[y * w + x] = edge ? fb[1] : fb[0];
                }
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
                if ((alpha && (argb >>> 24) != 0xFF) || (argb & 0xFFFFFF) != expected[y * w + x]) bad++;
            }
        }
        return bad;
    }

    /** {preenchimento, borda} de uma região sintética da v2. */
    static int[] v2Paint(String kind, String tone) {
        return switch (kind) {
            case "SURFACE" -> new int[] {V2_TONES.get(tone), V2_SURFACE_BORDER};
            case "MEDIA" -> new int[] {V2_MEDIA_FILL, V2_SURFACE_BORDER};
            case "FRAME" -> new int[] {V2_FRAME_FILL, FIELD_BORDER};
            case "CONTROL" -> new int[] {V2_CONTROL_FILL, FIELD_BORDER};
            case "FIELD" -> new int[] {FIELD_FILL, FIELD_BORDER};
            default -> throw new IllegalArgumentException("tipo");
        };
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
