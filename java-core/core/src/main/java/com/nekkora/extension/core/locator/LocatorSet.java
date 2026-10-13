package com.nekkora.extension.core.locator;

import java.util.ArrayList;
import java.util.List;

/**
 * Conjunto de localizadores de um alvo.
 *
 * @param nodeId    nó alvo no snapshot
 * @param primary   melhor localizador (pode não ser único — ver {@code warnings})
 * @param fallbacks alternativas em ordem decrescente de pontuação
 * @param warnings  alertas do conjunto (ex.: nenhum localizador único)
 */
public record LocatorSet(String nodeId, LocatorDescriptor primary, List<LocatorDescriptor> fallbacks, List<String> warnings) {

    public LocatorSet {
        fallbacks = List.copyOf(fallbacks);
        warnings = List.copyOf(warnings);
    }

    public List<LocatorDescriptor> all() {
        List<LocatorDescriptor> all = new ArrayList<>();
        if (primary != null) {
            all.add(primary);
        }
        all.addAll(fallbacks);
        return all;
    }
}
