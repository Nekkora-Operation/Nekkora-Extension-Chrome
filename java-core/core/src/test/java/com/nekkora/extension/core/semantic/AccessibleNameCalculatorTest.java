package com.nekkora.extension.core.semantic;

import com.nekkora.extension.core.analysis.PageAnalysis;
import com.nekkora.extension.core.testing.Engine;
import com.nekkora.extension.core.testing.HtmlSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AccessibleNameCalculatorTest {

    private static AccessibleName name(String html, String css) {
        HtmlSnapshot h = HtmlSnapshot.of(html);
        PageAnalysis a = Engine.analyze(h);
        return a.semantics().get(h.idOf(css)).name();
    }

    @Test
    void externalLabelByFor() { // T029
        AccessibleName n = name("<label for=nome>Nome completo</label><div><div><input id=nome></div></div>", "#nome");
        assertEquals("Nome completo", n.name());
        assertEquals(NameSource.LABEL_ELEMENT, n.source());
    }

    @Test
    void wrappingLabel() {
        AccessibleName n = name("<label>Aceito os termos <input id=c type=checkbox></label>", "#c");
        assertEquals("Aceito os termos", n.name());
        assertEquals(NameSource.WRAPPING_LABEL, n.source());
    }

    @Test
    void precedence() {
        assertEquals(NameSource.ARIA_LABELLEDBY,
                name("<span id=l>Via labelledby</span><label for=x>Label</label><input id=x aria-label=Aria aria-labelledby=l>", "#x").source());
        assertEquals("Aria", name("<label for=x>Label</label><input id=x aria-label=Aria>", "#x").name());
        assertEquals("Buscar...", name("<input id=x placeholder='Buscar...'>", "#x").name());
        assertEquals("Excluir", name("<button id=b>  Excluir\n </button>", "#b").name());
        assertEquals("Enviar", name("<input id=b type=submit value=Enviar>", "#b").name());
    }

    @Test
    void buttonNameIsNotContaminatedByContext() {
        AccessibleName n = name("<div class=cliente><h3>Cliente Ana</h3><p>Status: Ativo</p><button id=e>Editar</button></div>", "#e");
        assertEquals("Editar", n.name());
    }

    @Test
    void description() {
        AccessibleName n = name("<input id=d aria-describedby=h><small id=h>Formato DD/MM/AAAA</small>", "#d");
        assertEquals("Formato DD/MM/AAAA", n.description());
    }

    @Test
    void hiddenDescendantsDoNotContributeToNameFromContent() {
        assertEquals("Salvar", name("<button id=b>Salvar <span hidden>rascunho</span></button>", "#b").name());
    }
}
