package com.nekkora.extension.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.nekkora.extension.it.support.BrowserLab;
import com.nekkora.extension.it.support.BrowserLab.Page;
import com.nekkora.extension.it.support.LabIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** §23 — falhas corretas: a prioridade é evitar correspondências falsas. */
class NegativeCasesIT extends LabIT {

    @Test
    @DisplayName("T032 — Alvo ambíguo: recusa explícita")
    void ambiguousTarget() {
        Page p = open("/lab/complex/duplicated-ids.html");
        JsonNode s = select(p, "#indistinguiveis .confirmar:first-child");
        assertTrue(s.at("/identity/warnings").toString().contains("INDISTINGUISHABLE"), s.at("/identity/warnings").toString());
        JsonNode r = resolve(p, s.at("/identity/targetId").asText());
        assertEquals("AMBIGUOUS", r.get("status").asText(), r.get("evidence").toString());
        assertTrue(r.get("resolvedNodeId").isNull());
        // Nada fica destacado quando a resolução é recusada.
        JsonNode d = lab.call("page.diagnostics", p);
        assertFalse(d.at("/highlight/boxes/selection/visible").asBoolean(false));
    }

    @Test
    @DisplayName("N-A — Dois botões indistinguíveis: AMBIGUOUS")
    void caseA() {
        ambiguousTarget();
    }

    @Test
    @DisplayName("N-B — Elemento removido: NOT_FOUND ou STALE_TARGET")
    void caseB() {
        Page p = open("/lab/complex/duplicated-ids.html");
        String target = select(p, "#clientes-corpo tr[data-client=\"101\"] button").at("/identity/targetId").asText();
        lab.click(p, By.id("remover-ana"));
        JsonNode r = resolve(p, target);
        assertEquals("STALE_TARGET", r.get("status").asText(), r.get("evidence").toString());
        // Maria e João continuam na tabela, mas pertencem a outras linhas: desqualificados.
        for (JsonNode c : r.get("candidates")) {
            assertTrue(c.get("verdict").asText().equals("DISQUALIFIED") || c.get("verdict").asText().equals("INCOMPATIBLE"), c.toString());
        }
        // Após recarregar a página (nova sessão), o mesmo alvo não encontrado é NOT_FOUND.
        lab.focus(p);
        lab.driver().navigate().refresh();
        BrowserLab.waitUntil(Duration.ofSeconds(10), () -> lab.callRaw("page.diagnostics", java.util.Map.of("tabId", p.tabId())).path("ok").asBoolean(), "content script após recarga");
        lab.click(p, By.id("remover-ana"));
        assertEquals("NOT_FOUND", resolve(p, target).get("status").asText());
    }

    @Test
    @DisplayName("N-C — ID preservado com controle incompatível: IDENTITY_MISMATCH")
    void caseC() {
        Page p = open("/lab/complex/dynamic-ids.html");
        JsonNode s = select(p, "#email-contato");
        assertEquals("EMAIL_INPUT", s.at("/inspection/classification/kind").asText());
        lab.click(p, By.id("trocar-controle"));
        JsonNode r = resolve(p, s.at("/identity/targetId").asText());
        assertEquals("IDENTITY_MISMATCH", r.get("status").asText(), r.get("evidence").toString());
        assertTrue(r.get("candidates").toString().contains("INCOMPATIBLE"));
    }

    @Test
    @DisplayName("N-D — Elemento em região inacessível: SCOPE_UNAVAILABLE")
    void caseD() {
        Page p = open("/lab/complex/shadow-iframes.html");
        BrowserLab.waitUntil(Duration.ofSeconds(10), () -> Boolean.TRUE.equals(lab.js(p,
                "const f=document.getElementById('frame-pagamento'); return !!(f.contentDocument && f.contentDocument.getElementById('pagar'))")), "iframe carregado");
        JsonNode s = selectIn(p, "#pagar", "FRAME", "iframe#frame-pagamento");
        assertEquals("FRAME", s.at("/inspection/locators/primary/scope/type").asText());
        lab.click(p, By.id("isolar"));
        BrowserLab.waitUntil(Duration.ofSeconds(10), () -> Boolean.TRUE.equals(lab.js(p,
                "const f=document.getElementById('frame-pagamento'); try { return f.contentDocument === null; } catch(e) { return true; }")), "iframe isolado");
        JsonNode r = resolve(p, s.at("/identity/targetId").asText());
        assertEquals("SCOPE_UNAVAILABLE", r.get("status").asText(), r.get("evidence").toString());
    }

    @Test
    @DisplayName("N-E — Contexto insuficiente para comprovar identidade: INSUFFICIENT_EVIDENCE")
    void caseE() {
        Page p = open("/lab/complex/duplicated-ids.html");
        String target = select(p, "#clientes-corpo tr[data-client=\"101\"] button").at("/identity/targetId").asText();
        lab.click(p, By.id("esqueleto"));
        JsonNode r = resolve(p, target);
        assertEquals("INSUFFICIENT_EVIDENCE", r.get("status").asText(), r.get("evidence").toString());
        assertTrue(r.get("resolvedNodeId").isNull());
    }
}
