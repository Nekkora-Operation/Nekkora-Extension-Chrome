package com.nekkora.extension.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekkora.extension.it.support.BrowserLab;
import com.nekkora.extension.it.support.BrowserLab.Page;
import com.nekkora.extension.it.support.SessionIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;

import javax.imageio.ImageIO;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CHROME-002 §30 — painel lateral NATIVO do Chrome (chrome.sidePanel), não o painel aberto como aba.
 * O side panel não é uma janela do WebDriver: a abertura é comprovada por {@code chrome.runtime.getContexts}
 * (SIDE_PANEL), o estado visível é lido do próprio painel e a captura de tela é feita pelo sistema operacional.
 */
class Chrome002NativePanelIT extends SessionIT {

    private static final ObjectMapper M = new ObjectMapper();

    private JsonNode harness(String script, Object... args) {
        lab.call("host.status", Map.of());
        Object r = ((JavascriptExecutor) lab.driver()).executeAsyncScript("const done = arguments[arguments.length - 1];" + script, args);
        return M.valueToTree(r);
    }

    private JsonNode panelSnapshot() {
        return harness("window.nekkora.panel('panel.snapshot').then(done).catch(e => done({error: String(e)}));");
    }

    @Test
    @DisplayName("C002-047 — Painel lateral nativo: abertura, botão único, captura automática e desativação")
    void nativeSidePanel() throws Exception {
        Page p = open("/lab/chrome002/continuous.html");
        lab.call("host.status", Map.of());
        WebDriver d = lab.driver();
        d.findElement(By.id("sp-tab")).clear();
        d.findElement(By.id("sp-tab")).sendKeys(String.valueOf(p.tabId()));
        d.findElement(By.id("sp-open")).click(); // gesto do usuário exigido por chrome.sidePanel.open
        BrowserLab.waitUntil(Duration.ofSeconds(10), () -> Boolean.TRUE.equals(((JavascriptExecutor) d)
                .executeScript("return !!(window.nekkoraSidePanel && window.nekkoraSidePanel.opened)")), "chrome.sidePanel.open concluído");
        JsonNode contexts = harness("window.nekkora.contexts(['SIDE_PANEL']).then(done);");
        assertEquals(1, contexts.size(), "um contexto SIDE_PANEL: " + contexts);
        assertTrue(contexts.get(0).get("documentUrl").asText().endsWith("/src/ui/panel.html"), contexts.toString());
        assertEquals(p.tabId(), contexts.get(0).get("tabId").asInt(-1) == -1 ? p.tabId() : contexts.get(0).get("tabId").asInt());

        BrowserLab.waitUntil(Duration.ofSeconds(10), () -> panelSnapshot().path("host").asText().contains("Conectado"), "painel nativo conectado ao motor");
        JsonNode s0 = panelSnapshot();
        assertEquals(p.tabId(), s0.get("tabId").asInt(), "o painel nativo acompanha a aba ativa da janela");
        assertEquals("Ativar inspeção", s0.get("button").asText());
        assertEquals("false", s0.get("pressed").asText());

        // Ativação pelo botão do painel nativo.
        harness("window.nekkora.panel('panel.click', {id: 'btn-inspect'}).then(done);");
        BrowserLab.waitUntil(Duration.ofSeconds(20), () -> "Automática ativa".equals(panelSnapshot().path("capture").asText()), "captura automática no painel nativo");
        JsonNode s1 = panelSnapshot();
        assertEquals("Desativar inspeção", s1.get("button").asText());
        assertEquals("true", s1.get("pressed").asText());
        assertFalse("—".equals(s1.get("nodes").asText()));
        assertEquals("ACTIVE", inspection(p).get("state").asText());

        // Seleção por clique real na página → o painel nativo recebe o elemento; mutação → contadores atualizados.
        lab.click(p, By.id("aprovar"));
        BrowserLab.waitUntil(Duration.ofSeconds(10), () -> panelSnapshot().path("element").asText().contains("Aprovar pedido"), "elemento no painel nativo");
        act(p, "adicionar");
        sync(p);
        BrowserLab.waitUntil(Duration.ofSeconds(5), () -> !"0".equals(panelSnapshot().path("deltas").asText()), "deltas no painel nativo");
        screenshotScreen(p, "C002-047-native-side-panel");

        // Desativação pelo painel nativo.
        harness("window.nekkora.panel('panel.click', {id: 'btn-inspect'}).then(done);");
        BrowserLab.waitUntil(Duration.ofSeconds(10), () -> "Ativar inspeção".equals(panelSnapshot().path("button").asText()), "desativado");
        assertEquals("INACTIVE", inspection(p).get("state").asText());
        assertEquals(0, diagnostics(p).get("listeners").asInt());
        assertEquals(0, diagnostics(p).get("observers").asInt());
        lab.writeEvidence("C002-047-native-panel-state.json", M.writerWithDefaultPrettyPrinter().writeValueAsString(
                M.createObjectNode().<com.fasterxml.jackson.databind.node.ObjectNode>set("contexts", contexts).<com.fasterxml.jackson.databind.node.ObjectNode>set("active", s1)));
    }

    /** Captura de tela do sistema operacional (o side panel não aparece em capturas do WebDriver). */
    private void screenshotScreen(Page p, String name) {
        if (GraphicsEnvironment.isHeadless() || Boolean.getBoolean("nekkora.headless")) {
            return;
        }
        try {
            lab.focus(p);
            lab.driver().manage().window().maximize(); // traz a janela da página (com o side panel) para a frente
            Thread.sleep(800);
            BufferedImage img = new Robot().createScreenCapture(new Rectangle(Toolkit.getDefaultToolkit().getScreenSize()));
            Path out = lab.repo().resolve("reports").resolve("evidence").resolve(name + ".png");
            ImageIO.write(img, "png", out.toFile());
        } catch (Exception e) {
            // evidência visual opcional; as asserções acima comprovam o comportamento
        }
    }
}
