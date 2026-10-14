package com.nekkora.extension.it.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.nekkora.extension.it.support.BrowserLab.Page;
import org.junit.jupiter.api.AfterEach;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WindowType;

import java.time.Duration;
import java.util.Map;

/** Base dos testes CHROME-002: ciclo automático de captura, inspeção de botão único e gravação simulada. */
public abstract class SessionIT extends LabIT {

    @AfterEach
    void resetMode() {
        // Nenhum teste deixa o modo PRD simulado ligado para os seguintes.
        lab.callRaw("config.setMode", Map.of("mode", "DEV"));
    }

    protected JsonNode inspection(Page p) {
        return lab.call("inspection.state", p);
    }

    protected JsonNode toggle(Page p) {
        return lab.call("inspection.toggle", p);
    }

    protected JsonNode activate(Page p) {
        JsonNode r = lab.call("inspection.activate", p);
        waitSessionActive(p);
        return r;
    }

    protected JsonNode deactivate(Page p) {
        return lab.call("inspection.deactivate", p);
    }

    /** Sessão de captura corrente da aba (ou nó ausente). */
    protected JsonNode session(Page p) {
        return lab.call("session.status", p).path("session");
    }

    protected JsonNode lastEnded(Page p) {
        return lab.call("session.status", p).path("lastEnded");
    }

    protected void waitSessionActive(Page p) {
        BrowserLab.waitUntil(Duration.ofSeconds(30), () -> "ACTIVE".equals(session(p).path("status").asText()), "sessão ACTIVE");
    }

    /**
     * Executa o manipulador de um botão da página SEM evento de clique: com a inspeção ativa os cliques são
     * seleções (nenhuma ação da página é executada), então as mutações do cenário são acionadas assim.
     */
    protected void act(Page p, String buttonId) {
        lab.js(p, "document.getElementById(arguments[0]).onclick()", buttonId);
    }

    protected JsonNode sync(Page p) {
        return lab.call("session.synchronize", p);
    }

    protected JsonNode diagnostics(Page p) {
        return lab.call("page.diagnostics", p);
    }

    protected JsonNode recording(String type, String recordingId, Page p) {
        Map<String, Object> event = new java.util.LinkedHashMap<>();
        event.put("type", "recording.session." + type);
        event.put("sessionId", recordingId);
        event.put("tabId", p.tabId());
        if ("finished".equals(type)) {
            event.put("reason", "USER_FINISHED");
        }
        return lab.call("recording.event", Map.of("event", event));
    }

    /** Abre uma URL que NÃO recebe content script (origem sem permissão) e devolve a aba. */
    protected Page openWithoutContentScript(String url) {
        WebDriver d = lab.driver();
        d.switchTo().newWindow(WindowType.WINDOW);
        d.get(url);
        String handle = d.getWindowHandle();
        String current = d.getCurrentUrl();
        Object tab = harnessScript("const done = arguments[arguments.length - 1]; window.nekkora.newestTabId().then(done);");
        return new Page(handle, ((Number) tab).intValue(), current);
    }

    protected Object harnessScript(String asyncScript, Object... args) {
        WebDriver d = lab.driver();
        lab.call("host.status", Map.of()); // posiciona no harness
        return ((JavascriptExecutor) d).executeAsyncScript(asyncScript, args);
    }

    protected static String text(WebDriver d, String id) {
        return d.findElement(By.id(id)).getText();
    }

    protected static void waitText(WebDriver d, String id, String expected) {
        BrowserLab.waitUntil(Duration.ofSeconds(20), () -> text(d, id).contains(expected), "#" + id + " contém '" + expected + "'");
    }

    protected static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
