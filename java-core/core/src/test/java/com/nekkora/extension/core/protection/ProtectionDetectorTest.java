package com.nekkora.extension.core.protection;

import com.nekkora.extension.core.analysis.PageAnalysis;
import com.nekkora.extension.core.frames.FrameGraphBuilder;
import com.nekkora.extension.core.testing.Engine;
import com.nekkora.extension.core.testing.HtmlSnapshot;
import com.nekkora.extension.core.testing.TestCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Protection Detection Engine (CHROME-003). Fixtures sintéticas: referências de script INERTES (tipo não executável),
 * nenhuma chave real, nenhum tráfego.
 */
class ProtectionDetectorTest {

    private static final String INERT = "type=\"application/x-nekkora-inert\"";
    private final ProtectionDetector detector = new ProtectionDetector();

    private ProtectionReport assess(HtmlSnapshot h) {
        PageAnalysis a = Engine.analyze(h);
        return detector.assess(a, new FrameGraphBuilder().build(a, List.of()), TestCatalog.load());
    }

    private static ProtectionAssessment only(ProtectionReport r, String product) {
        List<ProtectionAssessment> l = r.assessments().stream().filter(x -> product.equals(x.product())).toList();
        assertEquals(1, l.size(), "assessments: " + r.assessments().stream().map(ProtectionAssessment::product).toList());
        return l.get(0);
    }

    @Test
    void catalogIsValidAndThirdPartySignaturesAreCapped() {
        SignatureCatalog c = TestCatalog.load();
        assertTrue(c.validate().isEmpty(), c.validate().toString());
        for (SignatureCatalog.Signature s : c.signatures()) {
            if ("THIRD_PARTY_OBSERVATION".equals(s.basis())) {
                assertNotEquals("HIGH", s.maxConfidence(), s.id());
            }
            assertFalse(s.sources().isEmpty(), s.id());
        }
        assertTrue(c.signatures().size() >= 11, "catálogo inicial de fornecedores");
    }

    @Test
    void recaptchaFixtureIsDetectedWithHighConfidence() { // P003-001
        ProtectionReport r = assess(HtmlSnapshot.of("""
                <script src="https://www.google.com/recaptcha/api.js?hl=pt" %s></script>
                <form id=login><input name=user><div class="g-recaptcha" data-sitekey="SITEKEY-FICTICIA"></div><button>Entrar</button></form>
                """.formatted(INERT)));
        ProtectionAssessment a = only(r, "RECAPTCHA_V2");
        assertEquals("OBSERVED", r.status());
        assertEquals("DETECTED", a.status());
        assertEquals("HIGH", a.confidence(), a.confidenceReasons().toString());
        assertEquals("CHALLENGE_PRESENT", a.challengeState());
        assertTrue(a.evidence().stream().anyMatch(e -> e.type().equals("SCRIPT_RESOURCE") && e.simulated()), "evidência de simulação marcada");
        assertTrue(a.evidence().stream().noneMatch(e -> e.detail().contains("hl=pt")), "query nunca registrada");
        assertTrue(a.evidence().stream().noneMatch(e -> e.detail().contains("SITEKEY-FICTICIA")), "chave nunca registrada");
        assertTrue(a.limitations().stream().anyMatch(l -> l.contains("simulação")));
    }

    @Test
    void hcaptchaAndTurnstileFixtures() { // P003-002, P003-003
        ProtectionReport h = assess(HtmlSnapshot.of("""
                <script src="https://js.hcaptcha.com/1/api.js" %s></script><div class="h-captcha" data-sitekey="X"></div>""".formatted(INERT)));
        assertEquals("HIGH", only(h, "HCAPTCHA").confidence());
        ProtectionReport t = assess(HtmlSnapshot.of("""
                <script src="https://challenges.cloudflare.com/turnstile/v0/api.js" %s></script>
                <form><div class="cf-turnstile" data-sitekey="X"></div></form>""".formatted(INERT)));
        ProtectionAssessment ta = only(t, "TURNSTILE");
        assertEquals("CLOUDFLARE", ta.provider());
        assertEquals("HUMAN_VERIFICATION", ta.category());
        assertEquals("HIGH", ta.confidence());
    }

    @Test
    void challengePageIsRecognizedWithCappedConfidence() { // P003-004
        HtmlSnapshot h = HtmlSnapshot.of("""
                <title>Just a moment...</title>
                <script src="/cdn-cgi/challenge-platform/h/b/orchestrate/chl_page/v1" %s></script>
                <div id=challenge-running>Verificando</div><form id=challenge-form></form>""".formatted(INERT));
        ProtectionAssessment a = only(assess(h), "CHALLENGE_PAGE");
        assertEquals("CHALLENGE_ACTIVE", a.challengeState());
        assertEquals("MEDIUM", a.confidence(), "assinatura não confirmada pelo fornecedor: teto MEDIUM");
        assertEquals("THIRD_PARTY_OBSERVATION", a.basis());
    }

    @Test
    void pageWithoutSignaturesIsNeverDeclaredUnprotected() { // P003-005, teste negativo F
        ProtectionReport r = assess(HtmlSnapshot.of("<form><input name=q><button>Buscar</button></form>"));
        assertEquals("NOT_OBSERVED", r.status());
        assertTrue(r.assessments().isEmpty());
        assertTrue(r.limitations().stream().anyMatch(l -> l.contains("não comprova ausência de proteção")), r.limitations().toString());
    }

    @Test
    void isolatedCaptchaTextIsNotAConfirmation() { // P003-006, teste negativo E
        ProtectionReport r = assess(HtmlSnapshot.of("<article><h1>O que é um CAPTCHA?</h1><p>Um CAPTCHA diferencia pessoas de programas.</p></article>"));
        assertEquals("NOT_OBSERVED", r.status());
        assertTrue(r.assessments().isEmpty(), "nenhuma tecnologia inventada");
        assertFalse(r.genericIndicators().isEmpty(), "a menção é registrada como indicador genérico");
    }

    @Test
    void protectionInsideIframeIsLocated() { // P003-007, LAB-P08
        HtmlSnapshot h = HtmlSnapshot.of("<form id=login><input name=u></form><iframe id=verif title=Verificação data-fixture-frame=v></iframe>")
                .frame("v", """
                        <script src="https://js.hcaptcha.com/1/api.js" %s></script><div class="h-captcha" data-sitekey="X"></div>""".formatted(INERT), true, null);
        ProtectionAssessment a = only(assess(h), "HCAPTCHA");
        assertTrue(a.location().insideFrame());
        assertEquals(List.of("Verificação"), a.location().frameChain());
        assertNotEquals("doc:f0", a.location().documentId());
        assertTrue(a.location().inspectable());
        assertNotNull(a.location().accessPath());
        assertEquals("IFRAME", a.location().accessPath().steps().get(1).type());
    }

    @Test
    void twoProvidersAreBothRepresented() { // P003-009, LAB-P07
        ProtectionReport r = assess(HtmlSnapshot.of("""
                <script src="https://js.datadome.co/tags.js" %1$s></script>
                <script src="https://www.google.com/recaptcha/api.js" %1$s></script><div class="g-recaptcha"></div>""".formatted(INERT)));
        assertTrue(r.assessments().stream().anyMatch(a -> "DATADOME".equals(a.provider())));
        assertTrue(r.assessments().stream().anyMatch(a -> "RECAPTCHA_V2".equals(a.product())));
        ProtectionAssessment dd = only(r, "DATADOME");
        assertEquals("BOT_MANAGEMENT", dd.category());
        assertEquals("TECHNOLOGY_DETECTED", dd.challengeState(), "biblioteca presente não é desafio");
    }

    @Test
    void partialSignatureReducesConfidence() { // P003-010
        ProtectionAssessment a = only(assess(HtmlSnapshot.of("<div class=\"h-captcha\"></div>")), "HCAPTCHA");
        assertEquals("MEDIUM", a.confidence(), "uma única evidência forte");
    }

    @Test
    void unknownTechnologyIsUnknown() { // P003-011, teste negativo D, LAB-P05/P10
        ProtectionReport r = assess(HtmlSnapshot.of("""
                <form><div class=verify-box data-sitekey="X"><p>Verify you are human</p></div><button>Continuar</button></form>"""));
        assertEquals("INSUFFICIENT_EVIDENCE", r.status());
        ProtectionAssessment a = r.assessments().get(0);
        assertEquals("UNKNOWN", a.provider());
        assertEquals("UNKNOWN", a.confidence());
        assertEquals("GENERIC", a.basis());
    }

    @Test
    void libraryWithoutChallengeVersusVisibleChallenge() { // P003-012, P003-013
        ProtectionReport lib = assess(HtmlSnapshot.of("""
                <script src="https://www.google.com/recaptcha/api.js?render=FICTICIA" %s></script>
                <div class="grecaptcha-badge" style="display:none"></div>""".formatted(INERT)));
        ProtectionAssessment v3 = only(lib, "RECAPTCHA_V3");
        assertEquals("TECHNOLOGY_DETECTED", v3.challengeState());
        ProtectionReport active = assess(HtmlSnapshot.of("""
                <script src="https://www.google.com/recaptcha/api.js" %s></script><div class="g-recaptcha"></div>
                <iframe title="recaptcha challenge" data-nekkora-simulated-src="https://www.google.com/recaptcha/api2/bframe?k=X"></iframe>""".formatted(INERT)));
        assertEquals("CHALLENGE_ACTIVE", only(active, "RECAPTCHA_V2").challengeState());
    }

    @Test
    void challengeIsCompletedOnlyWithExplicitEvidence() { // teste negativo J
        ProtectionReport notRun = assess(HtmlSnapshot.of("""
                <script src="https://js.hcaptcha.com/1/api.js" %s></script><div class="h-captcha"></div><textarea name="h-captcha-response"></textarea>""".formatted(INERT)));
        assertEquals("CHALLENGE_PRESENT", only(notRun, "HCAPTCHA").challengeState(), "campo vazio: desafio não concluído");
        ProtectionReport done = assess(HtmlSnapshot.of("""
                <script src="https://js.hcaptcha.com/1/api.js" %s></script><div class="h-captcha"></div><textarea name="h-captcha-response" value="TOKEN-FICTICIO"></textarea>""".formatted(INERT)));
        ProtectionAssessment d = only(done, "HCAPTCHA");
        assertEquals("CHALLENGE_COMPLETED", d.challengeState());
        assertTrue(d.evidence().stream().noneMatch(e -> e.detail().contains("TOKEN-FICTICIO")), "valor do campo nunca coletado");
        ProtectionReport vanished = assess(HtmlSnapshot.of("""
                <script src="https://js.hcaptcha.com/1/api.js" %s></script>""".formatted(INERT)));
        assertEquals("TECHNOLOGY_DETECTED", only(vanished, "HCAPTCHA").challengeState(), "widget ausente não significa desafio concluído");
    }

    @Test
    void conflictingSignaturesReduceConfidenceAndKeepEvidence() { // teste negativo I
        ProtectionReport r = assess(HtmlSnapshot.of("<div class=\"g-recaptcha h-captcha\"></div>"));
        ProtectionAssessment g = only(r, "RECAPTCHA_V2");
        ProtectionAssessment h = only(r, "HCAPTCHA");
        assertEquals("LOW", g.confidence());
        assertEquals("LOW", h.confidence());
        assertTrue(g.limitations().stream().anyMatch(l -> l.contains("conflitantes")));
        assertFalse(g.evidence().isEmpty());
    }

    @Test
    void widgetInUninspectableFrameDeclaresLimitation() { // P003-014
        HtmlSnapshot h = HtmlSnapshot.of("<iframe id=cap title=CAPTCHA src=\"https://www.google.com/recaptcha/api2/anchor?k=X\" data-fixture-frame=c></iframe>")
                .frame("c", "", false, "CROSS_ORIGIN", "https://www.google.com/recaptcha/api2/anchor");
        ProtectionAssessment a = only(assess(h), "RECAPTCHA_V2");
        assertFalse(a.location().inspectable());
        assertTrue(a.limitations().stream().anyMatch(l -> l.contains("sem inspeção de conteúdo")), a.limitations().toString());
        assertEquals("MEDIUM", a.confidence(), "somente a URL do iframe");
    }

    @Test
    void removedEvidenceIsReevaluated() { // P003-015
        HtmlSnapshot h = HtmlSnapshot.of("<div id=box class=\"cf-turnstile\"></div>");
        assertEquals("OBSERVED", assess(h).status());
        h.dom().getElementById("box").remove();
        assertEquals("NOT_OBSERVED", assess(h.reload()).status());
    }
}
