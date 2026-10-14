package com.nekkora.extension.core.protection;

import java.util.List;

/**
 * Resultado do diagnóstico de proteções de uma página.
 *
 * @param catalogVersion    versão do catálogo usada
 * @param status            {@code OBSERVED} (há tecnologia identificada), {@code INSUFFICIENT_EVIDENCE} (somente
 *                          indicadores genéricos ou fracos) ou {@code NOT_OBSERVED} — nunca "sem proteção"
 * @param assessments       diagnósticos por tecnologia (podem coexistir)
 * @param genericIndicators indicadores genéricos observados (ex.: texto "CAPTCHA"), que não identificam fornecedor
 * @param limitations       limites da observação (inclui sempre o limite de proteções no servidor/borda)
 */
public record ProtectionReport(String catalogVersion, String status, List<ProtectionAssessment> assessments,
                               List<ProtectionEvidence> genericIndicators, List<String> limitations) {

    public ProtectionReport {
        assessments = List.copyOf(assessments);
        genericIndicators = List.copyOf(genericIndicators);
        limitations = List.copyOf(limitations);
    }
}
