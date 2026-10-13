package com.nekkora.extension.core.resolve;

/** Resultado de uma tentativa de reencontro. Somente {@link #RESOLVED} autoriza o uso do elemento. */
public enum ResolutionStatus {
    /** Exatamente um candidato teve a identidade confirmada. */
    RESOLVED,
    /** Nenhum candidato compatível foi encontrado. */
    NOT_FOUND,
    /** Dois ou mais candidatos permanecem igualmente plausíveis. */
    AMBIGUOUS,
    /** O endereço do alvo agora contém um elemento semanticamente incompatível. */
    IDENTITY_MISMATCH,
    /** O nó referenciado foi removido nesta mesma sessão de página e não há substituto confirmável. */
    STALE_TARGET,
    /** O alvo pertence a um escopo (iframe, shadow root fechado) que não pode ser inspecionado. */
    SCOPE_UNAVAILABLE,
    /** Há candidato plausível, mas as evidências não bastam para comprovar a identidade. */
    INSUFFICIENT_EVIDENCE
}
