package com.nekkora.extension.core.resolve;

import com.nekkora.extension.core.analysis.PageAnalysis;
import com.nekkora.extension.core.identity.IdentityFeature;
import com.nekkora.extension.core.identity.TargetIdentity;
import com.nekkora.extension.core.locator.LocatorDescriptor;
import com.nekkora.extension.core.locator.LocatorStrategyKind;
import com.nekkora.extension.core.locator.StabilityLevel;
import com.nekkora.extension.core.testing.Engine;
import com.nekkora.extension.core.testing.Fixtures;
import com.nekkora.extension.core.testing.HtmlSnapshot;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetResolverTest {

    private static Element row(HtmlSnapshot h, String client) {
        return h.dom().selectFirst("tr[data-client=" + client + "]");
    }

    @Test
    void neverPicksFirstSilently() { // critério obrigatório §8.1
        HtmlSnapshot h = HtmlSnapshot.of(Fixtures.DUPLICATED_TABLES);
        PageAnalysis a = Engine.analyze(h);
        String maria = h.idOf(row(h, "202").selectFirst("button"));
        TargetIdentity id = Engine.register(a, maria);
        assertTrue(id.discriminators().contains(IdentityFeature.ROW_KEY));
        assertTrue(id.discriminators().contains(IdentityFeature.TABLE));
        assertEquals(1, id.lookalikeCount());

        ResolutionResult r = Engine.resolve(id, Engine.analyze(h));
        assertEquals(ResolutionStatus.RESOLVED, r.status());
        assertEquals(maria, r.resolvedNodeId(), "deve resolver a Maria, não o primeiro botão #excluir");
        LocatorCheck byId = r.locatorChecks().stream().filter(c -> c.strategy().equals("ID")).findFirst().orElseThrow();
        assertEquals(2, byId.matchCount(), "o seletor por id é ambíguo e isso fica registrado");
    }

    @Test
    void reorderPreservesIdentity() { // T009
        HtmlSnapshot h = HtmlSnapshot.of(Fixtures.SAME_TABLE_ROWS);
        PageAnalysis a = Engine.analyze(h);
        String ana = h.idOf(row(h, "101").selectFirst("button"));
        TargetIdentity id = Engine.register(a, ana);

        Element tbody = h.dom().selectFirst("tbody");
        tbody.prependChild(row(h, "303")); // João vai para o topo; Ana deixa de ser a primeira linha
        tbody.appendChild(row(h, "101"));  // Ana vai para o fim
        ResolutionResult r = Engine.resolve(id, Engine.analyze(h));
        assertEquals(ResolutionStatus.RESOLVED, r.status());
        assertEquals(ana, r.resolvedNodeId());
        assertFalse(r.nodeReplaced());
    }

    @Test
    void reRenderWithReorderRevalidatesIdentity() { // T009 + T026
        HtmlSnapshot h = HtmlSnapshot.of(Fixtures.SAME_TABLE_ROWS);
        PageAnalysis a = Engine.analyze(h);
        TargetIdentity id = Engine.register(a, h.idOf(row(h, "101").selectFirst("button")));

        h.dom().selectFirst("table").replaceWith(org.jsoup.Jsoup.parse(Fixtures.SAME_TABLE_ROWS_REORDERED).selectFirst("table"));
        PageAnalysis after = Engine.analyze(h);
        ResolutionResult r = Engine.resolve(id, after);
        assertEquals(ResolutionStatus.RESOLVED, r.status(), String.join("\n", r.evidence()));
        assertTrue(r.nodeReplaced());
        assertEquals(h.idOf(row(h, "101").selectFirst("button")), r.resolvedNodeId());
    }

    @Test
    void dynamicIdIsReacquiredAndStrategyReevaluated() { // T005 / §17
        HtmlSnapshot h = HtmlSnapshot.of(Fixtures.dynamicId("18731"));
        PageAnalysis a = Engine.analyze(h);
        TargetIdentity id = Engine.register(a, h.idOf("input"));

        for (String next : new String[]{"93482", "50291"}) {
            h.dom().selectFirst("form").replaceWith(org.jsoup.Jsoup.parse(Fixtures.dynamicId(next)).selectFirst("form"));
            ResolutionResult r = Engine.resolve(id, Engine.analyze(h));
            assertEquals(ResolutionStatus.RESOLVED, r.status(), String.join("\n", r.evidence()));
            assertEquals(h.idOf("input"), r.resolvedNodeId());
            assertTrue(r.attributeChanges().containsKey("id"));
            // CHROME-SECURITY-001 (P1-02): o id dinâmico (sufixo numérico longo) não é transportável — não há localizador ID
            // a reavaliar; a instabilidade fica no histórico de atributos, e o principal nunca é o id
            assertTrue(r.updatedLocators().all().stream().noneMatch(d -> d.strategy() == LocatorStrategyKind.ID), r.updatedLocators().all().toString());
            assertNotEquals(LocatorStrategyKind.ID, r.updatedLocators().primary().strategy());
            id = r.updatedIdentity();
        }
        assertEquals(3, id.attributeHistory().get("id").size());
    }

    @Test
    void removedElementIsStale() { // T025 / Caso B
        HtmlSnapshot h = HtmlSnapshot.of(Fixtures.SAME_TABLE_ROWS);
        PageAnalysis a = Engine.analyze(h);
        TargetIdentity id = Engine.register(a, h.idOf(row(h, "101").selectFirst("button")));
        row(h, "101").remove();
        ResolutionResult r = Engine.resolve(id, Engine.analyze(h));
        assertEquals(ResolutionStatus.STALE_TARGET, r.status(), String.join("\n", r.evidence()));
        assertNull(r.resolvedNodeId());
        assertTrue(r.candidates().stream().allMatch(c -> c.verdict().equals("DISQUALIFIED")),
                "Maria e João existem mas pertencem a outras linhas");
    }

    @Test
    void removedAfterReloadIsNotFound() {
        HtmlSnapshot h = HtmlSnapshot.of(Fixtures.SAME_TABLE_ROWS);
        TargetIdentity id = Engine.register(Engine.analyze(h), h.idOf(row(h, "101").selectFirst("button")));
        row(h, "101").remove();
        h.reload();
        assertEquals(ResolutionStatus.NOT_FOUND, Engine.resolve(id, Engine.analyze(h)).status());
    }

    @Test
    void indistinguishableButtonsAreAmbiguous() { // T032 / Caso A
        HtmlSnapshot h = HtmlSnapshot.of("<div><button>Confirmar</button><button>Confirmar</button></div>");
        PageAnalysis a = Engine.analyze(h);
        TargetIdentity id = Engine.register(a, h.idOf(h.dom().select("button").get(0)));
        assertTrue(id.indistinguishableAtRegistration());
        ResolutionResult r = Engine.resolve(id, Engine.analyze(h));
        assertEquals(ResolutionStatus.AMBIGUOUS, r.status());
        assertNull(r.resolvedNodeId());
    }

    @Test
    void newLookalikeWithEqualEvidenceIsAmbiguous() {
        HtmlSnapshot h = HtmlSnapshot.of("<section><h3>Cliente Ana</h3><button>Editar</button></section>");
        PageAnalysis a = Engine.analyze(h);
        TargetIdentity id = Engine.register(a, h.idOf("button"));
        h.dom().body().appendElement("section").html("<h3>Cliente Ana</h3><button>Editar</button>");
        h.dom().selectFirst("section").remove();
        h.dom().body().appendElement("section").html("<h3>Cliente Ana</h3><button>Editar</button>");
        ResolutionResult r = Engine.resolve(id, Engine.analyze(h));
        assertEquals(ResolutionStatus.AMBIGUOUS, r.status(), String.join("\n", r.evidence()));
    }

    @Test
    void semanticChangeWithConstantIdIsMismatch() { // T040 / Caso C
        HtmlSnapshot h = HtmlSnapshot.of("<label for=campo>Nome</label><input id=campo name=campo>");
        TargetIdentity id = Engine.register(Engine.analyze(h), h.idOf("input"));
        h.dom().selectFirst("label").text("CPF do titular");
        ResolutionResult r = Engine.resolve(id, Engine.analyze(h));
        assertEquals(ResolutionStatus.IDENTITY_MISMATCH, r.status(), String.join("\n", r.evidence()));
    }

    @Test
    void incompatibleControlWithSameIdIsMismatch() { // Caso C
        HtmlSnapshot h = HtmlSnapshot.of("<label for=email>E-mail</label><input id=email type=email>");
        TargetIdentity id = Engine.register(Engine.analyze(h), h.idOf("input"));
        h.dom().selectFirst("input").replaceWith(org.jsoup.Jsoup.parse("<button id=email>E-mail</button>").selectFirst("button"));
        ResolutionResult r = Engine.resolve(id, Engine.analyze(h));
        assertEquals(ResolutionStatus.IDENTITY_MISMATCH, r.status(), String.join("\n", r.evidence()));
        assertTrue(r.candidates().stream().anyMatch(c -> c.verdict().equals("INCOMPATIBLE")));
    }

    @Test
    void inaccessibleScopeIsDeclared() { // Caso D
        HtmlSnapshot h = HtmlSnapshot.of("<iframe id=pagamento data-fixture-frame=p></iframe>")
                .frame("p", "<button>Pagar</button>", true, null);
        PageAnalysis a = Engine.analyze(h);
        String pagar = a.semantics().all().stream().filter(c -> c.name().name().equals("Pagar")).findFirst().orElseThrow().nodeId();
        TargetIdentity id = Engine.register(a, pagar);
        h.frame("p", "<button>Pagar</button>", false, "SANDBOXED");
        ResolutionResult r = Engine.resolve(id, Engine.analyze(h));
        assertEquals(ResolutionStatus.SCOPE_UNAVAILABLE, r.status(), String.join("\n", r.evidence()));
    }

    @Test
    void missingContextIsInsufficientEvidence() { // Caso E
        HtmlSnapshot h = HtmlSnapshot.of(Fixtures.SAME_TABLE_ROWS);
        TargetIdentity id = Engine.register(Engine.analyze(h), h.idOf(row(h, "101").selectFirst("button")));
        // Tabela volta em estado "esqueleto": uma linha sem chave e sem textos.
        h.dom().selectFirst("tbody").html("<tr><td></td><td></td><td><button id=excluir>Excluir</button></td></tr>");
        ResolutionResult r = Engine.resolve(id, Engine.analyze(h));
        assertEquals(ResolutionStatus.INSUFFICIENT_EVIDENCE, r.status(), String.join("\n", r.evidence()));
        assertNull(r.resolvedNodeId());
    }

    @Test
    void replacedElementIsRevalidated() { // T026
        HtmlSnapshot h = HtmlSnapshot.of("<form id=f><label for=c>Cidade</label><input id=c name=cidade><button>Salvar</button></form>");
        TargetIdentity id = Engine.register(Engine.analyze(h), h.idOf("button"));
        String before = id.lastNodeId();
        h.dom().selectFirst("button").replaceWith(org.jsoup.Jsoup.parse("<button>Salvar</button>").selectFirst("button"));
        ResolutionResult r = Engine.resolve(id, Engine.analyze(h));
        assertEquals(ResolutionStatus.RESOLVED, r.status());
        assertTrue(r.nodeReplaced());
        assertNotEquals(before, r.resolvedNodeId());
    }

    @Test
    void responsiveRestructureKeepsIdentity() { // T031
        HtmlSnapshot h = HtmlSnapshot.of("<header><nav class=desktop><a href=/conta>Minha conta</a></nav></header><main><h1>Início</h1></main>");
        TargetIdentity id = Engine.register(Engine.analyze(h), h.idOf("a"));
        Element link = h.dom().selectFirst("a");
        h.dom().selectFirst("nav").remove();
        h.dom().selectFirst("header").appendElement("div").addClass("mobile-menu").appendChild(link);
        ResolutionResult r = Engine.resolve(id, Engine.analyze(h));
        assertEquals(ResolutionStatus.RESOLVED, r.status(), String.join("\n", r.evidence()));
    }

    @Test
    void billingStateIsNotShippingState() {
        HtmlSnapshot h = HtmlSnapshot.of(Fixtures.ADDRESS_FORMS);
        TargetIdentity id = Engine.register(Engine.analyze(h), h.idOf("#estado-c"));
        assertTrue(id.discriminators().contains(IdentityFeature.FIELD_GROUP));
        // Remove o campo de cobrança: o de entrega NÃO pode assumir sua identidade.
        h.dom().selectFirst("fieldset").remove();
        ResolutionResult r = Engine.resolve(id, Engine.analyze(h));
        assertNotEquals(ResolutionStatus.RESOLVED, r.status());
    }
}
