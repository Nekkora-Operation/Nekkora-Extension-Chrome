package com.nekkora.extension.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.nekkora.extension.it.support.BrowserLab;
import com.nekkora.extension.it.support.BrowserLab.Page;
import com.nekkora.extension.it.support.LabIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertEquals("ROW_CONTEXT", primary.get("strategy").asText());
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
        assertEquals(1, s.at("/inspection/locators/primary/matchCount").asInt());
        assertEquals("Secundário", s.at("/inspection/family/containerHeading").asText());
        assertEquals("Remover", s.at("/inspection/classification/name/name").asText(), "nome acessível não absorve o contexto");
        assertLiveAgreement(s);
    }

    @Test
    @DisplayName("T007 — Botões iguais em tabelas diferentes: família correta")
    void sameButtonsDifferentTables() {
        Page p = open(PAGE);
        JsonNode ana = select(p, "#clientes-ativos #excluir");
        JsonNode maria = select(p, "#clientes-inativos #excluir");
        assertEquals("clientes-ativos", ana.at("/inspection/family/table/idAttr").asText());
        assertEquals("101", ana.at("/inspection/family/row/rowKey/data-client").asText());
        assertTrue(ana.at("/inspection/family/row/cellTexts").toString().contains("Ana"));
        assertEquals("Clientes ativos", ana.at("/inspection/family/containerHeading").asText());
        assertEquals("clientes-inativos", maria.at("/inspection/family/table/idAttr").asText());
        assertEquals("202", maria.at("/inspection/family/row/rowKey/data-client").asText());
        assertTrue(maria.at("/inspection/family/row/cellTexts").toString().contains("Maria"));
        assertNotEquals(ana.at("/identity/targetId").asText(), maria.at("/identity/targetId").asText());
        assertTrue(maria.at("/identity/discriminators").toString().contains("ROW_KEY"));

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
        assertEquals("ROW_CONTEXT", primary.get("strategy").asText());
        assertTrue(primary.get("css").asText().contains("data-client=\"202\""), primary.get("css").asText());
        assertEquals("Ações", s.at("/inspection/family/column/headerText").asText());
        // id "excluir" aparece 5 vezes: 2 nas tabelas da seção §8.1 + 3 linhas desta tabela.
        assertEquals(5, locator(s, "ID").get("matchCount").asInt());
        assertLiveAgreement(s);
    }
}
