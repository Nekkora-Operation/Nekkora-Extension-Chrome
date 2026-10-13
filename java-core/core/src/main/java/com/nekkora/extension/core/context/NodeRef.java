package com.nekkora.extension.core.context;

import com.nekkora.extension.core.semantic.ElementKind;

import java.util.Map;

/**
 * Referência resumida a um elemento relacionado.
 *
 * @param nodeId        nó temporário
 * @param tag           tag
 * @param kind          finalidade
 * @param name          nome acessível ou texto curto
 * @param idAttr        atributo id, se houver
 * @param keyAttributes atributos que ajudam a identificar o contêiner (data-*, name, aria-label)
 */
public record NodeRef(String nodeId, String tag, ElementKind kind, String name, String idAttr,
                      Map<String, String> keyAttributes) {

    public NodeRef {
        keyAttributes = keyAttributes == null ? Map.of() : Map.copyOf(keyAttributes);
        name = name == null ? "" : name;
    }
}
