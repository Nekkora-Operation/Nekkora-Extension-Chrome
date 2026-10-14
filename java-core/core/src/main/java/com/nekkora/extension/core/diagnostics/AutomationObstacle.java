package com.nekkora.extension.core.diagnostics;

import java.util.List;

/**
 * Obstáculo técnico observado que poderá exigir tratamento especial por um futuro mecanismo de automação.
 *
 * @param type        ver {@link AutomationDiagnostics} (ex.: {@code NESTED_IFRAME}, {@code HUMAN_VERIFICATION})
 * @param severity    {@code LOW}, {@code MEDIUM} ou {@code HIGH}
 * @param description explicação legível
 * @param evidence    evidências que justificam o obstáculo
 * @param nodeIds     elementos relacionados (amostra)
 * @param frameIds    frames relacionados
 * @param count       quantidade de ocorrências
 * @param heuristic   o obstáculo foi inferido por heurística (rotulado como tal)
 */
public record AutomationObstacle(String type, String severity, String description, List<String> evidence, List<String> nodeIds,
                                 List<String> frameIds, int count, boolean heuristic) {

    public AutomationObstacle {
        evidence = List.copyOf(evidence);
        nodeIds = nodeIds == null ? List.of() : List.copyOf(nodeIds.subList(0, Math.min(20, nodeIds.size())));
        frameIds = frameIds == null ? List.of() : List.copyOf(frameIds);
    }
}
