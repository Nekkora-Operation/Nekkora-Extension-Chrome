package com.nekkora.extension.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nekkora.extension.it.support.BrowserLab;
import com.nekkora.extension.it.support.BrowserLab.Page;
import com.nekkora.extension.it.support.SessionIT;
import com.nekkora.extension.it.support.StudioBridgeLab;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.interactions.Actions;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NEKKORA-E2E-002 no Chrome real: "Selecionar dado" (SELECT_DATA) pela cadeia service worker ⇄ Native Host ⇄ Bridge.
 * Clicar no título, num LINK dentro da célula de data e num ITEM de lista dentro da célula de ocupação propõe leituras
 * (EXTRACT) dos contêineres de dado — título e CÉLULAS inteiras, ancoradas pelo cabeçalho da linha —, nada é executado
 * na página (o link não navega) e nenhum texto da página sai no descritor.
 */
class E2e002SelectDataIT extends SessionIT {

    private static final String FICHA = "/lab/e2e002/ficha.html";
    private static StudioBridgeLab studio;

    @BeforeAll
    static void bridge() throws Exception {
        lab = BrowserLab.get();
        studio = new StudioBridgeLab(lab.bridgeDir());
        lab.call("studio.link.set", Map.of("enabled", true));
        studio.awaitConnected(Duration.ofSeconds(30));
    }

    @AfterAll
    static void unbridge() throws IOException {
        try {
            lab.call("studio.link.set", Map.of("enabled", false));
        } finally {
            studio.close();
        }
        assertTrue(studio.contractViolations.isEmpty(), "mensagens fora do contrato: " + studio.contractViolations);
    }

    private static String id(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private String refOf(Page p) {
        String ref = lab.call("studio.tabRef", p).path("tabRef").asText();
        for (JsonNode t : studio.call("browser.tabs.list", null).at("/payload/tabs")) {
            if (ref.equals(t.path("tabRef").asText())) {
                return ref;
            }
        }
        throw new AssertionError("aba não listada: " + p.url());
    }

    private List<JsonNode> steps(String rid) {
        TreeMap<Integer, JsonNode> bySeq = new TreeMap<>();
        for (JsonNode e : studio.allEvents) {
            if ("studio.recording.step".equals(e.path("type").asText()) && rid.equals(e.at("/payload/recordingId").asText())) {
                bySeq.putIfAbsent(e.at("/payload/sequence").asInt(), e.get("payload"));
            }
        }
        return new ArrayList<>(bySeq.values());
    }

    private static JsonNode preferred(JsonNode s) {
        return s.at("/element/locators/" + s.at("/element/preferredLocator").asInt(-1));
    }

    @Test
    @DisplayName("E2E-002 — Selecionar dado: título, célula com link e célula com lista em linha viram leituras das CÉLULAS pelo rótulo da linha")
    void selecionarDado() throws Exception {
        Page p = open(FICHA);
        String rid = id("rec");
        ObjectNode pl = studio.obj().put("recordingId", rid).put("operationId", id("op")).put("tabRef", refOf(p)).put("mode", "SELECT_DATA")
                .put("leaseMs", 120000).put("routeRef", "route-e2e002");
        pl.putArray("capabilities").add("steps/1");
        JsonNode r = studio.call("studio.recording.start", pl);
        assertTrue(r.path("ok").asBoolean(), r.toString());
        BrowserLab.waitUntil(Duration.ofSeconds(15), () -> lab.call("page.diagnostics", p).at("/recorder/active").asBoolean(false), "sensor ligado");
        WebDriver d = lab.driver();
        lab.focus(p);
        new Actions(d).moveToElement(d.findElement(By.id("titulo"))).click().perform();
        lab.focus(p);
        new Actions(d).moveToElement(d.findElement(By.linkText("1901"))).click().perform();      // link DENTRO da célula de data
        lab.focus(p);
        new Actions(d).moveToElement(d.findElement(By.xpath("//li[normalize-space(.)='relojoeira']"))).click().perform(); // item de lista
        BrowserLab.waitUntil(Duration.ofSeconds(30), () -> steps(rid).size() >= 3, "3 leituras propostas; até agora " + steps(rid).size());
        Thread.sleep(800);
        List<JsonNode> s = steps(rid);
        assertFalse(String.valueOf(lab.js(p, "return location.hash")).contains("ano"), "Selecionar dado não executa: o link não navegou");
        assertEquals(List.of("EXTRACT", "EXTRACT", "EXTRACT"), s.stream().map(e -> e.path("action").asText()).toList(), s.toString());
        assertEquals("HEADING", s.get(0).at("/element/kind").asText());
        assertEquals("ID", preferred(s.get(0)).path("strategy").asText(), "título pelo id: " + s.get(0).at("/element/locators"));
        for (int k : new int[] {1, 2}) {
            JsonNode loc = preferred(s.get(k));
            assertEquals("TABLE_CELL", s.get(k).at("/element/kind").asText(), "a CÉLULA, não o link nem o item: " + s.get(k).at("/element"));
            assertEquals("ROW_CONTEXT", loc.path("strategy").asText(), "célula ancorada pelo cabeçalho da linha: " + s.get(k).at("/element/locators"));
        }
        assertTrue(preferred(s.get(1)).path("xpath").asText().contains("Nascimento"), preferred(s.get(1)).toString());
        assertTrue(preferred(s.get(2)).path("xpath").asText().contains("Ocupação"), preferred(s.get(2)).toString());
        String all = s.toString();
        for (String value : List.of("Pessoa Fictícia", "1901", "março", "relojoeira", "tecelã", "cartógrafa")) {
            assertFalse(all.contains(value), "texto da página no descritor: " + value);
        }
        JsonNode stop = studio.call("studio.recording.finish", studio.obj().put("recordingId", rid).put("operationId", id("op")));
        assertTrue(stop.path("ok").asBoolean(), stop.toString());
    }
}
