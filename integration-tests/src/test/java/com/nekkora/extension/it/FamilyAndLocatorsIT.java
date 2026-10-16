package com.nekkora.extension.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.nekkora.extension.it.support.BrowserLab;
import com.nekkora.extension.it.support.BrowserLab.Page;
import com.nekkora.extension.it.support.LabIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Família de elementos e desambiguação por contexto (§8 / §8.1). */
class FamilyAndLocatorsIT extends LabIT {

    private static final String PAGE = "/lab/complex/duplicated-ids.html";

    @Test
    @DisplayName("T004 — ID duplicado: desambiguação ou recusa")
    void duplicatedId() {
        Page p = open(PAGE);
        // A seleção por um seletor ambíguo é RECUSADA (nunca o primeiro silenciosamente).
        BrowserLab.CallException e = assertThrows(BrowserLab.CallException.class, () -> select(p, "#excluir"));
        assertEquals("AMBIGUOUS_SELECTOR", e.code);

        JsonNode s = select(p, "#clientes-inativos #excluir");
        JsonNode byId = locator(s, "ID");
        assertNotNull(byId);
        assertTrue(byId.get("matchCount").asInt() >= 2, "o id é compartilhado: " + byId.get("matchCount"));
        assertTrue(byId.get("warnings").toString().contains("DUPLICATE_ID"));
        // Contagem REAL no navegador para o seletor por id também é > 1.
        JsonNode liveId = null;
        for (JsonNode c : s.get("liveChecks")) {
            if (c.get("semantic").asText().equals(byId.get("semantic").asText())) {
                liveId = c;
            }
        }
        assertNotNull(liveId);
        assertEquals(byId.get("matchCount").asInt(), liveId.get("cssCount").asInt());
        JsonNode primary = s.at("/inspection/locators/primary");
        assertEquals(1, primary.get("matchCount").asInt());
        // CHROME-SECURITY-001: a chave da linha (data-client) é dado e não atravessa; o contexto que torna o localizador
        // único passa a ser o contêiner estrutural (CONTEXTUAL) — expressão sempre transportável
        assertTrue(java.util.Set.of("ROW_CONTEXT", "CONTEXTUAL").contains(primary.get("strategy").asText()), primary.toString());
        assertTrue(com.nekkora.extension.core.privacy.CapturePrivacyPolicy.expressionSafe(primary.path("css").asText("")), primary.toString());
        assertLiveAgreement(s);
    }

    @Test
    @DisplayName("T006 — Texto duplicado: contextualização correta")
    void duplicatedText() {
        Page p = open(PAGE);
        JsonNode s = select(p, "[data-contact=secundario] button");
        JsonNode text = locator(s, "TEXT");
        assertNotNull(text);
        assertEquals(2, text.get("matchCount").asInt(), "texto 'Remover' aparece duas vezes");
        // CHROME-SECURITY-001: o único diferencial era o título "Secundário" (fora do vocabulário). O motor NÃO inventa
        // unicidade: o localizador principal declara AMBIGUOUS_MATCH, a identidade declara que o alvo é indistinguível no
        // registro, e a única alternativa única é posicional (instável, declarada como tal).
        JsonNode primary = s.at("/inspection/locators/primary");
        assertEquals(2, primary.get("matchCount").asInt());
        assertTrue(primary.get("warnings").toString().contains("AMBIGUOUS_MATCH"), primary.toString());
        assertTrue(s.at("/identity/warnings").toString().contains("NO_DISCRIMINATOR"), s.at("/identity/warnings").toString());
        JsonNode positional = locator(s, "POSITIONAL");
        assertEquals(1, positional.get("matchCount").asInt());
        assertEquals("HEURISTIC_UNSTABLE", positional.get("stability").asText());
        assertTrue(s.at("/inspection/family/containerHeading").asText("").isEmpty(), "título de contêiner retido");
        assertEquals(com.nekkora.extension.it.support.Policy.name("Remover"), s.at("/inspection/classification/name/name").asText(), "nome acessível não absorve o contexto");
        assertLiveAgreement(s);
    }

    @Test
    @DisplayName("T007 — Botões iguais em tabelas diferentes: família correta")
    void sameButtonsDifferentTables() {
        Page p = open(PAGE);
        JsonNode ana = select(p, "#clientes-ativos #excluir");
        JsonNode maria = select(p, "#clientes-inativos #excluir");
        assertEquals("clientes-ativos", ana.at("/inspection/family/table/idAttr").asText());
        // CHROME-SECURITY-001: chave e textos da linha são DADOS (retidos); o que distingue as famílias é a tabela
        // (id "clientes-ativos"/"clientes-inativos", do vocabulário) e o título do contêiner
        assertTrue(ana.at("/inspection/family/row/rowKey/data-client").asText("").isEmpty(), "chave da linha retida");
        assertFalse(ana.at("/inspection/family/row").toString().contains("Ana"), "texto da linha retido");
        assertTrue(ana.at("/inspection/family/containerHeading").asText("").isEmpty(), "título de contêiner é conteúdo (não carrega nome de interface)");
        assertEquals("clientes-inativos", maria.at("/inspection/family/table/idAttr").asText());
        assertFalse(maria.at("/inspection/family/row").toString().contains("Maria"), "texto da linha retido");
        assertNotEquals(ana.at("/identity/targetId").asText(), maria.at("/identity/targetId").asText());

        JsonNode rAna = resolve(p, ana.at("/identity/targetId").asText());
        JsonNode rMaria = resolve(p, maria.at("/identity/targetId").asText());
        assertEquals("RESOLVED", rAna.get("status").asText());
        assertEquals("RESOLVED", rMaria.get("status").asText());
        assertEquals(nodeIdOf(p, "#clientes-ativos #excluir"), rAna.get("resolvedNodeId").asText());
        assertEquals(nodeIdOf(p, "#clientes-inativos #excluir"), rMaria.get("resolvedNodeId").asText());
    }

    @Test
    @DisplayName("T008 — Botões iguais em linhas diferentes: linha correta")
    void sameButtonsDifferentRows() {
        Page p = open(PAGE);
        JsonNode s = select(p, "#clientes-corpo tr[data-client=\"202\"] button");
        JsonNode primary = s.at("/inspection/locators/primary");
        // CHROME-SECURITY-001: o valor "202" de data-client é dado: não entra no localizador. A linha é localizada pela
        // estrutura (XPath relativo/posição), com a estabilidade declarada — nunca pela chave retida
        assertEquals(1, primary.get("matchCount").asInt(), primary.toString());
        String expr = primary.path("css").asText("") + " " + primary.path("xpath").asText("") + " " + primary.path("semantic").asText("");
        assertFalse(expr.contains("202"), expr);
        assertTrue(com.nekkora.extension.core.privacy.CapturePrivacyPolicy.expressionSafe(primary.path("css").asText("")), primary.toString());
        assertEquals(com.nekkora.extension.it.support.Policy.name("Ações"), s.at("/inspection/family/column/headerText").asText(""));
        // id "excluir" aparece 5 vezes: 2 nas tabelas da seção §8.1 + 3 linhas desta tabela.
        assertEquals(5, locator(s, "ID").get("matchCount").asInt());
        assertLiveAgreement(s);
    }
}
