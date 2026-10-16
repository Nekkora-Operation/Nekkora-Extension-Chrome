package com.nekkora.extension.core.locator;

import com.nekkora.extension.core.analysis.PageAnalysis;
import com.nekkora.extension.core.analysis.PageAnalyzer;
import com.nekkora.extension.core.locator.query.LocatorQuery;
import com.nekkora.extension.core.locator.query.LocatorStep;
import com.nekkora.extension.core.locator.query.Predicate;
import com.nekkora.extension.core.privacy.CapturePrivacyPolicy;
import com.nekkora.extension.core.snapshot.RawNode;
import com.nekkora.extension.core.snapshot.RawSnapshot;
import com.nekkora.extension.core.testing.HtmlSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CHROME-SECURITY-001 — revisão dos PRs #7, P1-01/P1-02: política aplicada à representação estruturada do localizador. */
class LocatorPrivacyTest {

    private static LocatorQuery q(Predicate... p) {
        return LocatorQuery.of(LocatorStep.descendant("button", p));
    }

    @Test
    @DisplayName("SEC001-089 — posição estrutural passa com qualquer número; VALOR numérico longo de atributo não")
    void positionVersusValue() {
        assertTrue(LocatorPrivacy.transportable(q(new Predicate.NthOfType(123))));
        assertFalse(LocatorPrivacy.transportable(q(new Predicate.AttrEquals("data-customer", "12345678900"))));
        assertFalse(LocatorPrivacy.transportable(q(new Predicate.AttrEquals("type", "48291736455"))));
        assertTrue(LocatorPrivacy.transportable(q(new Predicate.AttrEquals("type", "submit"))));
        assertTrue(LocatorPrivacy.transportable(q(new Predicate.AttrEquals("maxlength", "12"))));
        assertFalse(LocatorPrivacy.transportable(q(new Predicate.AttrEquals("aria-label", CapturePrivacyPolicy.HOLE))));
        assertFalse(LocatorPrivacy.transportable(q(new Predicate.RoleIs("cliente-joao"))));
        assertTrue(LocatorPrivacy.transportable(q(new Predicate.RoleIs("button"))));
        assertFalse(LocatorPrivacy.transportable(q(new Predicate.TextEquals("Consultar cliente Bartolomeu Quixadá"))));
        assertTrue(LocatorPrivacy.transportable(q(new Predicate.TextEquals("Consultar"))));
    }

    @Test
    @DisplayName("SEC001-088 — regra fechada por atributo estrutural (Java = JS pelos vetores; casos da revisão)")
    void structuralRules() {
        assertEquals("", CapturePrivacyPolicy.attribute("role", "cliente-joao", "div", null));
        assertEquals("", CapturePrivacyPolicy.attribute("autocomplete", "joao oliveira", "input", "text"));
        assertEquals("", CapturePrivacyPolicy.attribute("type", "48291736455", "input", null));
        assertEquals("button", CapturePrivacyPolicy.attribute("role", "button", "div", null));
        assertEquals("shipping street-address", CapturePrivacyPolicy.attribute("autocomplete", "shipping street-address", "input", "text"));
        assertFalse(CapturePrivacyPolicy.literalSafe("12345678900"));
        assertTrue(CapturePrivacyPolicy.literalSafe("12"));
    }

    @Test
    @DisplayName("SEC001-089 — motor sobre snapshot SEM filtro (extensão anterior): nenhum localizador leva o valor numérico longo")
    void engineDropsUnsafeCandidates() {
        RawSnapshot raw = HtmlSnapshot.of("<main><div role='cliente-joao'><input id='c' type='48291736455'>"
                + "<button data-testid='12345678900'>Salvar</button></div></main>").capture();
        PageAnalysis a = new PageAnalyzer().analyze(raw);
        for (RawNode n : a.model().inDocumentOrder()) {
            LocatorSet ls = a.locators().generate(n.id());
            if (ls == null) {
                continue;
            }
            for (LocatorDescriptor d : ls.all()) {
                String all = d.css() + " " + d.xpath() + " " + d.semantic();
                assertFalse(all.contains("48291736455") || all.contains("12345678900") || all.contains("cliente-joao"), all);
            }
        }
    }
}
