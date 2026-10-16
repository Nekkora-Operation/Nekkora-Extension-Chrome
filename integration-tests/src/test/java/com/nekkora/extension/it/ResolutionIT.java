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
        // CHROME-SECURITY-001: id com sufixo numérico longo não é do vocabulário → nem chega ao motor (sem localizador ID)
        JsonNode idLocator = locator(s, "ID");
        assertTrue(idLocator == null || idLocator.get("stability").asText().contains("UNSTABLE"), "id dinâmico: retido ou instável");
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
            // CHROME-SECURITY-001: os ids dinâmicos ("campo-<número longo>") não são do vocabulário — o histórico registra
            // que o id existia, sem o valor; a troca deixa de ser observável pelo id (a resolução segue pelo resto)
            for (String id : ids) {
                assertTrue("null".equals(id) || com.nekkora.extension.it.support.Policy.id(id) != null, "id retido no histórico: " + id);
            }
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
        // CHROME-SECURITY-001: a chave da linha (data-client) e os textos das células são DADOS e não atravessam — os
        // botões "Excluir" das linhas ficaram indistinguíveis no registro. Depois da reordenação (e da re-renderização), o
        // motor não pode afirmar qual é o da Ana: declara a ambiguidade, NUNCA escolhe o botão de outra linha.
        JsonNode r1 = resolve(p, target);
        assertHonest(r1, nodeIdOf(p, "#clientes-corpo tr[data-client=\"101\"] button"));
        lab.click(p, By.id("rerenderizar"));
        JsonNode r2 = resolve(p, target);
        assertHonest(r2, nodeIdOf(p, "#clientes-corpo tr[data-client=\"101\"] button"));
    }

    private static void assertHonest(JsonNode r, String right) {
        if ("RESOLVED".equals(r.get("status").asText())) {
            assertEquals(right, r.get("resolvedNodeId").asText(), r.toString());
        } else {
            assertTrue(java.util.Set.of("AMBIGUOUS", "INSUFFICIENT_EVIDENCE").contains(r.get("status").asText()), r.toString());
            assertTrue(r.path("resolvedNodeId").isNull(), r.toString());
        }
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
        assertEquals(com.nekkora.extension.it.support.Policy.id("desktop-nav"), s.at("/inspection/family/parent/idAttr").asText(null));
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
