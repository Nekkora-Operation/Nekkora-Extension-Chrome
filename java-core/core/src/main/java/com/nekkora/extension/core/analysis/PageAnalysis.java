package com.nekkora.extension.core.analysis;

import com.nekkora.extension.core.context.ContextGraph;
import com.nekkora.extension.core.locator.LocatorEngine;
import com.nekkora.extension.core.semantic.SemanticIndex;
import com.nekkora.extension.core.snapshot.PageModel;

/**
 * Resultado da análise de um snapshot: modelo indexado, classificação, grafo de contexto e uma sessão
 * de geração de localizadores, mais as medições de tempo.
 *
 * @param model            modelo indexado
 * @param semantics        classificações
 * @param graph            grafo de contexto
 * @param locators         sessão de localizadores
 * @param indexMs          tempo de indexação
 * @param classificationMs tempo de classificação
 */
public record PageAnalysis(PageModel model, SemanticIndex semantics, ContextGraph graph, LocatorEngine.Session locators,
                           double indexMs, double classificationMs) {
}
