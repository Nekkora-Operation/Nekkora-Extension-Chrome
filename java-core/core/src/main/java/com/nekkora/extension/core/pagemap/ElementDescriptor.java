package com.nekkora.extension.core.pagemap;

import com.nekkora.extension.core.context.ElementFamily;
import com.nekkora.extension.core.locator.LocatorSet;
import com.nekkora.extension.core.semantic.ElementCondition;
import com.nekkora.extension.core.snapshot.Rect;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Elemento no PageMap.
 *
 * @param nodeId          identificador TEMPORÁRIO do nó DOM (válido só nesta sessão de página)
 * @param tag             tag
 * @param scopeId         escopo de consulta
 * @param frameId         frame
 * @param parentNodeId    pai
 * @param kind            finalidade
 * @param category        categoria
 * @param role            papel
 * @param nativeSemantics semântica nativa
 * @param confidence      confiança
 * @param text            textos diferenciados (próprio, descendente, acessível, contextual)
 * @param attributes      atributos exportáveis (redigidos)
 * @param conditions      condições
 * @param sensitive       elemento sensível
 * @param rect            retângulo
 * @param family          família (somente para categorias configuradas)
 * @param locators        localizadores (somente para categorias configuradas)
 * @param evidence        evidências da classificação
 * @param uncertainties   incertezas
 */
public record ElementDescriptor(
        String nodeId,
        String tag,
        String scopeId,
        String frameId,
        String parentNodeId,
        String kind,
        String category,
        String role,
        boolean nativeSemantics,
        String confidence,
        ElementText text,
        Map<String, String> attributes,
        Set<ElementCondition> conditions,
        boolean sensitive,
        Rect rect,
        ElementFamily family,
        LocatorSet locators,
        List<String> evidence,
        List<String> uncertainties,
        String documentId) {

    /** Construtor CHROME-001/002 (documento derivado do frame). */
    public ElementDescriptor(String nodeId, String tag, String scopeId, String frameId, String parentNodeId, String kind,
                             String category, String role, boolean nativeSemantics, String confidence, ElementText text,
                             Map<String, String> attributes, Set<ElementCondition> conditions, boolean sensitive, Rect rect,
                             ElementFamily family, LocatorSet locators, List<String> evidence, List<String> uncertainties) {
        this(nodeId, tag, scopeId, frameId, parentNodeId, kind, category, role, nativeSemantics, confidence, text, attributes,
                conditions, sensitive, rect, family, locators, evidence, uncertainties, frameId == null ? null : "doc:" + frameId);
    }

    /**
     * Textos de um elemento, sem duplicar indiscriminadamente o texto dos descendentes.
     *
     * @param own                   texto dos nós de texto filhos diretos
     * @param descendant            texto renderizado da subárvore (somente controles, títulos, células, rótulos)
     * @param accessibleName        nome acessível
     * @param accessibleNameSource  origem do nome
     * @param accessibleDescription descrição acessível
     * @param contextual            textos da família usados para diferenciar o elemento
     */
    public record ElementText(String own, String descendant, String accessibleName, String accessibleNameSource,
                              String accessibleDescription, List<String> contextual, Boolean suppressed) {

        /** Construtor CHROME-002/003 (sem a marca de texto retido). */
        public ElementText(String own, String descendant, String accessibleName, String accessibleNameSource,
                           String accessibleDescription, List<String> contextual) {
            this(own, descendant, accessibleName, accessibleNameSource, accessibleDescription, contextual, null);
        }
    }
}
