package com.nekkora.extension.core.locator;

import java.util.Map;

/**
 * O que se espera encontrar ao executar o localizador. A identidade NÃO depende do seletor: este
 * resumo permite que qualquer consumidor valide o elemento retornado.
 *
 * @param kind     finalidade
 * @param role     papel
 * @param name     nome acessível
 * @param form     formulário (id/name/rótulo)
 * @param table    tabela (id/legenda)
 * @param rowKey   chave da linha
 * @param dialog   diálogo
 * @param heading  título do contêiner
 */
public record ExpectedTarget(String kind, String role, String name, String form, String table,
                             Map<String, String> rowKey, String dialog, String heading) {

    public ExpectedTarget {
        rowKey = rowKey == null ? Map.of() : Map.copyOf(rowKey);
    }
}
