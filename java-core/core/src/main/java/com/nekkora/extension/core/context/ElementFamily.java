package com.nekkora.extension.core.context;

import java.util.List;

/**
 * Família de um elemento: as estruturas que o cercam e que permitem diferenciá-lo de elementos
 * aparentemente idênticos. Campos ausentes são {@code null} (não determinável), nunca inventados.
 *
 * @param nodeId             elemento analisado
 * @param parent             pai direto
 * @param children           filhos diretos
 * @param ancestors          ancestrais relevantes (do mais próximo ao mais distante)
 * @param siblings           irmãos relevantes
 * @param form               formulário associado (ancestral ou atributo form)
 * @param fieldGroup         fieldset / role=group
 * @param labels             labels associados
 * @param description        descrição associada (texto)
 * @param region             landmark/seção nomeada
 * @param dialog             diálogo ou modal
 * @param table              tabela
 * @param row                linha da tabela
 * @param column             coluna e cabeçalho
 * @param listItem           item de lista
 * @param logicalContainer   contêiner lógico mais próximo (card, item, linha, seção)
 * @param containerHeading   título que encabeça o contêiner lógico
 * @param visualContainer    contêiner visual mais próximo (borda/fundo/sombra)
 * @param contextualTexts    textos da família usados para diferenciar o alvo (não fazem parte do nome)
 * @param scopeChain         cadeia de escopos (iframes e shadow hosts) do documento principal até o elemento
 * @param relations          relações declaradas ou inferidas que envolvem o elemento
 */
public record ElementFamily(
        String nodeId,
        NodeRef parent,
        List<NodeRef> children,
        List<NodeRef> ancestors,
        List<NodeRef> siblings,
        NodeRef form,
        NodeRef fieldGroup,
        List<NodeRef> labels,
        String description,
        NodeRef region,
        NodeRef dialog,
        NodeRef table,
        RowContext row,
        ColumnContext column,
        NodeRef listItem,
        NodeRef logicalContainer,
        String containerHeading,
        NodeRef visualContainer,
        List<String> contextualTexts,
        List<NodeRef> scopeChain,
        List<ContextRelation> relations) {

    public ElementFamily {
        children = List.copyOf(children);
        ancestors = List.copyOf(ancestors);
        siblings = List.copyOf(siblings);
        labels = List.copyOf(labels);
        contextualTexts = List.copyOf(contextualTexts);
        scopeChain = List.copyOf(scopeChain);
        relations = List.copyOf(relations);
    }
}
