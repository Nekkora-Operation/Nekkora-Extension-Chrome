package com.nekkora.extension.core.locator;

/**
 * Estratégias de localização com peso-base. O peso NÃO impõe hierarquia rígida: a pontuação final
 * combina peso, unicidade e estabilidade — um id instável perde para um contexto estável.
 */
public enum LocatorStrategyKind {
    TEST_ATTRIBUTE(0.95),
    ID(0.90),
    NAME(0.80),
    LABEL(0.80),
    ROLE_NAME(0.75),
    CONTEXTUAL(0.76),
    ROW_CONTEXT(0.78),
    ATTRIBUTE(0.65),
    TEXT(0.62),
    XPATH_RELATIVE(0.45),
    POSITIONAL(0.20);

    private final double baseWeight;

    LocatorStrategyKind(double baseWeight) {
        this.baseWeight = baseWeight;
    }

    public double baseWeight() {
        return baseWeight;
    }
}
