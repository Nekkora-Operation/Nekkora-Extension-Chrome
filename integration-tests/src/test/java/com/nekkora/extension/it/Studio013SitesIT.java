package com.nekkora.extension.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nekkora.extension.it.support.BrowserLab;
import com.nekkora.extension.it.support.BrowserLab.Page;
import com.nekkora.extension.it.support.SessionIT;
import com.nekkora.extension.it.support.StudioBridgeLab;
import com.nekkora.extension.lab.LabServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Point;

import java.awt.Robot;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NEKKORA-STUDIO-013 no Chrome REAL: captura do PDD em SITE autorizado, sem lista compilada. Caminho completo (Studio de
 * laboratório → Bridge → Native Host → extensão) com o gesto real Alt+Shift+K pelo SO.
 *
 * <p>Os "sites" são hosts de teste resolvidos para 127.0.0.1 pelo próprio Chrome ({@code *.nekkora.test}), em porta que
 * nenhuma lista compilada conhece: nenhum tráfego sai da máquina. A página é um "sistema real" sintético e adversarial
 * (tabela de pacientes fictícios, campos preenchidos, autofill, shadow roots, iframe de outra origem, sobreposições…), com
 * marcadores-sentinela em todo dado sensível. Prova: a descrição v3 que chega ao Studio não contém nenhum marcador, só
 * rótulos do vocabulário, e não traz imagem.
 */
class Studio013SitesIT extends SessionIT {

    private static final String SISTEMA = "/lab/studio013/sistema.html";
    private static final String SITE = "http://" + BrowserLab.SITE_A + ":" + BrowserLab.PORT;
    private static final String V3 = "nekkora-visual/3";
    private static final List<String> SENTINELAS = List.of("SENTINELA", "Sentinela", "Ana Souza", "Bruno Lima", "Carla Dias", "Zeta", "123.456.789-09",
            "987.654.321-00", "S3nh@Lab", "ana.souza", "autofill", "sombra.aberta", "Internado", "Observação", "Exame", "/lab/", "studio013", "nekkora.test/");
    /** Rótulos que a página pode mostrar (todos do vocabulário); nada fora disso pode virar texto. */
    private static final Set<String> ROTULOS = Set.of("Início", "Menu", "Usuário", "Senha", "Entrar", "Pesquisar", "Salvar", "Ver detalhes", "Confirmar",
            "Cancelar", "Sair");
    private static final ObjectMapper M = new ObjectMapper();
    private static StudioBridgeLab studio;
    private final List<Page> opened = new ArrayList<>();

    @BeforeAll
    static void bridge() throws Exception {
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
        for (JsonNode c : studio.call("studio.pdd.consent.list", studio.obj()).at("/payload/consents")) revoke(c.path("origin").asText());
        opened.forEach(lab::close);
        opened.clear();
    }

    // ------------------------------------------------------------------------------------------ apoio

    private Page site(String url) {
        Page p = lab.openUrl(url);
        opened.add(p);
        return p;
    }

    private Page sistema() {
        return site(SITE + SISTEMA);
    }

    private String ref(Page p) {
        return lab.call("studio.tabRef", p).path("tabRef").asText();
    }

    private JsonNode status(Page p) {
        JsonNode r = studio.call("studio.pdd.consent.status", studio.obj().put("tabRef", ref(p)));
        assertTrue(r.path("ok").asBoolean(), r.toString());
        return r.get("payload");
    }

    private JsonNode grant(Page p, String origin, String scope) {
        return studio.call("studio.pdd.consent.grant", studio.obj().put("tabRef", ref(p)).put("origin", origin).put("scope", scope).put("actor", "TEST_AGENT"));
    }

    /** Revisão da usuária: consentimento com o nível (MASKED ou FULL = print integral). */
    private JsonNode grant(Page p, String origin, String scope, String level) {
        return studio.call("studio.pdd.consent.grant", studio.obj().put("tabRef", ref(p)).put("origin", origin).put("scope", scope).put("actor", "TEST_AGENT")
                .put("level", level));
    }

    private JsonNode revoke(String origin) {
        return studio.call("studio.pdd.consent.revoke", studio.obj().put("origin", origin));
    }

    private static ObjectNode descriptor(String kind, String tag, String name, String htmlId) {
        ObjectNode d = M.createObjectNode().put("kind", kind).put("tag", tag).put("name", name).put("htmlId", htmlId);
        d.putNull("htmlName");
        d.putArray("locators").addObject().put("strategy", "ID").put("css", "#" + htmlId).putNull("xpath").put("description", "");
        d.putArray("accessPath").addObject().put("type", "MAIN_DOCUMENT").putNull("locator");
        return d;
    }

    private static final Object[][] STEPS = {
            {"step-u", "FILL", descriptor("TEXT_INPUT", "input", "Usuário", "usuario")},
            {"step-s", "FILL", descriptor("PASSWORD_INPUT", "input", "Senha", "senha")},
            {"step-e", "CLICK", descriptor("BUTTON", "input", "Entrar", "entrar")}};

    private JsonNode arm(Page p, String captureId, String policy, String origin) {
        ObjectNode pl = studio.obj().put("captureId", captureId).put("tabRef", ref(p)).put("leaseMs", 30000);
        if (policy != null) pl.put("policy", policy);
        if (origin != null) pl.put("origin", origin);
        ArrayNode a = pl.putArray("steps");
        for (Object[] s : STEPS) a.addObject().put("stepRef", (String) s[0]).putNull("targetId").put("actionType", (String) s[1]).set("descriptor", (JsonNode) s[2]);
        return studio.call("studio.pdd.capture.arm", pl);
    }

    private void gesture(Page p) throws Exception {
        // só os cenários com o gesto exigem o Chrome com janela; os demais também rodam na regressão headless
        Assumptions.assumeFalse(Boolean.getBoolean("nekkora.headless"), "o gesto (atalho do SO) exige o Chrome com janela");
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

    private void ctrlTab(int times) throws Exception {
        Robot r = new Robot();
        r.setAutoDelay(80);
        for (int i = 0; i < times; i++) {
            r.keyPress(KeyEvent.VK_CONTROL);
            r.keyPress(KeyEvent.VK_TAB);
            r.keyRelease(KeyEvent.VK_TAB);
            r.keyRelease(KeyEvent.VK_CONTROL);
        }
    }

    private JsonNode captured(String captureId) throws InterruptedException {
        JsonNode e = studio.awaitEvent(x -> "studio.pdd.captured".equals(x.path("type").asText()) && captureId.equals(x.at("/payload/captureId").asText()),
                Duration.ofSeconds(25));
        assertNotNull(e, "evento da captura " + captureId);
        return e.get("payload");
    }

    private JsonNode shoot(Page p, String origin) throws Exception {
        String cid = "cap-" + UUID.randomUUID();
        JsonNode armed = arm(p, cid, V3, origin);
        assertTrue(armed.path("ok").asBoolean(), armed.toString());
        assertEquals(V3, armed.at("/payload/policy").asText());
        gesture(p);
        return captured(cid);
    }

    /** A descrição v3: sem imagem, sem marcador-sentinela, só rótulos do vocabulário, barras quantizadas. */
    private static void assertSynthetic(JsonNode payload) {
        assertEquals("CAPTURED", payload.path("status").asText(), payload.toString());
        assertEquals(V3, payload.path("policy").asText());
        assertFalse(payload.has("png") || payload.has("sha256"), "a v3 não traz imagem");
        String all = payload.toString();
        for (String s : SENTINELAS) assertFalse(all.contains(s), "marcador na descrição: " + s);
        for (JsonNode r : payload.path("regions")) {
            String kind = r.path("kind").asText();
            if ("LABEL".equals(kind)) assertTrue(ROTULOS.contains(r.path("text").asText()), "rótulo fora do esperado: " + r);
            if ("TEXT".equals(kind)) assertEquals(0, r.path("width").asInt() % 16, "barra não quantizada: " + r);
            assertFalse(r.has("value") || r.has("placeholder") || r.has("color"), "campo fora do contrato: " + r);
        }
    }

    private static long count(JsonNode payload, String kind) {
        long n = 0;
        for (JsonNode r : payload.path("regions")) if (kind.equals(r.path("kind").asText())) n++;
        return n;
    }

    private static Set<String> labels(JsonNode payload) {
        Set<String> out = new HashSet<>();
        for (JsonNode r : payload.path("regions")) if ("LABEL".equals(r.path("kind").asText())) out.add(r.path("text").asText());
        return out;
    }

    private void evidence(JsonNode payload, String name) throws IOException {
        Files.writeString(lab.evidenceDir().resolve(name + ".json"), M.writerWithDefaultPrettyPrinter().writeValueAsString(payload)); // descrição sintética
    }

    private static String code(JsonNode reply) {
        return reply.at("/error/code").asText() + "/" + reply.at("/error/reason").asText();
    }

    // ------------------------------------------------------------------------------------------ cenários

    @Test
    @DisplayName("S013-01 site com acesso do Chrome e SEM consentimento PDD: listado com origem exata, captura recusada (PDD_CONSENT_REQUIRED)")
    void semConsentimento() {
        Page p = sistema();
        JsonNode st = status(p);
        assertEquals(SITE, st.path("origin").asText(), "origem exata, sem caminho");
        assertEquals("AUTHORIZED", st.path("access").asText());
        assertEquals("NONE", st.path("consent").asText());
        assertTrue(st.path("visualCapture").isNull());
        assertEquals("PDD_CONSENT_REQUIRED", st.path("reason").asText());
        JsonNode tabs = studio.call("browser.tabs.list", studio.obj()).at("/payload/tabs");
        boolean listed = false;
        for (JsonNode t : tabs) {
            if (t.path("tabRef").asText().equals(ref(p))) {
                listed = true;
                assertEquals(SITE, t.path("origin").asText());
                assertEquals("NONE", t.path("pddConsent").asText());
                assertTrue(t.path("visualCapture").isNull());
            }
            assertFalse(t.toString().contains("/lab/"), "lista de abas sem caminho: " + t);
        }
        assertTrue(listed);
        for (String policy : new String[] {V3, "nekkora-visual/2", null}) {
            JsonNode r = arm(p, "cap-" + UUID.randomUUID(), policy, policy == null ? null : SITE);
            assertEquals("VISUAL_CAPTURE_NOT_ALLOWED/PDD_CONSENT_REQUIRED", code(r), String.valueOf(policy));
        }
    }

    @Test
    @DisplayName("S013-02 consentir pelo Studio (sessão) e capturar com o gesto real: descrição v3 sintética, sem imagem e sem nenhum marcador; recaptura")
    void consentirECapturar() throws Exception {
        Page p = sistema();
        JsonNode g = grant(p, SITE, "SESSION");
        assertTrue(g.path("ok").asBoolean(), g.toString());
        assertEquals("TEST_AGENT", g.at("/payload/actor").asText(), "quem decidiu fica registrado");
        assertEquals("AUTHORIZED_SITE", status(p).path("visualCapture").asText());
        // Studio antigo (v1/v2) num site: recusado — a v2 copiaria pixels reais dos rótulos
        assertEquals("VISUAL_CAPTURE_NOT_ALLOWED/POLICY_REQUIRES_V3", code(arm(p, "cap-" + UUID.randomUUID(), "nekkora-visual/2", SITE)));
        assertEquals("VISUAL_CAPTURE_NOT_ALLOWED/POLICY_REQUIRES_V3", code(arm(p, "cap-" + UUID.randomUUID(), null, null)));
        JsonNode one = shoot(p, SITE);
        assertSynthetic(one);
        assertTrue(labels(one).containsAll(Set.of("Usuário", "Senha", "Entrar", "Pesquisar")), labels(one).toString());
        assertTrue(labels(one).contains("Confirmar") && labels(one).contains("Cancelar"), "shadow roots aberta e fechada percorridas: " + labels(one));
        assertTrue(count(one, "FIELD") >= 8, "campos (input, senha, placeholder, autofill, select, textarea, editável, sombra): " + count(one, "FIELD"));
        assertTrue(count(one, "FRAME") >= 1 && count(one, "MEDIA") >= 3, "iframe e mídias (svg, canvas, vídeo, fundo) só como lugar");
        assertTrue(one.path("limitations").toString().contains("TABLE_CONTENT_HIDDEN") && one.path("limitations").toString().contains("FRAMES_HIDDEN"));
        assertEquals(3, one.path("annotations").size());
        evidence(one, "S013-02-descricao-v3");
        JsonNode two = shoot(p, SITE);
        assertSynthetic(two);
        assertEquals(count(one, "LABEL"), count(two, "LABEL"), "recaptura da mesma tela");
    }

    @Test
    @DisplayName("S013-03 menu dinâmico aberto e DOM mudando durante a captura: rótulos do menu do vocabulário; texto mutante nunca sai")
    void menuEDomVivo() throws Exception {
        Page p = site(SITE + SISTEMA + "?menu=1&mutar=1");
        assertTrue(grant(p, SITE, "SESSION").path("ok").asBoolean());
        JsonNode d = shoot(p, SITE);
        assertSynthetic(d);
        assertTrue(labels(d).contains("Sair"), "item do menu aberto (vocabulário): " + labels(d));
        assertFalse(d.toString().contains("Atualização"), "texto que muda na página nunca vira texto");
    }

    @Test
    @DisplayName("S013-04 origem EXATA: outra porta do mesmo host e subdomínio não herdam o consentimento; sem acesso do Chrome, nem dá para consentir")
    void origemExata() throws Exception {
        Page p = sistema();
        assertTrue(grant(p, SITE, "REMEMBERED").path("ok").asBoolean());
        LabServer other = new LabServer(BrowserLab.PORT + 2, lab.repo().resolve("test-lab").resolve("pages"));
        other.start();
        try {
            Page porta = site("http://" + BrowserLab.SITE_A + ":" + (BrowserLab.PORT + 2) + SISTEMA);
            JsonNode st = status(porta);
            assertEquals("http://" + BrowserLab.SITE_A + ":" + (BrowserLab.PORT + 2), st.path("origin").asText());
            assertEquals("AUTHORIZED", st.path("access").asText(), "o Chrome permite o host em qualquer porta");
            assertEquals("PDD_CONSENT_REQUIRED", st.path("reason").asText(), "o consentimento é da porta " + BrowserLab.PORT);
            assertEquals("VISUAL_CAPTURE_NOT_ALLOWED/PDD_CONSENT_REQUIRED", code(arm(porta, "cap-" + UUID.randomUUID(), V3, st.path("origin").asText())));
        } finally {
            other.stop();
        }
        Page sub = site("http://" + BrowserLab.SUB_A + ":" + BrowserLab.PORT + SISTEMA);
        JsonNode st = status(sub);
        assertEquals("AUTHORIZED", st.path("access").asText(), "subdomínio com acesso do Chrome (construção de teste)");
        assertEquals("http://" + BrowserLab.SUB_A + ":" + BrowserLab.PORT, st.path("origin").asText());
        assertEquals("PDD_CONSENT_REQUIRED", st.path("reason").asText(), "subdomínio não herda o consentimento de site-a");
        assertEquals("VISUAL_CAPTURE_NOT_ALLOWED/PDD_CONSENT_REQUIRED", code(arm(sub, "cap-" + UUID.randomUUID(), V3, st.path("origin").asText())));
        Page semAcesso = lab.openWithoutContent("http://" + BrowserLab.SITE_X + ":" + BrowserLab.PORT + SISTEMA);
        opened.add(semAcesso);
        assertEquals("VISUAL_CAPTURE_NOT_ALLOWED/PERMISSION_REQUIRED",
                code(grant(semAcesso, "http://" + BrowserLab.SITE_X + ":" + BrowserLab.PORT, "SESSION")), "consentimento PDD exige o acesso do Chrome antes");
    }

    // S013-05 (permissão AMPLA do Chrome sem consentimento PDD): o controle "Acesso ao site" do Chrome não concede permissão
    // OPCIONAL nova sem o diálogo nativo (automatizar diálogo de permissão é proibido nesta homologação) — coberto em
    // studio-pdd-013.test.js com a política de acesso REAL carregada com https://*/* e http://*/*.

    @Test
    @DisplayName("S013-06 revogar o consentimento com a captura ARMADA e no MEIO da captura: nada publicado; reautorizar volta a capturar")
    void revogarArmadaEEmCurso() throws Exception {
        Page p = sistema();
        assertTrue(grant(p, SITE, "SESSION").path("ok").asBoolean());
        String cid = "cap-" + UUID.randomUUID();
        assertTrue(arm(p, cid, V3, SITE).path("ok").asBoolean());
        assertTrue(revoke(SITE).at("/payload/revoked").asBoolean());
        JsonNode f = captured(cid);
        assertEquals("FAILED", f.path("status").asText());
        assertEquals("PDD_CONSENT_REVOKED", f.path("code").asText());
        assertEquals("VISUAL_CAPTURE_NOT_ALLOWED/PDD_CONSENT_REQUIRED", code(arm(p, "cap-" + UUID.randomUUID(), V3, SITE)));
        // no meio: a página ocupa a thread principal (o plano espera) e a revogação chega nesse intervalo
        assertTrue(grant(p, SITE, "SESSION").path("ok").asBoolean());
        String c2 = "cap-" + UUID.randomUUID();
        assertTrue(arm(p, c2, V3, SITE).path("ok").asBoolean());
        lab.focus(p);
        ((JavascriptExecutor) lab.driver()).executeScript("window.nkOcupar(6500, 1200)");
        gesture(p);
        Thread.sleep(1500);
        assertTrue(revoke(SITE).at("/payload/revoked").asBoolean());
        JsonNode f2 = captured(c2);
        assertEquals("FAILED", f2.path("status").asText(), f2.toString());
        assertEquals("PDD_CONSENT_REVOKED", f2.path("code").asText());
        assertTrue(grant(p, SITE, "SESSION").path("ok").asBoolean());
        assertSynthetic(shoot(p, SITE));
    }

    @Test
    @DisplayName("S013-07 acesso do Chrome revogado com o consentimento PDD presente: armação cai (PERMISSION_REVOKED) e o consentimento é apagado")
    void chromeRevogado() throws Exception {
        Page p = sistema();
        assertTrue(grant(p, SITE, "REMEMBERED").path("ok").asBoolean());
        String cid = "cap-" + UUID.randomUUID();
        assertTrue(arm(p, cid, V3, SITE).path("ok").asBoolean());
        lab.siteAccess("http://" + BrowserLab.SITE_A + "/*", false);
        try {
            JsonNode f = captured(cid);
            assertEquals("PERMISSION_REVOKED", f.path("code").asText(), f.toString());
            BrowserLab.waitUntil(Duration.ofSeconds(10), () -> studio.call("studio.pdd.consent.list", studio.obj()).at("/payload/consents").isEmpty(),
                    "consentimento apagado com a remoção do acesso do Chrome");
        } finally {
            lab.siteAccess("http://" + BrowserLab.SITE_A + "/*", true);
        }
        BrowserLab.waitUntil(Duration.ofSeconds(10), () -> "AUTHORIZED".equals(status(p).path("access").asText()), "acesso devolvido");
        assertEquals("PDD_CONSENT_REQUIRED", status(p).path("reason").asText(), "devolver o acesso do Chrome não devolve o consentimento PDD");
    }

    @Test
    @DisplayName("S013-08 redirecionamento de login (SSO) para outra origem com a captura armada: PAGE_CHANGED; a nova origem não herda nada")
    void redirecionamentoSso() throws Exception {
        Page p = site(SITE + SISTEMA + "?sso=2500");
        assertTrue(grant(p, SITE, "SESSION").path("ok").asBoolean());
        String cid = "cap-" + UUID.randomUUID();
        assertTrue(arm(p, cid, V3, SITE).path("ok").asBoolean());
        JsonNode f = captured(cid);
        assertEquals("PAGE_CHANGED", f.path("code").asText(), f.toString());
        BrowserLab.waitUntil(Duration.ofSeconds(10), () -> ("http://" + BrowserLab.SITE_B + ":" + BrowserLab.PORT).equals(status(p).path("origin").asText()),
                "aba na outra origem");
        assertEquals("PDD_CONSENT_REQUIRED", status(p).path("reason").asText());
        assertEquals("VISUAL_CAPTURE_NOT_ALLOWED/ORIGIN_CHANGED", code(arm(p, "cap-" + UUID.randomUUID(), V3, SITE)), "a origem mostrada não é mais a da aba");
    }

    @Test
    @DisplayName("S013-09 A→B→A e cancelamento DURANTE a captura no site: nada publicado")
    void trocaDeAbaECancelamento() throws Exception {
        Page p = sistema();
        Page b = lab.openTab(p, "/lab/studio010/consulta.html");
        opened.add(b);
        assertTrue(grant(p, SITE, "SESSION").path("ok").asBoolean());
        String cid = "cap-" + UUID.randomUUID();
        assertTrue(arm(p, cid, V3, SITE).path("ok").asBoolean());
        lab.focus(p);
        ((JavascriptExecutor) lab.driver()).executeScript("window.nkOcupar(6500, 1200)");
        gesture(p);
        Thread.sleep(1500);
        ctrlTab(2); // A→B→A com o plano esperando
        JsonNode f = captured(cid);
        assertEquals("ACTIVE_TAB_CHANGED", f.path("code").asText(), f.toString());
        String c2 = "cap-" + UUID.randomUUID();
        assertTrue(arm(p, c2, V3, SITE).path("ok").asBoolean());
        lab.focus(p);
        ((JavascriptExecutor) lab.driver()).executeScript("window.nkOcupar(6500, 1200)");
        gesture(p);
        Thread.sleep(1500);
        assertTrue(studio.call("studio.pdd.capture.cancel", studio.obj().put("captureId", c2)).at("/payload/cancelled").asBoolean());
        assertEquals("CANCELLED", captured(c2).path("code").asText());
    }

    @Test
    @DisplayName("S013-10 páginas internas e sem acesso: motivo do navegador; nenhum comando de screenshot bruto existe no Bridge")
    void internasEScreenshotBruto() {
        Page in = lab.openWithoutContent("chrome://version/");
        opened.add(in);
        JsonNode st = status(in);
        assertEquals("BROWSER_INTERNAL", st.path("reason").asText());
        assertTrue(st.path("origin").isNull());
        Page x = lab.openWithoutContent("http://" + BrowserLab.SITE_X + ":" + BrowserLab.PORT + SISTEMA);
        opened.add(x);
        assertEquals("VISUAL_CAPTURE_NOT_ALLOWED/PERMISSION_REQUIRED", code(arm(x, "cap-" + UUID.randomUUID(), V3, null)));
        int before = studio.contractViolations.size();
        JsonNode raw = studio.call("studio.pdd.capture.raw", studio.obj().put("tabRef", ref(x)));
        assertFalse(raw.path("ok").asBoolean(), "não há captura bruta: " + raw);
        studio.contractViolations.subList(before, studio.contractViolations.size()).clear(); // a recusa do host é a resposta esperada
    }

    @Test
    @DisplayName("S013-11 laboratório local continua sem consentimento, agora também na v3 (sem pixels)")
    void laboratorioV3() throws Exception {
        Page p = lab.open(SISTEMA);
        opened.add(p);
        assertEquals("NOT_REQUIRED", status(p).path("consent").asText());
        JsonNode d = shoot(p, "http://127.0.0.1:" + BrowserLab.PORT);
        assertSynthetic(d);
    }

    private static final String V4 = "nekkora-visual/4";

    private JsonNode shootV4(Page p, String origin) throws Exception {
        String cid = "cap-" + UUID.randomUUID();
        JsonNode armed = arm(p, cid, V4, origin);
        assertTrue(armed.path("ok").asBoolean(), armed.toString());
        assertEquals(V4, armed.at("/payload/policy").asText());
        gesture(p);
        return captured(cid);
    }

    /** Controle positivo: a tela BRUTA (só em memória, nunca gravada) tem a cor-sentinela. */
    private static int rawSentinel(Page p) throws IOException {
        lab.focus(p);
        java.awt.image.BufferedImage raw = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(
                ((org.openqa.selenium.TakesScreenshot) lab.driver()).getScreenshotAs(org.openqa.selenium.OutputType.BYTES)));
        return Studio010SafeCaptureIT.sentinelPixels(raw);
    }

    @Test
    @DisplayName("S013-14 v4 (print real, texto de dados mascarado) no site com o gesto real: NENHUM pixel da cor-sentinela; tela bruta tem (controle)")
    void printRealMascarado() throws Exception {
        Page p = sistema();
        assertTrue(grant(p, SITE, "SESSION").path("ok").asBoolean());
        int control = rawSentinel(p);
        assertTrue(control > 200, "controle positivo: a tela bruta tem dados na cor-sentinela (" + control + " px)");
        JsonNode d = shootV4(p, SITE);
        assertEquals("CAPTURED", d.path("status").asText(), d.toString().substring(0, Math.min(300, d.toString().length())));
        assertEquals(V4, d.path("policy").asText());
        java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(java.util.Base64.getDecoder().decode(d.path("png").asText())));
        int leak = Studio010SafeCaptureIT.sentinelPixels(img);
        assertEquals(0, leak, "pixels da cor-sentinela no print mascarado");
        String meta = d.deepCopy() instanceof ObjectNode o ? o.without(List.of("png", "sha256")).toString() : "";
        for (String s : SENTINELAS) assertFalse(meta.contains(s), "marcador nos metadados: " + s);
        assertTrue(count(d, "FIELD") >= 8, "campos: " + count(d, "FIELD"));
        assertTrue(count(d, "BLOCK") >= 6, "tabela, frame, canvas, vídeo, fundo, pseudo-elemento, botão fora do vocabulário: " + count(d, "BLOCK"));
        assertTrue(count(d, "TEXT") >= 5, "linhas de texto de dados: " + count(d, "TEXT"));
        assertTrue(d.path("limitations").toString().contains("TABLE_CONTENT_HIDDEN"));
        Files.write(lab.evidenceDir().resolve("S013-14-print-v4-mascarado.png"), java.util.Base64.getDecoder().decode(d.path("png").asText())); // JÁ mascarado
    }

    @Test
    @DisplayName("S013-15 v4 com menu aberto e DOM mudando: ou falha (página mudou) ou sai sem nenhum pixel da cor-sentinela; laboratório também em v4")
    void printRealMenuEDomVivo() throws Exception {
        Page p = site(SITE + SISTEMA + "?menu=1&mutar=1");
        assertTrue(grant(p, SITE, "SESSION").path("ok").asBoolean());
        JsonNode d = shootV4(p, SITE);
        if ("CAPTURED".equals(d.path("status").asText())) {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(java.util.Base64.getDecoder().decode(d.path("png").asText())));
            assertEquals(0, Studio010SafeCaptureIT.sentinelPixels(img));
        } else {
            assertEquals("PAGE_CHANGED_DURING_CAPTURE", d.path("code").asText(), d.toString());
        }
        Page lab0 = lab.open(SISTEMA);
        opened.add(lab0);
        JsonNode l = shootV4(lab0, "http://127.0.0.1:" + BrowserLab.PORT);
        assertEquals("CAPTURED", l.path("status").asText(), l.path("code").asText());
        java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(java.util.Base64.getDecoder().decode(l.path("png").asText())));
        assertEquals(0, Studio010SafeCaptureIT.sentinelPixels(img), "laboratório em v4");
    }

    @Test
    @DisplayName("S013-16 plano v4 no motor REAL do Chrome (sem gesto): toda linha de texto na cor-sentinela está DENTRO de uma máscara")
    void planoV4CobreTodoDado() throws Exception {
        Page p = site(SITE + SISTEMA + "?menu=1");
        lab.focus(p);
        StringBuilder src = new StringBuilder("window.NekkoraCS = window.NekkoraCS || {};\n");
        for (String f : List.of("shared/ui-vocabulary.js", "shared/capture-policy.js", "content/pdd-plan.js")) {
            src.append(Files.readString(lab.repo().resolve("chrome-extension/src/" + f))).append('\n');
        }
        // mundo da página: as shadow roots ABERTAS são vistas pelo plano e por esta conferência (a fechada fica para o IT com gesto)
        src.append("const vw = Math.floor(innerWidth), vh = Math.floor(innerHeight);"
                + " const plan = NekkoraCS.pddPlan.v4(NekkoraCapturePolicy, vw, vh, () => {});"
                + " const inside = (r) => plan.regions.some((m) => r.left >= m.x - 0.5 && r.top >= m.y - 0.5 && r.right <= m.x + m.width + 0.5 && r.bottom <= m.y + m.height + 0.5);"
                + " const roots = [document]; document.querySelectorAll('*').forEach((e) => { if (e.shadowRoot) roots.push(e.shadowRoot); });"
                + " const range = document.createRange(); const loose = []; let checked = 0;"
                + " for (const root of roots) { const w = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);"
                + "   for (let n = w.nextNode(); n; n = w.nextNode()) { const el = n.parentElement; if (!el || !n.nodeValue.trim()) continue;"
                + "     if (getComputedStyle(el).color !== 'rgb(230, 0, 126)' || !el.checkVisibility({ opacityProperty: true, visibilityProperty: true })) continue;"
                + "     range.selectNodeContents(n); for (const r of range.getClientRects()) { if (r.width <= 0 || r.bottom <= 0 || r.top >= vh) continue; checked++;"
                + "       if (!inside(r)) loose.push(n.nodeValue.trim().slice(0, 12).replace(/[A-Za-z]/g, 'x')); } } }"
                + " document.querySelectorAll('input, textarea, select, [contenteditable=\"true\"]').forEach((e) => { const r = e.getBoundingClientRect();"
                + "   if (r.width > 0 && r.bottom > 0 && r.top < vh && e.checkVisibility() && getComputedStyle(e).color === 'rgb(230, 0, 126)') { checked++; if (!inside(r)) loose.push('campo'); } });"
                + " const tables = [...document.querySelectorAll('table')].filter((t) => { const r = t.getBoundingClientRect(); return r.width > 0 && r.bottom > 0 && r.top < vh && t.checkVisibility(); })"
                + "   .map((t) => { const r = t.getBoundingClientRect(); return plan.regions.some((m) => m.kind === 'BLOCK' && Math.max(r.left, 0) >= m.x - 0.5 && Math.max(r.top, 0) >= m.y - 0.5"
                + "     && Math.min(r.right, vw) <= m.x + m.width + 0.5 && Math.min(r.bottom, vh) <= m.y + m.height + 0.5); });"
                + " return JSON.stringify({ checked, loose, tables, limitations: plan.limitations, incomplete: plan.incomplete, kinds: plan.regions.map((m) => m.kind) });");
        JsonNode r = M.readTree((String) ((JavascriptExecutor) lab.driver()).executeScript(src.toString()));
        assertFalse(r.path("incomplete").asBoolean());
        assertTrue(r.path("checked").asInt() >= 12, "linhas e campos de dados conferidos: " + r.path("checked"));
        assertEquals(0, r.path("loose").size(), "linhas de dados fora de máscara (forma, sem o texto): " + r.path("loose"));
        // a tabela é coberta INTEIRA por um BLOCK (estrutura, zebrado, bordas), não só o texto das células
        assertTrue(r.path("tables").size() >= 1, "a página adversarial mostra a tabela de pacientes");
        for (JsonNode t : r.path("tables")) assertTrue(t.asBoolean(), "tabela visível fora de um BLOCK: " + r.path("tables"));
        assertTrue(r.path("limitations").toString().contains("TABLE_CONTENT_HIDDEN"), "limitação da tabela: " + r.path("limitations"));
        String order = "BLOCK FIELD TEXT";
        int last = -1;
        for (JsonNode k : r.path("kinds")) {
            assertTrue(order.indexOf(k.asText()) >= last, "ordem de pintura");
            last = order.indexOf(k.asText());
        }
    }

    @Test
    @DisplayName("S013-13 plano v3 no motor REAL do Chrome (sem gesto): mesmo pdd-plan.js na página adversarial — nenhum marcador, só rótulos do vocabulário")
    void planoV3NoMotorReal() throws Exception {
        Page p = sistema();
        lab.focus(p);
        StringBuilder src = new StringBuilder("window.NekkoraCS = window.NekkoraCS || {};\n");
        for (String f : List.of("shared/ui-vocabulary.js", "shared/capture-policy.js", "content/pdd-plan.js")) {
            src.append(Files.readString(lab.repo().resolve("chrome-extension/src/" + f))).append('\n');
        }
        // mundo da PÁGINA (não o isolado da extensão): sem chrome.dom, só a shadow root aberta é percorrida
        src.append("const vw = Math.floor(innerWidth), vh = Math.floor(innerHeight);"
                + " return JSON.stringify({ viewport: { width: vw, height: vh }, plan: NekkoraCS.pddPlan.v3(NekkoraCapturePolicy, vw, vh, () => {}) });");
        JsonNode r = M.readTree((String) ((JavascriptExecutor) lab.driver()).executeScript(src.toString()));
        JsonNode plan = r.get("plan");
        ObjectNode payload = M.createObjectNode().put("status", "CAPTURED").put("policy", V3);
        payload.set("regions", plan.get("regions"));
        payload.set("limitations", plan.get("limitations"));
        assertSynthetic(payload);
        String order = "SURFACE MEDIA FRAME CONTROL FIELD TEXT LABEL";
        int last = -1;
        for (JsonNode x : plan.get("regions")) {
            int k = order.indexOf(x.path("kind").asText());
            assertTrue(k >= last, "ordem de pintura: " + x);
            last = k;
            for (String f : List.of("x", "y", "width", "height")) assertTrue(x.path(f).isInt(), "px CSS inteiros: " + x);
            assertTrue(x.path("x").asInt() + x.path("width").asInt() <= r.at("/viewport/width").asInt(), "dentro do viewport: " + x);
        }
        assertTrue(labels(payload).containsAll(Set.of("Usuário", "Senha", "Entrar", "Pesquisar", "Confirmar")), labels(payload).toString());
        assertTrue(count(payload, "FIELD") >= 8 && count(payload, "FRAME") >= 1 && count(payload, "MEDIA") >= 3, payload.toString());
        assertTrue(count(payload, "TEXT") >= 3, "texto que não é rótulo vira barra: " + count(payload, "TEXT"));
        evidence(payload, "S013-13-plano-v3-motor-real");
    }

    private static final String V5 = "nekkora-visual/5";

    /**
     * Sentinela das SENHAS na página adversarial (#00B000): no print integral nenhum pixel dela pode sobrar. Só o miolo
     * saturado conta: a franja verde clara do antisserrilhado subpixel de texto preto (ex.: #AAEFAA) não é senha.
     */
    static int secretPixels(java.awt.image.BufferedImage img) {
        int n = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int c = img.getRGB(x, y);
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                if (g > 120 && r < 100 && b < 100) n++;
            }
        }
        return n;
    }

    /** Onde estão (até 5) os pixels da sentinela de senha — para a mensagem de falha. */
    static String secretWhere(java.awt.image.BufferedImage img) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (int y = 0; y < img.getHeight() && n < 5; y++) {
            for (int x = 0; x < img.getWidth() && n < 5; x++) {
                int c = img.getRGB(x, y);
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                if (g > 120 && r < 100 && b < 100) {
                    sb.append(String.format("(%d,%d #%06X) ", x, y, c & 0xFFFFFF));
                    n++;
                }
            }
        }
        return sb.toString() + "em " + img.getWidth() + "x" + img.getHeight();
    }

    private JsonNode shootV5(Page p, String origin) throws Exception {
        String cid = "cap-" + UUID.randomUUID();
        JsonNode armed = arm(p, cid, V5, origin);
        assertTrue(armed.path("ok").asBoolean(), armed.toString());
        assertEquals(V5, armed.at("/payload/policy").asText());
        gesture(p);
        return captured(cid);
    }

    private static java.awt.image.BufferedImage png(JsonNode d) throws IOException {
        return javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(java.util.Base64.getDecoder().decode(d.path("png").asText())));
    }

    @Test
    @DisplayName("S013-17 v5 (print integral): recusada sem o nível FULL; com FULL e o gesto real, os DADOS aparecem e NENHUM pixel de senha (inclusive shadow fechada e \"mostrar senha\")")
    void printIntegral() throws Exception {
        Page p = sistema();
        assertTrue(grant(p, SITE, "SESSION").path("ok").asBoolean());
        assertEquals("MASKED", status(p).path("level").asText());
        assertEquals("VISUAL_CAPTURE_NOT_ALLOWED/PDD_FULL_REQUIRED", code(arm(p, "cap-" + UUID.randomUUID(), V5, SITE)), "o site está no print mascarado");
        JsonNode g = grant(p, SITE, "SESSION", "FULL");
        assertTrue(g.path("ok").asBoolean(), g.toString());
        assertEquals("FULL", g.at("/payload/level").asText());
        assertEquals("FULL", status(p).path("level").asText());
        lab.focus(p);
        java.awt.image.BufferedImage raw = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(
                ((org.openqa.selenium.TakesScreenshot) lab.driver()).getScreenshotAs(org.openqa.selenium.OutputType.BYTES)));
        int rawData = Studio010SafeCaptureIT.sentinelPixels(raw);
        assertTrue(secretPixels(raw) > 20, "controle positivo: a tela bruta mostra as senhas (" + secretPixels(raw) + " px)");
        JsonNode d = shootV5(p, SITE);
        assertEquals("CAPTURED", d.path("status").asText(), d.path("code").asText());
        assertEquals(V5, d.path("policy").asText());
        java.awt.image.BufferedImage img = png(d);
        assertEquals(0, secretPixels(img), "pixels da sentinela de senha no print integral: " + secretWhere(img) + " " + d.path("regions"));
        int data = Studio010SafeCaptureIT.sentinelPixels(img);
        assertTrue(data > rawData / 2, "o print integral mostra os dados da tela: " + data + " de " + rawData + " px");
        assertEquals(0, count(d, "TEXT"), "a v5 não cobre texto");
        assertTrue(count(d, "FIELD") >= 3, "senha, \"mostrar senha\" e a senha da shadow fechada: " + count(d, "FIELD"));
        String meta = d.deepCopy() instanceof ObjectNode o ? o.without(List.of("png", "sha256")).toString() : "";
        for (String x : SENTINELAS) assertFalse(meta.contains(x), "marcador nos metadados: " + x);
        // rebaixar para MASKED: a v5 deixa de armar, a v4 continua
        assertTrue(grant(p, SITE, "SESSION", "MASKED").path("ok").asBoolean());
        assertEquals("VISUAL_CAPTURE_NOT_ALLOWED/PDD_FULL_REQUIRED", code(arm(p, "cap-" + UUID.randomUUID(), V5, SITE)));
        Files.write(lab.evidenceDir().resolve("S013-17-print-v5-integral.png"), java.util.Base64.getDecoder().decode(d.path("png").asText())); // página SINTÉTICA
    }

    @Test
    @DisplayName("S013-18 v5 com senha DENTRO de iframe de outra origem: os frames saem cobertos (BLOCK + FRAMES_HIDDEN), nenhum pixel de senha")
    void printIntegralSenhaNoFrame() throws Exception {
        Page p = site(SITE + SISTEMA + "?frame=senha");
        assertTrue(grant(p, SITE, "SESSION", "FULL").path("ok").asBoolean());
        lab.focus(p);
        java.awt.image.BufferedImage raw = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(
                ((org.openqa.selenium.TakesScreenshot) lab.driver()).getScreenshotAs(org.openqa.selenium.OutputType.BYTES)));
        assertTrue(secretPixels(raw) > 20, "controle positivo");
        JsonNode d = shootV5(p, SITE);
        assertEquals("CAPTURED", d.path("status").asText(), d.path("code").asText());
        assertEquals(0, secretPixels(png(d)), "senha do iframe no print integral: " + secretWhere(png(d)) + " " + d.path("regions"));
        assertTrue(count(d, "BLOCK") >= 1, "o iframe coberto");
        assertTrue(d.path("limitations").toString().contains("FRAMES_HIDDEN"), d.path("limitations").toString());
    }

    @Test
    @DisplayName("S013-19 plano v5 no motor REAL do Chrome (sem gesto): só máscaras FIELD de senha (nenhuma de texto); todo campo de senha dentro delas, os demais campos fora")
    void planoV5SoSenhas() throws Exception {
        Page p = sistema();
        lab.focus(p);
        StringBuilder src = new StringBuilder("window.NekkoraCS = window.NekkoraCS || {};\n");
        for (String f : List.of("shared/ui-vocabulary.js", "shared/capture-policy.js", "content/pdd-plan.js")) {
            src.append(Files.readString(lab.repo().resolve("chrome-extension/src/" + f))).append('\n');
        }
        src.append("const vw = Math.floor(innerWidth), vh = Math.floor(innerHeight);"
                + " const plan = NekkoraCS.pddPlan.v5(vw, vh, false);"
                + " const inside = (r) => plan.regions.some((m) => m.kind === 'FIELD' && Math.max(r.left, 0) >= m.x - 0.5 && Math.max(r.top, 0) >= m.y - 0.5"
                + "   && Math.min(r.right, vw) <= m.x + m.width + 0.5 && Math.min(r.bottom, vh) <= m.y + m.height + 0.5);"
                + " const vis = (e) => { const r = e.getBoundingClientRect(); return r.width > 0 && r.bottom > 0 && r.top < vh; };"
                + " const all = [...document.querySelectorAll('input')]; document.querySelectorAll('*').forEach((e) => { if (e.shadowRoot) all.push(...e.shadowRoot.querySelectorAll('input')); });"
                + " const secret = all.filter((e) => vis(e) && (e.type === 'password' || e.id === 'senha-visivel'));"
                + " const common = all.filter((e) => vis(e) && ['usuario', 'cpf', 'sombra-campo'].includes(e.id));"
                + " return JSON.stringify({ kinds: [...new Set(plan.regions.map((m) => m.kind))], incomplete: plan.incomplete, secrets: secret.length,"
                + "   secretOut: secret.filter((e) => !inside(e.getBoundingClientRect())).map((e) => e.id || e.type),"
                + "   commonIn: common.filter((e) => inside(e.getBoundingClientRect())).map((e) => e.id), count: NekkoraCS.pddPlan.secrets() });");
        JsonNode r = M.readTree((String) ((JavascriptExecutor) lab.driver()).executeScript(src.toString()));
        assertFalse(r.path("incomplete").asBoolean());
        assertEquals("[\"FIELD\"]", r.path("kinds").toString(), "só máscaras de senha");
        assertTrue(r.path("secrets").asInt() >= 2, "senha e \"mostrar senha\" visíveis: " + r);
        assertEquals(0, r.path("secretOut").size(), "campo de senha fora de máscara: " + r.path("secretOut"));
        assertEquals(0, r.path("commonIn").size(), "campo comum coberto (o print integral mostra os campos): " + r.path("commonIn"));
        assertTrue(r.path("count").asInt() >= r.path("secrets").asInt(), "contagem por frame (cs.pdd.secrets): " + r);
    }

    @Test
    @DisplayName("S013-20 print integral pelo PAINEL: \"Print integral\" só vale depois da confirmação no próprio painel; Cancelar não concede; voltar ao mascarado sem confirmação")
    void printIntegralPeloPainel() throws Exception {
        Page p = sistema();
        String panel = lab.openExtensionPage("src/ui/panel.html?tabId=" + p.tabId());
        try {
            JavascriptExecutor js = (JavascriptExecutor) lab.driver();
            // status() passa pela página do harness: volta ao painel depois de cada consulta
            java.util.function.Supplier<JsonNode> st = () -> {
                JsonNode r = status(p);
                lab.driver().switchTo().window(panel);
                return r;
            };
            BrowserLab.waitUntil(Duration.ofSeconds(10), () -> Boolean.TRUE.equals(js.executeScript("return !document.getElementById('pdd-level-box').hidden")),
                    "escolha da imagem do PDD no painel");
            assertEquals(Boolean.TRUE, js.executeScript("return document.querySelector('input[name=\"pdd-level\"][value=\"MASKED\"]').checked"), "mascarado é o padrão");
            lab.driver().findElement(By.cssSelector("input[name='pdd-level'][value='FULL']")).click();
            lab.driver().findElement(By.id("btn-pdd-grant")).click();
            BrowserLab.waitUntil(Duration.ofSeconds(5), () -> Boolean.TRUE.equals(js.executeScript("return !document.getElementById('pdd-confirm-full').hidden")),
                    "confirmação do print integral no painel");
            assertEquals("NONE", st.get().path("consent").asText(), "antes de confirmar, nada foi concedido");
            lab.screenshotWindow(panel, "S013-20-painel-confirmar-print-integral");
            lab.driver().findElement(By.id("btn-pdd-cancel-full")).click();
            assertEquals("NONE", st.get().path("consent").asText(), "Cancelar não concede");
            lab.driver().findElement(By.cssSelector("input[name='pdd-level'][value='FULL']")).click();
            lab.driver().findElement(By.id("btn-pdd-grant")).click();
            BrowserLab.waitUntil(Duration.ofSeconds(5), () -> Boolean.TRUE.equals(js.executeScript("return !document.getElementById('pdd-confirm-full').hidden")),
                    "confirmação de novo");
            lab.driver().findElement(By.id("btn-pdd-confirm-full")).click();
            BrowserLab.waitUntil(Duration.ofSeconds(10), () -> "FULL".equals(st.get().path("level").asText()), "nível FULL concedido pelo painel");
            JsonNode list = studio.call("studio.pdd.consent.list", studio.obj()).at("/payload/consents");
            assertEquals("FULL", list.get(0).path("level").asText());
            assertEquals("EXTENSION", list.get(0).path("via").asText());
            assertTrue(String.valueOf(js.executeScript("return document.getElementById('pdd-state').textContent")).contains("print integral"));
            lab.driver().findElement(By.id("btn-pdd-level")).click(); // "Voltar ao print mascarado neste site"
            BrowserLab.waitUntil(Duration.ofSeconds(10), () -> "MASKED".equals(st.get().path("level").asText()), "de volta ao mascarado, sem confirmação");
            assertEquals(Boolean.TRUE, js.executeScript("return document.getElementById('pdd-confirm-full').hidden"));
        } finally {
            lab.closeWindow(panel);
            revoke(SITE);
        }
    }

    @Test
    @DisplayName("S013-12 consentimento pelo PAINEL da extensão (via EXTENSION, DEVELOPER), visto pelo Studio; revogação pelo painel")
    void consentimentoPeloPainel() throws Exception {
        Page p = sistema();
        String panel = lab.openExtensionPage("src/ui/panel.html?tabId=" + p.tabId());
        try {
            JavascriptExecutor js = (JavascriptExecutor) lab.driver();
            BrowserLab.waitUntil(Duration.ofSeconds(10), () -> Boolean.TRUE.equals(js.executeScript("return !document.getElementById('btn-pdd-grant').hidden")),
                    "botão de autorizar captura PDD");
            assertTrue(String.valueOf(js.executeScript("return document.getElementById('pdd-origin').textContent")).equals(SITE));
            lab.driver().findElement(By.id("btn-pdd-grant")).click();
            BrowserLab.waitUntil(Duration.ofSeconds(10), () -> "SESSION".equals(js.executeScript("return document.getElementById('pdd-state').dataset.consent")),
                    "painel mostra a autorização");
            JsonNode list = studio.call("studio.pdd.consent.list", studio.obj()).at("/payload/consents");
            assertEquals(1, list.size());
            assertEquals("EXTENSION", list.get(0).path("via").asText());
            assertEquals("DEVELOPER", list.get(0).path("actor").asText());
            lab.screenshotWindow(panel, "S013-12-painel-consentimento");
            lab.driver().findElement(By.id("btn-pdd-revoke")).click();
            BrowserLab.waitUntil(Duration.ofSeconds(10), () -> "NONE".equals(js.executeScript("return document.getElementById('pdd-state').dataset.consent")),
                    "painel mostra a revogação");
        } finally {
            lab.closeWindow(panel);
        }
        assertEquals("PDD_CONSENT_REQUIRED", status(p).path("reason").asText());
    }
}
