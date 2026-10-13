package com.nekkora.extension.core.options;

import com.nekkora.extension.core.analysis.PageAnalysis;
import com.nekkora.extension.core.testing.Engine;
import com.nekkora.extension.core.testing.HtmlSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OptionSetAnalyzerTest {

    private final OptionSetAnalyzer analyzer = new OptionSetAnalyzer();

    /** Captura ANTES de pedir o id temporário (o id só existe após a captura). */
    private OptionSet analyzeNow(HtmlSnapshot h, String css, OptionHistory history) {
        var graph = Engine.analyze(h).graph();
        return analyzer.analyze(h.idOf(css), graph, history);
    }

    @Test
    void nativeSelect() { // T010
        HtmlSnapshot h = HtmlSnapshot.of("""
                <form id=f><label for=uf>UF</label>
                <select id=uf name=uf required><option value="">Selecione</option><option value=SP selected>São Paulo</option>
                <option value=RJ disabled>Rio de Janeiro</option></select></form>""");
        PageAnalysis a = Engine.analyze(h);
        OptionSet s = analyzer.analyze(h.idOf("#uf"), a.graph(), new OptionHistory());
        assertEquals("NATIVE", s.association());
        assertEquals(3, s.materializedCount());
        assertEquals("COMPLETE", s.completeness());
        assertEquals("SP", s.options().get(1).value());
        assertEquals(Boolean.TRUE, s.options().get(1).selected());
        assertTrue(s.options().get(2).disabled());
        assertFalse(s.multiple());
    }

    @Test
    void optgroups() { // T011
        HtmlSnapshot h = HtmlSnapshot.of("""
                <select id=c multiple><optgroup label="Sudeste"><option>SP</option><option>RJ</option></optgroup>
                <optgroup label="Sul" disabled><option>PR</option></optgroup></select>""");
        PageAnalysis a = Engine.analyze(h);
        OptionSet s = analyzer.analyze(h.idOf("#c"), a.graph(), new OptionHistory());
        assertEquals(2, s.groups().size());
        assertEquals("Sudeste", s.groups().get(0).label());
        assertEquals(2, s.groups().get(0).optionCount());
        assertTrue(s.groups().get(1).disabled());
        assertEquals("Sul", s.options().get(2).group());
        assertTrue(s.multiple());
    }

    @Test
    void portalCustomDropdown() { // T012 / T013
        HtmlSnapshot h = HtmlSnapshot.of("""
                <div id=cb role=combobox tabindex=0 aria-controls=lista-estados>Estado</div>
                <div id=portal><div id=lista-estados role=listbox><div role=option>São Paulo</div><div role=option>Rio de Janeiro</div></div></div>""");
        PageAnalysis a = Engine.analyze(h);
        OptionSet s = analyzer.analyze(h.idOf("#cb"), a.graph(), new OptionHistory());
        assertEquals("ARIA_CONTROLS", s.association());
        assertTrue(s.listOutsideControlTree());
        assertEquals(2, s.materializedCount());
        assertEquals("UNKNOWN", s.completeness(), "componente personalizado não comprova completude");
        // listbox controlado não gera conjunto duplicado
        assertEquals(1, analyzer.analyzeAll(a.graph(), new OptionHistory()).size());
    }

    @Test
    void dynamicListDeclaresCoverage() { // T014
        OptionHistory history = new OptionHistory();
        HtmlSnapshot h = HtmlSnapshot.of("<div id=cb role=combobox tabindex=0 aria-controls=l>Cidade</div><div id=l role=listbox aria-busy=true>Carregando...</div>");
        OptionSet first = analyzeNow(h, "#cb", history);
        assertEquals(0, first.materializedCount());
        assertTrue(first.dynamicLoading());
        assertEquals("UNKNOWN", first.completeness());

        h.dom().selectFirst("#l").removeAttr("aria-busy").html("<div role=option>Campinas</div><div role=option>Santos</div>");
        OptionSet second = analyzeNow(h, "#cb", history);
        assertEquals(2, second.materializedCount());
        h.dom().selectFirst("#l").html("<div role=option>Sorocaba</div>");
        OptionSet third = analyzeNow(h, "#cb", history);
        assertEquals(1, third.materializedCount());
        assertEquals(3, third.observedCount(), "observadas acumulam; materializadas são o estado atual");
        assertTrue(third.dynamicLoading());
    }

    @Test
    void notMaterializedList() {
        HtmlSnapshot h = HtmlSnapshot.of("<div id=cb role=combobox tabindex=0 aria-controls=ausente aria-expanded=false>Cidade</div>");
        OptionSet s = analyzeNow(h, "#cb", new OptionHistory());
        assertNull(s.listNodeId());
        assertEquals("UNKNOWN", s.completeness());
        assertTrue(s.evidence().stream().anyMatch(e -> e.contains("não materializada")));
    }

    @Test
    void virtualizedListIsNotComplete() { // T021
        StringBuilder opts = new StringBuilder();
        for (int i = 41; i <= 60; i++) {
            opts.append("<div role=option aria-posinset=").append(i).append(" aria-setsize=1000>Item ").append(i).append("</div>");
        }
        HtmlSnapshot h = HtmlSnapshot.of("<div id=l role=listbox>" + opts + "</div>");
        OptionSet s = analyzeNow(h, "#l", new OptionHistory());
        assertEquals(20, s.materializedCount());
        assertEquals(1000, s.announcedSetSize());
        assertTrue(s.virtualizationSuspected());
        assertEquals("INCOMPLETE", s.completeness());
    }
}
