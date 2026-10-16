package com.nekkora.extension.core.mutation;

import java.util.List;
import java.util.Map;

/**
 * Alteração de um nó presente nas duas capturas.
 *
 * @param nodeId           nó
 * @param attributeChanges atributo → [antes, depois]; o VALOR só aparece em atributos estruturais (CHROME-SECURITY-001),
 *                         nos demais vira {@code [omitido]}
 * @param textBefore       {@code [omitido]} quando o texto próprio mudou (o conteúdo nunca sai); {@code null} se não mudou
 * @param textAfter        idem
 * @param stateChanges     estados alterados (ex.: {@code visible: true→false})
 * @param childrenChanged  lista de filhos alterada
 */
public record NodeChange(String nodeId, Map<String, List<String>> attributeChanges, String textBefore, String textAfter,
                         List<String> stateChanges, boolean childrenChanged) {

    public NodeChange {
        // barreira: nenhum produtor de mudança (delta, comparação, reconciliação) faz o texto ou valor da página sair
        attributeChanges = com.nekkora.extension.core.privacy.CapturePrivacyPolicy.attributeChanges(attributeChanges);
        textBefore = com.nekkora.extension.core.privacy.CapturePrivacyPolicy.withheldText(textBefore);
        textAfter = com.nekkora.extension.core.privacy.CapturePrivacyPolicy.withheldText(textAfter);
        stateChanges = List.copyOf(stateChanges);
    }

    public boolean noChanges() {
        return attributeChanges.isEmpty() && textBefore == null && textAfter == null && stateChanges.isEmpty() && !childrenChanged;
    }
}
