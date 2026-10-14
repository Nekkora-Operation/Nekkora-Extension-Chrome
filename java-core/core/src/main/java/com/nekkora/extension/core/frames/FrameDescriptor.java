package com.nekkora.extension.core.frames;

import java.util.List;

/**
 * Propriedades de um frame (CHROME-003). O iframe é um DOCUMENTO associado a uma hierarquia, não só uma tag.
 *
 * @param frameId           identificador lógico do frame no snapshot ({@code f0} = documento principal)
 * @param documentId        documento lógico carregado no frame ({@code doc:<frameId>})
 * @param parentFrameId     frame pai lógico ({@code null} para o documento principal)
 * @param parentDocumentId  documento lógico pai
 * @param hostNodeId        elemento {@code iframe}/{@code frame} hospedeiro
 * @param depth             profundidade (0 = documento principal)
 * @param declaredSrc       URL declarada no atributo {@code src} (resolvida e sanitizada) — pode diferir da efetiva
 * @param srcdoc            o iframe usa {@code srcdoc}
 * @param effectiveUrl      URL efetiva do documento carregado, quando observável (sanitizada)
 * @param origin            origem efetiva ({@code null} quando não determinável; {@code "null"} para origem opaca)
 * @param name              atributo {@code name}
 * @param title             atributo {@code title} do iframe
 * @param documentTitle     título do documento do frame, quando inspecionado
 * @param sandbox           valor do atributo {@code sandbox} ({@code null} quando ausente)
 * @param documentKind      {@code MAIN}, {@code NETWORK}, {@code ABOUT_BLANK}, {@code INITIAL_ABOUT_BLANK}, {@code SRCDOC}, {@code UNKNOWN}
 * @param status            {@code INSPECTED}, {@code LOADING}, {@code PERMISSION_DENIED}, {@code SANDBOX_RESTRICTED},
 *                          {@code ATTACHING}, {@code INACCESSIBLE}
 * @param reason            motivo/explicação do status
 * @param inspectedBy       {@code top} (documento principal ou frame de mesma origem lido pelo pai) ou {@code x<n>} (content script do próprio frame)
 * @param insideShadowRoot  o hospedeiro está dentro de um shadow root
 * @param identity          identidade lógica
 * @param identityAmbiguous existe frame irmão com a mesma identidade (desambiguação impossível só pelo frame)
 * @param locator           localizador do iframe no escopo do documento pai (CSS), quando disponível
 * @param locatorStability  estabilidade do localizador do iframe
 * @param childFrameIds     frames filhos
 * @param elementCount      elementos inventariados no documento do frame
 * @param interactiveCount  elementos interativos
 * @param shadowRoots       shadow roots no documento do frame
 * @param browser           metadados do navegador, quando associados ({@code null} se indisponíveis)
 */
public record FrameDescriptor(String frameId, String documentId, String parentFrameId, String parentDocumentId,
                              String hostNodeId, int depth, String declaredSrc, boolean srcdoc, String effectiveUrl,
                              String origin, String name, String title, String documentTitle, String sandbox,
                              String documentKind, String status, String reason, String inspectedBy, boolean insideShadowRoot,
                              FrameIdentity identity, boolean identityAmbiguous, String locator, String locatorStability,
                              List<String> childFrameIds, int elementCount, int interactiveCount, int shadowRoots,
                              BrowserFrameMetadata browser) {

    public FrameDescriptor {
        childFrameIds = childFrameIds == null ? List.of() : List.copyOf(childFrameIds);
    }

    public boolean inspected() {
        return "INSPECTED".equals(status);
    }
}
