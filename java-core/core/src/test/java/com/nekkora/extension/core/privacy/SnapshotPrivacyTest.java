package com.nekkora.extension.core.privacy;

import com.nekkora.extension.core.analysis.PageAnalysis;
import com.nekkora.extension.core.analysis.PageAnalyzer;
import com.nekkora.extension.core.locator.LocatorDescriptor;
import com.nekkora.extension.core.mutation.SnapshotDelta;
import com.nekkora.extension.core.snapshot.RawNode;
import com.nekkora.extension.core.snapshot.RawSnapshot;
import com.nekkora.extension.core.testing.HtmlSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CHROME-SECURITY-001 — 3ª barreira ({@link SnapshotPrivacy}): um produtor ANTIGO ou hostil (texto cru, atributos crus,
 * valor de pesquisa) não faz texto da página entrar no motor. O snapshot de teste vem de HTML, como o de uma extensão
 * anterior à política nekkora-privacy/2.
 */
class SnapshotPrivacyTest {

    private static final String PAGE = """
            <main>
              <h1>Cadastro de clientes</h1>
              <p id=aviso>o cliente joão já existe</p>
              <p id=ativo>Ativo</p>
              <div id=misto>Cliente Severino Pitanguy <button id=consultar-misto type=button>Consultar</button></div>
              <button id=consultar aria-label="Consultar cliente Bartolomeu Quixadá">Consultar</button>
              <button id=salvar class="btn btn-primary cliente-rosamunda-teixeira" data-testid=btn-salvar>Salvar</button>
              <a id=perfil href="/clientes/cassiano-bonfim?id=55821#ficha">Perfil de Cassiano Bonfim</a>
              <label for=nome>Nome</label> <input id=nome name=usuario_hermengarda placeholder="ex.: Filomena Zanatta">
              <span id=rot>telefone do cliente Godofredo Ubirajara</span> <input id=fone type=tel aria-labelledby=rot>
              <span id=rot-ok>Telefone</span> <input id=fone2 type=tel aria-labelledby=rot-ok>
              <label>Estado <select id=estado><option>São Paulo</option><option selected>Zéfiro Quintanilha</option></select></label>
              <table id=carteira><tr data-cliente="Eustáquio Marangoni"><td>Wenceslau Barbacena</td><td>Ativo</td>
                <td><button type=button class=ver>Visualizar</button></td></tr></table>
              <div role=alert>Pedido de Ximena Tavolaro aprovado</div>
              <button type=button><svg><title>Excluir Doralice Mondego</title></svg></button>
              <input id=pesquisa type=search name=q>
            </main>""";

    private static final RawSnapshot RAW = HtmlSnapshot.of(PAGE).url("http://127.0.0.1:8765/clientes/joao-oliveira?nome=Joao#aba").capture();

    private static RawSnapshot sanitized() {
        return SnapshotPrivacy.apply(RAW);
    }

    /** O nó com o atributo na PÁGINA (snapshot cru), no snapshot filtrado — pelo id temporário de nó, que não muda. */
    private static RawNode byAttr(RawSnapshot s, String attr, String value) {
        String nodeId = RAW.nodes().stream().filter(n -> value.equals(n.attr(attr))).findFirst()
                .orElseThrow(() -> new AssertionError(attr + "=" + value)).id();
        return s.nodes().stream().filter(n -> n.id().equals(nodeId)).findFirst().orElseThrow();
    }

    private static RawNode first(RawSnapshot s, Predicate<RawNode> p) {
        return s.nodes().stream().filter(p).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("SEC001-001/022/037/048 — texto fora de portador de nome (parágrafo, célula, alerta) vira presença, nunca conteúdo — mesmo 'Ativo', do vocabulário")
    void freeTextBecomesPresence() {
        RawSnapshot s = sanitized();
        RawNode aviso = byAttr(s, "id", "aviso");
        assertEquals("", aviso.ownText());
        assertTrue(aviso.hasText(), "a estrutura sabe que há texto");
        assertEquals(Boolean.TRUE, aviso.textSuppressed());
        assertEquals("", byAttr(s, "id", "ativo").ownText(), "conteúdo de negócio não passa por ser palavra do vocabulário");
        assertTrue(s.nodes().stream().filter(n -> n.tag().equals("td")).allMatch(n -> n.ownText().isEmpty()), "células de tabela");
        assertEquals("", first(s, n -> "alert".equals(n.attr("role"))).ownText(), "mensagem");
        assertEquals("", first(s, n -> n.tag().equals("h1")).ownText(), "título de seção é texto livre");
    }

    @Test
    @DisplayName("SEC001-002/013/014/021/024 — portador de nome: passa só se o texto INTEIRO for do vocabulário")
    void carriersNeedWholeVocabulary() {
        RawSnapshot s = sanitized();
        assertEquals("Salvar", byAttr(s, "data-testid", "btn-salvar").ownText());
        assertEquals("Consultar", byAttr(s, "id", "consultar").ownText(), "o conteúdo do botão passa; o aria-label não");
        // a fonte de nome retida fica PRESENTE e marcada (o valor nunca): o motor não pode recorrer ao conteúdo
        assertEquals(CapturePrivacyPolicy.HOLE, byAttr(s, "id", "consultar").attr("aria-label"));
        assertEquals("", byAttr(s, "id", "perfil").ownText(), "link 'Perfil de Cassiano Bonfim'");
        assertEquals("Visualizar", first(s, n -> "ver".equals(n.attr("class"))).ownText(), "botão dentro de célula é controle");
        assertEquals("", first(s, n -> n.tag().equals("svg")).ownText(), "título de SVG com nome de pessoa");
        RawNode misto = byAttr(s, "id", "misto");
        assertEquals("", misto.ownText(), "texto do div misto não é nome");
        assertNull(misto.textSegments());
        assertEquals("Nome", first(s, n -> n.tag().equals("label") && "Nome".equals(n.ownText())).ownText());
        RawNode estadoLabel = first(s, n -> n.tag().equals("label") && n.childIds().stream().anyMatch(c -> c.equals(byAttr(s, "id", "estado").id())));
        assertEquals("Estado", estadoLabel.ownText(), "rótulo com lista: as opções (valor) não entram no texto do portador");
        assertTrue(s.nodes().stream().filter(n -> n.tag().equals("option")).allMatch(n -> n.ownText().isEmpty()), "opções nunca");
    }

    @Test
    @DisplayName("SEC001-014 — alvo de aria-labelledby é portador: 'Telefone' passa, 'telefone do cliente …' não")
    void labelledByTargets() {
        RawSnapshot s = sanitized();
        assertEquals("", byAttr(s, "id", "rot").ownText());
        assertTrue(s.nodes().stream().anyMatch(n -> "Telefone".equals(n.ownText())));
    }

    @Test
    @DisplayName("SEC001-087 — fonte de nome retida não é substituída por uma fonte de prioridade menor (nome não inventado)")
    void withheldNameSourceIsNotReplaced() {
        RawSnapshot s = sanitized();
        PageAnalysis a = new PageAnalyzer().analyze(s);
        RawNode consultar = byAttr(s, "id", "consultar");
        var name = a.semantics().get(consultar.id()).name();
        // aria-label "Consultar cliente Bartolomeu Quixadá" (retido) é o nome real; o conteúdo "Consultar" NÃO é o nome
        assertEquals("", name.name(), "nome retido inteiro: " + name);
        assertEquals(com.nekkora.extension.core.semantic.NameSource.ARIA_LABEL, name.source());
    }

    @Test
    @DisplayName("SEC001-015..020/081 — atributos por categoria: ids, name, classes, data-*, placeholder; exposedValue nunca")
    void attributes() {
        RawSnapshot s = sanitized();
        RawNode nome = byAttr(s, "id", "nome");
        assertNull(nome.attr("name"), "usuario_hermengarda");
        assertEquals(CapturePrivacyPolicy.HOLE, nome.attr("placeholder"), "ex.: Filomena Zanatta (presença marcada, valor retido)");
        assertEquals("btn btn-primary", byAttr(s, "data-testid", "btn-salvar").attr("class"));
        RawNode linha = first(s, n -> n.tag().equals("tr"));
        assertEquals("", linha.attr("data-cliente"), "presença do atributo; valor nunca");
        assertEquals("", byAttr(s, "id", "perfil").attr("href"), "presença do link; endereço nunca");
        assertEquals("", byAttr(s, "id", "fone").attr("aria-labelledby"), "relação sem o valor do id");
        assertTrue(s.nodes().stream().noneMatch(n -> n.exposedValue() != null));
        assertFalse(s.toString().contains("Quixad") || s.toString().contains("hermengarda") || s.toString().contains("Marangoni")
                || s.toString().contains("Pitanguy") || s.toString().contains("Tavolaro") || s.toString().contains("Quintanilha")
                || s.toString().contains("Mondego") || s.toString().contains("Zanatta") || s.toString().contains("rosamunda"), s.toString());
    }

    @Test
    @DisplayName("SEC001-049/086 — URL do documento como modelo; título só se for do vocabulário")
    void documentAndUrl() {
        RawSnapshot s = sanitized();
        assertEquals("http://127.0.0.1:8765/clientes/" + CapturePrivacyPolicy.HOLE + "?nome=" + CapturePrivacyPolicy.HOLE, s.document().url());
        assertFalse(s.toString().contains("oliveira"));
        assertFalse(s.toString().contains("55821"));
    }

    @Test
    @DisplayName("SEC001-065 — idempotente: aplicar de novo (snapshot já filtrado pela página) não muda nada")
    void idempotent() {
        RawSnapshot once = sanitized();
        RawSnapshot twice = SnapshotPrivacy.apply(once);
        for (int i = 0; i < once.nodes().size(); i++) {
            assertEquals(once.nodes().get(i), twice.nodes().get(i), "nó " + once.nodes().get(i).id());
        }
        assertEquals(once.document(), twice.document());
    }

    @Test
    @DisplayName("SEC001-029/030 — delta: texto inserido depois da captura segue a mesma regra, com o contexto vindo da base")
    void deltaUsesBaseContext() {
        HtmlSnapshot h = HtmlSnapshot.of("<main><p id=c>Cliente não informado</p><button id=b>Carregar</button></main>");
        RawSnapshot base = SnapshotPrivacy.apply(h.capture());
        h.dom().selectFirst("#c").text("Cliente Ulisses Paranhos");
        h.dom().selectFirst("#b").text("Carregar mais");
        RawSnapshot after = h.capture();
        List<RawNode> changed = after.nodes().stream().filter(n -> "c".equals(n.attr("id")) || "b".equals(n.attr("id"))).toList();
        String cId = changed.stream().filter(n -> "c".equals(n.attr("id"))).findFirst().orElseThrow().id();
        String bId = changed.stream().filter(n -> "b".equals(n.attr("id"))).findFirst().orElseThrow().id();
        SnapshotDelta raw = new SnapshotDelta(base.captureId(), "cap-2", after.capturedAt(), after.document(), changed, List.of(), null, null,
                List.of(), 2, 1, after.stats());
        Map<String, RawNode> baseIdx = new java.util.HashMap<>();
        base.nodes().forEach(n -> baseIdx.put(n.id(), n));
        SnapshotDelta d = SnapshotPrivacy.apply(raw, baseIdx::get);
        assertEquals("", d.upserts().stream().filter(n -> n.id().equals(cId)).findFirst().orElseThrow().ownText());
        assertEquals("Carregar mais", d.upserts().stream().filter(n -> n.id().equals(bId)).findFirst().orElseThrow().ownText());
        assertFalse(d.toString().contains("Paranhos"));
    }

    @Test
    @DisplayName("SEC001-078 — sem texto, a resolução não inventa identidade: trocar o campo por um botão não confirma OUTRO campo")
    void resolutionNeverInventsIdentity() {
        // NegativeCasesIT.caseC no Chrome real: o rótulo "E-mail de contato" e o id "email-contato" ficam fora do vocabulário;
        // só sobrava um texto de contexto, e o campo "Nome" era confirmado no lugar do e-mail
        HtmlSnapshot h = HtmlSnapshot.of("""
                <form id=cadastro><label for=campo-principal>Nome</label><input id=campo-principal name=campoPrincipal>
                <label for=email-contato>E-mail de contato</label><input id=email-contato type=email name=emailContato></form>""");
        var before = com.nekkora.extension.core.testing.Engine.analyze(SnapshotPrivacy.apply(h.capture()));
        String email = h.idOf(h.dom().selectFirst("#email-contato"));
        var id = com.nekkora.extension.core.testing.Engine.register(before, email);
        h.dom().selectFirst("#email-contato").replaceWith(org.jsoup.Jsoup.parse("<button type=button id=email-contato>Enviar e-mail</button>")
                .selectFirst("button"));
        var r = com.nekkora.extension.core.testing.Engine.resolve(id, com.nekkora.extension.core.testing.Engine.analyze(SnapshotPrivacy.apply(h.capture())));
        String other = h.idOf(h.dom().selectFirst("#campo-principal"));
        assertFalse(other.equals(r.resolvedNodeId()), "o campo Nome NÃO pode ser confirmado como o alvo: " + r.evidence());
        assertFalse(r.status() == com.nekkora.extension.core.resolve.ResolutionStatus.RESOLVED, r.status() + " " + r.evidence());
        // o id do alvo continua no vocabulário? não ("email-contato"); sem endereço forte, o resultado é a falta de prova
        assertTrue(java.util.Set.of(com.nekkora.extension.core.resolve.ResolutionStatus.IDENTITY_MISMATCH,
                com.nekkora.extension.core.resolve.ResolutionStatus.INSUFFICIENT_EVIDENCE, com.nekkora.extension.core.resolve.ResolutionStatus.AMBIGUOUS,
                com.nekkora.extension.core.resolve.ResolutionStatus.NOT_FOUND).contains(r.status()), r.status().toString());
    }

    @Test
    @DisplayName("SEC001-044/074 — o motor continua localizando: localizadores do snapshot filtrado só têm literais transportáveis")
    void engineStillLocatesSafely() {
        PageAnalysis a = new PageAnalyzer().analyze(sanitized());
        int interactive = 0;
        for (RawNode n : a.model().inDocumentOrder()) {
            var c = a.semantics().get(n.id());
            if (c == null || c.category() != com.nekkora.extension.core.semantic.ElementCategory.INTERACTIVE) {
                continue;
            }
            interactive++;
            for (LocatorDescriptor l : a.locators().generate(n.id()).all()) {
                assertTrue(CapturePrivacyPolicy.expressionSafe(l.css()) && CapturePrivacyPolicy.expressionSafe(l.xpath())
                        && CapturePrivacyPolicy.expressionSafe(l.semantic()), "localizador transportável: " + l.semantic() + " | " + l.css() + " | " + l.xpath());
            }
            assertFalse(c.name().name().contains("Quixad") || c.name().name().contains("Ubirajara"), c.name().name());
        }
        assertTrue(interactive >= 8, "controles continuam classificados: " + interactive);
    }
}
