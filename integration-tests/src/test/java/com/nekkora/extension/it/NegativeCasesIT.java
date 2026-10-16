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
        // CHROME-SECURITY-001: a chave da linha (data-client="101") é dado e não atravessa — no registro, o botão da Ana já
        // era indistinguível dos botões das outras linhas. Removida a linha, o motor NÃO escolhe Maria nem João: declara
        // que nenhuma escolha é segura (ou que o alvo sumiu), nunca resolve para outra linha.
        assertTrue(java.util.Set.of("STALE_TARGET", "AMBIGUOUS", "NOT_FOUND", "INSUFFICIENT_EVIDENCE").contains(r.get("status").asText()), r.toString());
        assertTrue(r.path("resolvedNodeId").isNull(), r.toString());
        if ("AMBIGUOUS".equals(r.get("status").asText())) {
            assertTrue(r.get("evidence").toString().contains("indistinguível"), r.get("evidence").toString());
        }
        // Após recarregar a página (nova sessão), o mesmo alvo não encontrado é NOT_FOUND.
        lab.focus(p);
        lab.driver().navigate().refresh();
        BrowserLab.waitUntil(Duration.ofSeconds(10), () -> lab.callRaw("page.diagnostics", java.util.Map.of("tabId", p.tabId())).path("ok").asBoolean(), "content script após recarga");
        lab.click(p, By.id("remover-ana"));
        JsonNode again = resolve(p, target);
        assertTrue(java.util.Set.of("NOT_FOUND", "AMBIGUOUS", "INSUFFICIENT_EVIDENCE").contains(again.get("status").asText()), again.toString());
        assertTrue(again.path("resolvedNodeId").isNull(), again.toString());
    }

    @Test
    @DisplayName("N-C — ID preservado com controle incompatível: IDENTITY_MISMATCH")
    void caseC() {
        Page p = open("/lab/complex/dynamic-ids.html");
        JsonNode s = select(p, "#email-contato");
        assertEquals("EMAIL_INPUT", s.at("/inspection/classification/kind").asText());
        lab.click(p, By.id("trocar-controle"));
        JsonNode r = resolve(p, s.at("/identity/targetId").asText());
        if (com.nekkora.extension.it.support.Policy.id("email-contato") != null) {
            assertEquals("IDENTITY_MISMATCH", r.get("status").asText(), r.get("evidence").toString());
            assertTrue(r.get("candidates").toString().contains("INCOMPATIBLE"));
        } else {
            // CHROME-SECURITY-001: o id "email-contato" está fora do vocabulário — o motor não sabe mais que o controle novo
            // ocupa o MESMO endereço; o resultado honesto é a falta de evidência, nunca o controle incompatível resolvido
            assertTrue(java.util.Set.of("IDENTITY_MISMATCH", "INSUFFICIENT_EVIDENCE").contains(r.get("status").asText()), r.toString());
            assertTrue(r.path("resolvedNodeId").isNull(), r.toString());
        }
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
