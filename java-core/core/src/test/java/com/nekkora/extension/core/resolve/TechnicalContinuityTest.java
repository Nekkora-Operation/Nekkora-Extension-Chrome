package com.nekkora.extension.core.resolve;

import com.nekkora.extension.core.analysis.PageAnalysis;
import com.nekkora.extension.core.identity.TargetIdentity;
import com.nekkora.extension.core.privacy.SnapshotPrivacy;
import com.nekkora.extension.core.testing.Engine;
import com.nekkora.extension.core.testing.HtmlSnapshot;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CHROME-SECURITY-001, decisão D7: continuidade TÉCNICA do objeto DOM, separada da identidade LÓGICA. Os snapshots passam
 * pela política (como em produção): sem texto da página, mas com a época do registro de nós e as revisões de texto.
 */
class TechnicalContinuityTest {

    private static PageAnalysis analyze(HtmlSnapshot h) {
        return Engine.analyze(SnapshotPrivacy.apply(h.capture()));
    }

    private static void assertLogicalNotFromContinuity(ResolutionResult r) {
        // continuidade nunca vira resolução lógica, localizador persistente nem identidade atualizada
        if (r.status() != ResolutionStatus.RESOLVED) {
            assertNull(r.resolvedNodeId(), r.toString());
            assertNull(r.updatedLocators());
            assertNull(r.updatedIdentity());
        }
    }

    @Test
    @DisplayName("D7-1 — dois botões indistinguíveis que permanecem: lógica AMBIGUOUS (preservada); continuidade técnica do nó ancorado")
    void indistinguishableButtonsRemain() {
        HtmlSnapshot h = HtmlSnapshot.of("<div><button>Ok</button><button>Ok</button></div>");
        PageAnalysis a0 = analyze(h);
        String first = h.idOf(h.dom().select("button").get(0));
        TargetIdentity id = Engine.register(a0, first);
        ResolutionResult r = Engine.resolve(id, analyze(h));
        assertEquals(ResolutionStatus.AMBIGUOUS, r.status(), String.join("\n", r.evidence()));
        assertLogicalNotFromContinuity(r);
        assertEquals(TechnicalContinuity.LIVE, r.continuity().status(), r.continuity().toString());
        assertEquals(first, r.continuity().nodeId());
        assertTrue(r.continuity().limitations().contains(TechnicalContinuity.NOT_LOGICAL));
    }

    @Test
    @DisplayName("D7-2 — remoção de um botão e criação de outro na mesma posição: continuidade quebrada (NODE_GONE)")
    void removedAndRecreatedAtSamePosition() {
        HtmlSnapshot h = HtmlSnapshot.of("<div><button>Ok</button><button>Ok</button></div>");
        Element b = h.dom().select("button").get(0);
        TargetIdentity id = Engine.register(analyze(h), h.idOf(b));
        b.before("<button>Ok</button>");
        b.remove();
        ResolutionResult r = Engine.resolve(id, analyze(h));
        assertEquals(TechnicalContinuity.BROKEN, r.continuity().status());
        assertTrue(r.continuity().limitations().get(0).startsWith("NODE_GONE"), r.continuity().toString());
        assertLogicalNotFromContinuity(r);
    }

    @Test
    @DisplayName("D7-3 — re-renderização com novo objeto DOM: continuidade quebrada; a resolução lógica (por evidência) é independente")
    void rerenderWithNewObject() {
        HtmlSnapshot h = HtmlSnapshot.of("<form id='login'><button id='entrar' type='submit'>Entrar</button></form>");
        TargetIdentity id = Engine.register(analyze(h), h.idOf("button"));
        h.dom().selectFirst("form").replaceWith(org.jsoup.Jsoup.parse("<form id='login'><button id='entrar' type='submit'>Entrar</button></form>")
                .selectFirst("form"));
        ResolutionResult r = Engine.resolve(id, analyze(h));
        assertEquals(TechnicalContinuity.BROKEN, r.continuity().status(), r.continuity().toString());
        assertEquals(ResolutionStatus.RESOLVED, r.status(), "identidade lógica comprovada por id/nome do vocabulário");
        assertEquals(h.idOf("button"), r.resolvedNodeId());
        assertNotEquals(id.lastNodeId(), r.resolvedNodeId(), "objeto novo");
        // a âncora técnica passa ao nó LOGICAMENTE resolvido
        assertEquals(TechnicalContinuity.LIVE, Engine.resolve(r.updatedIdentity(), analyze(h)).continuity().status());
    }

    @Test
    @DisplayName("D7-4 — virtualização que reaproveita o mesmo nó para outro registro: continuidade com conteúdo alterado; nunca identidade lógica")
    void virtualizationReusesNode() {
        HtmlSnapshot h = HtmlSnapshot.of("<table><tbody><tr><td>Cliente A</td><td><button>Ver</button></td></tr>"
                + "<tr><td>Cliente B</td><td><button>Ver</button></td></tr></tbody></table>");
        Element btn = h.dom().select("button").get(0);
        TargetIdentity id = Engine.register(analyze(h), h.idOf(btn));
        h.dom().select("td").get(0).text("Cliente Z"); // a lista virtual reaproveita a mesma linha para outro registro
        ResolutionResult r = Engine.resolve(id, analyze(h));
        assertEquals(TechnicalContinuity.LIVE, r.continuity().status(), r.continuity().toString());
        assertTrue(r.continuity().limitations().stream().anyMatch(l -> l.startsWith("ROW_CONTENT_CHANGED")), r.continuity().toString());
        assertNotEquals(ResolutionStatus.RESOLVED, r.status(), "linhas indistinguíveis sem texto: a lógica não confirma");
        assertLogicalNotFromContinuity(r);
    }

    @Test
    @DisplayName("D7-5 — mudança de documento/navegação: nova época do registro de nós; continuidade quebrada")
    void documentChange() {
        HtmlSnapshot h = HtmlSnapshot.of("<div><button>Ok</button><button>Ok</button></div>");
        TargetIdentity id = Engine.register(analyze(h), h.idOf(h.dom().select("button").get(0)));
        h.reload();
        ResolutionResult r = Engine.resolve(id, analyze(h));
        assertEquals(TechnicalContinuity.BROKEN, r.continuity().status());
        assertTrue(r.continuity().limitations().get(0).startsWith("REGISTRY_EPOCH_CHANGED"), r.continuity().toString());
        assertLogicalNotFromContinuity(r);
    }

    @Test
    @DisplayName("D7-6 — iframe recarregado: continuidade quebrada no frame; nós do documento principal continuam")
    void iframeReloaded() {
        HtmlSnapshot h = HtmlSnapshot.of("<button>Sair</button><iframe data-fixture-frame='a'></iframe>")
                .frame("a", "<button>Salvar</button>", true, null);
        PageAnalysis a = analyze(h);
        String inFrame = a.semantics().all().stream().filter(c -> c.name().name().equals("Salvar")).findFirst().orElseThrow().nodeId();
        TargetIdentity frameTarget = Engine.register(a, inFrame);
        TargetIdentity mainTarget = Engine.register(a, h.idOf(h.dom().selectFirst("button")));
        h.reloadFrame("a");
        PageAnalysis b = analyze(h);
        assertEquals(TechnicalContinuity.BROKEN, Engine.resolve(frameTarget, b).continuity().status());
        assertEquals(TechnicalContinuity.LIVE, Engine.resolve(mainTarget, b).continuity().status());
        // iframe removido/substituído (o frame ancorado não existe mais): quebrada, não "não provada"
        h.dom().selectFirst("iframe").remove();
        ResolutionResult gone = Engine.resolve(frameTarget, analyze(h));
        assertEquals(TechnicalContinuity.BROKEN, gone.continuity().status());
        assertTrue(gone.continuity().limitations().get(0).startsWith("FRAME_GONE"), gone.continuity().toString());
    }

    @Test
    @DisplayName("D7-7 — reinicialização do registro de nós: o MESMO id passa a outro objeto; a época prova que não é continuidade")
    void registryReset() {
        HtmlSnapshot h = HtmlSnapshot.of("<main><p>x</p><div><button>Ok</button><button>Ok</button></div></main>");
        PageAnalysis a0 = analyze(h);
        String second = h.idOf(h.dom().select("button").get(1));
        TargetIdentity id = Engine.register(a0, second);
        h.resetRegistry();
        h.dom().selectFirst("p").after("<p>y</p>"); // a ordem muda: o id antigo passa a outro elemento (o 1º botão)
        PageAnalysis b = analyze(h);
        assertTrue(b.model().find(second).isPresent(), "o identificador técnico foi reutilizado");
        assertEquals(h.idOf(h.dom().select("button").get(0)), second, "…e agora aponta para OUTRO objeto (o 1º botão)");
        ResolutionResult r = Engine.resolve(id, b);
        assertEquals(TechnicalContinuity.BROKEN, r.continuity().status(), r.continuity().toString());
        assertTrue(r.continuity().limitations().get(0).startsWith("REGISTRY_EPOCH_CHANGED"));
    }

    @Test
    @DisplayName("D7-8 — mudança de tipo/finalidade do mesmo objeto: continuidade quebrada (TYPE_CHANGED)")
    void typeChange() {
        HtmlSnapshot h = HtmlSnapshot.of("<div><span role='button'>Ok</span></div>");
        Element span = h.dom().selectFirst("span");
        TargetIdentity id = Engine.register(analyze(h), h.idOf(span));
        span.attr("role", "checkbox");
        ResolutionResult r = Engine.resolve(id, analyze(h));
        assertEquals(TechnicalContinuity.BROKEN, r.continuity().status());
        assertTrue(r.continuity().limitations().get(0).startsWith("TYPE_CHANGED"), r.continuity().toString());
    }

    @Test
    @DisplayName("D7-9 — identidade de versão anterior (sem âncora): continuidade NÃO PROVADA; nada é presumido pelo id")
    void identityWithoutAnchor() {
        HtmlSnapshot h = HtmlSnapshot.of("<div><button>Ok</button><button>Ok</button></div>");
        PageAnalysis a0 = analyze(h);
        TargetIdentity id = Engine.register(a0, h.idOf(h.dom().select("button").get(0)));
        TargetIdentity old = new TargetIdentity(id.targetId(), id.schemaVersion(), id.registeredAt(), id.origin(), id.signature(), id.discriminators(),
                id.lookalikeCount(), id.locators(), id.lastNodeId(), id.lastPageSessionId(), id.lastCaptureId(), id.attributeHistory(), id.warnings(),
                id.frameChain());
        assertEquals(TechnicalContinuity.NOT_PROVEN, Engine.resolve(old, analyze(h)).continuity().status());
    }
}
