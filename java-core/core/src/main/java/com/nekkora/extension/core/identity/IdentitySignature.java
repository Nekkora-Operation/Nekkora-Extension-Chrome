package com.nekkora.extension.core.identity;

import java.util.List;
import java.util.Map;

/**
 * Valores observados das características de identidade de um elemento. Campos {@code null} indicam
 * que a característica não se aplica ou não foi determinável.
 *
 * @param kind             finalidade
 * @param compatibility    família de compatibilidade (ver {@code ElementKind#compatibilityFamily})
 * @param role             papel
 * @param tag              tag
 * @param name             nome acessível
 * @param text             texto (textContent normalizado, truncado)
 * @param idAttribute      atributo id
 * @param nameAttribute    atributo name
 * @param testAttribute    atributo de teste (data-testid...) como {@code attr=value}
 * @param typeAttribute    atributo type
 * @param label            texto do label associado
 * @param form             chave do formulário
 * @param fieldGroup       nome do fieldset/grupo
 * @param table            chave da tabela (id, legenda ou título precedente)
 * @param rowKey           chave da linha
 * @param rowKeySource     origem da chave da linha
 * @param rowTexts         textos das demais células
 * @param column           cabeçalho da coluna
 * @param dialog           chave do diálogo
 * @param region           chave da região
 * @param containerHeading título do contêiner
 * @param contextTexts     textos contextuais
 * @param scopePath        cadeia de escopos (descrição dos hosts)
 */
public record IdentitySignature(
        String kind,
        String compatibility,
        String role,
        String tag,
        String name,
        String text,
        String idAttribute,
        String nameAttribute,
        String testAttribute,
        String typeAttribute,
        String label,
        String form,
        String fieldGroup,
        String table,
        Map<String, String> rowKey,
        String rowKeySource,
        List<String> rowTexts,
        String column,
        String dialog,
        String region,
        String containerHeading,
        List<String> contextTexts,
        List<String> scopePath) {

    public IdentitySignature {
        rowKey = rowKey == null ? Map.of() : Map.copyOf(rowKey);
        rowTexts = rowTexts == null ? List.of() : List.copyOf(rowTexts);
        contextTexts = contextTexts == null ? List.of() : List.copyOf(contextTexts);
        scopePath = scopePath == null ? List.of() : List.copyOf(scopePath);
    }
}
