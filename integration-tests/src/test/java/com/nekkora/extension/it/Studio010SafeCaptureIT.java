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
import org.openqa.selenium.Point;

import java.awt.Robot;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STUDIO-010 G4/G5 no Chrome REAL: Studio (de laboratório) → Bridge → Native Host → extensão. A captura só acontece com
 * o GESTO do usuário (atalho Alt+Shift+K pressionado no sistema operacional) depois de o Studio armar; a imagem sai
 * SANITIZADA por lista de permissão (política nekkora-visual/1) e o host reverifica os pixels.
 *
 * <p>Prova por cor: todo texto sensível do laboratório é desenhado na cor-sentinela #E6007E; nenhum pixel dessa cor pode
 * existir na imagem que chega ao Studio. Os rótulos de interface seguros (cor escura) têm de aparecer. Só as imagens JÁ
 * sanitizadas são gravadas como evidência.
 */
class Studio010SafeCaptureIT extends SessionIT {

    private static final String LOGIN = "/lab/studio010/login.html";
    private static final String CONSULTA = "/lab/studio010/consulta.html";
    private static final String SENTINELAS = "/lab/studio010/sentinelas.html";
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
        if (studio == null) {
            return;
        }
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

    private static String id(String p) {
        return p + "-" + UUID.randomUUID();
    }

    private Page page(String path) {
        Page p = lab.open(path);
        opened.add(p);
        return p;
    }

    private String refOf(Page p) {
        return lab.call("studio.tabRef", p).path("tabRef").asText();
    }

    private static ObjectNode descriptor(String kind, String tag, String name, String htmlId) {
        ObjectNode d = M.createObjectNode().put("kind", kind).put("tag", tag).put("name", name).put("htmlId", htmlId);
        d.putNull("htmlName");
        d.putArray("locators").addObject().put("strategy", "ID").put("css", "#" + htmlId).putNull("xpath").put("description", "");
        d.putArray("accessPath").addObject().put("type", "MAIN_DOCUMENT").putNull("locator");
        return d;
    }

    private JsonNode arm(Page p, String captureId, Object[][] steps) {
        ObjectNode pl = studio.obj().put("captureId", captureId).put("tabRef", refOf(p)).put("leaseMs", 30000);
        ArrayNode a = pl.putArray("steps");
        for (Object[] s : steps) {
            a.addObject().put("stepRef", (String) s[0]).putNull("targetId").put("actionType", (String) s[1]).set("descriptor", (JsonNode) s[2]);
        }
        return studio.call("studio.pdd.capture.arm", pl);
    }

    /** O GESTO: foco do SO na janela do Chrome (faixa de abas, fora da página) e Alt+Shift+K pelo teclado do sistema. */
    private void gesture(Page p) throws Exception {
        lab.focus(p);
        Point pos = lab.driver().manage().window().getPosition();
        Dimension size = lab.driver().manage().window().getSize();
        Robot r = new Robot();
        r.setAutoDelay(40);
        r.mouseMove(pos.getX() + size.getWidth() - 230, pos.getY() + 16);
        r.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        r.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        Thread.sleep(400);
        String fg = foregroundProcess();
        assertTrue(fg.toLowerCase().contains("chrome-for-testing"), "outro programa em primeiro plano — nenhuma tecla enviada: " + fg);
        r.keyPress(KeyEvent.VK_ALT);
        r.keyPress(KeyEvent.VK_SHIFT);
        r.keyPress(KeyEvent.VK_K);
        r.keyRelease(KeyEvent.VK_K);
        r.keyRelease(KeyEvent.VK_SHIFT);
        r.keyRelease(KeyEvent.VK_ALT);
    }

    /** Caminho do executável dono da janela em primeiro plano (só o nome vai à mensagem; nunca o título). */
    static String foregroundProcess() throws Exception {
        String ps = "Add-Type -TypeDefinition 'using System; using System.Runtime.InteropServices; public static class NkFg {"
                + " [DllImport(\"user32.dll\")] public static extern IntPtr GetForegroundWindow();"
                + " [DllImport(\"user32.dll\")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint p); }';"
                + " $p = [uint32]0; [void][NkFg]::GetWindowThreadProcessId([NkFg]::GetForegroundWindow(), [ref]$p); (Get-Process -Id $p).Path";
        // STUDIO-013: sem console, o PowerShell pode mandar registros de progresso (CLIXML) no fluxo de erro — ficam de fora
        ps = "$ProgressPreference = 'SilentlyContinue'; " + ps;
        String encoded = Base64.getEncoder().encodeToString(ps.getBytes(java.nio.charset.StandardCharsets.UTF_16LE));
        Process pr = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        String out = new String(pr.getInputStream().readAllBytes()).trim();
        pr.waitFor();
        String last = "";
        for (String line : out.split("\\R")) if (!line.isBlank() && !line.startsWith("#<")) last = line.trim();
        return last;
    }

    private void zoom(Page p, int steps) throws Exception {
        lab.focus(p);
        Point pos = lab.driver().manage().window().getPosition();
        Dimension size = lab.driver().manage().window().getSize();
        Robot f = new Robot();
        f.mouseMove(pos.getX() + size.getWidth() - 230, pos.getY() + 16);
        f.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        f.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        Thread.sleep(300);
        assertTrue(foregroundProcess().toLowerCase().contains("chrome-for-testing"), "outro programa em primeiro plano — nenhuma tecla enviada");
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

    private static BufferedImage image(JsonNode payload) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(payload.path("png").asText())));
    }

    /**
     * A cor-sentinela #E6007E misturada com fundo claro (suavização): r alto, g baixo, b ENTRE g e r, com r-b ≈ 0,45·(r-g).
     * Franjas de ClearType de texto azul (b ≥ r) não contam — eram o falso positivo da 1ª rodada.
     */
    static boolean sentinel(int r, int g, int b) {
        int rg = r - g;
        return r > 170 && rg > 70 && b > g && r - b > 0.25 * rg && r - b < 0.7 * rg;
    }

    /** Pixels da família da cor-sentinela. */
    static int sentinelPixels(BufferedImage img) {
        int n = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int c = img.getRGB(x, y);
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                if (sentinel(r, g, b)) n++;
            }
        }
        return n;
    }

    /** Mesma regra do host/Studio: fora dos portadores, só as três cores sintéticas. */
    static long violations(BufferedImage img, JsonNode regions) {
        int w = img.getWidth();
        boolean[] in = new boolean[w * img.getHeight()];
        for (JsonNode r : regions) {
            if (!"CARRIER".equals(r.path("kind").asText())) continue;
            for (int y = r.path("y").asInt(); y < r.path("y").asInt() + r.path("height").asInt(); y++) {
                for (int x = r.path("x").asInt(); x < r.path("x").asInt() + r.path("width").asInt(); x++) in[y * w + x] = true;
            }
        }
        long bad = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < w; x++) {
                if (in[y * w + x]) continue;
                int c = img.getRGB(x, y) & 0xFFFFFF;
                if (c != 0xD5D8DD && c != 0xFFFFFF && c != 0x8A9099) bad++;
            }
        }
        return bad;
    }

    /** Diagnóstico SEM imagem: retângulo dos pixels-sentinela e os portadores que os contêm. */
    static String where(BufferedImage img, JsonNode regions) {
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = -1, y1 = -1;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int c = img.getRGB(x, y);
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                if (sentinel(r, g, b)) {
                    x0 = Math.min(x0, x); y0 = Math.min(y0, y); x1 = Math.max(x1, x); y1 = Math.max(y1, y);
                }
            }
        }
        StringBuilder sb = new StringBuilder("[" + x0 + "," + y0 + " → " + x1 + "," + y1 + "] em portadores: ");
        for (JsonNode r : regions) {
            int rx = r.path("x").asInt(), ry = r.path("y").asInt(), rw = r.path("width").asInt(), rh = r.path("height").asInt();
            if ("CARRIER".equals(r.path("kind").asText()) && x0 < rx + rw && rx <= x1 && y0 < ry + rh && ry <= y1) sb.append(r).append(' ');
        }
        return sb.toString();
    }

    private static int carriers(JsonNode payload) {
        int n = 0;
        for (JsonNode r : payload.path("regions")) if ("CARRIER".equals(r.path("kind").asText())) n++;
        return n;
    }

    /** Algum portador cobre o centro do elemento (retângulo medido na página, em px do dispositivo)? */
    private boolean carrierOver(Page p, JsonNode payload, String selector) {
        @SuppressWarnings("unchecked")
        List<Number> c = (List<Number>) lab.js(p, "const r = document.querySelector(arguments[0]).getBoundingClientRect(); const d = devicePixelRatio;"
                + " return [(r.left + r.width / 2) * d, (r.top + r.height / 2) * d];", selector);
        double cx = c.get(0).doubleValue(), cy = c.get(1).doubleValue();
        for (JsonNode r : payload.path("regions")) {
            if ("CARRIER".equals(r.path("kind").asText()) && cx >= r.path("x").asInt() && cx < r.path("x").asInt() + r.path("width").asInt()
                    && cy >= r.path("y").asInt() && cy < r.path("y").asInt() + r.path("height").asInt()) {
                return true;
            }
        }
        return false;
    }

    private void evidence(JsonNode payload, String name) throws IOException {
        Files.write(lab.evidenceDir().resolve(name + ".png"), Base64.getDecoder().decode(payload.path("png").asText())); // JÁ sanitizada
    }

    private static final Object[][] LOGIN_STEPS = {
            {"step-u", "FILL", descriptor("TEXT_INPUT", "input", "Usuário", "usuario")},
            {"step-s", "FILL", descriptor("PASSWORD_INPUT", "input", "Senha", "senha")},
            {"step-e", "CLICK", descriptor("BUTTON", "button", "Entrar", "entrar")}};

    // ------------------------------------------------------------------------------------------ cenários

    @Test
    @DisplayName("G4-01 login preenchido: gesto real → PNG sanitizado (0 pixel-sentinela, 0 violação), rótulos Usuário/Senha/Entrar visíveis, 3 marcadores no lugar")
    void loginSanitizado() throws Exception {
        Page p = page(LOGIN);
        lab.js(p, "document.getElementById('usuario').value = 'ana.souza'; document.getElementById('senha').value = 'S3nh@Lab';");
        String cid = id("cap");
        JsonNode armed = arm(p, cid, LOGIN_STEPS);
        assertTrue(armed.path("ok").asBoolean(), armed.toString());
        assertEquals("Alt+Shift+K", armed.at("/payload/shortcut").asText());
        gesture(p);
        JsonNode e = captured(cid);
        assertEquals("CAPTURED", e.path("status").asText(), e.toString());
        BufferedImage img = image(e);
        assertEquals(0, sentinelPixels(img), "nenhum pixel do texto sensível (título, nota, valores digitados)");
        assertEquals(0, violations(img, e.path("regions")));
        assertTrue(carrierOver(p, e, "label[for=usuario]") && carrierOver(p, e, "label[for=senha]") && carrierOver(p, e, "#entrar"),
                "rótulos de interface aparecem: " + e.path("regions"));
        for (int i = 0; i < 3; i++) {
            JsonNode a = e.path("annotations").get(i);
            assertEquals("PLACED", a.path("status").asText(), a.toString());
            String sel = new String[] {"#usuario", "#senha", "#entrar"}[i];
            @SuppressWarnings("unchecked")
            List<Number> r = (List<Number>) lab.js(p, "const r = document.querySelector(arguments[0]).getBoundingClientRect();"
                    + " return [r.left / innerWidth, r.top / innerHeight];", sel);
            assertEquals(r.get(0).doubleValue(), a.path("x").asDouble(), 0.005, sel);
            assertEquals(r.get(1).doubleValue(), a.path("y").asDouble(), 0.005, sel);
        }
        assertTrue(lab.js(p, "return document.getElementById('usuario').value").equals("ana.souza"), "a página não foi alterada");
        evidence(e, "S010-G4-01-login-sanitizado");
    }

    @Test
    @DisplayName("G4-02 sentinelas por classe (pseudo-elemento, fundo, imagem, SVG, canvas, tabela, toast sobre botão, iframes, shadow, campos): nenhum vaza")
    void sentinelasPorClasse() throws Exception {
        Page p = page(SENTINELAS);
        String cid = id("cap");
        assertTrue(arm(p, cid, new Object[][] {{"step-c", "FILL", descriptor("TEXT_INPUT", "input", "Código do registro", "codigo")}}).path("ok").asBoolean());
        gesture(p);
        JsonNode e = captured(cid);
        assertEquals("CAPTURED", e.path("status").asText(), e.toString());
        BufferedImage img = image(e);
        BufferedImage bruta = ImageIO.read(new ByteArrayInputStream(((org.openqa.selenium.TakesScreenshot) lab.driver())
                .getScreenshotAs(org.openqa.selenium.OutputType.BYTES))); // só em memória: nunca gravada
        assertTrue(sentinelPixels(bruta) > 1000, "controle: o detector enxerga a sentinela na tela bruta (" + sentinelPixels(bruta) + ")");
        bruta = null;
        assertEquals(0, sentinelPixels(img), "nenhuma classe de sentinela vaza; onde: " + where(img, e.path("regions")));
        assertEquals(0, violations(img, e.path("regions")));
        assertTrue(carrierOver(p, e, "label[for=codigo]") && carrierOver(p, e, "#consultar") && carrierOver(p, e, "#sair"), "controles positivos visíveis: "
                + carrierOver(p, e, "label[for=codigo]") + "/" + carrierOver(p, e, "#consultar") + "/" + carrierOver(p, e, "#sair"));
        assertTrue(!carrierOver(p, e, "#coberto"), "botão coberto pelo toast não é portador");
        assertTrue(e.path("limitations").toString().contains("FRAMES_HIDDEN") && e.path("limitations").toString().contains("SOME_LABELS_HIDDEN"),
                e.path("limitations").toString());
        evidence(e, "S010-G4-02-sentinelas-sanitizado");
    }

    @Test
    @DisplayName("G4-03 modal por cima de tudo: nenhum portador sobrevive (todos cobertos); nada vaza")
    void modal() throws Exception {
        Page p = page(SENTINELAS + "?v=modal");
        String cid = id("cap");
        assertTrue(arm(p, cid, new Object[][] {{"step-c", "FILL", descriptor("TEXT_INPUT", "input", "Código do registro", "codigo")}}).path("ok").asBoolean());
        gesture(p);
        JsonNode e = captured(cid);
        assertEquals("CAPTURED", e.path("status").asText());
        assertEquals(0, carriers(e), "tudo coberto pelo modal: nenhum rótulo é copiado");
        assertEquals(0, sentinelPixels(image(e)));
    }

    @Test
    @DisplayName("G4-04 página mudando sem parar: PAGE_CHANGED_DURING_CAPTURE, nenhuma imagem")
    void paginaMudando() throws Exception {
        Page p = page(SENTINELAS + "?v=mudanca");
        String cid = id("cap");
        assertTrue(arm(p, cid, new Object[][] {{"step-c", "FILL", descriptor("TEXT_INPUT", "input", "Código do registro", "codigo")}}).path("ok").asBoolean());
        gesture(p);
        JsonNode e = captured(cid);
        assertEquals("FAILED", e.path("status").asText());
        assertEquals("PAGE_CHANGED_DURING_CAPTURE", e.path("code").asText());
        assertTrue(!e.has("png"));
    }

    @Test
    @DisplayName("G4-05 origem fora do laboratório (site autorizado): VISUAL_CAPTURE_NOT_ALLOWED ao armar; navegar depois de armar desarma (PAGE_CHANGED)")
    void negativas() throws Exception {
        Page site = lab.openUrl(lab.externalUrl(BrowserLab.SITE_A, LOGIN));
        opened.add(site);
        JsonNode r = arm(site, id("cap"), LOGIN_STEPS);
        assertEquals("VISUAL_CAPTURE_NOT_ALLOWED", r.at("/error/code").asText(), r.toString());
        Page p = page(LOGIN);
        String cid = id("cap");
        assertTrue(arm(p, cid, LOGIN_STEPS).path("ok").asBoolean());
        lab.js(p, "location.href = 'consulta.html'");
        JsonNode e = captured(cid);
        assertEquals("PAGE_CHANGED", e.path("code").asText(), e.toString());
    }

    @Test
    @DisplayName("G5-01 zoom de 150%: imagem em px do dispositivo, marcadores normalizados no lugar, nada vaza")
    void zoom150() throws Exception {
        Page p = page(LOGIN);
        zoom(p, 3); // 110% → 125% → 150%
        try {
            double dpr = ((Number) lab.js(p, "return devicePixelRatio")).doubleValue();
            assertEquals(1.5, dpr, 0.01, "zoom do navegador em 150%");
            String cid = id("cap");
            assertTrue(arm(p, cid, LOGIN_STEPS).path("ok").asBoolean());
            gesture(p);
            JsonNode e = captured(cid);
            assertEquals("CAPTURED", e.path("status").asText(), e.toString());
            assertEquals(1.5, e.path("devicePixelRatio").asDouble(), 0.01);
            assertEquals(0, sentinelPixels(image(e)));
            JsonNode a = e.path("annotations").get(2);
            @SuppressWarnings("unchecked")
            List<Number> r = (List<Number>) lab.js(p, "const r = document.getElementById('entrar').getBoundingClientRect(); return [r.left / innerWidth, r.top / innerHeight];");
            assertEquals(r.get(0).doubleValue(), a.path("x").asDouble(), 0.005);
            assertEquals(r.get(1).doubleValue(), a.path("y").asDouble(), 0.005);
            evidence(e, "S010-G5-01-login-150-sanitizado");
        } finally {
            zoom(p, -3);
        }
    }

    @Test
    @DisplayName("G5-02 página rolada: marcadores no lugar relativo ao viewport; alvos fora da área visível → OMITTED (TARGET_OUTSIDE_VIEWPORT), nada vaza")
    void rolagem() throws Exception {
        Page p = page(LOGIN);
        lab.js(p, "const s = document.createElement('div'); s.style.height = '1500px'; document.body.prepend(s);"
                + " document.getElementById('usuario').value = 'ana.souza';");
        lab.js(p, "scrollTo(0, document.getElementById('entrar').getBoundingClientRect().top + scrollY - 200)");
        Thread.sleep(300);
        assertTrue(((Number) lab.js(p, "return scrollY")).doubleValue() > 500, "página rolada: "
                + lab.js(p, "return [scrollY, innerHeight, document.documentElement.scrollHeight, document.getElementById('entrar').getBoundingClientRect().top].join(' ')"));
        String cid = id("cap");
        assertTrue(arm(p, cid, LOGIN_STEPS).path("ok").asBoolean());
        gesture(p);
        JsonNode e = captured(cid);
        assertEquals("CAPTURED", e.path("status").asText(), e.toString());
        assertEquals(0, sentinelPixels(image(e)));
        assertEquals(0, violations(image(e), e.path("regions")));
        for (int i = 0; i < 3; i++) {
            JsonNode a = e.path("annotations").get(i);
            assertEquals("PLACED", a.path("status").asText(), a.toString());
            @SuppressWarnings("unchecked")
            List<Number> r = (List<Number>) lab.js(p, "const r = document.querySelector(arguments[0]).getBoundingClientRect();"
                    + " return [r.left / innerWidth, r.top / innerHeight];", new String[] {"#usuario", "#senha", "#entrar"}[i]);
            assertEquals(r.get(0).doubleValue(), a.path("x").asDouble(), 0.005);
            assertEquals(r.get(1).doubleValue(), a.path("y").asDouble(), 0.005, "y relativo ao viewport (com a rolagem)");
        }
        lab.js(p, "scrollTo(0, 0)");
        Thread.sleep(300);
        String cid2 = id("cap");
        assertTrue(arm(p, cid2, LOGIN_STEPS).path("ok").asBoolean());
        gesture(p);
        JsonNode e2 = captured(cid2);
        assertEquals("CAPTURED", e2.path("status").asText(), e2.toString());
        for (JsonNode a : e2.path("annotations")) {
            assertEquals("OMITTED", a.path("status").asText(), a.toString());
            assertEquals("TARGET_OUTSIDE_VIEWPORT", a.path("reason").asText(), a.toString());
        }
        assertEquals(0, sentinelPixels(image(e2)));
    }

    // ------------------------------------------------------------------- R1 da revisão: identidade da aba na captura

    /** Página do login com a thread ocupada (preparação lenta) + uma 2ª aba do laboratório na MESMA janela. */
    private Page[] slowWithSecondTab() throws Exception {
        return slowWithSecondTab(700);
    }

    private Page[] slowWithSecondTab(int busyMs) throws Exception {
        Page a = page(LOGIN);
        Page b = lab.openTab(a, CONSULTA);
        opened.add(b);
        lab.focus(a);
        assertEquals("visible", lab.js(a, "return document.visibilityState"), "a aba A está à frente");
        lab.js(a, "window.nkLenta(true, " + busyMs + ")");
        return new Page[] {a, b};
    }

    private static void noImage(JsonNode e) {
        assertEquals("FAILED", e.path("status").asText(), e.toString());
        assertTrue(!e.has("png") && !e.has("regions"), "nenhuma imagem publicada: " + e);
    }

    @Test
    @DisplayName("R1-01 troca de aba logo depois do gesto (preparação em andamento): ACTIVE_TAB_CHANGED, nenhuma imagem")
    void trocaDepoisDoGesto() throws Exception {
        Page[] t = slowWithSecondTab();
        try {
            String cid = id("cap");
            assertTrue(arm(t[0], cid, LOGIN_STEPS).path("ok").asBoolean());
            gesture(t[0]);
            lab.focus(t[1]); // a aba B (laboratório, mesma janela) fica ativa antes da captura
            assertEquals("visible", lab.js(t[1], "return document.visibilityState"));
            JsonNode e = captured(cid);
            noImage(e);
            assertEquals("ACTIVE_TAB_CHANGED", e.path("code").asText());
        } finally {
            lab.js(t[0], "window.nkLenta(false)");
        }
    }

    @Test
    @DisplayName("R1-02 troca de aba no meio de uma preparação demorada: ACTIVE_TAB_CHANGED; depois, captura limpa na aba original funciona")
    void trocaDuranteAPreparacao() throws Exception {
        Page[] t = slowWithSecondTab(2500); // cada mensagem ao content script espera até 2,5 s: preparação de vários segundos
        String cid = id("cap");
        assertTrue(arm(t[0], cid, LOGIN_STEPS).path("ok").asBoolean());
        gesture(t[0]);
        Thread.sleep(1200); // já dentro da preparação (página ocupada)
        lab.focus(t[1]);
        JsonNode e = captured(cid);
        noImage(e);
        assertEquals("ACTIVE_TAB_CHANGED", e.path("code").asText());
        lab.focus(t[0]);
        lab.js(t[0], "window.nkLenta(false)");
        String cid2 = id("cap");
        assertTrue(arm(t[0], cid2, LOGIN_STEPS).path("ok").asBoolean());
        gesture(t[0]);
        JsonNode ok = captured(cid2);
        assertEquals("CAPTURED", ok.path("status").asText(), ok.toString());
        assertEquals(0, sentinelPixels(image(ok)));
    }

    @Test
    @DisplayName("R1-03 segunda aba do laboratório: armada para A, gesto em B → TAB_MISMATCH, nada de B é capturado")
    void segundaAbaDoLaboratorio() throws Exception {
        Page a = page(LOGIN);
        Page b = lab.openTab(a, CONSULTA);
        opened.add(b);
        String cid = id("cap");
        assertTrue(arm(a, cid, LOGIN_STEPS).path("ok").asBoolean());
        gesture(b);
        JsonNode e = captured(cid);
        noImage(e);
        assertEquals("TAB_MISMATCH", e.path("code").asText());
    }

    @Test
    @DisplayName("R1-04 ida e volta A→B→A durante a preparação: ACTIVE_TAB_CHANGED mesmo com A ativa no fim")
    void idaEVolta() throws Exception {
        Page[] t = slowWithSecondTab();
        try {
            String cid = id("cap");
            assertTrue(arm(t[0], cid, LOGIN_STEPS).path("ok").asBoolean());
            gesture(t[0]);
            lab.focus(t[1]);
            Thread.sleep(200);
            lab.focus(t[0]); // de volta à aba armada
            JsonNode e = captured(cid);
            noImage(e);
            assertEquals("ACTIVE_TAB_CHANGED", e.path("code").asText());
        } finally {
            lab.js(t[0], "window.nkLenta(false)");
        }
    }

    @Test
    @DisplayName("R1-05 cancelar depois do gesto, com a captura em andamento: cancelled=true, evento CANCELLED, nenhuma imagem")
    void cancelarEmAndamento() throws Exception {
        Page[] t = slowWithSecondTab();
        try {
            String cid = id("cap");
            assertTrue(arm(t[0], cid, LOGIN_STEPS).path("ok").asBoolean());
            gesture(t[0]);
            Thread.sleep(300);
            JsonNode c = studio.call("studio.pdd.capture.cancel", studio.obj().put("captureId", cid));
            assertTrue(c.at("/payload/cancelled").asBoolean(), c.toString());
            JsonNode e = captured(cid);
            noImage(e);
            assertEquals("CANCELLED", e.path("code").asText());
        } finally {
            lab.js(t[0], "window.nkLenta(false)");
        }
    }
}
