package com.nekkora.extension.core.interaction;

import com.nekkora.extension.core.mutation.MutationRecord;
import com.nekkora.extension.core.navigation.NavigationChange;

import java.util.List;
import java.util.Map;

/**
 * O que mudou entre o estado anterior e o posterior a uma interação. Cada fato carrega a sua FONTE,
 * para que nunca se confunda alteração visual com requisição de rede, nem query de URL com consulta
 * ao backend.
 *
 * @param beforeCaptureId  captura anterior
 * @param afterCaptureId   captura posterior
 * @param urlChange        mudança de URL (fonte: {@code URL})
 * @param uiQueries        valores de pesquisa/filtro na interface (fonte: {@code DOM})
 * @param apiRequests      requisições emitidas pela página entre as capturas (fonte: {@code RESOURCE_TIMING})
 * @param resultRegions    regiões de resultado cuja quantidade de itens mudou (fonte: {@code DOM})
 * @param domChanges       alterações da DOM (fonte: {@code DOM})
 * @param instrumentation  valores lidos de hooks de teste declarados pela página local (fonte: {@code TEST_INSTRUMENTATION})
 * @param notes            observações e ressalvas
 */
public record InteractionObservation(
        String beforeCaptureId,
        String afterCaptureId,
        NavigationChange urlChange,
        List<UiQuery> uiQueries,
        List<ApiRequest> apiRequests,
        List<ResultRegionChange> resultRegions,
        MutationRecord domChanges,
        Map<String, Object> instrumentation,
        List<String> notes) {

    public InteractionObservation {
        uiQueries = List.copyOf(uiQueries);
        apiRequests = List.copyOf(apiRequests);
        resultRegions = List.copyOf(resultRegions);
        instrumentation = instrumentation == null ? Map.of() : Map.copyOf(instrumentation);
        notes = List.copyOf(notes);
    }

    /** Valor de campo de pesquisa/filtro antes e depois. */
    public record UiQuery(String nodeId, String kind, String label, String before, String after, String source) {
    }

    /** Requisição observada via Resource Timing (somente URL e tempos). */
    public record ApiRequest(String url, String path, Map<String, List<String>> query, String initiatorType,
                             double startTime, String source) {
        public ApiRequest {
            query = Map.copyOf(query);
        }
    }

    /** Região de resultados (tabela/lista) com contagem de itens antes e depois. */
    public record ResultRegionChange(String nodeId, String kind, String name, int itemsBefore, int itemsAfter,
                                     List<String> sampleAfter, String source) {
        public ResultRegionChange {
            sampleAfter = List.copyOf(sampleAfter);
        }
    }
}
