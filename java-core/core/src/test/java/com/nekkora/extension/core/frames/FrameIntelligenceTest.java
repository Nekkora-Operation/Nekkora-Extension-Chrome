package com.nekkora.extension.core.frames;

import com.nekkora.extension.core.analysis.PageAnalysis;
import com.nekkora.extension.core.identity.TargetIdentity;
import com.nekkora.extension.core.resolve.ResolutionResult;
import com.nekkora.extension.core.resolve.ResolutionStatus;
import com.nekkora.extension.core.testing.Engine;
import com.nekkora.extension.core.testing.HtmlSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Frame Intelligence Engine, grafo documental, trajetória e reencontro entre documentos (CHROME-003). */
class FrameIntelligenceTest {

    private final FrameGraphBuilder builder = new FrameGraphBuilder();
    private final AccessPathBuilder paths = new AccessPathBuilder();

    private FrameGraph graph(PageAnalysis a) {
        return builder.build(a, List.of());
    }

    @Test
    void pageWithoutIframeHasOnlyTheMainDocument() { // F003-001
        PageAnalysis a = Engine.analyze(HtmlSnapshot.of("<h1>Início</h1><button id=b>Ok</button>"));
        FrameGraph g = graph(a);
        assertEquals(1, g.documents().size());
        assertEquals(1, g.frames().size());
        assertEquals(0, g.maxDepth());
        assertEquals("MAIN", g.frames().get(0).documentKind());
        assertEquals("doc:f0", g.mainDocumentId());
    }

    private static HtmlSnapshot financeiro() {
        return HtmlSnapshot.of("""
                <header>Portal</header><nav>Menu</nav>
                <iframe id=financeiro title="Sistema Financeiro" data-fixture-frame=fin src="/financeiro.html"></iframe>""")
                .frame("fin", """
                        <title>Financeiro</title><nav>Menu interno</nav>
                        <iframe name=contas title="Contas a pagar" data-fixture-frame=contas src="/contas.html"></iframe>""", true, null,
                        "http://127.0.0.1:8765/financeiro.html")
                .frame("contas", """
                        <title>Contas</title><section id=filtros>Filtros</section>
                        <iframe name=formulario title="Formulário" data-fixture-frame=form src="/form.html"></iframe>""", true, null,
                        "http://127.0.0.1:8765/contas.html")
                .frame("form", """
                        <title>Pagamento</title>
                        <payment-component id=pc><template shadowrootmode="open">
                          <form id=pagamento aria-label="Pagamento"><button id=confirmar>Confirmar pagamento</button></form>
                        </template></payment-component>""", true, null, "http://127.0.0.1:8765/form.html");
    }

    @Test
    void threeNestedLevelsWithShadowDomProduceACompositeAccessPath() { // F003-004, F003-008, cenário C
        HtmlSnapshot h = financeiro();
        PageAnalysis a = Engine.analyze(h);
        FrameGraph g = graph(a);
        assertEquals(4, g.documents().size());
        assertEquals(3, g.maxDepth());
        assertTrue(g.frames().stream().allMatch(f -> "INSPECTED".equals(f.status())));
        String button = a.model().inDocumentOrder().stream().filter(n -> "confirmar".equals(n.attr("id"))).findFirst().orElseThrow().id();
        ElementAccessPath p = paths.build(a, g, button);
        assertEquals(List.of("MAIN_DOCUMENT", "IFRAME", "IFRAME", "IFRAME", "SHADOW_ROOT", "ELEMENT"),
                p.steps().stream().map(ElementAccessPath.Step::type).toList());
        assertEquals(4, p.documentsTraversed());
        assertEquals(3, p.frameDepth());
        assertEquals(1, p.shadowDepth());
        assertTrue(p.accessible());
        assertEquals("Sistema Financeiro", p.steps().get(1).label());
        assertEquals("Contas a pagar", p.steps().get(2).label());
        assertEquals("iframe#financeiro", p.steps().get(1).locator(), "localizador do iframe no escopo do documento pai");
        assertTrue(p.steps().get(2).locator().contains("contas") || p.steps().get(2).locator().contains("Contas a pagar"), p.steps().get(2).locator());
        for (ElementAccessPath.Step s : p.steps()) {
            assertTrue(s.locator() == null || !s.locator().contains(">>"), "nenhum localizador finge atravessar documentos");
        }
        assertEquals("doc:f0", p.steps().get(1).documentId());
        assertNotEquals(p.steps().get(1).documentId(), p.steps().get(2).documentId(), "cada passo executa no próprio documento");
        assertTrue(p.explanation().contains("3º nível de iframe"), p.explanation());
        assertTrue(p.explanation().contains("Sistema Financeiro") && p.explanation().contains("hierarquia documental"), p.explanation());
        assertEquals("Confirmar pagamento", p.target().name());
        var types = g.relations().stream().map(FrameGraph.Relation::type).distinct().toList();
        for (String t : List.of("DOCUMENT_CONTAINS_FRAME", "FRAME_HAS_DOCUMENT", "FRAME_PARENT_OF", "FRAME_CHILD_OF", "FRAME_HOSTED_BY_ELEMENT",
                "ELEMENT_BELONGS_TO_DOCUMENT", "ELEMENT_BELONGS_TO_FRAME", "SHADOW_ROOT_BELONGS_TO_DOCUMENT")) {
            assertTrue(types.contains(t), t + " em " + types); // §6
        }
        String host = h.idOf("#financeiro");
        assertTrue(g.relations().contains(new FrameGraph.Relation("ELEMENT_BELONGS_TO_DOCUMENT", host, "doc:f0")));
    }

    @Test
    void shadowDomContainingAnIframe() { // F003-009, cenário D
        HtmlSnapshot h = HtmlSnapshot.of("""
                <app-shell id=shell><template shadowrootmode="open">
                  <iframe id=interno title="Relatório" data-fixture-frame=r src="/r.html"></iframe>
                </template></app-shell>""").frame("r", "<button id=gerar>Gerar</button>", true, null);
        PageAnalysis a = Engine.analyze(h);
        FrameGraph g = graph(a);
        FrameDescriptor f = g.frames().stream().filter(x -> x.depth() == 1).findFirst().orElseThrow();
        assertTrue(f.insideShadowRoot());
        ElementAccessPath p = paths.build(a, g, h.idInFrame("r", "#gerar"));
        assertEquals(List.of("MAIN_DOCUMENT", "SHADOW_ROOT", "IFRAME", "ELEMENT"), p.steps().stream().map(ElementAccessPath.Step::type).toList());
    }

    @Test
    void identicalButtonsInSiblingFramesAreDistinctTargets() { // F003-005, F003-018, cenário E
        HtmlSnapshot h = HtmlSnapshot.of("""
                <iframe id=a title="Cobrança" data-fixture-frame=a></iframe>
                <iframe id=b title="Entrega" data-fixture-frame=b></iframe>""")
                .frame("a", "<form id=f><button id=salvar>Salvar</button></form>", true, null)
                .frame("b", "<form id=f><button id=salvar>Salvar</button></form>", true, null);
        PageAnalysis a = Engine.analyze(h);
        String inB = h.idInFrame("b", "#salvar");
        TargetIdentity id = Engine.register(a, inB);
        assertEquals(1, id.frameChain().size());
        assertEquals("b", id.frameChain().get(0).idAttr());
        ResolutionResult r = Engine.resolve(id, Engine.analyze(h.reload()));
        assertEquals(ResolutionStatus.RESOLVED, r.status(), r.evidence().toString());
        assertEquals(h.idInFrame("b", "#salvar"), r.resolvedNodeId(), "resolvido no frame correto");
        // id repetido em DOCUMENTOS diferentes não é id duplicado no escopo
        assertTrue(id.locators().primary().warnings().stream().noneMatch(w -> w.startsWith("DUPLICATE_ID")), id.locators().primary().warnings().toString());
    }

    @Test
    void framesWithSameNameAndUrlAreDisambiguatedByContainer() { // F003-006, F003-007, cenário F
        HtmlSnapshot h = HtmlSnapshot.of("""
                <section id=cobranca><h2>Cobrança</h2><iframe name=endereco data-fixture-frame=e1 src="/endereco.html"></iframe></section>
                <section id=entrega><h2>Entrega</h2><iframe name=endereco data-fixture-frame=e2 src="/endereco.html"></iframe></section>""")
                .frame("e1", "<input id=cep aria-label=CEP>", true, null, "http://127.0.0.1:8765/endereco.html")
                .frame("e2", "<input id=cep aria-label=CEP>", true, null, "http://127.0.0.1:8765/endereco.html");
        PageAnalysis a = Engine.analyze(h);
        FrameGraph g = graph(a);
        List<FrameDescriptor> frames = g.frames().stream().filter(f -> f.depth() == 1).toList();
        assertEquals(2, frames.size());
        assertNotEquals(frames.get(0).identity().container(), frames.get(1).identity().container());
        assertTrue(frames.stream().noneMatch(FrameDescriptor::identityAmbiguous));
        TargetIdentity id = Engine.register(a, h.idInFrame("e2", "#cep"));
        ResolutionResult r = Engine.resolve(id, Engine.analyze(h.reload()));
        assertEquals(ResolutionStatus.RESOLVED, r.status(), r.evidence() + " " + r.diagnostics());
        assertEquals(h.idInFrame("e2", "#cep"), r.resolvedNodeId(), "contexto (contêiner) correto");
    }

    @Test
    void indistinguishableFramesAreAmbiguousNeverTheFirstIndex() { // teste negativo A
        HtmlSnapshot h = HtmlSnapshot.of("""
                <div><iframe name=w data-fixture-frame=w1 src="/w.html"></iframe><iframe name=w data-fixture-frame=w2 src="/w.html"></iframe></div>""")
                .frame("w1", "<button>Enviar</button>", true, null, "http://127.0.0.1:8765/w.html")
                .frame("w2", "<button>Enviar</button>", true, null, "http://127.0.0.1:8765/w.html");
        PageAnalysis a = Engine.analyze(h);
        assertTrue(graph(a).frames().stream().filter(f -> f.depth() == 1).allMatch(FrameDescriptor::identityAmbiguous));
        TargetIdentity id = Engine.register(a, h.idInFrame("w1", "button"));
        ResolutionResult r = Engine.resolve(id, Engine.analyze(h.reload()));
        assertTrue(r.status() == ResolutionStatus.AMBIGUOUS || r.status() == ResolutionStatus.INSUFFICIENT_EVIDENCE,
                r.status() + " " + r.evidence());
        assertNull(r.resolvedNodeId(), "nenhum frame escolhido arbitrariamente");
        assertTrue(r.diagnostics().contains("FRAME_AMBIGUOUS"), r.diagnostics().toString());
        assertTrue(id.warnings().stream().anyMatch(w -> w.startsWith("INDISTINGUISHABLE_AT_REGISTRATION")),
                "frames equivalentes: o alvo já é indistinguível no registro " + id.warnings());
    }

    @Test
    void inaccessibleAndSandboxedFramesDeclareLimits() { // F003-012, F003-014, teste negativo C
        HtmlSnapshot h = HtmlSnapshot.of("""
                <iframe id=ext data-fixture-frame=x src="http://127.0.0.2:8765/x.html"></iframe>
                <iframe id=sb sandbox="allow-scripts" data-fixture-frame=s src="/s.html"></iframe>""")
                .frame("x", "<button>Secreto</button>", false, "CROSS_ORIGIN", "http://127.0.0.2:8765/x.html")
                .frame("s", "<button>Isolado</button>", false, "SANDBOXED");
        FrameGraph g = graph(Engine.analyze(h));
        FrameDescriptor ext = g.frames().stream().filter(f -> "ext".equals(f.identity() == null ? null : f.identity().idAttr())).findFirst().orElseThrow();
        FrameDescriptor sb = g.frames().stream().filter(f -> "sb".equals(f.identity() == null ? null : f.identity().idAttr())).findFirst().orElseThrow();
        assertEquals("PERMISSION_DENIED", ext.status());
        assertEquals(0, ext.elementCount(), "conteúdo nunca inventariado sem acesso");
        assertEquals("SANDBOX_RESTRICTED", sb.status());
        assertEquals("null", sb.origin(), "sandbox sem allow-same-origin: origem opaca");
        assertEquals(2, g.counts().get("inaccessible"));
        assertFalse(g.limitations().isEmpty());
    }

    @Test
    void aboutBlankSrcdocAndPendingLoadAreDistinguished() { // F003-015, F003-016, F003-020
        HtmlSnapshot h = HtmlSnapshot.of("""
                <iframe id=vazio data-fixture-frame=blank></iframe>
                <iframe id=doc srcdoc="&lt;p&gt;x&lt;/p&gt;" data-fixture-frame=srcdoc></iframe>
                <iframe id=lento src="/lento.html" data-fixture-frame=lento></iframe>""")
                .frame("blank", "<p id=escrito>Conteúdo escrito por script</p>", true, null, "about:blank")
                .frame("srcdoc", "<p id=inline>Inline</p>", true, null, "about:srcdoc")
                .frame("lento", "", true, null, "about:blank");
        PageAnalysis a = Engine.analyze(h);
        FrameGraph g = graph(a);
        assertEquals("ABOUT_BLANK", byId(g, "vazio").documentKind());
        assertEquals("INSPECTED", byId(g, "vazio").status());
        assertEquals("http://127.0.0.1:8765", byId(g, "vazio").origin(), "about:blank herda a origem do criador");
        assertEquals("SRCDOC", byId(g, "doc").documentKind());
        assertEquals("INITIAL_ABOUT_BLANK", byId(g, "lento").documentKind());
        assertEquals("LOADING", byId(g, "lento").status(), "carregamento pendente não é ausência");
        assertEquals("http://127.0.0.1:8765/lento.html", byId(g, "lento").declaredSrc());
    }

    @Test
    void targetInsideLoadingFrameIsNotDeclaredAbsent() { // F003-020, teste negativo F (frames)
        HtmlSnapshot ready = HtmlSnapshot.of("<iframe id=lento src=\"/lento.html\" data-fixture-frame=l></iframe>")
                .frame("l", "<button id=ok>Confirmar</button>", true, null, "http://127.0.0.1:8765/lento.html");
        TargetIdentity id = Engine.register(Engine.analyze(ready), ready.idInFrame("l", "#ok"));
        HtmlSnapshot loading = HtmlSnapshot.of("<iframe id=lento src=\"/lento.html\" data-fixture-frame=l></iframe>")
                .frame("l", "", true, null, "about:blank");
        ResolutionResult r = Engine.resolve(id, Engine.analyze(loading));
        assertEquals(ResolutionStatus.SCOPE_UNAVAILABLE, r.status(), r.evidence().toString());
        assertTrue(r.diagnostics().contains("FRAME_LOADING"), r.diagnostics().toString());
    }

    @Test
    void removedAndRecreatedFrames() { // F003-010, F003-011, testes negativos B e G
        HtmlSnapshot h = HtmlSnapshot.of("<iframe id=pag title=Pagamentos data-fixture-frame=p></iframe>")
                .frame("p", "<button id=pagar>Pagar</button>", true, null);
        TargetIdentity id = Engine.register(Engine.analyze(h), h.idInFrame("p", "#pagar"));
        // Removido: trajetória antiga inválida, falha explícita.
        ResolutionResult gone = Engine.resolve(id, Engine.analyze(HtmlSnapshot.of("<p>sem iframe</p>")));
        assertNotEquals(ResolutionStatus.RESOLVED, gone.status());
        assertTrue(gone.diagnostics().contains("FRAME_NOT_FOUND"), gone.diagnostics().toString());
        // Recriado (novo documento, novos nós): identidade validada e reencontro.
        HtmlSnapshot again = HtmlSnapshot.of("<p>novo</p><iframe id=pag title=Pagamentos data-fixture-frame=p></iframe>")
                .frame("p", "<button id=pagar>Pagar</button>", true, null);
        PageAnalysis b = Engine.analyze(again.reload());
        ResolutionResult r = Engine.resolve(id, b);
        assertEquals(ResolutionStatus.RESOLVED, r.status(), r.evidence().toString());
        assertEquals(again.idInFrame("p", "#pagar"), r.resolvedNodeId());
        assertTrue(r.nodeReplaced());
    }

    @Test
    void internalNavigationIsReportedAsDocumentChange() { // F003-017
        HtmlSnapshot h = HtmlSnapshot.of("<iframe id=app title=App data-fixture-frame=a src=\"/pagina1.html\"></iframe>")
                .frame("a", "<button id=voltar>Voltar</button>", true, null, "http://127.0.0.1:8765/pagina1.html");
        TargetIdentity id = Engine.register(Engine.analyze(h), h.idInFrame("a", "#voltar"));
        HtmlSnapshot nav = HtmlSnapshot.of("<iframe id=app title=App data-fixture-frame=a src=\"/pagina1.html\"></iframe>")
                .frame("a", "<button id=voltar>Voltar</button>", true, null, "http://127.0.0.1:8765/pagina2.html");
        ResolutionResult r = Engine.resolve(id, Engine.analyze(nav.reload()));
        assertTrue(r.diagnostics().contains("FRAME_DOCUMENT_CHANGED"), r.diagnostics().toString());
    }

    @Test
    void browserMetadataIsAssociatedWithoutInventingIdentifiers() { // §5.2
        HtmlSnapshot h = HtmlSnapshot.of("<iframe id=f1 data-fixture-frame=a src=\"/a.html\"></iframe><iframe id=f2 data-fixture-frame=b src=\"/b.html\"></iframe>")
                .frame("a", "<p>a</p>", true, null, "http://127.0.0.1:8765/a.html")
                .frame("b", "<p>b</p>", false, "CROSS_ORIGIN", "http://localhost:8766/b.html");
        PageAnalysis a = Engine.analyze(h);
        String hostA = h.idOf("#f1");
        List<BrowserFrameMetadata> meta = List.of(
                new BrowserFrameMetadata(0, "DOC-TOP", -1, null, "http://127.0.0.1:8765/lab/test.html", "outermost_frame", "active", null, "TOP", "TOP", "top", null),
                new BrowserFrameMetadata(7, "DOC-A", 0, "DOC-TOP", "http://127.0.0.1:8765/a.html", "sub_frame", "active", hostA, "HANDSHAKE", "CAPTURED_BY_PARENT", null, null),
                new BrowserFrameMetadata(9, "DOC-B", 0, "DOC-TOP", "about:blank", "sub_frame", "active", null, "UNMAPPED", "NO_PERMISSION", null, null));
        FrameGraph g = builder.build(a, meta);
        assertEquals("DOC-TOP", g.document("doc:f0").orElseThrow().browserDocumentId());
        FrameDescriptor fa = g.frameHostedBy(hostA).orElseThrow();
        assertEquals(7, fa.browser().browserFrameId());
        assertEquals("DOC-A", g.document(fa.documentId()).orElseThrow().browserDocumentId());
        FrameDescriptor fb = g.frameHostedBy(h.idOf("#f2")).orElseThrow();
        assertNull(fb.browser(), "sem associação por handshake e URL diferente: nada é inventado");
        assertNull(g.document(fb.documentId()).orElseThrow().browserDocumentId());
        assertTrue(g.limitations().stream().noneMatch(l -> l.contains("indisponíveis")));
    }

    @Test
    void aboutBlankWithoutContentScriptIsAssociatedOnlyWhenTheUrlIsUniqueUnderTheSameParent() { // §5.2 / §5.3
        HtmlSnapshot one = HtmlSnapshot.of("<iframe id=b1 title=Rascunho data-fixture-frame=x></iframe><iframe id=s1 srcdoc=\"<p>r</p>\" data-fixture-frame=y></iframe>")
                .frame("x", "<p>x</p>", true, null, "about:blank")
                .frame("y", "<p>r</p>", true, null, "about:srcdoc");
        List<BrowserFrameMetadata> meta = List.of(
                new BrowserFrameMetadata(0, "DOC-TOP", -1, null, "http://127.0.0.1:8765/lab/test.html", "outermost_frame", "active", null, "TOP", "TOP", "top", null),
                new BrowserFrameMetadata(4, "DOC-BLANK", 0, "DOC-TOP", "about:blank", "sub_frame", "active", null, "UNMAPPED", "NO_CONTENT_SCRIPT", null, null),
                new BrowserFrameMetadata(5, "DOC-SRCDOC", 0, "DOC-TOP", "about:srcdoc", "sub_frame", "active", null, "UNMAPPED", "NO_CONTENT_SCRIPT", null, null),
                new BrowserFrameMetadata(8, "DOC-OTHER", 3, "DOC-X", "about:blank", "sub_frame", "active", null, "UNMAPPED", "NO_CONTENT_SCRIPT", null, null));
        FrameGraph g = builder.build(Engine.analyze(one), meta);
        FrameDescriptor blank = g.frameHostedBy(one.idOf("#b1")).orElseThrow();
        assertEquals("DOC-BLANK", blank.browser().browserDocumentId(), "filho do mesmo pai com URL única (o about:blank de outro pai é ignorado)");
        assertEquals("URL_MATCH", blank.browser().mapping());
        assertEquals("ABOUT_BLANK", blank.documentKind());
        assertEquals("DOC-SRCDOC", g.frameHostedBy(one.idOf("#s1")).orElseThrow().browser().browserDocumentId());

        HtmlSnapshot two = HtmlSnapshot.of("<iframe id=b1 data-fixture-frame=x></iframe><iframe id=b2 data-fixture-frame=z></iframe>")
                .frame("x", "<p>x</p>", true, null, "about:blank")
                .frame("z", "<p>z</p>", true, null, "about:blank");
        List<BrowserFrameMetadata> meta2 = List.of(meta.get(0), meta.get(1),
                new BrowserFrameMetadata(6, "DOC-BLANK-2", 0, "DOC-TOP", "about:blank", "sub_frame", "active", null, "UNMAPPED", "NO_CONTENT_SCRIPT", null, null));
        FrameGraph g2 = builder.build(Engine.analyze(two), meta2);
        assertNull(g2.frameHostedBy(two.idOf("#b1")).orElseThrow().browser(), "dois about:blank no mesmo pai: nenhuma associação por adivinhação");
        assertNull(g2.frameHostedBy(two.idOf("#b2")).orElseThrow().browser());
    }

    @Test
    void urlsAreSanitized() { // §31
        assertEquals("https://www.google.com/recaptcha/api.js", UrlSanitizer.sanitize("https://www.google.com/recaptcha/api.js?render=KEY&hl=pt#x"));
        assertEquals(List.of("render", "hl"), UrlSanitizer.queryKeys("https://x.test/a?render=KEY&hl=pt"));
        assertEquals("about:srcdoc", UrlSanitizer.sanitize("about:srcdoc"));
        assertNotNull(UrlSanitizer.origin("http://localhost:8766/x"));
    }

    private static FrameDescriptor byId(FrameGraph g, String id) {
        return g.frames().stream().filter(f -> f.identity() != null && id.equals(f.identity().idAttr())).findFirst().orElseThrow();
    }
}
