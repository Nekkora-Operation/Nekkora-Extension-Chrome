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
                        String title, String readyState, String registryEpoch) {

    /** D7: época do registro de nós do content script que produziu o documento (forma fechada; senão null). */
    private static final java.util.regex.Pattern EPOCH = java.util.regex.Pattern.compile("[a-z0-9]{1,16}");

    /** Construtor CHROME-003 (sem época do registro de nós). */
    public FrameInfo(String frameId, String parentFrameId, String hostNodeId, String url, boolean accessible, String reason,
                     String title, String readyState) {
        this(frameId, parentFrameId, hostNodeId, url, accessible, reason, title, readyState, null);
    }

    /** Segunda barreira de privacidade (NEKKORA-REAL-001): {@code url} sanitizada (antes: URL completa). */
    public FrameInfo {
        url = com.nekkora.extension.core.privacy.Privacy.sanitizeUrl(url);
        title = com.nekkora.extension.core.privacy.Privacy.maskText(title);
        registryEpoch = registryEpoch != null && EPOCH.matcher(registryEpoch).matches() ? registryEpoch : null;
    }

    /** Construtor CHROME-001/002. */
    public FrameInfo(String frameId, String parentFrameId, String hostNodeId, String url, boolean accessible, String reason) {
        this(frameId, parentFrameId, hostNodeId, url, accessible, reason, null, null);
    }

    public FrameInfo withParent(String newParentFrameId, String newHostNodeId) {
        return new FrameInfo(frameId, newParentFrameId, newHostNodeId, url, accessible, reason, title, readyState, registryEpoch);
    }
}
