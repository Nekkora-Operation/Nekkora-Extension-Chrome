package com.nekkora.extension.core.identity;

/**
 * Comparação de uma característica entre a identidade registrada e um candidato.
 *
 * @param feature  característica
 * @param outcome  resultado
 * @param quality  qualidade da concordância em [0,1]
 * @param expected valor esperado (resumo)
 * @param actual   valor observado (resumo)
 */
public record FeatureResult(IdentityFeature feature, FeatureOutcome outcome, double quality, String expected, String actual) {
}
