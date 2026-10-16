package com.nekkora.extension.core.locator;

import com.nekkora.extension.core.analysis.PageAnalysis;
import com.nekkora.extension.core.testing.Engine;
import com.nekkora.extension.core.testing.Fixtures;
import com.nekkora.extension.core.testing.HtmlSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocatorEngineTest {

    private static LocatorSet locate(HtmlSnapshot h, String css) {
        PageAnalysis a = Engine.analyze(h);
        return a.locators().generate(h.idOf(css));
    }

    @Test
    void stableIdIsPrimary() { // T001
        LocatorSet s = locate(HtmlSnapshot.of("<label for=customer-name>Nome</label><input id=customer-name name=nome>"), "#customer-name");
        assertEquals(LocatorStrategyKind.ID, s.primary().strategy());
        assertEquals("input#customer-name", s.primary().css());
        assertEquals("//input[@id=\"customer-name\"]", s.primary().xpath());
        assertEquals(1, s.primary().matchCount());
        assertEquals(StabilityLevel.HEURISTIC_STABLE, s.primary().stability());
        assertEquals("HEURISTIC", s.primary().stabilityBasis());
        assertTrue(s.fallbacks().size() >= 3, "deve oferecer alternativas");
        assertEquals("textbox", s.primary().expected().role());
        assertEquals("Nome", s.primary().expected().name());
    }

    @Test
    void inputWithoutIdGetsAlternative() { // T002
        LocatorSet s = locate(HtmlSnapshot.of("<form><label>E-mail <input type=email name=email></label></form>"), "input");
        assertTrue(s.primary().unique());
        assertNotEquals(LocatorStrategyKind.POSITIONAL, s.primary().strategy());
        assertTrue(s.all().stream().anyMatch(d -> d.strategy() == LocatorStrategyKind.NAME));
        assertTrue(s.all().stream().anyMatch(d -> d.strategy() == LocatorStrategyKind.LABEL));
    }

    @Test
    void inputWithoutNameGetsContextualLocator() { // T003
        HtmlSnapshot h = HtmlSnapshot.of("""
                <form id="a"><fieldset><legend>Cobrança</legend><input placeholder="Cidade"></fieldset></form>
                <form id="b"><fieldset><legend>Entrega</legend><input placeholder="Cidade"></fieldset></form>
                """);
        PageAnalysis a = Engine.analyze(h);
        LocatorSet s = a.locators().generate(h.idOf(h.dom().select("input").get(1)));
        // CHROME-SECURITY-001 (P1-02): "#b" não é identificador do vocabulário — o contêiner não pode ser citado pelo id;
        // existe localizador ÚNICO só com o que a política permite; o principal ambíguo é DECLARADO (não inventado)
        assertTrue(s.all().stream().anyMatch(LocatorDescriptor::unique), s.all().toString());
        assertTrue(s.primary().unique() || s.primary().warnings().stream().anyMatch(w -> w.startsWith("AMBIGUOUS_MATCH")), s.primary().toString());
        assertTrue(s.all().stream().noneMatch(d -> String.valueOf(d.css()).contains("#b")), s.all().toString());
        // a estratégia CONTEXTUAL continua funcionando quando o contêiner tem identificador do vocabulário
        HtmlSnapshot safe = HtmlSnapshot.of("""
                <form id="cliente"><fieldset><legend>Cobrança</legend><input placeholder="Cidade"></fieldset></form>
                <form id="pagamento"><fieldset><legend>Entrega</legend><input placeholder="Cidade"></fieldset></form>
                """);
        LocatorSet t = Engine.analyze(safe).locators().generate(safe.idOf(safe.dom().select("input").get(1)));
        assertTrue(t.primary().unique(), t.primary().toString());
        assertEquals(LocatorStrategyKind.CONTEXTUAL, t.primary().strategy());
        assertTrue(t.primary().css().contains("#pagamento"), t.primary().css());
    }

    @Test
    void duplicatedIdIsDisambiguatedByFamily() { // T004 / T007
        HtmlSnapshot h = HtmlSnapshot.of(Fixtures.DUPLICATED_TABLES);
        PageAnalysis a = Engine.analyze(h);
        LocatorSet s = a.locators().generate(h.idOf(h.dom().select("button").get(1)));
        LocatorDescriptor byId = s.all().stream().filter(d -> d.strategy() == LocatorStrategyKind.ID).findFirst().orElseThrow();
        assertEquals(2, byId.matchCount());
        assertTrue(byId.warnings().stream().anyMatch(w -> w.startsWith("DUPLICATE_ID")));
        assertTrue(s.primary().unique());
        // CHROME-SECURITY-001 (P1-02): a chave "202" não é transportável; a família é distinguida pela tabela do vocabulário
        assertTrue(java.util.Set.of(LocatorStrategyKind.ROW_CONTEXT, LocatorStrategyKind.CONTEXTUAL).contains(s.primary().strategy()),
                s.primary().toString());
        assertTrue(s.primary().css().contains("clientes-inativos") && !s.primary().css().contains("202"), s.primary().css());
        assertTrue(s.primary().score() > byId.score());
    }

    @Test
    void sameIdDifferentRowsUsesRowKey() { // T008
        HtmlSnapshot h = HtmlSnapshot.of(Fixtures.SAME_TABLE_ROWS);
        PageAnalysis a = Engine.analyze(h);
        LocatorSet s = a.locators().generate(h.idOf(h.dom().select("button").get(1)));
        // CHROME-SECURITY-001 (P1-02): VALOR numérico "202" da chave de linha não vira localizador; o resultado continua único
        assertEquals(1, s.primary().matchCount());
        assertFalse(String.valueOf(s.primary().css()).contains("202") || String.valueOf(s.primary().xpath()).contains("202"), s.primary().toString());
        // ROW_CONTEXT continua funcionando quando a chave da linha é um valor permitido pela política
        HtmlSnapshot safe = HtmlSnapshot.of("""
                <table id="clientes"><tbody>
                <tr data-client="ativo"><td>A</td><td><button id="excluir">Excluir</button></td></tr>
                <tr data-client="pendente"><td>B</td><td><button id="excluir">Excluir</button></td></tr>
                </tbody></table>
                """);
        LocatorSet t = Engine.analyze(safe).locators().generate(safe.idOf(safe.dom().select("button").get(1)));
        assertEquals(LocatorStrategyKind.ROW_CONTEXT, t.primary().strategy(), t.primary().toString());
        assertEquals("table#clientes tr[data-client=\"pendente\"] button#excluir", t.primary().css());
        assertEquals(1, t.primary().matchCount());
    }

    @Test
    void dynamicIdHasReducedStability() { // T005 (heurística)
        HtmlSnapshot h = HtmlSnapshot.of(Fixtures.dynamicId("18731"));
        PageAnalysis a = Engine.analyze(h);
        LocatorSet s = a.locators().generate(h.idOf("input"));
        // CHROME-SECURITY-001 (P1-02): id com sufixo numérico longo nem chega a ser localizador (valor não transportável)
        assertTrue(s.all().stream().noneMatch(d -> d.strategy() == LocatorStrategyKind.ID), s.all().toString());
        assertNotEquals(LocatorStrategyKind.ID, s.primary().strategy(), "id instável não deve ser o principal");
    }

    @Test
    void observedStabilityPromotesOrDemotes() {
        HtmlSnapshot h = HtmlSnapshot.of("<input id=campo-nome name=nome>");
        PageAnalysis a = Engine.analyze(h);
        String id = h.idOf("input");
        LocatorSet stable = a.locators().generate(id, StabilityObservations.of(java.util.Map.of("id", java.util.List.of("campo-nome", "campo-nome", "campo-nome"))));
        assertEquals(StabilityLevel.OBSERVED_STABLE, stable.all().stream().filter(d -> d.strategy() == LocatorStrategyKind.ID).findFirst().orElseThrow().stability());
        LocatorSet unstable = a.locators().generate(id, StabilityObservations.of(java.util.Map.of("id", java.util.List.of("x-1", "campo-nome"))));
        LocatorDescriptor d = unstable.all().stream().filter(x -> x.strategy() == LocatorStrategyKind.ID).findFirst().orElseThrow();
        assertEquals(StabilityLevel.OBSERVED_UNSTABLE, d.stability());
        assertEquals("OBSERVED", d.stabilityBasis());
    }

    @Test
    void duplicatedTextIsContextualized() { // T006
        HtmlSnapshot h = HtmlSnapshot.of(Fixtures.CARDS);
        PageAnalysis a = Engine.analyze(h);
        LocatorSet s = a.locators().generate(h.idOf(h.dom().select("button").get(1)));
        LocatorDescriptor text = s.all().stream().filter(d -> d.strategy() == LocatorStrategyKind.TEXT).findFirst().orElseThrow();
        assertEquals(2, text.matchCount());
        // CHROME-SECURITY-001: "Cliente Maria" (título de cartão) não é metadado do vocabulário — não entra no localizador
        assertTrue(s.all().stream().noneMatch(d -> (d.css() + " " + d.xpath() + " " + d.semantic()).contains("Maria")), s.all().toString());
        // contextualização por texto continua quando o texto de contexto é do vocabulário
        HtmlSnapshot safe = HtmlSnapshot.of("""
                <div class="card"><h3>Clientes ativos</h3><button>Remover</button></div>
                <div class="card"><h3>Clientes inativos</h3><button>Remover</button></div>
                """);
        LocatorSet t = Engine.analyze(safe).locators().generate(safe.idOf(safe.dom().select("button").get(1)));
        assertTrue(t.primary().unique(), t.primary().toString());
        assertNotNull(t.primary().xpath());
        assertTrue(t.primary().xpath().contains("Clientes inativos"), t.primary().xpath());
    }

    @Test
    void positionalIsLastResortAndFlagged() {
        HtmlSnapshot h = HtmlSnapshot.of("<div><span>a</span><span>b</span></div>");
        PageAnalysis a = Engine.analyze(h);
        LocatorSet s = a.locators().generate(h.idOf(h.dom().select("span").get(1)));
        LocatorDescriptor pos = s.all().stream().filter(d -> d.strategy() == LocatorStrategyKind.POSITIONAL).findFirst().orElseThrow();
        assertTrue(pos.warnings().contains("POSITIONAL_DEPENDENCY"));
        assertEquals(StabilityLevel.HEURISTIC_UNSTABLE, pos.stability());
        assertEquals("html > body > div:nth-of-type(1) > span:nth-of-type(2)", pos.css());
    }

    @Test
    void indistinguishableElementsYieldNoUniqueLocatorWarning() {
        HtmlSnapshot h = HtmlSnapshot.of("<div><button>Ok</button><button>Ok</button></div>");
        PageAnalysis a = Engine.analyze(h);
        LocatorSet s = a.locators().generate(h.idOf(h.dom().select("button").get(0)));
        // O único localizador único é posicional: deve existir, mas o conjunto não é considerado confiável.
        assertFalse(s.all().stream().filter(LocatorDescriptor::unique)
                .anyMatch(d -> d.strategy() != LocatorStrategyKind.POSITIONAL && d.strategy() != LocatorStrategyKind.XPATH_RELATIVE));
    }

    @Test
    void scopedLocatorsForShadowAndFrames() {
        HtmlSnapshot h = HtmlSnapshot.of("""
                <nk-card id="host"><template shadowrootmode="open"><button id="sb">Interno</button></template></nk-card>
                <iframe id="fr" data-fixture-frame="a"></iframe>
                """).frame("a", "<button id='fb'>No frame</button>", true, null);
        PageAnalysis a = Engine.analyze(h);
        String sb = a.semantics().all().stream().filter(c -> c.name().name().equals("Interno")).findFirst().orElseThrow().nodeId();
        LocatorSet s = a.locators().generate(sb);
        assertEquals("SHADOW_ROOT", s.primary().scope().type());
        // CHROME-SECURITY-001: "host" não é identificador do vocabulário — o hospedeiro é citado só pelo que a política permite
        assertFalse(s.primary().scope().chain().get(0).css().contains("#host"), s.primary().scope().chain().get(0).css());
        assertTrue(s.primary().scope().chain().get(0).css().contains("nk-card"));
        assertEquals(null, s.primary().xpath(), "XPath não atravessa shadow root");
        String fb = a.semantics().all().stream().filter(c -> c.name().name().equals("No frame")).findFirst().orElseThrow().nodeId();
        LocatorSet f = a.locators().generate(fb);
        assertEquals("FRAME", f.primary().scope().type());
        assertTrue(f.primary().scope().chain().get(0).css().contains("iframe") && !f.primary().scope().chain().get(0).css().contains("#fr"),
                f.primary().scope().chain().get(0).css());
    }
}
