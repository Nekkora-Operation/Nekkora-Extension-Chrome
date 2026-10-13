package com.nekkora.extension.core.pagemap;

import java.util.List;

/**
 * Evidência de identificação ou resolução, sempre com a FONTE da informação.
 *
 * @param subject    nó temporário ou alvo lógico
 * @param kind       {@code CLASSIFICATION}, {@code FAMILY}, {@code LOCATOR}, {@code LIVE_VERIFICATION}, {@code RESOLUTION}, {@code INTERACTION}
 * @param source     {@code DOM}, {@code ENGINE}, {@code BROWSER_LIVE}, {@code RESOURCE_TIMING}, {@code TEST_INSTRUMENTATION}
 * @param statements afirmações verificáveis
 * @param recordedAt instante
 */
public record InspectionEvidence(String subject, String kind, String source, List<String> statements, String recordedAt) {

    public InspectionEvidence {
        statements = List.copyOf(statements);
    }
}
