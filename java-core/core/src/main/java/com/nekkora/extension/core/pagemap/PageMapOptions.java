package com.nekkora.extension.core.pagemap;

import com.nekkora.extension.core.semantic.ElementCategory;

import java.util.EnumSet;
import java.util.Set;

/**
 * Controle de volume do PageMap.
 *
 * @param familyFor         categorias que recebem família completa
 * @param locatorsFor       categorias que recebem localizadores
 * @param includeContainers inclui contêineres genéricos sem texto próprio
 * @param maxElements       limite de elementos exportados
 */
public record PageMapOptions(Set<ElementCategory> familyFor, Set<ElementCategory> locatorsFor, boolean includeContainers,
                             int maxElements) {

    public static PageMapOptions defaults() {
        return new PageMapOptions(EnumSet.of(ElementCategory.INTERACTIVE, ElementCategory.TEXT),
                EnumSet.of(ElementCategory.INTERACTIVE), false, 20_000);
    }

    public static PageMapOptions minimal() {
        return new PageMapOptions(EnumSet.noneOf(ElementCategory.class), EnumSet.noneOf(ElementCategory.class), false, 20_000);
    }
}
