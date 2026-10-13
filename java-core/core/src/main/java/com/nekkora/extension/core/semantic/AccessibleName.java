package com.nekkora.extension.core.semantic;

import java.util.List;

/**
 * Nome e descrição acessíveis.
 *
 * @param name               nome acessível normalizado (pode ser vazio)
 * @param source             de onde o nome veio
 * @param sourceNodeIds      nós que forneceram o nome (labels, aria-labelledby)
 * @param description        descrição acessível (aria-describedby / title), pode ser vazia
 * @param descriptionNodeIds nós que forneceram a descrição
 */
public record AccessibleName(String name, NameSource source, List<String> sourceNodeIds,
                             String description, List<String> descriptionNodeIds) {

    public static final AccessibleName NONE = new AccessibleName("", NameSource.NONE, List.of(), "", List.of());

    public AccessibleName {
        sourceNodeIds = sourceNodeIds == null ? List.of() : List.copyOf(sourceNodeIds);
        descriptionNodeIds = descriptionNodeIds == null ? List.of() : List.copyOf(descriptionNodeIds);
        name = name == null ? "" : name;
        description = description == null ? "" : description;
    }
}
