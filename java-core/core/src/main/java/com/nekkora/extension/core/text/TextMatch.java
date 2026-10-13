package com.nekkora.extension.core.text;

/** Nível de concordância textual, do mais forte ao mais fraco. */
public enum TextMatch {
    EXACT(1.0),
    BOTH_EMPTY(0.9),
    FOLDED(0.9),
    DIGITS_VARIED(0.7),
    PARTIAL(0.4),
    NONE(0.0);

    private final double weight;

    TextMatch(double weight) {
        this.weight = weight;
    }

    public double weight() {
        return weight;
    }

    /** Concordância suficiente para considerar o mesmo texto lógico. */
    public boolean isStrong() {
        return this == EXACT || this == FOLDED || this == BOTH_EMPTY;
    }

    public boolean isAcceptable() {
        return isStrong() || this == DIGITS_VARIED;
    }
}
