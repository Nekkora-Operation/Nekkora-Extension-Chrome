package com.nekkora.extension.core.snapshot;

/**
 * Estatísticas da captura medidas no navegador.
 *
 * @param elementsVisited elementos percorridos
 * @param nodesEmitted    nós emitidos no snapshot
 * @param durationMs      duração total da captura no content script
 * @param truncated       {@code true} quando o limite de nós foi atingido
 * @param maxNodes        limite configurado
 * @param mode            {@code SYNC} (travessia contínua) ou {@code COOPERATIVE} (fatias com cessão da thread);
 *                        {@code null} em capturas anteriores à CHROME-002
 * @param slices          fatias executadas (captura cooperativa)
 * @param maxSliceMs      maior fatia contínua de trabalho na thread da página
 * @param consistency     {@code INSTANT} (síncrona) ou {@code BASE_PLUS_DELTA}: a travessia cooperativa não é um
 *                        instante único; mutações observadas durante ela são entregues no primeiro delta
 */
public record CaptureStats(int elementsVisited, int nodesEmitted, double durationMs, boolean truncated, int maxNodes,
                           String mode, Integer slices, Double maxSliceMs, String consistency) {

    public CaptureStats(int elementsVisited, int nodesEmitted, double durationMs, boolean truncated, int maxNodes) {
        this(elementsVisited, nodesEmitted, durationMs, truncated, maxNodes, null, null, null, null);
    }
}
