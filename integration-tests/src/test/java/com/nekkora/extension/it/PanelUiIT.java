package com.nekkora.extension.it;

import com.nekkora.extension.it.support.BrowserLab;
import com.nekkora.extension.it.support.BrowserLab.Page;
import com.nekkora.extension.it.support.LabIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** §26 — interface mínima: cada ação do painel exercitada pela interface real. */
class PanelUiIT extends LabIT {

    private static String text(WebDriver d, String id) {
        return d.findElement(By.id(id)).getText();
    }

    private static void waitText(WebDriver d, String id, String expected) {
        BrowserLab.waitUntil(Duration.ofSeconds(15), () -> text(d, id).contains(expected), "#" + id + " contém '" + expected + "'");
    }

    @Test
    @DisplayName("C-9 — Painel: inspecionar, família, localizadores, reencontro, comparação, exportação e desativação")
    void panelActions() throws Exception {
        // CHROME-002: "Ativar/Desativar inspeção" é um botão único; "Capturar página" virou "Forçar captura
        // completa" (área DEV) e "Acompanhar mutações" deixou de existir (captura contínua automática).
        Page p = open("/lab/simple/form-basics.html");
        String panel = lab.openExtensionPage("src/ui/panel.html?tabId=" + p.tabId());
        WebDriver d = lab.driver();
        try {
            waitText(d, "host-status", "Java 21");

            d.findElement(By.id("btn-capture")).click();
            waitText(d, "message", "Captura completa");

            d.findElement(By.id("btn-inspect")).click();
            waitText(d, "btn-inspect", "Desativar inspeção");
            waitText(d, "st-capture", "Automática ativa");

            // Seleção por clique real na página (o painel é atualizado pelo service worker).
            lab.click(p, By.id("customer-name"));
            d.switchTo().window(panel);
            waitText(d, "element-info", "TEXT_INPUT");
            assertTrue(text(d, "element-info").contains("Nome"));
            waitText(d, "family-info", "customer-form");
            BrowserLab.waitUntil(Duration.ofSeconds(5), () -> d.findElements(By.cssSelector("#locators tbody tr")).size() >= 3, "localizadores listados");
            assertTrue(text(d, "locators").contains("input#customer-name"));
            lab.screenshotWindow(panel, "C-9-panel-inspection");
            lab.screenshot(p, "C-9-page-selection");

            d.switchTo().window(panel);
            d.findElement(By.id("btn-resolve")).click();
            waitText(d, "resolution", "RESOLVED");

            d.findElement(By.id("btn-compare")).click();
            waitText(d, "resolution", "relevante");

            d.findElement(By.id("btn-export")).click();
            waitText(d, "message", "PageMap exportado");

            d.findElement(By.id("btn-inspect")).click();
            waitText(d, "btn-inspect", "Ativar inspeção");
            assertTrue(lab.call("page.diagnostics", p).at("/listeners").asInt() == 0);
            assertTrue(lab.call("page.diagnostics", p).at("/observers").asInt() == 0);
        } finally {
            lab.closeWindow(panel);
        }
    }
}
