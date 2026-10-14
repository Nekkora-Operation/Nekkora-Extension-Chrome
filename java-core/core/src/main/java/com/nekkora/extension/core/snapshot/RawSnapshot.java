package com.nekkora.extension.core.snapshot;

import java.util.List;

/**
 * Captura estrutural somente-leitura de uma página.
 *
 * @param captureId    identificador único da captura
 * @param capturedAt   instante ISO-8601
 * @param document     contexto do documento principal
 * @param frames       frames encontrados (acessíveis ou não)
 * @param shadowRoots  shadow roots detectados (abertos ou fechados)
 * @param nodes        elementos inventariados
 * @param requests     requisições observadas via Resource Timing (pode ser vazio)
 * @param stats        estatísticas da captura
 */
public record RawSnapshot(
        String captureId,
        String capturedAt,
        DocumentInfo document,
        List<FrameInfo> frames,
        List<ShadowRootInfo> shadowRoots,
        List<RawNode> nodes,
        List<ObservedRequest> requests,
        CaptureStats stats,
        List<ResourceRef> resources) {

    /** Construtor CHROME-001/002 (sem recursos observados). */
    public RawSnapshot(String captureId, String capturedAt, DocumentInfo document, List<FrameInfo> frames,
                       List<ShadowRootInfo> shadowRoots, List<RawNode> nodes, List<ObservedRequest> requests, CaptureStats stats) {
        this(captureId, capturedAt, document, frames, shadowRoots, nodes, requests, stats, null);
    }

    public RawSnapshot {
        frames = frames == null ? List.of() : List.copyOf(frames);
        shadowRoots = shadowRoots == null ? List.of() : List.copyOf(shadowRoots);
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        requests = requests == null ? List.of() : List.copyOf(requests);
        resources = resources == null ? List.of() : List.copyOf(resources);
    }
}
