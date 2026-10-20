package com.nekkora.extension.core.identity;

import com.nekkora.extension.core.resolve.ResolutionResult;
import com.nekkora.extension.core.resolve.ResolutionStatus;
import com.nekkora.extension.core.semantic.ElementKind;
import com.nekkora.extension.core.testing.Engine;
import com.nekkora.extension.core.testing.HtmlSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** NEKKORA-E2E-002: identidade transitória do descritor respeita a REGIÃO gravada no localizador contextual. */
class DescriptorIdentityTest {

    /** Como a página pública: a busca do cabeçalho (combobox editável depois do foco) e a cópia com o mesmo name fora dele. */
    private static final String PAGE = "<header><form role=search><input type=search name=search aria-label=Pesquisar></form></header>"
            + "<div><form><input type=search name=search aria-label=Pesquisar></form></div><main><h1>Artigo</h1></main>";

    @Test
    void contextualRegionSeparatesTheHeaderSearchFromItsCopy() {
        HtmlSnapshot h = HtmlSnapshot.of(PAGE);
        var a = Engine.analyze(h);
        TargetIdentity id = DescriptorIdentity.of(a, ElementKind.COMBOBOX, "input", "Pesquisar", null, "search", null,
                List.of("header input[name=\"search\"]"));
        assertEquals(java.util.Set.of(IdentityFeature.REGION), id.discriminators());
        ResolutionResult r = Engine.resolve(id, a);
        assertEquals(ResolutionStatus.RESOLVED, r.status(), String.join("\n", r.evidence()));
        assertEquals(h.idOf("header input"), r.resolvedNodeId(), "o campo do cabeçalho, nunca a cópia");
    }

    @Test
    void withoutTheContextualLocatorTheCopiesStayUndecided() {
        HtmlSnapshot h = HtmlSnapshot.of(PAGE);
        var a = Engine.analyze(h);
        TargetIdentity id = DescriptorIdentity.of(a, ElementKind.COMBOBOX, "input", "Pesquisar", null, "search", null, List.of("[name=\"search\"]"));
        assertTrue(id.discriminators().isEmpty());
        assertNotEquals(ResolutionStatus.RESOLVED, Engine.resolve(id, a).status(), "nenhum palpite entre as duas cópias");
    }

    @Test
    void recordedContextualLocatorPicksOnlyTheHeaderField() {
        HtmlSnapshot h = HtmlSnapshot.of(PAGE);
        var a = Engine.analyze(h);
        assertEquals(h.idOf("header input"), DescriptorIdentity.uniqueByRegionLocator(a, "header input[name=\"search\"]", ElementKind.COMBOBOX, "input"));
        assertNull(DescriptorIdentity.uniqueByRegionLocator(a, "input[name=\"search\"]", ElementKind.COMBOBOX, "input"), "sem região: não decide");
        assertNull(DescriptorIdentity.uniqueByRegionLocator(a, "header button[name=\"search\"]", ElementKind.BUTTON, "button"), "nada selecionado");
    }

    @Test
    void regionThatSelectsTwoElementsDecidesNothing() {
        HtmlSnapshot h = HtmlSnapshot.of("<header><input type=search name=q aria-label=Buscar></header><header><input type=search name=q aria-label=Buscar></header>");
        var a = Engine.analyze(h);
        assertNull(DescriptorIdentity.regionOf(a, "header input[name=\"q\"]", ElementKind.SEARCH_INPUT, "input"));
    }

    @Test
    void regionWithoutIdOrNameStillHasAKey() { // o marco mais próximo: o form role=search dentro do header
        HtmlSnapshot h = HtmlSnapshot.of(PAGE);
        var a = Engine.analyze(h);
        assertEquals("landmark:form", new SignatureExtractor().extract(h.idOf("header input"), a.graph()).region());
    }
}
