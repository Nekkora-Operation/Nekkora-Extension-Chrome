package com.nekkora.extension.core.snapshot;

/**
 * Informações do documento principal observadas diretamente.
 *
 * @param url            {@code location.href}
 * @param title          {@code document.title}
 * @param readyState     {@code document.readyState}
 * @param viewport       dimensões do viewport
 * @param navigationKind origem da captura: {@code INITIAL}, {@code SPA_ROUTE}, {@code RELOAD}, {@code MANUAL}
 * @param pageSessionId  identificador gerado pelo content script a cada carga de documento; ids de nós
 *                       só são comparáveis entre capturas da MESMA sessão de página
 */
public record DocumentInfo(String url, String title, String readyState, Viewport viewport, String navigationKind,
                           String pageSessionId) {

    /** Segunda barreira de privacidade (NEKKORA-REAL-001): URL e título sob a mesma política do navegador. */
    public DocumentInfo {
        url = com.nekkora.extension.core.privacy.Privacy.sanitizeUrl(url);
        title = com.nekkora.extension.core.privacy.Privacy.maskText(title);
    }
}
