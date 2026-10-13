package com.nekkora.extension.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.nekkora.extension.it.support.BrowserLab;
import com.nekkora.extension.it.support.BrowserLab.Page;
import com.nekkora.extension.it.support.LabIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reencontro após mudanças reais na página (cliques reais nos botões do laboratório). */
class ResolutionIT extends LabIT {

    @Test
    @DisplayName("T005 — IDs dinâmicos: reencontro após renderização")
    void dynamicIds() {
        Page p = open("/lab/complex/dynamic-ids.html");
        JsonNode s = select(p, "#form-host input");
        assertTrue(locator(s, "ID").get("stability").asText().contains("UNSTABLE"), "id com sufixo numérico longo: estabilidade reduzida por heurística");
        assertNotEquals("ID", s.at("/inspection/locators/primary/strategy").asText());
        String target = s.at("/identity/targetId").asText();
        String original = s.at("/inspection/nodeId").asText();
        for (int i = 0; i < 2; i++) {
            lab.click(p, By.id("rerender-form"));
            JsonNode r = resolve(p, target);
            assertEquals("RESOLVED", r.get("status").asText(), r.get("evidence").toString());
            assertEquals(nodeIdOf(p, "#form-host input"), r.get("resolvedNodeId").asText());
            assertNotEquals(original, r.get("resolvedNodeId").asText(), "nó original foi destruído");
            JsonNode identity = identity(target);
            java.util.Set<String> ids = new java.util.HashSet<>();
            identity.at("/attributeHistory/id").forEach(v -> ids.add(v.asText()));
            assertEquals(i + 2, ids.size(), "ids observados: " + identity.at("/attributeHistory/id"));
            JsonNode byId = null;
            for (JsonNode l : r.at("/updatedLocators/fallbacks")) {
                if ("ID".equals(l.get("strategy").asText())) {
                    byId = l;
                }
            }
            if (byId != null) {
                assertEquals("OBSERVED_UNSTABLE", byId.get("stability").asText(), "estratégia reavaliada por observação");
                assertEquals("OBSERVED", byId.get("stabilityBasis").asText());
            }
            assertNotEquals("ID", r.at("/updatedLocators/primary/strategy").asText());
        }
    }

    private static JsonNode identity(String targetId) {
        for (JsonNode t : lab.call("target.list", java.util.Map.of())) {
            if (t.get("targetId").asText().equals(targetId)) {
                return t;
            }
        }
        throw new AssertionError("alvo não registrado: " + targetId);
    }

    /** O destaque acompanhou a re-renderização: o service worker reencontrou o alvo automaticamente. */
    private static void assertAutoReacquired(String targetId) {
        BrowserLab.waitUntil(Duration.ofSeconds(5), () -> {
            for (JsonNode e : lab.events("resolution")) {
                if (targetId.equals(e.at("/data/result/targetId").asText()) && "RESOLVED".equals(e.at("/data/result/status").asText())) {
                    return true;
                }
            }
            return false;
        }, "reencontro automático do alvo destacado");
    }

    @Test
    @DisplayName("T009 — Reordenação de tabela: identidade preservada")
    void reorder() {
        Page p = open("/lab/complex/duplicated-ids.html");
        JsonNode s = select(p, "#clientes-corpo tr[data-client=\"101\"] button");
        String target = s.at("/identity/targetId").asText();
        lab.click(p, By.id("reordenar"));
        assertEquals("303", String.valueOf(lab.js(p, "return document.querySelector('#clientes-corpo tr').dataset.client")));
        JsonNode r1 = resolve(p, target);
        assertEquals("RESOLVED", r1.get("status").asText());
        assertEquals(nodeIdOf(p, "#clientes-corpo tr[data-client=\"101\"] button"), r1.get("resolvedNodeId").asText());
        assertFalse(r1.get("nodeReplaced").asBoolean(), "mesmo nó, apenas movido");

        String original = r1.get("resolvedNodeId").asText();
        lab.clearEvents();
        lab.click(p, By.id("rerenderizar"));
        assertAutoReacquired(target);
        JsonNode r2 = resolve(p, target);
        assertEquals("RESOLVED", r2.get("status").asText(), r2.get("evidence").toString());
        assertNotEquals(original, r2.get("resolvedNodeId").asText(), "tabela re-renderizada: nó novo");
        assertEquals(nodeIdOf(p, "#clientes-corpo tr[data-client=\"101\"] button"), r2.get("resolvedNodeId").asText(),
                "o botão da Ana continua sendo o da Ana após mudar de posição");
    }

    @Test
    @DisplayName("T025 — Elemento removido: referência invalidada")
    void removed() {
        Page p = open("/lab/complex/rerender.html");
        String target = select(p, "#baixar").at("/identity/targetId").asText();
        lab.click(p, By.id("remover"));
        JsonNode r = resolve(p, target);
        assertEquals("STALE_TARGET", r.get("status").asText(), r.get("evidence").toString());
        assertTrue(r.get("resolvedNodeId").isNull());
    }

    @Test
    @DisplayName("T026 — Elemento substituído: identidade revalidada")
    void replaced() {
        Page p = open("/lab/complex/rerender.html");
        JsonNode s = select(p, ".salvar");
        String before = s.at("/inspection/nodeId").asText();
        lab.clearEvents();
        lab.click(p, By.id("substituir"));
        assertAutoReacquired(s.at("/identity/targetId").asText());
        JsonNode r = resolve(p, s.at("/identity/targetId").asText());
        assertEquals("RESOLVED", r.get("status").asText(), r.get("evidence").toString());
        assertNotEquals(before, r.get("resolvedNodeId").asText());
        assertEquals(nodeIdOf(p, ".salvar"), r.get("resolvedNodeId").asText());
    }

    @Test
    @DisplayName("T031 — Variação responsiva: reencontro validado")
    void responsive() {
        Page p = open("/lab/complex/responsive.html");
        JsonNode s = select(p, "#minha-conta");
        assertEquals("desktop-nav", s.at("/inspection/family/parent/idAttr").asText());
        Dimension original = lab.driver().manage().window().getSize();
        try {
            lab.focus(p);
            lab.driver().manage().window().setSize(new Dimension(600, 900));
            BrowserLab.waitUntil(Duration.ofSeconds(5), () -> "mobile-menu".equals(lab.js(p, "return document.getElementById('minha-conta').parentElement.id")), "link movido para o menu móvel");
            JsonNode r = resolve(p, s.at("/identity/targetId").asText());
            assertEquals("RESOLVED", r.get("status").asText(), r.get("evidence").toString());
            assertEquals(nodeIdOf(p, "#minha-conta"), r.get("resolvedNodeId").asText());
        } finally {
            lab.driver().manage().window().setSize(original);
        }
    }

    @Test
    @DisplayName("T040 — Alteração semântica com ID constante: identidade incompatível detectada")
    void semanticChangeSameId() {
        Page p = open("/lab/complex/dynamic-ids.html");
        JsonNode s = select(p, "#campo-principal");
        assertEquals("Nome", s.at("/inspection/classification/name/name").asText());
        lab.click(p, By.id("trocar-significado"));
        JsonNode r = resolve(p, s.at("/identity/targetId").asText());
        assertEquals("IDENTITY_MISMATCH", r.get("status").asText(), r.get("evidence").toString());
        assertTrue(r.get("resolvedNodeId").isNull());
    }
}
