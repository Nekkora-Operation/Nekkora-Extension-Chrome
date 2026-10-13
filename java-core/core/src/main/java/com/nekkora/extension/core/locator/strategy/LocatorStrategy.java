package com.nekkora.extension.core.locator.strategy;

import java.util.List;

/**
 * Estratégia de geração de localizadores. Contrato: propor apenas localizadores que, no snapshot,
 * INCLUEM o alvo; nunca decidir sozinha qual é o melhor (isso é responsabilidade do motor).
 * Novas estratégias são adicionadas registrando uma nova implementação, sem alterar o motor.
 */
public interface LocatorStrategy {

    List<LocatorCandidate> propose(TargetContext target);
}
