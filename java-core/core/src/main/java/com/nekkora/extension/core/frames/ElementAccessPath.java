package com.nekkora.extension.core.frames;

import java.util.List;

/**
 * Trajetória de acesso a um elemento (CHROME-003): a sequência de escopos (documento principal → iframes →
 * shadow roots → elemento) separada do localizador do elemento. Independente da API do Chrome: um executor
 * futuro entra em cada escopo pelo localizador do passo e valida a condição esperada.
 *
 * @param targetNodeId        nó temporário do alvo
 * @param target              tipo e nome do alvo
 * @param steps               passos, do documento principal ao elemento
 * @param documentsTraversed  documentos atravessados (inclui o principal)
 * @param frameDepth          iframes atravessados
 * @param shadowDepth         shadow roots atravessados
 * @param accessible          todos os passos são inspecionáveis
 * @param blockingRestriction primeira restrição que impede o acesso ({@code null} se nenhuma)
 * @param stability           pior estabilidade entre os localizadores dos passos
 * @param explanation         explicação legível produzida a partir das evidências
 */
public record ElementAccessPath(String targetNodeId, Target target, List<Step> steps, int documentsTraversed, int frameDepth,
                                int shadowDepth, boolean accessible, String blockingRestriction, String stability,
                                String explanation) {

    public ElementAccessPath {
        steps = List.copyOf(steps);
    }

    /** @param kind tipo semântico; @param name nome acessível */
    public record Target(String kind, String name) {
    }

    /**
     * Passo da trajetória.
     *
     * @param type              {@code MAIN_DOCUMENT}, {@code IFRAME}, {@code SHADOW_ROOT}, {@code ELEMENT}
     * @param label             rótulo legível (ex.: título/nome do iframe, nome do elemento)
     * @param documentId        documento lógico onde o passo é executado (para IFRAME: documento que CONTÉM o iframe)
     * @param enteredDocumentId documento lógico em que se entra (somente IFRAME)
     * @param browserDocumentId {@code documentId} do Chrome do documento em que se entra, quando conhecido
     * @param nodeId            nó do snapshot (iframe, host de shadow ou elemento)
     * @param locator           localizador CSS (ou semântico) no escopo do passo
     * @param locatorStrategy   estratégia do localizador
     * @param matchCount        correspondências do localizador no escopo
     * @param stability         estabilidade do localizador
     * @param frameIdentity     identidade lógica do frame (somente IFRAME)
     * @param url               URL efetiva do documento em que se entra (somente IFRAME / MAIN_DOCUMENT), sanitizada
     * @param expectedCondition condição a validar ao executar o passo
     * @param restriction       restrição de acesso ({@code null}, {@code FRAME_SOURCE_CONTEXT}, {@code PERMISSION_DENIED},
     *                          {@code SANDBOX_RESTRICTED}, {@code FRAME_LOADING}, {@code SHADOW_ROOT_CLOSED})
     */
    public record Step(String type, String label, String documentId, String enteredDocumentId, String browserDocumentId,
                       String nodeId, String locator, String locatorStrategy, int matchCount, String stability,
                       FrameIdentity frameIdentity, String url, String expectedCondition, String restriction) {
    }
}
