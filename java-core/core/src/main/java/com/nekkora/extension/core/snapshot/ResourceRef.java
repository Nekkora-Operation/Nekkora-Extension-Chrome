package com.nekkora.extension.core.snapshot;

/**
 * Referência a um recurso observável de um documento (CHROME-003), usada pelo diagnóstico de proteções.
 *
 * <p>A URL é SANITIZADA no navegador: somente esquema, host, porta e caminho (sem query, fragmento ou
 * credenciais). Nenhum corpo, cabeçalho ou cookie é coletado.
 *
 * @param kind       {@code SCRIPT}, {@code STYLESHEET}, {@code IFRAME}, {@code OTHER}
 * @param url        URL sanitizada
 * @param observedBy {@code DOM} (elemento com {@code src}/{@code href}) ou {@code RESOURCE_TIMING} (efetivamente carregado)
 * @param frameId    frame lógico do documento que referencia o recurso
 * @param type       atributo {@code type} do script, quando houver (ex.: tipo inerte usado pelo laboratório)
 * @param nodeId     elemento de origem (somente {@code DOM}; scripts não são inventariados como nós)
 * @param queryKeys  NOMES dos parâmetros da query da URL original (valores descartados no navegador)
 */
public record ResourceRef(String kind, String url, String observedBy, String frameId, String type, String nodeId,
                          java.util.List<String> queryKeys) {

    public ResourceRef {
        url = com.nekkora.extension.core.privacy.Privacy.sanitizeUrl(url); // segunda barreira (NEKKORA-REAL-001)
        queryKeys = queryKeys == null ? java.util.List.of() : java.util.List.copyOf(queryKeys);
    }
}
