package com.nekkora.extension.core.resolve;

import com.nekkora.extension.core.identity.TargetIdentity;
import com.nekkora.extension.core.locator.LocatorSet;

import java.util.List;
import java.util.Map;

/**
 * Resultado de uma tentativa de reencontro, com todas as evidências.
 *
 * @param schemaVersion     versão do contrato
 * @param targetId          alvo lógico
 * @param captureId         captura usada
 * @param status            resultado
 * @param resolvedNodeId    nó resolvido (somente quando {@code RESOLVED})
 * @param score             pontuação do candidato escolhido (ou do melhor candidato)
 * @param nodeReplaced      o nó resolvido não é o mesmo nó registrado (re-renderização/substituição)
 * @param scope             situação do escopo ({@code AVAILABLE}, {@code UNAVAILABLE: ...}, {@code HOST_NOT_FOUND})
 * @param locatorChecks     execução de cada localizador registrado
 * @param candidates        candidatos avaliados
 * @param attributeChanges  atributos voláteis que mudaram ({@code atributo -> [antes, depois]})
 * @param evidence          explicação legível da decisão
 * @param updatedLocators   localizadores reavaliados para o nó resolvido
 * @param updatedIdentity   identidade com referência e histórico atualizados (somente quando {@code RESOLVED})
 * @param diagnostics       detalhes de diagnóstico compatíveis com o status (CHROME-003): {@code FRAME_NOT_FOUND},
 *                          {@code FRAME_AMBIGUOUS}, {@code FRAME_DOCUMENT_CHANGED}, {@code FRAME_PERMISSION_DENIED},
 *                          {@code FRAME_SANDBOX_RESTRICTED}, {@code FRAME_LOADING}, {@code SHADOW_ROOT_CLOSED},
 *                          {@code SHADOW_HOST_NOT_FOUND}, {@code TARGET_NOT_MATERIALIZED}, {@code TARGET_AMBIGUOUS}
 */
public record ResolutionResult(
        String schemaVersion,
        String targetId,
        String captureId,
        ResolutionStatus status,
        String resolvedNodeId,
        double score,
        boolean nodeReplaced,
        String scope,
        List<LocatorCheck> locatorChecks,
        List<CandidateEvaluation> candidates,
        Map<String, List<String>> attributeChanges,
        List<String> evidence,
        LocatorSet updatedLocators,
        TargetIdentity updatedIdentity,
        List<String> diagnostics) {

    /** Construtor CHROME-001/002 (sem diagnósticos detalhados). */
    public ResolutionResult(String schemaVersion, String targetId, String captureId, ResolutionStatus status, String resolvedNodeId,
                            double score, boolean nodeReplaced, String scope, List<LocatorCheck> locatorChecks,
                            List<CandidateEvaluation> candidates, Map<String, List<String>> attributeChanges, List<String> evidence,
                            LocatorSet updatedLocators, TargetIdentity updatedIdentity) {
        this(schemaVersion, targetId, captureId, status, resolvedNodeId, score, nodeReplaced, scope, locatorChecks, candidates,
                attributeChanges, evidence, updatedLocators, updatedIdentity, List.of());
    }

    public ResolutionResult {
        locatorChecks = List.copyOf(locatorChecks);
        candidates = List.copyOf(candidates);
        attributeChanges = Map.copyOf(attributeChanges);
        evidence = List.copyOf(evidence);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }
}
