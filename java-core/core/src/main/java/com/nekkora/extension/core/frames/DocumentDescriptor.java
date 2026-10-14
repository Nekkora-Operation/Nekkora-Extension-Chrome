package com.nekkora.extension.core.frames;

import java.util.List;

/**
 * Documento observado (principal ou aninhado). Identificadores de elementos só têm significado dentro do
 * documento: um id repetido em documentos diferentes não é um id duplicado.
 *
 * @param documentId        identificador lógico ({@code doc:<frameId>})
 * @param frameId           frame lógico que carrega o documento
 * @param browserDocumentId {@code documentId} do Chrome, quando informado pela extensão ({@code null} caso contrário)
 * @param url               URL efetiva sanitizada
 * @param queryKeys         nomes dos parâmetros da query (valores nunca registrados)
 * @param origin            origem efetiva
 * @param title             título
 * @param readyState        {@code loading}, {@code interactive}, {@code complete} quando observado
 * @param kind              ver {@link FrameDescriptor#documentKind()}
 * @param lifecycle         {@code ACTIVE}, {@code LOADING}, {@code INACCESSIBLE}, {@code TEMPORARY}
 * @param accessible        conteúdo inventariado
 * @param pageSessionId     sessão de página do content script que capturou o documento (quando houver)
 * @param elementCount      elementos inventariados
 */
public record DocumentDescriptor(String documentId, String frameId, String browserDocumentId, String url, List<String> queryKeys,
                                 String origin, String title, String readyState, String kind, String lifecycle,
                                 boolean accessible, String pageSessionId, int elementCount) {

    public DocumentDescriptor {
        queryKeys = queryKeys == null ? List.of() : List.copyOf(queryKeys);
    }
}
