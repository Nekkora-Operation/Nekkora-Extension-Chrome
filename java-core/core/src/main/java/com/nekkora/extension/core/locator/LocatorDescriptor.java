package com.nekkora.extension.core.locator;

import java.util.List;

/**
 * Localizador gerado, com evidências.
 *
 * @param strategy         estratégia
 * @param css              seletor CSS (ou {@code null})
 * @param xpath            XPath 1.0 (ou {@code null})
 * @param semantic         notação semântica (sempre presente)
 * @param query            localizador estruturado (reexecutável pelo motor Java)
 * @param scope            escopo de execução
 * @param matchCount       correspondências no snapshot
 * @param matchesTarget    o alvo está entre as correspondências
 * @param matchedNodeIds   nós correspondentes no snapshot (até 20)
 * @param stability        estabilidade estimada
 * @param stabilityBasis   {@code HEURISTIC} ou {@code OBSERVED}
 * @param stabilityReasons motivos
 * @param score            pontuação usada na ordenação
 * @param expected         o que se espera encontrar
 * @param warnings         alertas (id duplicado, dependência de posição...)
 * @param live             verificação no navegador real (null até ser verificada)
 */
public record LocatorDescriptor(
        LocatorStrategyKind strategy,
        String css,
        String xpath,
        String semantic,
        com.nekkora.extension.core.locator.query.LocatorQuery query,
        LocatorScope scope,
        int matchCount,
        boolean matchesTarget,
        List<String> matchedNodeIds,
        StabilityLevel stability,
        String stabilityBasis,
        List<String> stabilityReasons,
        double score,
        ExpectedTarget expected,
        List<String> warnings,
        LiveCheck live) {

    public LocatorDescriptor {
        matchedNodeIds = List.copyOf(matchedNodeIds);
        stabilityReasons = List.copyOf(stabilityReasons);
        warnings = List.copyOf(warnings);
    }

    public boolean unique() {
        return matchCount == 1 && matchesTarget;
    }

    public LocatorDescriptor withLive(LiveCheck check) {
        return new LocatorDescriptor(strategy, css, xpath, semantic, query, scope, matchCount, matchesTarget, matchedNodeIds,
                stability, stabilityBasis, stabilityReasons, score, expected, warnings, check);
    }
}
