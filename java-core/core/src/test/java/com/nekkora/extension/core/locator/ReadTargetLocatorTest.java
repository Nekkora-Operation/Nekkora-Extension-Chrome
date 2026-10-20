package com.nekkora.extension.core.locator;

import com.nekkora.extension.core.analysis.PageAnalysis;
import com.nekkora.extension.core.testing.Engine;
import com.nekkora.extension.core.testing.HtmlSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NEKKORA-E2E-002 — alvos de LEITURA (texto não interativo) e campo de busca recriado, em páginas de referência
 * genéricas (pessoas fictícias): a célula de dado é identificada pelo CABEÇALHO DA LINHA (relação th → td e dt → dd,
 * texto do vocabulário), nunca pela posição; o campo de busca dentro do cabeçalho da página se distingue da cópia oculta
 * do cabeçalho fixo pelo contêiner semântico, sem cadeia posicional.
 */
class ReadTargetLocatorTest {

    private static final String FICHA = """
            <main><h1 id="firstHeading">Pessoa Fictícia</h1>
            <table class="quadro"><tbody>
              <tr><th colspan="2">Pessoa Fictícia</th></tr>
              <tr><th scope="row">Nome completo</th><td>Pessoa Fictícia de Tal</td></tr>
              <tr><th scope="row">Nascimento</th><td><div class="d"><a href="/d/1">21 de junho</a> de <a href="/a/1">1839</a></div><br><div>Cidade</div></td></tr>
              <tr><th scope="row">Morte</th><td>29 de setembro de 1908</td></tr>
              <tr><th scope="row">Cônjuge</th><td>Outra Pessoa</td></tr>
              <tr><th scope="row">Ocupação</th><td><div class="hlist"><ul><li>escritora</li><li>jornalista</li></ul></div></td></tr>
            </tbody></table>
            <dl><dt>Nacionalidade</dt><dd>brasileira</dd><dt>Situação</dt><dd>ativa</dd></dl>
            </main>
            """;

    private static LocatorSet locate(HtmlSnapshot h, org.jsoup.nodes.Element e) {
        PageAnalysis a = Engine.analyze(h);
        return a.locators().generate(h.idOf(e));
    }

    private static org.jsoup.nodes.Element cell(HtmlSnapshot h, String header) {
        return h.dom().select("tr").stream().filter(r -> r.selectFirst("> th") != null && r.selectFirst("> th").text().equals(header))
                .findFirst().orElseThrow().selectFirst("> td");
    }

    private static List<LocatorDescriptor> anchored(LocatorSet s, String text) {
        return s.all().stream().filter(d -> d.strategy() == LocatorStrategyKind.ROW_CONTEXT && d.xpath() != null
                && d.xpath().contains("th[normalize-space(.)=\"" + text + "\"]")).toList();
    }

    @Test
    void celulaDeDadoIdentificadaPeloCabecalhoDaLinha() {
        HtmlSnapshot h = HtmlSnapshot.of(FICHA);
        for (String header : List.of("Nome completo", "Nascimento")) {
            LocatorSet s = locate(h, cell(h, header));
            List<LocatorDescriptor> a = anchored(s, header);
            assertFalse(a.isEmpty(), header + ": sem localizador pelo cabeçalho da linha " + s.all());
            assertEquals(1, a.get(0).matchCount(), a.toString());
            assertTrue(a.get(0).xpath().endsWith("\"]]//td") && !a.get(0).xpath().matches(".*\\[\\d+].*"), a.get(0).xpath());
            assertNotEquals(LocatorStrategyKind.POSITIONAL, s.primary().strategy(), header + " → " + s.primary());
            assertEquals(a.get(0).xpath(), s.primary().xpath(), "o cabeçalho da linha é o localizador preferido do dado");
        }
    }

    @Test
    void termoDeDescricaoAncoraODd() {
        HtmlSnapshot h = HtmlSnapshot.of(FICHA);
        LocatorSet s = locate(h, h.dom().select("dd").get(1));
        List<LocatorDescriptor> a = s.all().stream().filter(d -> d.xpath() != null && d.xpath().contains("normalize-space(.)=\"Situação\"") && d.xpath().contains("dt")).toList();
        assertFalse(a.isEmpty(), s.all().toString());
        assertEquals(1, a.get(0).matchCount());
        assertNotEquals(LocatorStrategyKind.POSITIONAL, s.primary().strategy());
    }

    @Test
    void cabecalhoForaDoVocabularioNuncaSaiComoAncora() {
        HtmlSnapshot h = HtmlSnapshot.of(FICHA);
        LocatorSet s = locate(h, cell(h, "Cônjuge"));
        assertTrue(s.all().stream().noneMatch(d -> String.valueOf(d.xpath()).contains("Cônjuge") || String.valueOf(d.css()).contains("Cônjuge")
                || String.valueOf(d.semantic()).contains("Cônjuge")), s.all().toString());
    }

    @Test
    void cabecalhoRepetidoNaoViraAlvoUnico() {
        HtmlSnapshot h = HtmlSnapshot.of("""
                <table><tr><th scope="row">Nome completo</th><td>A</td></tr><tr><th scope="row">Nome completo</th><td>B</td></tr></table>
                """);
        LocatorSet s = locate(h, h.dom().select("td").get(1));
        for (LocatorDescriptor d : anchored(s, "Nome completo")) {
            assertEquals(2, d.matchCount());
            assertFalse(d.unique());
        }
        assertFalse(anchored(s, "Nome completo").stream().anyMatch(d -> d == s.primary() && d.unique()));
    }

    @Test
    void tituloComIdEstavel() {
        HtmlSnapshot h = HtmlSnapshot.of(FICHA);
        LocatorSet s = locate(h, h.dom().selectFirst("h1"));
        assertEquals(LocatorStrategyKind.ID, s.primary().strategy());
        assertTrue(s.primary().unique());
    }

    @Test
    void buscaDoCabecalhoSeDistingueDaCopiaOcultaSemPosicao() {
        HtmlSnapshot h = HtmlSnapshot.of("""
                <div class="pagina">
                  <div class="topo"><header class="cab-x"><div class="fim">
                    <div id="p-search" role="search"><form id="searchform" action="/w/index.php">
                      <div class="cx"><input type="search" name="search" role="combobox" aria-label="Pesquisar no acervo"></div>
                      <button>Procurar</button></form></div></div></header></div>
                  <main><header class="corpo"><h1 id="firstHeading">Pessoa</h1></header></main>
                  <div id="cab-fixo" style="display:none"><div role="search"><form id="sticky-search-form">
                      <div class="cx"><input type="search" name="search" role="combobox"></div></form></div></div>
                </div>
                """);
        LocatorSet s = locate(h, h.dom().select("input").get(0));
        assertNotEquals(LocatorStrategyKind.POSITIONAL, s.primary().strategy(), s.all().toString());
        assertTrue(s.primary().unique(), s.primary().toString());
        assertFalse(String.valueOf(s.primary().css()).contains("nth"), s.primary().toString());
    }
}
