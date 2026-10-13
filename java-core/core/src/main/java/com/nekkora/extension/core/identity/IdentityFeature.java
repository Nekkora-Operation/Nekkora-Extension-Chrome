package com.nekkora.extension.core.identity;

/**
 * Características que compõem a identidade de um alvo, com o peso usado na comparação.
 * A ordem da declaração é também a prioridade como discriminador (do mais forte ao mais fraco).
 */
public enum IdentityFeature {
    TEST_ATTRIBUTE(3.0),
    ROW_KEY(3.0),
    TABLE(2.0),
    DIALOG(2.0),
    FORM(1.5),
    FIELD_GROUP(1.5),
    LABEL(2.0),
    CONTAINER_HEADING(2.0),
    ROW_TEXTS(2.0),
    COLUMN(1.0),
    REGION(1.0),
    CONTEXT_TEXTS(1.5),
    NAME(3.0),
    TEXT(1.0),
    NAME_ATTRIBUTE(2.0),
    ID_ATTRIBUTE(2.0),
    TYPE_ATTRIBUTE(0.5),
    SCOPE(1.0);

    private final double weight;

    IdentityFeature(double weight) {
        this.weight = weight;
    }

    public double weight() {
        return weight;
    }
}
