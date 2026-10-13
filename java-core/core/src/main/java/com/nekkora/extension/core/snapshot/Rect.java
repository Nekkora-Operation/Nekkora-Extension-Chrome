package com.nekkora.extension.core.snapshot;

/** Retângulo em pixels CSS relativo ao viewport do frame no momento da captura. */
public record Rect(double x, double y, double width, double height) {

    public boolean hasNoArea() {
        return width <= 0 || height <= 0;
    }
}
