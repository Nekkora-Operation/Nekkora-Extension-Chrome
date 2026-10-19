package com.nekkora.extension.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nekkora.extension.it.support.BrowserLab;
import com.nekkora.extension.it.support.BrowserLab.Page;
import com.nekkora.extension.it.support.SessionIT;
import com.nekkora.extension.it.support.StudioBridgeLab;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.Point;
import org.openqa.selenium.TakesScreenshot;

import java.awt.Robot;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STUDIO-012 PDD-01 no Chrome REAL: política {@code nekkora-visual/2} (representação documental). Mesmo caminho da
 * STUDIO-010 (Studio de laboratório → Bridge → Native Host → extensão, gesto real Alt+Shift+K pelo SO), com o Studio
 * PEDINDO a v2 ao armar. A imagem é redesenhada com paleta fechada; só os portadores trazem pixels reais.
 *
 * <p>Prova por cor: todo texto sensível do laboratório é desenhado na cor-sentinela #E6007E; nenhum pixel dela pode
 * existir na imagem que chega. Prova por repintura: cada pixel fora dos portadores é exatamente a cor que as regiões
 * declaradas mandam pintar ali. Só imagens JÁ sanitizadas (de páginas sintéticas) são gravadas como evidência.
 */
class Studio012PddVisualIT extends SessionIT {

    private static final String LOGIN = "/lab/studio012/pdd-login.html";
    private static final String SENSIVEIS = "/lab/studio012/pdd-sensiveis.html";
    private static final String SENTINELAS = "/lab/studio010/sentinelas.html";
    private static final String LOGIN010 = "/lab/studio010/login.html";
    private static final String CONSULTA010 = "/lab/studio010/consulta.html";
    private static final String V2 = "nekkora-visual/2";
    private static final ObjectMapper M = new ObjectMapper();
    private static StudioBridgeLab studio;
    private final List<Page> opened = new ArrayList<>();

    @BeforeAll
    static void bridge() throws Exception {
        Assumptions.assumeFalse(Boolean.getBoolean("nekkora.headless"), "o gesto (atalho do SO) exige o Chrome com janela");
        System.setProperty("java.awt.headless", "false");
        lab = BrowserLab.get();
        studio = new StudioBridgeLab(lab.bridgeDir());
        lab.call("studio.link.set", Map.of("enabled", true));
        studio.awaitConnected(Duration.ofSeconds(30));
    }

    @AfterAll
    static void unbridge() throws IOException {
        if (studio == null) return;
        try {
            lab.call("studio.link.set", Map.of("enabled", false));
        } finally {
            studio.close();
        }
        assertTrue(studio.contractViolations.isEmpty(), "mensagens fora do contrato: " + studio.contractViolations);
    }

    @AfterEach
    void cleanUp() {
        opened.forEach(lab::close);
        opened.clear();
    }

    // ------------------------------------------------------------------------------------------ apoio

    private Page page(String path) {
        Page p = lab.open(path);
        opened.add(p);
        return p;
    }

    private static ObjectNode descriptor(String kind, String tag, String name, String htmlId) {
        ObjectNode d = M.createObjectNode().put("kind", kind).put("tag", tag).put("name", name).put("htmlId", htmlId);
        d.putNull("htmlName");
        d.putArray("locators").addObject().put("strategy", "ID").put("css", "#" + htmlId).putNull("xpath").put("description", "");
        d.putArray("accessPath").addObject().put("type", "MAIN_DOCUMENT").putNull("locator");
        return d;
    }

    private JsonNode arm(Page p, String captureId, Object[][] steps, String policy) {
        ObjectNode pl = studio.obj().put("captureId", captureId).put("tabRef", lab.call("studio.tabRef", p).path("tabRef").asText()).put("leaseMs", 30000);
        if (policy != null) pl.put("policy", policy);
        ArrayNode a = pl.putArray("steps");
        for (Object[] s : steps) {
            a.addObject().put("stepRef", (String) s[0]).putNull("targetId").put("actionType", (String) s[1]).set("descriptor", (JsonNode) s[2]);
        }
        return studio.call("studio.pdd.capture.arm", pl);
    }

    /** O GESTO: foco do SO na janela do Chrome (faixa de abas) e Alt+Shift+K pelo teclado do sistema. */
    private void gesture(Page p) throws Exception {
        clickTabStrip(p);
        Robot r = new Robot();
        r.setAutoDelay(40);
        r.keyPress(KeyEvent.VK_ALT);
        r.keyPress(KeyEvent.VK_SHIFT);
        r.keyPress(KeyEvent.VK_K);
        r.keyRelease(KeyEvent.VK_K);
        r.keyRelease(KeyEvent.VK_SHIFT);
        r.keyRelease(KeyEvent.VK_ALT);
    }

    private void clickTabStrip(Page p) throws Exception {
        lab.focus(p);
        Point pos = lab.driver().manage().window().getPosition();
        Dimension size = lab.driver().manage().window().getSize();
        Robot r = new Robot();
        r.setAutoDelay(40);
        r.mouseMove(pos.getX() + size.getWidth() - 230, pos.getY() + 16);
        r.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        r.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        Thread.sleep(400);
        String fg = Studio010SafeCaptureIT.foregroundProcess();
        assertTrue(fg.toLowerCase().contains("chrome-for-testing"), "outro programa em primeiro plano — nenhuma tecla enviada: " + fg);
    }

    private void zoom(Page p, int steps) throws Exception {
        clickTabStrip(p);
        Robot r = new Robot();
        r.setAutoDelay(60);
        for (int i = 0; i < Math.abs(steps); i++) {
            r.keyPress(KeyEvent.VK_CONTROL);
            r.keyPress(steps > 0 ? KeyEvent.VK_ADD : KeyEvent.VK_SUBTRACT);
            r.keyRelease(steps > 0 ? KeyEvent.VK_ADD : KeyEvent.VK_SUBTRACT);
            r.keyRelease(KeyEvent.VK_CONTROL);
        }
        Thread.sleep(800);
    }

    private JsonNode captured(String captureId) throws InterruptedException {
        return studio.awaitEvent(e -> "studio.pdd.captured".equals(e.path("type").asText()) && captureId.equals(e.at("/payload/captureId").asText()),
                Duration.ofSeconds(25)).get("payload");
    }

    private JsonNode shoot(Page p, Object[][] steps, String policy) throws Exception {
        String cid = "cap-" + UUID.randomUUID();
        JsonNode armed = arm(p, cid, steps, policy);
        assertTrue(armed.path("ok").asBoolean(), armed.toString());
        if (policy != null) assertEquals(policy, armed.at("/payload/policy").asText());
        gesture(p);
        return captured(cid);
    }

    private static BufferedImage image(JsonNode payload) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(payload.path("png").asText())));
    }

    /** Paleta e ordem da v2 (as mesmas do service worker, do host e do Studio). */
    static int[] paint(String kind, String tone) {
        return switch (kind) {
            case "SURFACE" -> new int[] {switch (tone) { case "LIGHT" -> 0xF4F6F8; case "MID" -> 0xE6EAEE; default -> 0x5B6675; }, 0xC9D1D9};
            case "MEDIA" -> new int[] {0xEDEFF2, 0xC9D1D9};
            case "FRAME" -> new int[] {0xF4F6F8, 0x8A9099};
            case "CONTROL" -> new int[] {0xE6EAEE, 0x8A9099};
            case "FIELD" -> new int[] {0xFFFFFF, 0x8A9099};
            default -> throw new IllegalArgumentException(kind);
        };
    }

    /** Repintura independente: pixels fora dos portadores diferentes do que as regiões mandam pintar. */
    static long violationsV2(BufferedImage img, JsonNode regions) {
        int w = img.getWidth();
        int h = img.getHeight();
        int[] expected = new int[w * h];
        Arrays.fill(expected, 0xFFFFFF);
        boolean[] in = new boolean[w * h];
        for (JsonNode r : regions) {
            int x0 = r.path("x").asInt(), y0 = r.path("y").asInt(), x1 = x0 + r.path("width").asInt(), y1 = y0 + r.path("height").asInt();
            String kind = r.path("kind").asText();
            for (int y = y0; y < y1; y++) {
                for (int x = x0; x < x1; x++) {
                    if ("CARRIER".equals(kind)) {
                        in[y * w + x] = true;
                        continue;
                    }
                    int[] fb = paint(kind, r.path("tone").asText(null));
                    expected[y * w + x] = y == y0 || y == y1 - 1 || x == x0 || x == x1 - 1 ? fb[1] : fb[0];
                }
            }
        }
        long bad = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!in[y * w + x] && (img.getRGB(x, y) & 0xFFFFFF) != expected[y * w + x]) bad++;
            }
        }
        return bad;
    }

    /** Retângulo do elemento em px do dispositivo. */
    @SuppressWarnings("unchecked")
    private double[] rect(Page p, String selector) {
        List<Number> c = (List<Number>) lab.js(p, "const r = document.querySelector(arguments[0]).getBoundingClientRect(); const d = devicePixelRatio;"
                + " return [r.left * d, r.top * d, r.right * d, r.bottom * d];", selector);
        return new double[] {c.get(0).doubleValue(), c.get(1).doubleValue(), c.get(2).doubleValue(), c.get(3).doubleValue()};
    }

    /** Alguma região do tipo cobre o centro do elemento? */
    private boolean regionOver(Page p, JsonNode payload, String selector, String kind) {
        double[] b = rect(p, selector);
        double cx = (b[0] + b[2]) / 2, cy = (b[1] + b[3]) / 2;
        for (JsonNode r : payload.path("regions")) {
            if (kind.equals(r.path("kind").asText()) && cx >= r.path("x").asInt() && cx < r.path("x").asInt() + r.path("width").asInt()
                    && cy >= r.path("y").asInt() && cy < r.path("y").asInt() + r.path("height").asInt()) {
                return true;
            }
        }
        return false;
    }

    private static long count(JsonNode payload, String kind) {
        long n = 0;
        for (JsonNode r : payload.path("regions")) if (kind.equals(r.path("kind").asText())) n++;
        return n;
    }

    private void evidence(JsonNode payload, String name) throws IOException {
        Files.write(lab.evidenceDir().resolve(name + ".png"), Base64.getDecoder().decode(payload.path("png").asText())); // JÁ sanitizada
    }

    private static int rawSentinel(Page p) throws IOException {
        lab.focus(p); // o screenshot do driver é o da janela em foco: sem isto, mede-se outra aba
        BufferedImage bruta = ImageIO.read(new ByteArrayInputStream(((TakesScreenshot) lab.driver()).getScreenshotAs(OutputType.BYTES))); // só em memória
        return Studio010SafeCaptureIT.sentinelPixels(bruta);
    }

    private static final Object[][] LOGIN_STEPS = {
            {"step-u", "FILL", descriptor("TEXT_INPUT", "input", "Usuário", "ds_login")},
            {"step-s", "FILL", descriptor("PASSWORD_INPUT", "input", "Senha", "passwordTemp")},
            {"step-e", "CLICK", descriptor("BUTTON", "input", "Entrar", "Button_DoLogin")}};

    // ------------------------------------------------------------------------------------------ cenários

    @Test
    @DisplayName("PDD01-01 réplica do login: v1 reproduz o defeito (cinza, botão vazio); v2 = fundo claro, estrutura redesenhada, botão Entrar legível, 0 sentinela, 0 violação")
    void loginAntesEDepois() throws Exception {
        Page p = page(LOGIN);
        lab.js(p, "document.getElementById('ds_login').value = 'ana.souza'; document.getElementById('passwordTemp').value = 'S3nh@Lab';");
        int raw = rawSentinel(p);
        assertTrue(raw > 500, "controle: o detector enxerga a sentinela na tela bruta (" + raw + " px)");
        // ANTES (nekkora-visual/1, o Studio não pede política): reproduz o PDF da vistoria
        JsonNode v1 = shoot(p, LOGIN_STEPS, null);
        assertEquals("CAPTURED", v1.path("status").asText(), v1.toString());
        assertEquals("nekkora-visual/1", v1.path("policy").asText());
        BufferedImage i1 = image(v1);
        assertEquals(0, Studio010SafeCaptureIT.sentinelPixels(i1));
        assertEquals(0, Studio010SafeCaptureIT.violations(i1, v1.path("regions")));
        assertFalse(regionOver(p, v1, "#Button_DoLogin", "CARRIER"), "v1: o botão (input submit) não é portador — fica cinza (causa C2)");
        evidence(v1, "S012-PDD01-01-login-v1-antes");
        // DEPOIS (nekkora-visual/2)
        JsonNode v2 = shoot(p, LOGIN_STEPS, V2);
        assertEquals("CAPTURED", v2.path("status").asText(), v2.toString());
        assertEquals(V2, v2.path("policy").asText());
        BufferedImage i2 = image(v2);
        assertEquals(0, Studio010SafeCaptureIT.sentinelPixels(i2), "nenhum pixel do título, da nota, do logotipo, do rodapé nem dos valores digitados");
        assertEquals(0, violationsV2(i2, v2.path("regions")), "repintura confere pixel a pixel");
        assertTrue(regionOver(p, v2, "label[for=ds_login]", "CARRIER") && regionOver(p, v2, "label[for=passwordTemp]", "CARRIER"), "rótulos visíveis");
        assertTrue(regionOver(p, v2, "#Button_DoLogin", "CARRIER"), "v2: o botão Entrar (input submit) é portador: " + v2.path("regions"));
        assertTrue(regionOver(p, v2, "#ds_login", "FIELD") && regionOver(p, v2, "#passwordTemp", "FIELD"), "campos como molduras vazias");
        assertTrue(regionOver(p, v2, ".cartao", "SURFACE") && regionOver(p, v2, ".topo", "SURFACE") && regionOver(p, v2, ".rodape", "SURFACE"),
                "estrutura: cartão, faixa do topo e rodapé redesenhados");
        assertTrue(regionOver(p, v2, ".marca svg", "MEDIA"), "logotipo só como lugar");
        int light = 0;
        for (int y = 0; y < i2.getHeight(); y++) for (int x = 0; x < i2.getWidth(); x++) if ((i2.getRGB(x, y) & 0xFFFFFF) == 0xD5D8DD) light++;
        assertEquals(0, light, "o cinza de ocultação da v1 não existe na v2");
        for (int i = 0; i < 3; i++) assertEquals("PLACED", v2.path("annotations").get(i).path("status").asText(), v2.path("annotations").toString());
        assertEquals("ana.souza", lab.js(p, "return document.getElementById('ds_login').value"), "a página não foi alterada");
        evidence(v2, "S012-PDD01-01-login-v2-depois");
    }

    @Test
    @DisplayName("PDD01-02 sensíveis: menu, tabela, iframe de outra origem, rótulo sobre imagem, botão grande, senha preenchida e status — nada vaza; tabela = 1 superfície")
    void sensiveis() throws Exception {
        Page p = page(SENSIVEIS);
        Thread.sleep(500); // o iframe externo carrega
        int raw = rawSentinel(p);
        assertTrue(raw > 1000, "controle: o detector enxerga a sentinela na tela bruta (" + raw + " px)");
        JsonNode e = shoot(p, new Object[][] {{"step-s", "FILL", descriptor("PASSWORD_INPUT", "input", "Senha", "senha")}}, V2);
        assertEquals("CAPTURED", e.path("status").asText(), e.toString());
        BufferedImage img = image(e);
        assertEquals(0, Studio010SafeCaptureIT.sentinelPixels(img), "onde: " + Studio010SafeCaptureIT.where(img, e.path("regions")));
        assertEquals(0, violationsV2(img, e.path("regions")));
        assertTrue(regionOver(p, e, "#inicio", "CARRIER") && regionOver(p, e, "#consultar", "CARRIER") && regionOver(p, e, "#sair", "CARRIER")
                && regionOver(p, e, "label[for=senha]:not(#sobre-imagem)", "CARRIER"), "controles positivos visíveis");
        assertFalse(regionOver(p, e, "#sobre-imagem", "CARRIER"), "rótulo sobre imagem/gradiente de fundo não é portador");
        assertFalse(regionOver(p, e, "#grande", "CARRIER"), "botão grande demais não é portador...");
        assertTrue(regionOver(p, e, "#grande", "CONTROL"), "...mas aparece como formato de botão");
        assertTrue(regionOver(p, e, "#externo", "FRAME"), "iframe de outra origem: só o lugar");
        assertTrue(e.path("limitations").toString().contains("FRAMES_HIDDEN"), e.path("limitations").toString());
        assertTrue(regionOver(p, e, ".status", "SURFACE"), "status: superfície com tom, nunca a cor");
        double[] t = rect(p, "table");
        long inside = 0;
        for (JsonNode r : e.path("regions")) {
            int x = r.path("x").asInt(), y = r.path("y").asInt(), x2 = x + r.path("width").asInt(), y2 = y + r.path("height").asInt();
            boolean strictlyInside = x >= t[0] + 1 && y >= t[1] + 1 && x2 <= t[2] - 1 && y2 <= t[3] - 1;
            if (strictlyInside) inside++;
        }
        assertEquals(0, inside, "nada de dentro da tabela é descrito (linhas, células)");
        assertTrue(regionOver(p, e, "table", "SURFACE"));
        evidence(e, "S012-PDD01-02-sensiveis-v2");
    }

    @Test
    @DisplayName("PDD01-03 sentinelas por classe da STUDIO-010 sob a v2: nenhuma vaza (pseudo, fundo, imagem, SVG, canvas, tabela, toast, iframes, shadow, campos)")
    void sentinelasV2() throws Exception {
        Page p = page(SENTINELAS);
        Thread.sleep(500);
        int raw = rawSentinel(p);
        assertTrue(raw > 1000, "controle positivo do detector (" + raw + " px)");
        JsonNode e = shoot(p, new Object[][] {{"step-c", "FILL", descriptor("TEXT_INPUT", "input", "Código do registro", "codigo")}}, V2);
        assertEquals("CAPTURED", e.path("status").asText(), e.toString());
        BufferedImage img = image(e);
        assertEquals(0, Studio010SafeCaptureIT.sentinelPixels(img), "onde: " + Studio010SafeCaptureIT.where(img, e.path("regions")));
        assertEquals(0, violationsV2(img, e.path("regions")));
        assertFalse(regionOver(p, e, "#coberto", "CARRIER"), "botão coberto pelo toast não é portador");
        assertTrue(count(e, "FRAME") >= 2, "os dois iframes como lugar");
        evidence(e, "S012-PDD01-03-sentinelas-v2");
    }

    @Test
    @DisplayName("PDD01-04 zoom de 150% sob a v2: px do dispositivo, repintura confere, marcadores no lugar")
    void zoom150V2() throws Exception {
        Page p = page(LOGIN);
        zoom(p, 3);
        try {
            assertEquals(1.5, ((Number) lab.js(p, "return devicePixelRatio")).doubleValue(), 0.01, "zoom do navegador em 150%");
            JsonNode e = shoot(p, LOGIN_STEPS, V2);
            assertEquals("CAPTURED", e.path("status").asText(), e.toString());
            assertEquals(1.5, e.path("devicePixelRatio").asDouble(), 0.01);
            BufferedImage img = image(e);
            assertEquals(0, Studio010SafeCaptureIT.sentinelPixels(img));
            assertEquals(0, violationsV2(img, e.path("regions")));
            assertTrue(regionOver(p, e, "#Button_DoLogin", "CARRIER"));
            JsonNode a = e.path("annotations").get(2);
            @SuppressWarnings("unchecked")
            List<Number> r = (List<Number>) lab.js(p, "const r = document.getElementById('Button_DoLogin').getBoundingClientRect(); return [r.left / innerWidth, r.top / innerHeight];");
            assertEquals(r.get(0).doubleValue(), a.path("x").asDouble(), 0.005);
            assertEquals(r.get(1).doubleValue(), a.path("y").asDouble(), 0.005);
            evidence(e, "S012-PDD01-04-login-150-v2");
        } finally {
            zoom(p, -3);
        }
    }

    @Test
    @DisplayName("PDD01-05 capturas inválidas sob a v2: página mudando → PAGE_CHANGED_DURING_CAPTURE; troca de aba → ACTIVE_TAB_CHANGED; nenhuma imagem")
    void invalidasV2() throws Exception {
        Page p = page(SENTINELAS + "?v=mudanca");
        JsonNode e = shoot(p, new Object[][] {{"step-c", "FILL", descriptor("TEXT_INPUT", "input", "Código", "codigo")}}, V2);
        assertEquals("FAILED", e.path("status").asText(), e.toString());
        assertEquals("PAGE_CHANGED_DURING_CAPTURE", e.path("code").asText());
        assertFalse(e.has("png"));
        Page a = page(LOGIN010);
        Page b = lab.openTab(a, CONSULTA010);
        opened.add(b);
        lab.focus(a);
        lab.js(a, "window.nkLenta(true, 1500)");
        try {
            String cid = "cap-" + UUID.randomUUID();
            assertTrue(arm(a, cid, new Object[][] {{"step-u", "FILL", descriptor("TEXT_INPUT", "input", "Usuário", "usuario")}}, V2).path("ok").asBoolean());
            gesture(a);
            lab.focus(b);
            JsonNode t = captured(cid);
            assertEquals("FAILED", t.path("status").asText(), t.toString());
            assertEquals("ACTIVE_TAB_CHANGED", t.path("code").asText());
            assertFalse(t.has("png"));
        } finally {
            lab.js(a, "window.nkLenta(false)");
        }
    }
}
