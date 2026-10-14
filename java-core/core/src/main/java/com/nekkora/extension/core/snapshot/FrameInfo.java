package com.nekkora.extension.core.snapshot;

/**
 * Frame encontrado durante a captura.
 *
 * @param frameId       identificador LÓGICO do frame no snapshot ({@code f0} = documento principal); não é o
 *                      {@code frameId} do Chrome
 * @param parentFrameId frame pai, ou {@code null}
 * @param hostNodeId    nó {@code iframe}/{@code frame} que hospeda este documento, ou {@code null}
 * @param url           URL efetiva do documento do frame quando observável
 * @param accessible    {@code true} quando a DOM do frame pôde ser percorrida
 * @param reason        motivo da inacessibilidade (ex.: {@code CROSS_ORIGIN}, {@code SANDBOXED}, {@code NOT_LOADED})
 * @param title         título do documento do frame (CHROME-003, opcional)
 * @param readyState    {@code document.readyState} do frame no momento da captura (CHROME-003, opcional)
 */
public record FrameInfo(String frameId, String parentFrameId, String hostNodeId, String url, boolean accessible, String reason,
                        String title, String readyState) {

    /** Construtor CHROME-001/002. */
    public FrameInfo(String frameId, String parentFrameId, String hostNodeId, String url, boolean accessible, String reason) {
        this(frameId, parentFrameId, hostNodeId, url, accessible, reason, null, null);
    }

    public FrameInfo withParent(String newParentFrameId, String newHostNodeId) {
        return new FrameInfo(frameId, newParentFrameId, newHostNodeId, url, accessible, reason, title, readyState);
    }
}
