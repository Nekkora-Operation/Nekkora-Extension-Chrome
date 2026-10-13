package com.nekkora.extension.core.resolve;

import java.util.List;

/**
 * Execução de um localizador registrado durante a resolução.
 *
 * @param strategy       estratégia
 * @param semantic       notação do localizador
 * @param matchCount     correspondências no snapshot atual
 * @param matchedNodeIds nós correspondentes (até 20)
 */
public record LocatorCheck(String strategy, String semantic, int matchCount, List<String> matchedNodeIds) {

    public LocatorCheck {
        matchedNodeIds = List.copyOf(matchedNodeIds);
    }
}
