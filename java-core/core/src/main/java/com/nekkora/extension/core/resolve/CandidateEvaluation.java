package com.nekkora.extension.core.resolve;

import com.nekkora.extension.core.identity.FeatureResult;

import java.util.List;

/**
 * Avaliação de um candidato durante a resolução.
 *
 * @param nodeId   nó candidato
 * @param verdict  {@code CONFIRMED}, {@code VIABLE}, {@code DISQUALIFIED} ou {@code INCOMPATIBLE}
 * @param score    pontuação ponderada em [0,1]
 * @param foundBy  como o candidato foi encontrado (estratégias de localizador ou {@code BROAD_SEARCH})
 * @param features comparação de cada característica
 * @param reasons  justificativas do veredito
 */
public record CandidateEvaluation(String nodeId, String verdict, double score, List<String> foundBy,
                                  List<FeatureResult> features, List<String> reasons) {

    public CandidateEvaluation {
        foundBy = List.copyOf(foundBy);
        features = List.copyOf(features);
        reasons = List.copyOf(reasons);
    }
}
