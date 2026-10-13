package com.nekkora.extension.core.identity;

import com.nekkora.extension.core.locator.IdentifierHeuristics;
import com.nekkora.extension.core.locator.StabilityLevel;

import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Peso de identificadores voláteis (id, name) na comparação de identidade. */
public final class IdentifierStability {

    private static final IdentifierHeuristics HEURISTICS = new IdentifierHeuristics();

    private IdentifierStability() {
    }

    /**
     * Peso cheio para identificadores estáveis; reduzido quando a aparência sugere geração dinâmica;
     * quase nulo quando o histórico já mostrou mudança.
     */
    public static double weightFor(String attr, String value, Map<String, List<String>> history) {
        IdentityFeature f = attr.equals("id") ? IdentityFeature.ID_ATTRIBUTE : IdentityFeature.NAME_ATTRIBUTE;
        List<String> h = history.get(attr);
        if (h != null && new HashSet<>(h).size() > 1) {
            return 0.1;
        }
        if (value != null && HEURISTICS.assess(value).level() == StabilityLevel.HEURISTIC_UNSTABLE) {
            return 0.3;
        }
        return f.weight();
    }
}
