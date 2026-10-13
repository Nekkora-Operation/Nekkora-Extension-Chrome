package com.nekkora.extension.core.snapshot;

/**
 * Estatísticas da captura medidas no navegador.
 *
 * @param elementsVisited elementos percorridos
 * @param nodesEmitted    nós emitidos no snapshot
 * @param durationMs      duração da captura no content script
 * @param truncated       {@code true} quando o limite de nós foi atingido
 * @param maxNodes        limite configurado
 */
public record CaptureStats(int elementsVisited, int nodesEmitted, double durationMs, boolean truncated, int maxNodes) {
}
