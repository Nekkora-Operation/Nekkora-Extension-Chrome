package com.nekkora.extension.core.navigation;

import java.util.List;
import java.util.Map;

/**
 * Contexto de navegação. {@code observed} contém apenas fatos lidos da URL/documento; {@code inferred}
 * contém interpretações (tela, filtros) que podem estar erradas e são rotuladas como tal.
 *
 * @param observed fatos observados
 * @param inferred inferências
 */
public record NavigationContext(Observed observed, Inferred inferred) {

    /**
     * @param href     URL completa
     * @param origin   origem
     * @param protocol protocolo
     * @param host     host com porta
     * @param hostname host sem porta
     * @param port     porta (vazia se padrão)
     * @param pathname caminho
     * @param search   query string bruta
     * @param query    parâmetros (valores múltiplos preservados)
     * @param fragment fragmento sem '#'
     * @param title    título do documento
     */
    public record Observed(String href, String origin, String protocol, String host, String hostname, String port,
                           String pathname, String search, Map<String, List<String>> query, String fragment, String title) {
        public Observed {
            query = Map.copyOf(query);
        }
    }

    /**
     * @param screen     tela inferida
     * @param filters    parâmetros interpretados como filtros
     * @param pagination parâmetros interpretados como paginação
     * @param sort       parâmetros interpretados como ordenação
     * @param basis      como cada inferência foi feita
     * @param caveat     aviso permanente: parâmetros de URL não comprovam consulta ao backend
     */
    public record Inferred(String screen, Map<String, String> filters, Map<String, String> pagination,
                           Map<String, String> sort, List<String> basis, String caveat) {
        public Inferred {
            filters = Map.copyOf(filters);
            pagination = Map.copyOf(pagination);
            sort = Map.copyOf(sort);
            basis = List.copyOf(basis);
        }
    }
}
