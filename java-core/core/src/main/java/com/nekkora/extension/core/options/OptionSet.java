package com.nekkora.extension.core.options;

import java.util.List;

/**
 * Opções observadas de um controle de escolha (select nativo, combobox, listbox, dropdown personalizado).
 *
 * <p>Uma opção NÃO observada não é considerada inexistente: {@link #completeness()} declara o que se
 * sabe sobre a completude.
 *
 * @param controlNodeId           controle (select/combobox/gatilho)
 * @param listNodeId              lista associada (o próprio select, ou o listbox), {@code null} se não materializada
 * @param association             como controle e lista foram associados ({@code NATIVE}, {@code ARIA_CONTROLS}, {@code ARIA_OWNS}, {@code DESCENDANT}, {@code NOT_FOUND})
 * @param listOutsideControlTree  a lista está fora da subárvore do controle (portal)
 * @param multiple                seleção múltipla
 * @param options                 opções materializadas
 * @param groups                  grupos (optgroup / role=group)
 * @param observedCount           opções distintas observadas nesta e em capturas anteriores
 * @param materializedCount       opções presentes na DOM agora
 * @param announcedSetSize        tamanho anunciado (aria-setsize), {@code null} se ausente
 * @param dynamicLoading          evidência de carregamento dinâmico
 * @param virtualizationSuspected evidência de virtualização
 * @param completeness            {@code COMPLETE}, {@code INCOMPLETE} ou {@code UNKNOWN}
 * @param evidence                justificativas
 */
public record OptionSet(
        String controlNodeId,
        String listNodeId,
        String association,
        boolean listOutsideControlTree,
        boolean multiple,
        List<OptionItem> options,
        List<OptionGroup> groups,
        int observedCount,
        int materializedCount,
        Integer announcedSetSize,
        boolean dynamicLoading,
        boolean virtualizationSuspected,
        String completeness,
        List<String> evidence) {

    public OptionSet {
        options = List.copyOf(options);
        groups = List.copyOf(groups);
        evidence = List.copyOf(evidence);
    }

    /**
     * @param nodeId    nó da opção
     * @param text      texto
     * @param value     atributo value (somente opções nativas)
     * @param disabled  desabilitada
     * @param selected  selecionada ({@code null} quando o controle é sensível)
     * @param group     rótulo do grupo
     * @param posInSet  aria-posinset
     */
    public record OptionItem(String nodeId, String text, String value, boolean disabled, Boolean selected, String group,
                             Integer posInSet) {
    }

    /**
     * @param nodeId      nó do grupo
     * @param label       rótulo
     * @param disabled    desabilitado
     * @param optionCount opções no grupo
     */
    public record OptionGroup(String nodeId, String label, boolean disabled, int optionCount) {
    }
}
