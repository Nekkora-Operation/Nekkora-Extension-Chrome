package com.nekkora.extension.core.semantic;

import com.nekkora.extension.core.analysis.PageAnalysis;
import com.nekkora.extension.core.testing.Engine;
import com.nekkora.extension.core.testing.HtmlSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Casos do AccName 1.2 / HTML-AAM (resultados esperados segundo a especificação). */
class AccNameConformanceTest {

    private static AccessibleName name(String html, String css) {
        HtmlSnapshot h = HtmlSnapshot.of(html);
        PageAnalysis a = Engine.analyze(h);
        return a.semantics().get(h.idOf(css)).name();
    }

    @Test
    void multipleLabelledByInDeclaredOrder() { // C002-034
        AccessibleName n = name("<span id=a>Excluir</span><span id=b>cliente Ana</span><button id=x aria-labelledby='a b'>X</button>", "#x");
        assertEquals("Excluir cliente Ana", n.name());
        assertEquals(NameSource.ARIA_LABELLEDBY, n.source());
        assertEquals("HIGH", n.confidence());
    }

    @Test
    void recursiveLabelledByUsesReferencedNodeAlternative() { // C002-033
        // O nó referenciado tem aria-label: a alternativa dele é usada (não seu conteúdo).
        String html = "<span id=r aria-label='Remover'>X</span><span id=t>pedido 42</span>"
                + "<button id=b aria-labelledby='r t'>?</button>";
        assertEquals("Remover pedido 42", name(html, "#b").name());
        // Referência a um elemento cujo conteúdo contém outro com aria-label (recursão no conteúdo).
        String nested = "<div id=l>Status <span aria-label='ativo'>✔</span></div><input id=i aria-labelledby=l>";
        assertEquals("Status ativo", name(nested, "#i").name());
    }

    @Test
    @Timeout(5)
    void circularReferencesDoNotRecurseForever() { // C002-035
        String html = "<span id=a aria-labelledby=b>Alfa</span><span id=b aria-labelledby=a>Beta</span>"
                + "<button id=x aria-labelledby=a>?</button>";
        assertEquals("Alfa", name(html, "#x").name());
        String self = "<button id=x aria-labelledby='x y'>Salvar</button><span id=y>agora</span>";
        // Autorreferência: o próprio nó contribui com a alternativa SEM reaplicar 2B (conteúdo "Salvar"), como no
        // Chrome. (A expectativa inicial desta missão — ignorar a autorreferência — contrariava a especificação.)
        assertEquals("Salvar agora", name(self, "#x").name());
        String selfLabel = "<button id=x aria-labelledby='x y' aria-label=Enviar>?</button><span id=y>agora</span>";
        assertEquals("Enviar agora", name(selfLabel, "#x").name(), "autorreferência usa aria-label (2C)");
    }

    @Test
    void hiddenReferencedElementIsIncluded() { // C002-036
        String html = "<span id=h hidden>Pesquisar clientes</span><button id=b aria-labelledby=h>Ir</button>";
        assertEquals("Pesquisar clientes", name(html, "#b").name());
        // Mas oculto NÃO referenciado não entra no nome por conteúdo.
        assertEquals("Ir", name("<button id=b>Ir <span hidden>agora</span></button>", "#b").name());
        assertEquals("Ir", name("<button id=b>Ir <span aria-hidden=true>→</span></button>", "#b").name());
    }

    @Test
    void iconButtons() { // C002-037
        assertEquals("Pesquisar", name("<button id=b><img src=lupa.png alt='Pesquisar'></button>", "#b").name());
        assertEquals("Fechar", name("<button id=b><svg><title>Fechar</title><path d=''/></svg></button>", "#b").name());
        assertEquals("Excluir", name("<button id=b><span class=icon aria-label='Excluir'></span></button>", "#b").name());
        assertEquals("Menu", name("<button id=b title=Menu><i class=icon></i></button>", "#b").name());
        assertEquals(NameSource.TITLE, name("<button id=b title=Menu><i class=icon></i></button>", "#b").source());
    }

    @Test
    void ariaLabelTakesPrecedenceOverContent() {
        AccessibleName n = name("<button id=b aria-label='Pesquisar clientes'><span>🔍</span> Buscar</button>", "#b");
        assertEquals("Pesquisar clientes", n.name());
        assertEquals(NameSource.ARIA_LABEL, n.source());
    }

    @Test
    void descriptions() {
        assertEquals("Formato DD/MM", name("<input id=d aria-description='Formato DD/MM'>", "#d").description());
        assertEquals("Dica oculta", name("<input id=d aria-describedby=h><span id=h hidden>Dica oculta</span>", "#d").description());
    }

    @Test
    void embeddedControlInLabel() {
        String html = "<label for=q>Mostrar <select><option>10</option><option selected>20</option></select> itens</label>"
                + "<input id=q type=checkbox>";
        // O select embutido contribui com a opção selecionada (2E) na POSIÇÃO em que aparece: com os segmentos de
        // texto (CHROME-002) a ordem da especificação é preservada. (CHROME-001 produzia "Mostrar itens 20".)
        assertEquals("Mostrar 20 itens", name(html, "#q").name());
    }

    @Test
    void mixedContentOrderPreserved() {
        assertEquals("Ver detalhes do pedido", name("<a id=a href=#x>Ver <strong>detalhes</strong> do pedido</a>", "#a").name());
    }

    @Test
    void legacySnapshotWithoutSegmentsKeepsFallbackOrder() {
        // Snapshot sem textSegments (produzido pela CHROME-001): texto próprio antes dos filhos (comportamento anterior).
        HtmlSnapshot h = HtmlSnapshot.of("<a id=a href=#x>Ver <strong>detalhes</strong> do pedido</a>");
        com.nekkora.extension.core.snapshot.RawSnapshot snap = h.capture();
        java.util.List<com.nekkora.extension.core.snapshot.RawNode> nodes = new java.util.ArrayList<>();
        for (com.nekkora.extension.core.snapshot.RawNode n : snap.nodes()) {
            nodes.add(new com.nekkora.extension.core.snapshot.RawNode(n.id(), n.parentId(), n.childIds(), n.tag(), n.frameId(), n.scopeId(),
                    n.attributes(), n.ownText(), n.state(), n.rect(), n.labelIds(), n.scroll(), n.exposedValue()));
        }
        com.nekkora.extension.core.snapshot.RawSnapshot legacy = new com.nekkora.extension.core.snapshot.RawSnapshot(snap.captureId(),
                snap.capturedAt(), snap.document(), snap.frames(), snap.shadowRoots(), nodes, snap.requests(), snap.stats());
        PageAnalysis a = Engine.analyze(legacy);
        assertEquals("Ver do pedido detalhes", a.semantics().get(h.idOf("#a")).name().name());
    }

    @Test
    void confidenceBySource() {
        assertEquals("MEDIUM", name("<button id=b>Ok</button>", "#b").confidence());
        assertEquals("LOW", name("<input id=b placeholder=Buscar>", "#b").confidence());
    }
}
