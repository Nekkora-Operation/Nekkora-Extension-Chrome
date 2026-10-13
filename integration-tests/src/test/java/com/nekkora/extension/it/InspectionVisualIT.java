package com.nekkora.extension.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.nekkora.extension.it.support.BrowserLab;
import com.nekkora.extension.it.support.BrowserLab.Page;
import com.nekkora.extension.it.support.LabIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.interactions.Actions;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fase 09 — destaque rosa, modos de inspeção e desativação. */
class InspectionVisualIT extends LabIT {

    private static final String NEKKORA_PINK = "rgb(194, 70, 109)"; // #C2466D
    private static final double PAD = 4; // outline 2px + offset 2px

    /** Deixa o laço requestAnimationFrame da aba rodar e devolve o estado do destaque. */
    private JsonNode settledHighlight(Page p) throws InterruptedException {
        lab.focus(p);
        Thread.sleep(350);
        return lab.call("page.diagnostics", p).get("highlight");
    }

    private static void assertBoxMatches(JsonNode box, String what) {
        assertTrue(box.get("visible").asBoolean(), what + ": caixa visível");
        JsonNode r = box.get("rect");
        JsonNode t = box.get("targetRect");
        assertEquals(t.get("left").asDouble() - PAD, r.get("left").asDouble(), 1.0, what + ": left");
        assertEquals(t.get("top").asDouble() - PAD, r.get("top").asDouble(), 1.0, what + ": top");
        assertEquals(t.get("width").asDouble() + 2 * PAD, r.get("width").asDouble(), 1.0, what + ": width");
        assertEquals(t.get("height").asDouble() + 2 * PAD, r.get("height").asDouble(), 1.0, what + ": height");
    }

    @Test
    @DisplayName("T034 — Destaque rosa: contorno visual correto")
    void pinkHighlight() throws Exception {
        Page p = open("/lab/complex/responsive.html");
        String layoutScript = "const el=document.getElementById('alvo-movel'); const r=el.getBoundingClientRect();"
                + " return [r.x, r.y, r.width, r.height, el.getAttribute('style'), getComputedStyle(el).outlineStyle, document.documentElement.scrollHeight].join('|');";
        Object layoutBefore = lab.js(p, layoutScript);

        JsonNode s = select(p, "#alvo-movel");
        JsonNode h = settledHighlight(p);
        JsonNode box = h.at("/boxes/selection");
        assertTrue(h.get("overlayPresent").asBoolean());
        assertEquals(NEKKORA_PINK, box.get("borderColor").asText(), "rosa oficial da Nekkora");
        assertEquals("2px", box.get("borderWidth").asText());
        assertBoxMatches(box, "posição inicial");
        assertEquals(layoutBefore, lab.js(p, layoutScript), "o destaque não altera layout nem estilo do elemento");
        Path shot = lab.screenshot(p, "T034-highlight-selection");
        zoom(shot, box.get("rect"), "T034-highlight-zoom");

        // Scroll
        lab.js(p, "window.scrollTo(0, 60)");
        assertBoxMatches(settledHighlight(p).at("/boxes/selection"), "após scroll");
        // Reposicionamento e mudança de tamanho (cliques reais; inspeção não está ativa)
        lab.click(p, By.id("mover"));
        assertBoxMatches(settledHighlight(p).at("/boxes/selection"), "após mover");
        lab.click(p, By.id("redimensionar"));
        assertBoxMatches(settledHighlight(p).at("/boxes/selection"), "após redimensionar");
        // Alteração responsiva da janela
        Dimension original = lab.driver().manage().window().getSize();
        try {
            lab.driver().manage().window().setSize(new Dimension(900, 760));
            assertBoxMatches(settledHighlight(p).at("/boxes/selection"), "após redimensionar a janela");
        } finally {
            lab.driver().manage().window().setSize(original);
        }
        // Re-renderização: o nó destacado é destruído; o alvo é reencontrado e o destaque acompanha.
        String before = s.at("/inspection/nodeId").asText();
        lab.clearEvents();
        lab.click(p, By.id("rerender-alvo"));
        BrowserLab.waitUntil(Duration.ofSeconds(8), () -> {
            for (JsonNode e : lab.events("resolution")) {
                if ("RESOLVED".equals(e.at("/data/result/status").asText())) {
                    return true;
                }
            }
            return false;
        }, "reencontro automático após re-renderização");
        JsonNode after = settledHighlight(p).at("/boxes/selection");
        assertNotEquals(before, after.get("nodeId").asText(), "destaque migrou para o novo nó");
        assertEquals(nodeIdOf(p, "#alvo-movel"), after.get("nodeId").asText());
        assertBoxMatches(after, "após re-renderização");
        zoom(lab.screenshot(p, "T034-highlight-after-rerender"), after.get("rect"), "T034-highlight-after-rerender-zoom");

        // Modo inspeção: contorno ao passar o mouse.
        lab.call("page.inspect.start", p);
        lab.focus(p);
        new Actions(lab.driver()).moveToElement(lab.driver().findElement(By.id("rodape"))).perform();
        JsonNode hover = settledHighlight(p).at("/boxes/hover");
        assertTrue(hover.get("visible").asBoolean());
        assertEquals(NEKKORA_PINK, hover.get("borderColor").asText());
        lab.screenshot(p, "T034-hover-inspection");
        lab.call("page.disable", p);
    }

    @Test
    @DisplayName("T035 — Desativação da inspeção: recursos removidos")
    void disableRemovesEverything() throws Exception {
        Page p = open("/lab/complex/responsive.html");
        lab.call("page.inspect.start", p);
        lab.call("tracking.start", p);
        select(p, "#alvo-movel");
        lab.focus(p);
        new Actions(lab.driver()).moveToElement(lab.driver().findElement(By.id("mover"))).perform();
        JsonNode active = lab.call("page.diagnostics", p);
        assertTrue(active.get("listeners").asInt() > 0);
        assertTrue(active.get("observers").asInt() > 0);

        JsonNode off = lab.call("page.disable", p);
        assertEquals("OFF", off.get("inspectorMode").asText());
        assertEquals(0, off.get("listeners").asInt());
        assertEquals(0, off.get("observers").asInt());
        assertFalse(off.at("/highlight/overlayPresent").asBoolean());
        assertFalse(off.at("/highlight/loopActive").asBoolean());
        assertEquals(Boolean.TRUE, lab.js(p, "return document.querySelector('nekkora-overlay') === null"));
        // Sem efeitos residuais: mouse não destaca e o clique volta a executar a ação normal.
        new Actions(lab.driver()).moveToElement(lab.driver().findElement(By.id("redimensionar"))).perform();
        Thread.sleep(200);
        assertFalse(lab.call("page.diagnostics", p).at("/highlight/overlayPresent").asBoolean());
        lab.click(p, By.id("mover"));
        assertEquals("120px", lab.js(p, "return document.getElementById('alvo-movel').style.marginLeft"));
    }

    @Test
    @DisplayName("T039 — Seleção em modo inspector: nenhuma ação acidental")
    void noAccidentalActions() throws Exception {
        Page p = open("/lab/inspector/actions.html");
        String url = p.url();
        lab.call("page.inspect.start", p);
        lab.clearEvents();
        for (String id : List.of("incrementar", "excluir-registro", "sair", "enviar")) {
            lab.click(p, By.id(id));
        }
        BrowserLab.waitUntil(Duration.ofSeconds(10), () -> lab.events("inspection").size() >= 4, "4 seleções registradas");
        lab.focus(p);
        assertEquals(url, lab.driver().getCurrentUrl(), "link e envio de formulário não navegaram");
        assertEquals("0", lab.js(p, "return document.getElementById('contador').textContent"));
        assertEquals(Boolean.TRUE, lab.js(p, "return !!document.querySelector('#registros tr[data-id=\"1\"]')"));
        assertEquals(0L, lab.js(p, "return window.NekkoraLab.actions.length"), "nenhum manipulador da página executado");
        JsonNode last = lab.events("inspection");
        assertEquals("BUTTON", last.get(last.size() - 1).at("/data/inspection/classification/kind").asText());
        lab.screenshot(p, "T039-selection-without-action");
        // Fora do modo inspector, o comportamento normal é restaurado.
        lab.call("page.disable", p);
        lab.click(p, By.id("incrementar"));
        assertEquals("1", lab.js(p, "return document.getElementById('contador').textContent"));
    }

    /** Recorte ampliado ao redor do destaque, para evidência visual legível. */
    private static void zoom(Path screenshot, JsonNode rect, String name) throws Exception {
        BufferedImage img = ImageIO.read(screenshot.toFile());
        double dpr = img.getWidth() / ((Number) ((org.openqa.selenium.JavascriptExecutor) lab.driver()).executeScript("return window.innerWidth")).doubleValue();
        int margin = (int) (40 * dpr);
        int x = Math.max(0, (int) (rect.get("left").asDouble() * dpr) - margin);
        int y = Math.max(0, (int) (rect.get("top").asDouble() * dpr) - margin);
        int w = Math.min(img.getWidth() - x, (int) (rect.get("width").asDouble() * dpr) + 2 * margin);
        int hgt = Math.min(img.getHeight() - y, (int) (rect.get("height").asDouble() * dpr) + 2 * margin);
        BufferedImage crop = img.getSubimage(x, y, w, hgt);
        BufferedImage big = new BufferedImage(w * 3, hgt * 3, BufferedImage.TYPE_INT_RGB);
        big.getGraphics().drawImage(crop.getScaledInstance(w * 3, hgt * 3, java.awt.Image.SCALE_REPLICATE), 0, 0, null);
        Path out = screenshot.resolveSibling(name + ".png");
        Files.createDirectories(out.getParent());
        ImageIO.write(big, "png", out.toFile());
    }
}
