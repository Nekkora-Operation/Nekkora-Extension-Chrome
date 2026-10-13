package com.nekkora.extension.core.semantic;

import java.util.List;

/**
 * Nome e descrição acessíveis.
 *
 * @param name               nome acessível normalizado (pode ser vazio)
 * @param source             de onde o nome veio
 * @param sourceNodeIds      nós que forneceram o nome (labels, aria-labelledby)
 * @param description        descrição acessível (aria-describedby / aria-description / title), pode ser vazia
 * @param descriptionNodeIds nós que forneceram a descrição
 * @param confidence         confiança da fonte: {@code HIGH} (rótulo explícito ou nativo), {@code MEDIUM}
 *                           (conteúdo), {@code LOW} (title/placeholder), {@code NONE}
 */
public record AccessibleName(String name, NameSource source, List<String> sourceNodeIds,
                             String description, List<String> descriptionNodeIds, String confidence) {

    public static final AccessibleName NONE = new AccessibleName("", NameSource.NONE, List.of(), "", List.of(), "NONE");

    public AccessibleName(String name, NameSource source, List<String> sourceNodeIds, String description,
                          List<String> descriptionNodeIds) {
        this(name, source, sourceNodeIds, description, descriptionNodeIds, AccessibleNameCalculator.confidenceOf(source));
    }

    public AccessibleName {
        sourceNodeIds = sourceNodeIds == null ? List.of() : List.copyOf(sourceNodeIds);
        descriptionNodeIds = descriptionNodeIds == null ? List.of() : List.copyOf(descriptionNodeIds);
        name = name == null ? "" : name;
        description = description == null ? "" : description;
        source = source == null ? NameSource.NONE : source;
        confidence = confidence == null ? AccessibleNameCalculator.confidenceOf(source) : confidence;
    }
}
