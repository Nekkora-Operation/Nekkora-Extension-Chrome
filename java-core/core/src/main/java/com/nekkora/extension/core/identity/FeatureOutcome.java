package com.nekkora.extension.core.identity;

/** Resultado da comparação de uma característica. */
public enum FeatureOutcome {
    /** Valores equivalentes. */
    MATCH,
    /** Semelhança parcial (não comprova, não refuta). */
    PARTIAL,
    /** Valores diferentes: evidência CONTRA a identidade. */
    MISMATCH,
    /** Esperado presente, ausente no candidato: não comprova nem refuta. */
    ABSENT,
    /** A característica não existia no registro. */
    NOT_APPLICABLE
}
