package com.nekkora.extension.core.diagnostics;

import com.nekkora.extension.core.analysis.PageAnalysis;
import com.nekkora.extension.core.frames.FrameGraph;
import com.nekkora.extension.core.frames.FrameGraphBuilder;
import com.nekkora.extension.core.options.OptionHistory;
import com.nekkora.extension.core.options.OptionSetAnalyzer;
import com.nekkora.extension.core.protection.ProtectionDetector;
import com.nekkora.extension.core.testing.Engine;
import com.nekkora.extension.core.testing.HtmlSnapshot;
import com.nekkora.extension.core.testing.TestCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Automation Diagnostics Engine e AutomationReadinessAssessment (CHROME-003). */
class AutomationDiagnosticsTest {

    private AutomationReadinessAssessment assess(HtmlSnapshot h) {
        PageAnalysis a = Engine.analyze(h);
        FrameGraph g = new FrameGraphBuilder().build(a, List.of());
        var p = new ProtectionDetector().assess(a, g, TestCatalog.load());
        return new AutomationDiagnostics().assess(a, g, p, new OptionSetAnalyzer().analyzeAll(a.graph(), new OptionHistory()), List.of());
    }

    private static boolean has(AutomationReadinessAssessment r, String type) {
        return r.obstacles().stream().anyMatch(o -> o.type().equals(type));
    }

    @Test
    void simplePageHasNoKnownObstaclesButKeepsTheServerSideLimit() {
        AutomationReadinessAssessment r = assess(HtmlSnapshot.of("<form id=f><label for=n>Nome</label><input id=n><button>Salvar</button></form>"));
        assertEquals("NO_KNOWN_OBSTACLES", r.status());
        assertEquals("HIGH", r.confidence());
        assertEquals("NO_ACTION_REQUIRED", r.recommendations().get(0).type());
        assertTrue(r.limitations().stream().anyMatch(l -> l.contains("servidor")), "proteção no servidor nunca descartada");
    }

    @Test
    void activeChallengeRequiresHumanIntervention() { // cenário 5 / §15
        AutomationReadinessAssessment r = assess(HtmlSnapshot.of("""
                <title>Just a moment...</title>
                <script src="https://example.test/cdn-cgi/challenge-platform/h/b/orchestrate/x" type="application/x-nekkora-inert"></script>
                <form id=challenge-form></form>"""));
        assertEquals("REQUIRES_HUMAN_INTERVENTION", r.status());
        assertTrue(has(r, "HUMAN_VERIFICATION"));
        assertTrue(r.recommendations().stream().anyMatch(x -> x.type().equals("HUMAN_INTERVENTION")));
        assertTrue(r.recommendations().stream().anyMatch(x -> x.type().equals("AUTHORIZED_API")));
        assertTrue(r.recommendations().stream().noneMatch(x -> x.reason().toLowerCase().contains("contornar")), "nunca recomenda evasão");
    }

    @Test
    void multipleObstaclesAreClassifiedByEvidence() { // cenário 9
        HtmlSnapshot h = HtmlSnapshot.of("""
                <iframe id=fin title=Financeiro data-fixture-frame=fin></iframe>
                <iframe id=ext data-fixture-frame=x src="http://127.0.0.2:8765/x.html"></iframe>
                <nk-card><template shadowrootmode="closed"><button>X</button></template></nk-card>
                <button id="ember1234">Salvar</button><button>Excluir</button><button>Excluir</button>
                <details><summary>Mais opções</summary><button>Arquivar</button></details>
                <dialog id=d><button>Confirmar</button></dialog>""")
                .frame("fin", "<iframe name=pag title=Pagamentos data-fixture-frame=pag></iframe>", true, null)
                .frame("pag", "<button>Pagar</button>", true, null)
                .frame("x", "<p>x</p>", false, "CROSS_ORIGIN");
        AutomationReadinessAssessment r = assess(h);
        for (String t : List.of("NESTED_IFRAME", "INACCESSIBLE_FRAME", "CLOSED_SHADOW_ROOT", "DYNAMIC_IDS", "AMBIGUOUS_ELEMENTS",
                "INTERACTION_DEPENDENCY", "MODAL")) {
            assertTrue(has(r, t), t + " em " + r.obstacles().stream().map(AutomationObstacle::type).toList());
        }
        assertEquals("REQUIRES_SPECIAL_HANDLING", r.status());
        assertEquals("MEDIUM", r.confidence(), "regiões não observáveis reduzem a confiança");
        assertEquals(4, r.documents().get("count"), "principal, Financeiro, Pagamentos e o documento inacessível");
        assertEquals(1, r.documents().get("inaccessible"));
        assertEquals(2, r.documents().get("maximumFrameDepth"));
        assertTrue(r.interactionDependencies().stream().anyMatch(d -> d.type().equals("EXPAND_SECTION")));
        assertTrue(r.interactionDependencies().stream().anyMatch(d -> d.type().equals("OPEN_MODAL")));
        assertTrue(r.recommendations().stream().anyMatch(x -> x.type().equals("MANUAL_VALIDATION")));
        assertTrue(r.obstacles().stream().filter(o -> o.type().equals("DYNAMIC_IDS")).allMatch(AutomationObstacle::heuristic));
    }

    @Test
    void loadingFrameLowersConfidence() { // §23.2
        HtmlSnapshot h = HtmlSnapshot.of("<iframe id=lento src=\"/lento.html\" data-fixture-frame=l></iframe>")
                .frame("l", "", true, null, "about:blank");
        AutomationReadinessAssessment r = assess(h);
        assertTrue(has(r, "FRAME_LOADING"));
        assertEquals("LOW", r.confidence());
    }

    @Test
    void unknownProtectionIsReportedAsSuch() {
        AutomationReadinessAssessment r = assess(HtmlSnapshot.of("<div data-sitekey=X><p>Verify you are human</p></div>"));
        assertTrue(has(r, "UNKNOWN_PROTECTION"));
        assertTrue(r.recommendations().stream().anyMatch(x -> x.type().equals("UNKNOWN_PROTECTION")));
    }
}
