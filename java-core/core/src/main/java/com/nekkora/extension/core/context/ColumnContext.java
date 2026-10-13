package com.nekkora.extension.core.context;

/**
 * Coluna da célula que contém o elemento.
 *
 * @param index        índice da coluna (considera colspan)
 * @param headerText   texto do cabeçalho associado
 * @param headerNodeId nó do cabeçalho, se encontrado
 * @param basis        {@code HEADERS_ATTRIBUTE}, {@code THEAD}, {@code FIRST_ROW_TH} ou {@code NONE}
 */
public record ColumnContext(int index, String headerText, String headerNodeId, String basis) {
}
