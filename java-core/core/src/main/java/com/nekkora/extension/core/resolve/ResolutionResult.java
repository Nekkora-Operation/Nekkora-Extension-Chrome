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
        TargetIdentity updatedIdentity) {

    public ResolutionResult {
        locatorChecks = List.copyOf(locatorChecks);
        candidates = List.copyOf(candidates);
        attributeChanges = Map.copyOf(attributeChanges);
        evidence = List.copyOf(evidence);
    }
}
