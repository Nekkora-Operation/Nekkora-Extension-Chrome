package com.nekkora.extension.core.frames;

/**
 * Metadados de um frame fornecidos pelo NAVEGADOR (chrome.webNavigation), associados pela extensão ao frame
 * lógico do snapshot. Identificadores do Chrome são mantidos separados dos identificadores lógicos do motor e
 * nunca são inventados: campos indisponíveis ficam {@code null}.
 *
 * @param browserFrameId       {@code frameId} do Chrome (válido no contexto do navegador, não é localizador lógico)
 * @param browserDocumentId    {@code documentId} do Chrome (diferencia documentos ao longo de navegações)
 * @param parentBrowserFrameId {@code parentFrameId} do Chrome ({@code -1} para o documento principal)
 * @param parentDocumentId     {@code parentDocumentId} do Chrome
 * @param url                  URL efetiva sanitizada informada pelo navegador
 * @param frameType            {@code outermost_frame}, {@code sub_frame}, {@code fenced_frame} (quando informado)
 * @param documentLifecycle    {@code active}, {@code prerender}, {@code cached}, {@code pending_deletion} (quando informado)
 * @param hostNodeId           iframe hospedeiro no snapshot, quando associado por handshake ({@code null} se não associado)
 * @param mapping              {@code TOP}, {@code HANDSHAKE}, {@code URL_MATCH} ou {@code UNMAPPED}
 * @param inspection           situação da inspeção decidida pela extensão: {@code TOP}, {@code ATTACHED},
 *                             {@code CAPTURED_BY_PARENT}, {@code PENDING}, {@code NOT_ATTACHED}, {@code FAILED},
 *                             {@code NO_PERMISSION}, {@code NO_CONTENT_SCRIPT}
 * @param source               fonte de captura ({@code top} ou {@code x<frameId>}) quando o frame foi anexado
 * @param reason               motivo complementar (ex.: {@code POLICY_SANDBOX})
 */
public record BrowserFrameMetadata(Integer browserFrameId, String browserDocumentId, Integer parentBrowserFrameId,
                                   String parentDocumentId, String url, String frameType, String documentLifecycle,
                                   String hostNodeId, String mapping, String inspection, String source, String reason) {

    /** CHROME-SECURITY-001 (3ª barreira): a URL que o navegador informa entra no motor só como MODELO (nekkora-privacy/2). */
    public BrowserFrameMetadata {
        url = com.nekkora.extension.core.privacy.CapturePrivacyPolicy.urlTemplate(url);
    }
}
