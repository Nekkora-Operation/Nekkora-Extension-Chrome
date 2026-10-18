package com.nekkora.extension.core.locator;

import com.nekkora.extension.core.locator.query.LocatorQuery;
import com.nekkora.extension.core.locator.query.LocatorStep;
import com.nekkora.extension.core.locator.query.Predicate;
import com.nekkora.extension.core.privacy.CapturePrivacyPolicy;

/**
 * CHROME-SECURITY-001 (revisão dos PRs #7, P1-02): verificação da política sobre a representação ESTRUTURADA do
 * localizador, antes de ele virar CSS/XPath. Cada predicado é julgado pelo seu papel sintático:
 * <ul>
 *   <li>{@code NthOfType}, {@code NthChild} — índice de POSIÇÃO estrutural: qualquer número é aceito;</li>
 *   <li>{@code AttrEquals} — VALOR de atributo: a regra da categoria do atributo (estrutural fechada, identificador do
 *       vocabulário, nome de interface) ou, para os demais, o literal da política (número só até 2 dígitos);</li>
 *   <li>texto/nome/rótulo — metadado de interface do vocabulário controlado;</li>
 *   <li>{@code RoleIs} — a regra fechada do atributo {@code role};</li>
 *   <li>nome de elemento do passo e nome de atributo (2ª revisão dos PRs #7) — catálogo HTML/SVG/MathML ou nome inteiro
 *       do vocabulário; o marcador neutro {@code custom-element} nunca entra num localizador.</li>
 * </ul>
 * A verificação textual ({@link CapturePrivacyPolicy#expressionSafe(String)}) continua nas barreiras que só recebem a
 * expressão pronta (service worker, host, Studio).
 */
public final class LocatorPrivacy {

    private LocatorPrivacy() {
    }

    public static boolean transportable(LocatorQuery q) {
        for (LocatorStep s : q.steps()) {
            if (s.tag() != null && !CapturePrivacyPolicy.tagSafe(s.tag())) {
                return false;
            }
            for (Predicate p : s.predicates()) {
                if (!safe(p)) {
                    return false;
                }
            }
        }
        return true;
    }

    static boolean safe(Predicate p) {
        return switch (p) {
            case Predicate.NthOfType n -> true;
            case Predicate.NthChild n -> true;
            case Predicate.AttrPresent a -> CapturePrivacyPolicy.attributeNameSafe(a.name());
            case Predicate.AttrEquals a -> CapturePrivacyPolicy.attributeNameSafe(a.name()) && attributeValueSafe(a.name(), a.value());
            case Predicate.RoleIs r -> !CapturePrivacyPolicy.structuralValueOf("role", r.role()).isEmpty();
            case Predicate.TextEquals t -> CapturePrivacyPolicy.interfaceText(t.text()) != null;
            case Predicate.NameEquals n -> CapturePrivacyPolicy.interfaceText(n.name()) != null;
            case Predicate.LabelTextEquals l -> CapturePrivacyPolicy.interfaceText(l.text()) != null;
            case Predicate.HasChildText h -> (h.tag() == null || CapturePrivacyPolicy.tagSafe(h.tag()))
                    && CapturePrivacyPolicy.interfaceText(h.text()) != null;
        };
    }

    static boolean attributeValueSafe(String name, String value) {
        if (value == null || CapturePrivacyPolicy.HOLE.equals(value)) {
            return false;
        }
        String n = name.toLowerCase(java.util.Locale.ROOT);
        if (CapturePrivacyPolicy.STRUCTURAL_ATTRIBUTES.contains(n)) {
            return value.equals(CapturePrivacyPolicy.structuralValueOf(n, value)) && !value.isEmpty();
        }
        if (CapturePrivacyPolicy.IDENTIFIERS.contains(n)) {
            return (CapturePrivacyPolicy.DEV_IDENTIFIERS.contains(n) ? CapturePrivacyPolicy.developerIdentifier(value)
                    : CapturePrivacyPolicy.identifier(value)) != null;
        }
        if (n.equals("class")) {
            return value.equals(CapturePrivacyPolicy.classes(value));
        }
        if (CapturePrivacyPolicy.NAMING.contains(n)) {
            return CapturePrivacyPolicy.interfaceText(value) != null;
        }
        return CapturePrivacyPolicy.literalSafe(value);
    }
}
