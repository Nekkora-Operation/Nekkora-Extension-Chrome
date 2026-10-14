package com.nekkora.extension.core.diagnostics;

import java.util.List;
import java.util.Map;

/**
 * Avaliação técnica final da página (CHROME-003): resume os obstáculos observados e os limites da observação.
 * Não é uma pontuação de "facilidade": categorias justificadas por evidências.
 *
 * @param page                    URL (sanitizada) e título
 * @param documents               {@code count}, {@code maximumFrameDepth}, {@code inaccessible}, {@code loading}
 * @param shadowRoots             {@code open}, {@code closed}
 * @param elements                {@code observed}, {@code interactive}
 * @param obstacles               obstáculos observados
 * @param interactionDependencies elementos que só materializam após uma interação (registrados, nunca executados)
 * @param protections             resumo das proteções ({@code provider}, {@code product}, {@code confidence}, {@code challengeState})
 * @param protectionStatus        status do diagnóstico de proteções
 * @param recommendations         recomendações classificadas por tipo
 * @param status                  {@code NO_KNOWN_OBSTACLES}, {@code MANAGEABLE}, {@code REQUIRES_SPECIAL_HANDLING},
 *                                {@code REQUIRES_HUMAN_INTERVENTION}
 * @param confidence              confiança da avaliação ({@code HIGH}/{@code MEDIUM}/{@code LOW}) conforme a cobertura observada
 * @param basis                   justificativas do status e da confiança
 * @param limitations             limites da observação
 */
public record AutomationReadinessAssessment(Map<String, String> page, Map<String, Integer> documents, Map<String, Integer> shadowRoots,
                                            Map<String, Integer> elements, List<AutomationObstacle> obstacles,
                                            List<InteractionDependency> interactionDependencies, List<Map<String, String>> protections,
                                            String protectionStatus, List<Recommendation> recommendations, String status,
                                            String confidence, List<String> basis, List<String> limitations) {

    public AutomationReadinessAssessment {
        obstacles = List.copyOf(obstacles);
        interactionDependencies = List.copyOf(interactionDependencies);
        protections = List.copyOf(protections);
        recommendations = List.copyOf(recommendations);
        basis = List.copyOf(basis);
        limitations = List.copyOf(limitations);
    }

    /**
     * @param type   {@code HUMAN_INTERVENTION}, {@code AUTHORIZED_API}, {@code ENVIRONMENT_CONFIGURATION},
     *               {@code MANUAL_VALIDATION}, {@code UNKNOWN_PROTECTION}, {@code NO_ACTION_REQUIRED}
     * @param reason justificativa
     * @param relatedObstacles obstáculos relacionados
     */
    public record Recommendation(String type, String reason, List<String> relatedObstacles) {
        public Recommendation {
            relatedObstacles = relatedObstacles == null ? List.of() : List.copyOf(relatedObstacles);
        }
    }

    /**
     * Elemento/região que só aparece após interação.
     *
     * @param type             {@code OPEN_MODAL}, {@code OPEN_DROPDOWN}, {@code SELECT_TAB}, {@code EXPAND_SECTION},
     *                         {@code LOAD_MORE}, {@code NAVIGATE}
     * @param regionNodeId     região/elemento dependente
     * @param controllerNodeId controle que a materializa, quando conhecido
     * @param interactiveInside elementos interativos ocultos dentro da região
     * @param basis            evidência ({@code aria-controls}, {@code details}, {@code dialog fechado}, heurística…)
     */
    public record InteractionDependency(String type, String regionNodeId, String controllerNodeId, int interactiveInside, String basis) {
    }
}
