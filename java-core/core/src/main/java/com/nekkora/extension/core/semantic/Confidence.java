package com.nekkora.extension.core.semantic;

/** Confiança da classificação. */
public enum Confidence {
    /** Semântica nativa do HTML. */
    HIGH,
    /** Papel ARIA com evidências de suporte (foco, manipulador declarado, widget composto). */
    MEDIUM,
    /** Papel ARIA ou heurística sem evidência de suporte suficiente. */
    LOW
}
