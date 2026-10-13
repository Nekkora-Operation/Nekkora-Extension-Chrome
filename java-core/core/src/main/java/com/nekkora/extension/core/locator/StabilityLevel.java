package com.nekkora.extension.core.locator;

/** Estabilidade estimada de um localizador ou atributo, do mais confiável ao menos confiável. */
public enum StabilityLevel {
    /** Valor permaneceu idêntico em múltiplas capturas. */
    OBSERVED_STABLE(1.1),
    /** Nenhum indício de geração dinâmica (inferência). */
    HEURISTIC_STABLE(1.0),
    /** Sem informação suficiente. */
    UNKNOWN(0.85),
    /** Aparência de valor gerado (UUID, timestamp, sufixo numérico longo) — inferência. */
    HEURISTIC_UNSTABLE(0.45),
    /** Valor mudou entre capturas do mesmo alvo. */
    OBSERVED_UNSTABLE(0.1);

    private final double factor;

    StabilityLevel(double factor) {
        this.factor = factor;
    }

    public double factor() {
        return factor;
    }

    public boolean isObserved() {
        return this == OBSERVED_STABLE || this == OBSERVED_UNSTABLE;
    }

    public static StabilityLevel weakest(StabilityLevel a, StabilityLevel b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
