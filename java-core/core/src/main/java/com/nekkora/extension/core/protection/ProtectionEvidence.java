package com.nekkora.extension.core.protection;

/**
 * Evidência observada para uma classificação de proteção.
 *
 * @param type        {@code SCRIPT_RESOURCE}, {@code FRAME_RESOURCE}, {@code NETWORK_RESOURCE}, {@code WIDGET},
 *                    {@code DOM_ELEMENT}, {@code ATTRIBUTE}, {@code RESPONSE_FIELD}, {@code DOCUMENT_TITLE}, {@code TEXT}
 * @param indicatorId indicador do catálogo
 * @param detail      descrição legível (URLs sempre sanitizadas; valores de campos nunca)
 * @param source      {@code DOM}, {@code RESOURCE_TIMING}, {@code FRAME_METADATA}, {@code BROWSER_METADATA}
 * @param strength    força do indicador
 * @param signal      o que evidencia (tecnologia, widget, desafio ativo, página de desafio, bloqueio, campo preenchido)
 * @param nodeId      elemento relacionado (quando houver)
 * @param documentId  documento lógico
 * @param frameId     frame lógico
 * @param visible     elemento visível no momento da captura ({@code null} quando não aplicável)
 * @param simulated   evidência produzida por metadado de SIMULAÇÃO do laboratório (ex.: script com tipo inerte,
 *                    {@code data-nekkora-simulated-src}); nunca ocorre em páginas reais
 */
public record ProtectionEvidence(String type, String indicatorId, String detail, String source, String strength, String signal,
                                 String nodeId, String documentId, String frameId, Boolean visible, boolean simulated) {
}
