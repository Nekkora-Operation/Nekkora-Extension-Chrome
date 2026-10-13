package com.nekkora.extension.core.snapshot;

/**
 * Requisição de rede observada pela Resource Timing API (somente URL, tipo e tempos — nunca corpo ou
 * cabeçalhos). Só existe quando a página realmente emitiu a requisição.
 *
 * @param url           URL requisitada
 * @param initiatorType {@code fetch}, {@code xmlhttprequest}, ...
 * @param startTime     instante relativo ao início da navegação (ms)
 * @param duration      duração (ms)
 */
public record ObservedRequest(String url, String initiatorType, double startTime, double duration) {
}
