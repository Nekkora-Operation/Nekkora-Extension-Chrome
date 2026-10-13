package com.nekkora.extension.core.snapshot;

/** Métricas de rolagem de um elemento rolável (overflow auto/scroll). */
public record ScrollMetrics(double scrollHeight, double clientHeight, double scrollTop) {
}
