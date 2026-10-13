package com.nekkora.extension.core.context;

import com.nekkora.extension.core.analysis.PageAnalysis;
import com.nekkora.extension.core.semantic.ElementKind;
import com.nekkora.extension.core.testing.Engine;
import com.nekkora.extension.core.testing.Fixtures;
import com.nekkora.extension.core.testing.HtmlSnapshot;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextGraphTest {

    @Test
    void duplicatedIdsInDifferentTablesHaveDistinctFamilies() { // §8.1 / T007
        HtmlSnapshot h = HtmlSnapshot.of(Fixtures.DUPLICATED_TABLES);
        PageAnalysis a = Engine.analyze(h);
        List<Element> buttons = h.dom().select("button#excluir");
        ElementFamily fa = a.graph().family(h.idOf(buttons.get(0)));
        ElementFamily fb = a.graph().family(h.idOf(buttons.get(1)));

        assertEquals("clientes-ativos", fa.table().idAttr());
        assertEquals(Map.of("data-client", "101"), fa.row().rowKey());
        assertEquals("DATA_ATTRIBUTE", fa.row().rowKeySource());
        assertTrue(fa.row().cellTexts().contains("Ana"));
        assertEquals("Clientes ativos", fa.containerHeading());

        assertEquals("clientes-inativos", fb.table().idAttr());
        assertEquals(Map.of("data-client", "202"), fb.row().rowKey());
        assertTrue(fb.row().cellTexts().contains("Maria"));
        assertEquals("Clientes inativos", fb.containerHeading());
    }

    @Test
    void columnHeadersAreAssociated() {
        HtmlSnapshot h = HtmlSnapshot.of(Fixtures.SAME_TABLE_ROWS);
        PageAnalysis a = Engine.analyze(h);
        ElementFamily f = a.graph().family(h.idOf(h.dom().select("button").get(1)));
        assertEquals("Ações", f.column().headerText());
        assertEquals(2, f.column().index());
        assertEquals("THEAD", f.column().basis());
        assertEquals(Map.of("data-client", "202"), f.row().rowKey());
    }

    @Test
    void cardContextWithoutPollutingName() { // §9
        HtmlSnapshot h = HtmlSnapshot.of(Fixtures.CARDS);
        PageAnalysis a = Engine.analyze(h);
        String editarAna = h.idOf(h.dom().select("button").get(0));
        ElementFamily f = a.graph().family(editarAna);
        assertEquals("Cliente Ana", f.containerHeading());
        assertTrue(f.contextualTexts().contains("Status: Ativo"), f.contextualTexts().toString());
        assertEquals("Editar", a.semantics().get(editarAna).name().name());
        assertNotNull(f.logicalContainer());
        assertEquals("div", f.logicalContainer().tag());
        assertEquals("Clientes", f.region().name());
    }

    @Test
    void billingAndShippingStatesAreDifferentFamilies() {
        HtmlSnapshot h = HtmlSnapshot.of(Fixtures.ADDRESS_FORMS);
        PageAnalysis a = Engine.analyze(h);
        ElementFamily c = a.graph().family(h.idOf("#estado-c"));
        ElementFamily e = a.graph().family(h.idOf("#estado-e"));
        assertEquals("Endereço de cobrança", c.fieldGroup().name());
        assertEquals("Endereço de entrega", e.fieldGroup().name());
        assertEquals("checkout", c.form().idAttr());
        assertEquals("Estado", c.labels().get(0).name());
    }

    @Test
    void portalListboxIsLogicallyAssociated() { // T013
        HtmlSnapshot h = HtmlSnapshot.of("""
                <form><div id="cb" role="combobox" tabindex="0" aria-controls="lista-estados" aria-expanded="true">Estado</div></form>
                <div id="portal"><div id="lista-estados" role="listbox"><div role="option">São Paulo</div><div role="option">Rio de Janeiro</div></div></div>
                """);
        PageAnalysis a = Engine.analyze(h);
        ElementFamily list = a.graph().family(h.idOf("#lista-estados"));
        assertTrue(list.relations().stream().anyMatch(r -> r.type() == RelationType.CONTROLS
                && r.fromNodeId().equals(h.idOf("#cb")) && r.basis() == ContextRelation.Basis.ARIA_ATTRIBUTE));
        ElementFamily combo = a.graph().family(h.idOf("#cb"));
        assertTrue(combo.relations().stream().anyMatch(r -> r.type() == RelationType.CONTROLS && r.toNodeId().equals(h.idOf("#lista-estados"))));
        assertEquals(ElementKind.COMBOBOX, a.semantics().get(h.idOf("#cb")).kind());
    }

    @Test
    void modalContext() { // T015
        HtmlSnapshot h = HtmlSnapshot.of("""
                <button>Salvar</button>
                <dialog id="editar" open data-fixture-modal aria-labelledby="t"><h2 id="t">Editar cliente</h2>
                  <form><label for="n">Nome</label><input id="n"><button>Salvar</button></form></dialog>
                """);
        PageAnalysis a = Engine.analyze(h);
        String modalSave = h.idOf(h.dom().select("button").get(1));
        ElementFamily f = a.graph().family(modalSave);
        assertNotNull(f.dialog());
        assertEquals(ElementKind.MODAL, f.dialog().kind());
        assertEquals("Editar cliente", f.dialog().name());
        assertEquals(null, a.graph().family(h.idOf(h.dom().select("button").get(0))).dialog());
    }

    @Test
    void shadowAndFrameScopeChains() {
        HtmlSnapshot h = HtmlSnapshot.of("""
                <nk-card id="host"><template shadowrootmode="open"><button id="sb">Interno</button></template></nk-card>
                <iframe id="fr" data-fixture-frame="a"></iframe>
                """).frame("a", "<button id='fb'>No frame</button>", true, null);
        PageAnalysis a = Engine.analyze(h);
        String shadowButton = a.semantics().all().stream().filter(c -> c.name().name().equals("Interno")).findFirst().orElseThrow().nodeId();
        ElementFamily sf = a.graph().family(shadowButton);
        assertEquals("host", sf.scopeChain().get(0).idAttr());
        String frameButton = a.semantics().all().stream().filter(c -> c.name().name().equals("No frame")).findFirst().orElseThrow().nodeId();
        ElementFamily ff = a.graph().family(frameButton);
        assertEquals("fr", ff.scopeChain().get(0).idAttr());
    }
}
