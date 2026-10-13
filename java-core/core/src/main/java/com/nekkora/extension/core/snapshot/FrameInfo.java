package com.nekkora.extension.core.snapshot;

/**
 * Frame encontrado durante a captura.
 *
 * @param frameId       identificador do frame ({@code f0} = documento principal)
 * @param parentFrameId frame pai, ou {@code null}
 * @param hostNodeId    nó {@code iframe}/{@code frame} que hospeda este documento, ou {@code null}
 * @param url           URL do frame quando observável
 * @param accessible    {@code true} quando a DOM do frame pôde ser percorrida
 * @param reason        motivo da inacessibilidade (ex.: {@code CROSS_ORIGIN}, {@code SANDBOXED}, {@code NOT_LOADED})
 */
public record FrameInfo(String frameId, String parentFrameId, String hostNodeId, String url, boolean accessible, String reason) {
}
