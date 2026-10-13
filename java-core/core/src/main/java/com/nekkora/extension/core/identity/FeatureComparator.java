package com.nekkora.extension.core.identity;

import com.nekkora.extension.core.text.TextMatch;
import com.nekkora.extension.core.text.TextNormalizer;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Compara características de identidade entre duas assinaturas. Determinístico e sem efeitos colaterais. */
public final class FeatureComparator {

    public FeatureResult compare(IdentityFeature f, IdentitySignature expected, IdentitySignature actual) {
        return switch (f) {
            case TEST_ATTRIBUTE -> exact(f, expected.testAttribute(), actual.testAttribute());
            case ID_ATTRIBUTE -> exact(f, expected.idAttribute(), actual.idAttribute());
            case NAME_ATTRIBUTE -> exact(f, expected.nameAttribute(), actual.nameAttribute());
            case TYPE_ATTRIBUTE -> exact(f, expected.typeAttribute(), actual.typeAttribute());
            case NAME -> text(f, expected.name(), actual.name());
            case TEXT -> text(f, expected.text(), actual.text());
            case LABEL -> key(f, expected.label(), actual.label());
            case FORM -> key(f, expected.form(), actual.form());
            case FIELD_GROUP -> key(f, expected.fieldGroup(), actual.fieldGroup());
            case TABLE -> key(f, expected.table(), actual.table());
            case COLUMN -> key(f, expected.column(), actual.column());
            case DIALOG -> key(f, expected.dialog(), actual.dialog());
            case REGION -> key(f, expected.region(), actual.region());
            case CONTAINER_HEADING -> key(f, expected.containerHeading(), actual.containerHeading());
            case ROW_KEY -> rowKey(expected.rowKey(), actual.rowKey());
            case ROW_TEXTS -> list(f, expected.rowTexts(), actual.rowTexts());
            case CONTEXT_TEXTS -> list(f, expected.contextTexts(), actual.contextTexts());
            case SCOPE -> scope(expected.scopePath(), actual.scopePath());
        };
    }

    private static FeatureResult exact(IdentityFeature f, String e, String a) {
        if (e == null) {
            return new FeatureResult(f, FeatureOutcome.NOT_APPLICABLE, 0, null, a);
        }
        if (a == null) {
            return new FeatureResult(f, FeatureOutcome.ABSENT, 0, e, null);
        }
        boolean eq = e.equals(a);
        return new FeatureResult(f, eq ? FeatureOutcome.MATCH : FeatureOutcome.MISMATCH, eq ? 1 : 0, e, a);
    }

    private static FeatureResult text(IdentityFeature f, String e, String a) {
        if (e == null) {
            return new FeatureResult(f, FeatureOutcome.NOT_APPLICABLE, 0, null, a);
        }
        if (a == null) {
            return new FeatureResult(f, FeatureOutcome.ABSENT, 0, e, null);
        }
        TextMatch m = TextNormalizer.compare(e, a);
        FeatureOutcome o = switch (m) {
            case EXACT, FOLDED, BOTH_EMPTY, DIGITS_VARIED -> FeatureOutcome.MATCH;
            case PARTIAL -> FeatureOutcome.PARTIAL;
            case NONE -> FeatureOutcome.MISMATCH;
        };
        return new FeatureResult(f, o, m.weight(), e, a);
    }

    /**
     * Chaves estruturais (formulário, grupo, tabela, diálogo, título do contêiner) são identidades:
     * semelhança parcial NÃO conta ("Endereço de cobrança" ≠ "Endereço de entrega").
     */
    private static FeatureResult key(IdentityFeature f, String e, String a) {
        if (e == null) {
            return new FeatureResult(f, FeatureOutcome.NOT_APPLICABLE, 0, null, a);
        }
        if (a == null) {
            return new FeatureResult(f, FeatureOutcome.ABSENT, 0, e, null);
        }
        TextMatch m = TextNormalizer.compare(e, a);
        boolean same = m == TextMatch.EXACT || m == TextMatch.FOLDED || m == TextMatch.DIGITS_VARIED;
        return new FeatureResult(f, same ? FeatureOutcome.MATCH : FeatureOutcome.MISMATCH, same ? m.weight() : 0, e, a);
    }

    private static FeatureResult rowKey(Map<String, String> e, Map<String, String> a) {
        if (e == null || e.isEmpty()) {
            return new FeatureResult(IdentityFeature.ROW_KEY, FeatureOutcome.NOT_APPLICABLE, 0, null, String.valueOf(a));
        }
        if (a == null || a.isEmpty()) {
            return new FeatureResult(IdentityFeature.ROW_KEY, FeatureOutcome.ABSENT, 0, e.toString(), null);
        }
        Set<String> common = new HashSet<>(e.keySet());
        common.retainAll(a.keySet());
        if (common.isEmpty()) {
            // Chaves de naturezas diferentes não são comparáveis: não comprovam nem refutam.
            return new FeatureResult(IdentityFeature.ROW_KEY, FeatureOutcome.ABSENT, 0, e.toString(), a.toString());
        }
        for (String k : common) {
            if (TextNormalizer.compare(e.get(k), a.get(k)) != TextMatch.EXACT
                    && TextNormalizer.compare(e.get(k), a.get(k)) != TextMatch.FOLDED) {
                return new FeatureResult(IdentityFeature.ROW_KEY, FeatureOutcome.MISMATCH, 0, e.toString(), a.toString());
            }
        }
        return new FeatureResult(IdentityFeature.ROW_KEY, FeatureOutcome.MATCH, 1, e.toString(), a.toString());
    }

    private static FeatureResult list(IdentityFeature f, List<String> e, List<String> a) {
        if (e == null || e.isEmpty()) {
            return new FeatureResult(f, FeatureOutcome.NOT_APPLICABLE, 0, null, String.valueOf(a));
        }
        if (a == null || a.isEmpty()) {
            return new FeatureResult(f, FeatureOutcome.ABSENT, 0, String.join(" | ", e), null);
        }
        Set<String> ef = new HashSet<>();
        e.forEach(s -> ef.add(TextNormalizer.fold(s)));
        Set<String> af = new HashSet<>();
        a.forEach(s -> af.add(TextNormalizer.fold(s)));
        Set<String> inter = new HashSet<>(ef);
        inter.retainAll(af);
        double overlap = (double) inter.size() / ef.size();
        FeatureOutcome o = overlap >= 0.6 ? FeatureOutcome.MATCH : overlap >= 0.25 ? FeatureOutcome.PARTIAL : FeatureOutcome.MISMATCH;
        return new FeatureResult(f, o, overlap, String.join(" | ", e), String.join(" | ", a));
    }

    private static FeatureResult scope(List<String> e, List<String> a) {
        if ((e == null || e.isEmpty()) && (a == null || a.isEmpty())) {
            return new FeatureResult(IdentityFeature.SCOPE, FeatureOutcome.NOT_APPLICABLE, 0, null, null);
        }
        boolean eq = Objects.equals(e, a);
        return new FeatureResult(IdentityFeature.SCOPE, eq ? FeatureOutcome.MATCH : FeatureOutcome.MISMATCH, eq ? 1 : 0,
                String.valueOf(e), String.valueOf(a));
    }
}
