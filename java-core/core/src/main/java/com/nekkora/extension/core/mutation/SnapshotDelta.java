package com.nekkora.extension.core.mutation;

import com.nekkora.extension.core.snapshot.CaptureStats;
import com.nekkora.extension.core.snapshot.DocumentInfo;
import com.nekkora.extension.core.snapshot.FrameInfo;
import com.nekkora.extension.core.snapshot.ObservedRequest;
import com.nekkora.extension.core.snapshot.RawNode;
import com.nekkora.extension.core.snapshot.ShadowRootInfo;

import java.util.List;

/**
 * Atualização incremental produzida pelo Mutation Tracker do navegador: em vez de recapturar a
 * página inteira, envia apenas os nós adicionados/alterados e os removidos.
 *
 * @param baseCaptureId    captura sobre a qual o delta se aplica
 * @param captureId        identificador da captura resultante
 * @param capturedAt       instante
 * @param document         documento (URL/título podem mudar)
 * @param upserts          nós novos ou alterados (descritor completo; pais com filhos alterados incluídos)
 * @param removed          raízes de subárvores removidas
 * @param frames           lista atualizada de frames ({@code null} = inalterada)
 * @param shadowRoots      lista atualizada de shadow roots ({@code null} = inalterada)
 * @param requests         novas requisições observadas
 * @param rawMutationCount registros brutos do MutationObserver agregados neste delta
 * @param batchCount       lotes agregados
 * @param stats            estatísticas da captura incremental
 * @param sessionId        sessão de captura de origem (deltas sequenciados); {@code null} = delta legado encadeado por captura-base
 * @param source           fonte do delta: {@code top} (documento principal) ou {@code x<frameId>} (content script de um frame)
 * @param epoch            época da sessão (incrementada a cada captura completa / ressincronização)
 * @param seq              sequência do delta dentro da fonte e da época (1, 2, 3...)
 */
public record SnapshotDelta(
        String baseCaptureId,
        String captureId,
        String capturedAt,
        DocumentInfo document,
        List<RawNode> upserts,
        List<String> removed,
        List<FrameInfo> frames,
        List<ShadowRootInfo> shadowRoots,
        List<ObservedRequest> requests,
        int rawMutationCount,
        int batchCount,
        CaptureStats stats,
        String sessionId,
        String source,
        int epoch,
        long seq,
        List<com.nekkora.extension.core.snapshot.ResourceRef> resources,
        List<GeometryUpdate> geometry) {

    /** Delta sequenciado CHROME-003 (sem atualizações compactas de geometria). */
    public SnapshotDelta(String baseCaptureId, String captureId, String capturedAt, DocumentInfo document,
                         List<RawNode> upserts, List<String> removed, List<FrameInfo> frames,
                         List<ShadowRootInfo> shadowRoots, List<ObservedRequest> requests, int rawMutationCount,
                         int batchCount, CaptureStats stats, String sessionId, String source, int epoch, long seq,
                         List<com.nekkora.extension.core.snapshot.ResourceRef> resources) {
        this(baseCaptureId, captureId, capturedAt, document, upserts, removed, frames, shadowRoots, requests,
                rawMutationCount, batchCount, stats, sessionId, source, epoch, seq, resources, null);
    }

    /** Delta sequenciado CHROME-002 (sem recursos observados: {@code resources == null} = inalterados). */
    public SnapshotDelta(String baseCaptureId, String captureId, String capturedAt, DocumentInfo document,
                         List<RawNode> upserts, List<String> removed, List<FrameInfo> frames,
                         List<ShadowRootInfo> shadowRoots, List<ObservedRequest> requests, int rawMutationCount,
                         int batchCount, CaptureStats stats, String sessionId, String source, int epoch, long seq) {
        this(baseCaptureId, captureId, capturedAt, document, upserts, removed, frames, shadowRoots, requests,
                rawMutationCount, batchCount, stats, sessionId, source, epoch, seq, null, null);
    }

    /** Delta legado (CHROME-001): encadeado somente pela captura-base. */
    public SnapshotDelta(String baseCaptureId, String captureId, String capturedAt, DocumentInfo document,
                         List<RawNode> upserts, List<String> removed, List<FrameInfo> frames,
                         List<ShadowRootInfo> shadowRoots, List<ObservedRequest> requests, int rawMutationCount,
                         int batchCount, CaptureStats stats) {
        this(baseCaptureId, captureId, capturedAt, document, upserts, removed, frames, shadowRoots, requests,
                rawMutationCount, batchCount, stats, null, null, 0, 0, null, null);
    }

    public boolean sequenced() {
        return sessionId != null;
    }

    public String sourceOrTop() {
        return source == null || source.isBlank() ? Sources.TOP : source;
    }

    public SnapshotDelta {
        upserts = upserts == null ? List.of() : List.copyOf(upserts);
        removed = removed == null ? List.of() : List.copyOf(removed);
        requests = requests == null ? List.of() : List.copyOf(requests);
        geometry = geometry == null ? List.of() : List.copyOf(geometry);
    }
}
