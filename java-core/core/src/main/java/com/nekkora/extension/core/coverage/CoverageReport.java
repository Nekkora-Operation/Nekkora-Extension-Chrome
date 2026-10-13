package com.nekkora.extension.core.coverage;

import java.util.List;
import java.util.Map;

/**
 * Cobertura da captura: o que foi inventariado e, principalmente, o que NÃO pôde ser.
 *
 * @param elementCount        elementos inventariados
 * @param byCategory          contagem por categoria
 * @param byKind              contagem por finalidade
 * @param visible             elementos visíveis
 * @param hidden              elementos ocultos
 * @param unknownState        elementos com estado desconhecido
 * @param lowConfidence       classificações de baixa confiança
 * @param unclassified        elementos sem semântica reconhecida
 * @param frames              frames (acessíveis ou não)
 * @param shadowRoots         shadow roots (inspecionados ou não)
 * @param truncated           limite de nós atingido
 * @param maxNodes            limite de nós configurado
 * @param captureDurationMs   duração da captura no navegador
 * @param limitations         limitações declaradas
 */
public record CoverageReport(
        int elementCount,
        Map<String, Integer> byCategory,
        Map<String, Integer> byKind,
        int visible,
        int hidden,
        int unknownState,
        int lowConfidence,
        int unclassified,
        List<ScopeCoverage> frames,
        List<ScopeCoverage> shadowRoots,
        boolean truncated,
        int maxNodes,
        double captureDurationMs,
        List<String> limitations) {

    public CoverageReport {
        byCategory = Map.copyOf(byCategory);
        byKind = Map.copyOf(byKind);
        frames = List.copyOf(frames);
        shadowRoots = List.copyOf(shadowRoots);
        limitations = List.copyOf(limitations);
    }

    /**
     * @param id         frameId ou nó host
     * @param hostNodeId nó host
     * @param kind       {@code FRAME} / {@code SHADOW_ROOT_OPEN} / {@code SHADOW_ROOT_CLOSED}
     * @param inspected  conteúdo inventariado
     * @param elements   elementos inventariados no escopo
     * @param reason     motivo de não inspeção
     * @param url        URL (frames)
     */
    public record ScopeCoverage(String id, String hostNodeId, String kind, boolean inspected, int elements, String reason, String url) {
    }
}
