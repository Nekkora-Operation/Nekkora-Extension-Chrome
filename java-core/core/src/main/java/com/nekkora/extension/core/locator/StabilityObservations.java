package com.nekkora.extension.core.locator;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Histórico de valores de atributos de um mesmo alvo lógico em capturas sucessivas. Permite promover
 * a estabilidade de "inferida" para "comprovada" (ou "instável observada").
 */
@FunctionalInterface
public interface StabilityObservations {

    StabilityObservations NONE = attr -> Optional.empty();

    /** Valores observados do atributo em cada captura (ordem cronológica; {@code null} = ausente). */
    Optional<List<String>> history(String attribute);

    static StabilityObservations of(Map<String, List<String>> map) {
        return attr -> Optional.ofNullable(map.get(attr));
    }
}
