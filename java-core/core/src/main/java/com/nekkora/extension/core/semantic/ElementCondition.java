package com.nekkora.extension.core.semantic;

/** Condições observáveis de um elemento. Podem coexistir. */
public enum ElementCondition {
    PRESENT,
    VISIBLE,
    HIDDEN,
    OUT_OF_VIEWPORT,
    ENABLED,
    DISABLED,
    READ_ONLY,
    REQUIRED,
    INTERACTIVE,
    NON_INTERACTIVE,
    /** Previsto (ex.: opção anunciada por aria-setsize) mas ausente da DOM. */
    NOT_MATERIALIZED,
    /** Presente mas fora de alcance do usuário (inert, bloqueado por modal, escopo fechado). */
    INACCESSIBLE,
    /** Estado não pôde ser observado. */
    UNKNOWN_STATE
}
