package com.nekkora.extension.core.context;

/**
 * Relação estrutural ou semântica entre dois elementos.
 *
 * @param type       tipo
 * @param fromNodeId origem
 * @param toNodeId   destino
 * @param basis      como a relação foi estabelecida
 * @param detail     detalhe legível (ex.: atributo que declarou a relação)
 */
public record ContextRelation(RelationType type, String fromNodeId, String toNodeId, Basis basis, String detail) {

    /** Fundamento da relação. */
    public enum Basis {
        /** Hierarquia da DOM. */
        DOM_STRUCTURE,
        /** Declarada por atributo ARIA (aria-controls, aria-labelledby...). */
        ARIA_ATTRIBUTE,
        /** Declarada por atributo HTML (for, form, headers...). */
        HTML_ATTRIBUTE,
        /** Inferida por regra heurística (ex.: título mais próximo do contêiner). */
        HEURISTIC
    }
}
