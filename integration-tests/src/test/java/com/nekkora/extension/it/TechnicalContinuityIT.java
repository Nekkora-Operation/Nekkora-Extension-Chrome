package com.nekkora.extension.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.nekkora.extension.it.support.BrowserLab;
import com.nekkora.extension.it.support.BrowserLab.Page;
import com.nekkora.extension.it.support.LabIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CHROME-SECURITY-001, decisão D7 — continuidade TÉCNICA do objeto DOM × identidade LÓGICA, no Chrome real (página 14).
 * A continuidade vem da época do registro de nós do content script; ela nunca vira resolução, localizador nem ação.
 */
class TechnicalContinuityIT extends LabIT {

    private static final String PAGE = "/lab/chrome-security-001/continuidade.html";

    private static void assertNoLogicalUse(JsonNode r) {
        if (!"RESOLVED".equals(r.get("status").asText())) {
            assertTrue(r.path("resolvedNodeId").isNull(), r.toString());
            assertTrue(r.path("updatedLocators").isNull() && r.path("updatedIdentity").isNull(), r.toString());
        }
    }

    private static String status(JsonNode r) {
        return r.at("/continuity/status").asText();
    }

    @Test
    @DisplayName("D7-1 — botões indistinguíveis que permanecem: AMBIGUOUS lógico preservado; continuidade técnica do nó registrado")
    void indistinguishable() {
        Page p = open(PAGE);
        JsonNode s = select(p, "#iguais .ok:first-child");
        String node = s.at("/inspection/nodeId").asText();
        JsonNode r = resolve(p, s.at("/identity/targetId").asText());
        assertEquals("AMBIGUOUS", r.get("status").asText(), r.toString());
        assertNoLogicalUse(r);
        assertEquals("LIVE_NODE_CONTINUITY", status(r), r.path("continuity").toString());
        assertEquals(node, r.at("/continuity/nodeId").asText());
        assertTrue(r.at("/continuity/limitations").toString().contains("LOGICAL_IDENTITY_NOT_REVALIDATED"));
        // nada é destacado como alvo confirmado
        assertTrue(!lab.call("page.diagnostics", p).at("/highlight/boxes/selection/visible").asBoolean(false));
    }

    @Test
    @DisplayName("D7-2 — remoção e criação de outro botão na mesma posição: continuidade quebrada")
    void removedAndRecreated() {
        Page p = open(PAGE);
        JsonNode s = select(p, "#iguais .ok:first-child");
        lab.click(p, By.id("trocar"));
        JsonNode r = resolve(p, s.at("/identity/targetId").asText());
        assertEquals("CONTINUITY_BROKEN", status(r), r.path("continuity").toString());
        assertTrue(r.at("/continuity/limitations/0").asText().startsWith("NODE_GONE"));
        assertNoLogicalUse(r);
    }

    @Test
    @DisplayName("D7-3 — re-renderização com objeto DOM novo: continuidade quebrada; a resolução lógica segue a evidência")
    void rerender() {
        Page p = open(PAGE);
        JsonNode s = select(p, "#entrar");
        String before = s.at("/inspection/nodeId").asText();
        // a seleção (destaque) passa a outro elemento: o reencontro AUTOMÁTICO do alvo destacado não move a âncora antes
        // da verificação — a 1ª resolução lógica de #entrar depois da re-renderização é a deste teste
        select(p, "#trocar");
        lab.click(p, By.id("rerenderizar"));
        JsonNode r = resolve(p, s.at("/identity/targetId").asText());
        assertEquals("CONTINUITY_BROKEN", status(r), r.path("continuity").toString());
        assertEquals("RESOLVED", r.get("status").asText(), "id 'entrar' e nome do vocabulário comprovam a identidade lógica");
        assertNotEquals(before, r.get("resolvedNodeId").asText());
        assertEquals(nodeIdOf(p, "#entrar"), r.get("resolvedNodeId").asText());
    }

    @Test
    @DisplayName("D7-4 — virtualização reaproveita a linha para outro registro: continuidade com conteúdo alterado, nunca identidade lógica")
    void virtualization() {
        Page p = open(PAGE);
        JsonNode s = select(p, "#virtual tr:first-child .ver");
        lab.click(p, By.id("rolar"));
        BrowserLab.waitUntil(Duration.ofSeconds(5), () -> "Registro 3".equals(lab.js(p, "return document.querySelector('#virtual td.c').textContent")),
                "linha reaproveitada");
        JsonNode[] last = new JsonNode[1];
        BrowserLab.waitUntil(Duration.ofSeconds(10), () -> {
            last[0] = resolve(p, s.at("/identity/targetId").asText());
            return last[0].at("/continuity/limitations").toString().contains("ROW_CONTENT_CHANGED");
        }, "mudança de conteúdo da linha observada (revisão de texto)");
        JsonNode r = last[0];
        assertEquals("LIVE_NODE_CONTINUITY", status(r), "é o MESMO objeto DOM");
        assertNotEquals("RESOLVED", r.get("status").asText(), "linhas indistinguíveis sem texto: a identidade lógica não é confirmada");
        assertNoLogicalUse(r);
        assertTrue(!r.toString().contains("Registro"), "nenhum texto da linha no resultado");
    }

    @Test
    @DisplayName("D7-5 — navegação (novo documento): nova época do registro de nós, continuidade quebrada")
    void navigation() {
        Page p = open(PAGE);
        JsonNode s = select(p, "#iguais .ok:first-child");
        lab.focus(p);
        lab.driver().navigate().refresh();
        BrowserLab.waitUntil(Duration.ofSeconds(10), () -> lab.callRaw("page.diagnostics", Map.of("tabId", p.tabId())).path("ok").asBoolean(),
                "content script após recarga");
        JsonNode r = resolve(p, s.at("/identity/targetId").asText());
        assertNotEquals("LIVE_NODE_CONTINUITY", status(r), r.path("continuity").toString());
        assertNoLogicalUse(r);
    }

    @Test
    @DisplayName("D7-6 — iframe recarregado: continuidade quebrada no frame; o documento principal continua")
    void iframeReloaded() {
        Page p = open(PAGE);
        BrowserLab.waitUntil(Duration.ofSeconds(10), () -> Boolean.TRUE.equals(lab.js(p,
                "const f = document.getElementById('quadro'); return !!(f.contentDocument && f.contentDocument.getElementById('no-quadro'))")), "iframe");
        JsonNode inFrame = selectIn(p, "#no-quadro", "FRAME", "iframe#quadro");
        JsonNode main = select(p, "#entrar");
        lab.js(p, "document.getElementById('quadro').contentWindow.location.reload()");
        JsonNode[] last = new JsonNode[1];
        try {
            BrowserLab.waitUntil(Duration.ofSeconds(10), () -> {
                last[0] = resolve(p, inFrame.at("/identity/targetId").asText());
                return "CONTINUITY_BROKEN".equals(status(last[0]));
            }, "continuidade do frame recarregado quebrada");
        } catch (AssertionError | RuntimeException e) {
            throw new AssertionError("último resultado: " + last[0] + " | antes: " + inFrame.at("/identity"), e);
        }
        assertEquals("LIVE_NODE_CONTINUITY", status(resolve(p, main.at("/identity/targetId").asText())));
    }

    @Test
    @DisplayName("D7-8 — reinício do Native Host: âncora persistida; continuidade do mesmo documento mantida, lógica inalterada")
    void hostRestart() {
        Page p = open(PAGE);
        JsonNode s = select(p, "#iguais .ok:first-child");
        String node = s.at("/inspection/nodeId").asText();
        lab.call("host.disconnect", Map.of());
        JsonNode r = resolve(p, s.at("/identity/targetId").asText());
        assertEquals("AMBIGUOUS", r.get("status").asText(), r.toString());
        assertEquals("LIVE_NODE_CONTINUITY", status(r), r.path("continuity").toString());
        assertEquals(node, r.at("/continuity/nodeId").asText());
    }
}
