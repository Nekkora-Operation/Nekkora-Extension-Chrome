package com.nekkora.extension.core.context;

import java.util.List;
import java.util.Map;

/**
 * Linha de tabela que contém o elemento.
 *
 * @param rowNodeId     nó da linha
 * @param positionHint  posição observada entre as linhas da tabela (informativa — NUNCA usada sozinha como identidade)
 * @param rowKey        chave lógica da linha (data-*, id, cabeçalho de linha ou primeira célula)
 * @param rowKeySource  origem da chave: {@code DATA_ATTRIBUTE}, {@code ROW_ID}, {@code ROW_HEADER}, {@code FIRST_CELL_TEXT}, {@code NONE}
 * @param cellTexts     textos das demais células (contexto textual)
 */
public record RowContext(String rowNodeId, int positionHint, Map<String, String> rowKey, String rowKeySource,
                         List<String> cellTexts) {

    public RowContext {
        rowKey = rowKey == null ? Map.of() : Map.copyOf(rowKey);
        cellTexts = cellTexts == null ? List.of() : List.copyOf(cellTexts);
    }
}
