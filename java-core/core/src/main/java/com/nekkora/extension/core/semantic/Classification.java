package com.nekkora.extension.core.semantic;

import java.util.List;
import java.util.Set;

/**
 * Resultado da classificação semântica de um elemento, com as evidências que a sustentam.
 *
 * @param nodeId          nó temporário
 * @param kind            finalidade identificada
 * @param role            papel efetivo (ARIA explícito válido ou implícito)
 * @param nativeSemantics a finalidade vem do HTML nativo
 * @param confidence      confiança
 * @param name            nome/descrição acessíveis
 * @param conditions      condições observadas
 * @param sensitive       elemento pode conter dado sensível (valor jamais exportado)
 * @param evidence        fatos observados que sustentam a classificação
 * @param uncertainties   pontos sem evidência suficiente
 */
public record Classification(
        String nodeId,
        ElementKind kind,
        String role,
        boolean nativeSemantics,
        Confidence confidence,
        AccessibleName name,
        Set<ElementCondition> conditions,
        boolean sensitive,
        List<String> evidence,
        List<String> uncertainties) {

    public Classification {
        conditions = Set.copyOf(conditions);
        evidence = List.copyOf(evidence);
        uncertainties = List.copyOf(uncertainties);
    }

    public ElementCategory category() {
        return kind.category();
    }

    public boolean interactive() {
        return kind.category() == ElementCategory.INTERACTIVE;
    }

    public boolean has(ElementCondition c) {
        return conditions.contains(c);
    }
}
