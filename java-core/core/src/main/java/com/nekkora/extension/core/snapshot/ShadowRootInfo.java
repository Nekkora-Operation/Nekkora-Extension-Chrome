package com.nekkora.extension.core.snapshot;

/**
 * Shadow root detectado.
 *
 * @param hostNodeId nó host
 * @param mode       {@code open} ou {@code closed}
 * @param inspected  {@code true} quando o conteúdo foi inventariado
 * @param detection  como foi detectado: {@code ELEMENT_SHADOW_ROOT}, {@code EXTENSION_API}, {@code HEURISTIC}
 * @param reason     motivo de não inspeção, quando aplicável
 */
public record ShadowRootInfo(String hostNodeId, String mode, boolean inspected, String detection, String reason) {
}
