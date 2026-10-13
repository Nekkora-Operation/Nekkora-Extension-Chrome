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
        CaptureStats stats) {

    public SnapshotDelta {
        upserts = upserts == null ? List.of() : List.copyOf(upserts);
        removed = removed == null ? List.of() : List.copyOf(removed);
        requests = requests == null ? List.of() : List.copyOf(requests);
    }
}
