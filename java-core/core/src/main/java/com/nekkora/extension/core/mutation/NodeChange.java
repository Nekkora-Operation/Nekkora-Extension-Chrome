package com.nekkora.extension.core.mutation;

import java.util.List;
import java.util.Map;

/**
 * Alteração de um nó presente nas duas capturas.
 *
 * @param nodeId           nó
 * @param attributeChanges atributo → [antes, depois]
 * @param textBefore       texto próprio anterior (se mudou)
 * @param textAfter        texto próprio atual (se mudou)
 * @param stateChanges     estados alterados (ex.: {@code visible: true→false})
 * @param childrenChanged  lista de filhos alterada
 */
public record NodeChange(String nodeId, Map<String, List<String>> attributeChanges, String textBefore, String textAfter,
                         List<String> stateChanges, boolean childrenChanged) {

    public NodeChange {
        attributeChanges = Map.copyOf(attributeChanges);
        stateChanges = List.copyOf(stateChanges);
    }

    public boolean noChanges() {
        return attributeChanges.isEmpty() && textBefore == null && textAfter == null && stateChanges.isEmpty() && !childrenChanged;
    }
}
