package com.nekkora.extension.core.protection;

import com.nekkora.extension.core.frames.ElementAccessPath;

import java.util.List;

/**
 * Diagnóstico de UMA tecnologia de proteção observada (CHROME-003). Passivo: nenhuma verificação é executada.
 *
 * @param assessmentId    identificador estável dentro da análise ({@code protection:<assinatura>@<documento>})
 * @param status          {@code DETECTED} (confiança alta/média), {@code POSSIBLE} (baixa) ou {@code INSUFFICIENT_EVIDENCE}
 * @param provider        fornecedor ({@code UNKNOWN} quando não identificado)
 * @param product         produto ({@code UNKNOWN} quando não identificado)
 * @param category        {@code CAPTCHA}, {@code HUMAN_VERIFICATION}, {@code RISK_ASSESSMENT}, {@code CHALLENGE},
 *                        {@code CHALLENGE_PAGE}, {@code BOT_MANAGEMENT}, {@code WAF}, {@code ACCESS_BLOCK}
 * @param confidence      {@code HIGH}, {@code MEDIUM}, {@code LOW} ou {@code UNKNOWN}
 * @param challengeState  {@code TECHNOLOGY_DETECTED}, {@code CHALLENGE_PRESENT}, {@code CHALLENGE_ACTIVE},
 *                        {@code CHALLENGE_COMPLETED}, {@code ACCESS_BLOCKED} ou {@code UNKNOWN}
 * @param signatureId     assinatura do catálogo que originou o diagnóstico ({@code null} para genérico)
 * @param signatureVersion versão da assinatura
 * @param basis           base da assinatura ({@code OFFICIAL_DOCUMENTATION}/{@code THIRD_PARTY_OBSERVATION}/{@code GENERIC})
 * @param evidence        evidências usadas
 * @param location        onde a proteção foi observada
 * @param confidenceReasons justificativa do nível de confiança
 * @param limitations     limites da observação
 */
public record ProtectionAssessment(String assessmentId, String status, String provider, String product, String category,
                                   String confidence, String challengeState, String signatureId, String signatureVersion,
                                   String basis, List<ProtectionEvidence> evidence, Location location,
                                   List<String> confidenceReasons, List<String> limitations) {

    public ProtectionAssessment {
        evidence = List.copyOf(evidence);
        confidenceReasons = List.copyOf(confidenceReasons);
        limitations = List.copyOf(limitations);
    }

    /**
     * Localização na hierarquia documental.
     *
     * @param documentId   documento lógico onde a evidência principal foi observada
     * @param frameId      frame lógico
     * @param insideFrame  a proteção está dentro de um iframe (ou é um iframe)
     * @param frameChain   rótulos dos frames do principal até o documento
     * @param inspectable  o conteúdo da região pôde ser inspecionado
     * @param widgetNodeId elemento principal do widget/desafio, quando houver
     * @param accessPath   trajetória até o elemento principal
     */
    public record Location(String documentId, String frameId, boolean insideFrame, List<String> frameChain, boolean inspectable,
                           String widgetNodeId, ElementAccessPath accessPath) {
        public Location {
            frameChain = frameChain == null ? List.of() : List.copyOf(frameChain);
        }
    }
}
