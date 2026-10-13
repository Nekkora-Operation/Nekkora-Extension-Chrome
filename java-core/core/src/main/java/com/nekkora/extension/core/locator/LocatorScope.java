package com.nekkora.extension.core.locator;

import java.util.List;

/**
 * Escopo onde o localizador deve ser executado.
 *
 * @param type    {@code MAIN_DOCUMENT}, {@code FRAME} ou {@code SHADOW_ROOT}
 * @param scopeId identificador interno do escopo no snapshot
 * @param frameId frame que contém o escopo
 * @param chain   saltos do documento principal até o escopo (cada salto localiza um iframe ou shadow host)
 */
public record LocatorScope(String type, String scopeId, String frameId, List<Hop> chain) {

    public LocatorScope {
        chain = List.copyOf(chain);
    }

    /**
     * Salto até um escopo interno.
     *
     * @param kind       {@code FRAME} ou {@code SHADOW_HOST}
     * @param hostNodeId nó host no snapshot
     * @param css        CSS do host no escopo pai
     * @param xpath      XPath do host no escopo pai (null em shadow)
     * @param matchCount correspondências do CSS do host no escopo pai
     * @param query      localizador estruturado do host
     */
    public record Hop(String kind, String hostNodeId, String css, String xpath, int matchCount,
                      com.nekkora.extension.core.locator.query.LocatorQuery query) {
    }

    public static LocatorScope main() {
        return new LocatorScope("MAIN_DOCUMENT", "doc:f0", "f0", List.of());
    }
}
