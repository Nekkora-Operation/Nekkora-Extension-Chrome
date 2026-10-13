package com.nekkora.extension.core.snapshot;

/** Viewport do documento principal no momento da captura. */
public record Viewport(double width, double height, double scrollX, double scrollY, double devicePixelRatio) {
}
