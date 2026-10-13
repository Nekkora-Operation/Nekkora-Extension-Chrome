package com.nekkora.extension.core.navigation;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Interpreta URL e título em {@link NavigationContext}. */
public final class UrlContextParser {

    private static final Set<String> PAGINATION = Set.of("page", "pagina", "p", "size", "pagesize", "page_size", "limit",
            "offset", "per_page", "porpagina");
    private static final Set<String> SORT = Set.of("sort", "order", "ordem", "orderby", "ordenar", "dir", "direction");
    static final String CAVEAT = "Parâmetros de URL são observados no endereço; não comprovam que uma consulta ao backend ocorreu.";

    public NavigationContext parse(String href, String title) {
        URI uri;
        try {
            uri = URI.create(href);
        } catch (IllegalArgumentException | NullPointerException e) {
            return new NavigationContext(new NavigationContext.Observed(href, null, null, null, null, null, null, null,
                    Map.of(), null, title), new NavigationContext.Inferred(null, Map.of(), Map.of(), Map.of(),
                    List.of("URL não interpretável"), CAVEAT));
        }
        String rawQuery = uri.getRawQuery();
        Map<String, List<String>> query = parseQuery(rawQuery);
        String port = uri.getPort() < 0 ? "" : Integer.toString(uri.getPort());
        String host = uri.getHost() == null ? null : uri.getHost() + (port.isEmpty() ? "" : ":" + port);
        String origin = uri.getScheme() == null || host == null ? null : uri.getScheme() + "://" + host;
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : decode(uri.getRawPath());
        String fragment = uri.getRawFragment() == null ? null : decode(uri.getRawFragment());
        NavigationContext.Observed observed = new NavigationContext.Observed(href, origin, uri.getScheme(), host,
                uri.getHost(), port, path, rawQuery == null ? "" : "?" + rawQuery, query, fragment, title);

        List<String> basis = new ArrayList<>();
        String screen = screenOf(path, fragment, basis);
        Map<String, String> filters = new LinkedHashMap<>();
        Map<String, String> pagination = new LinkedHashMap<>();
        Map<String, String> sort = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : query.entrySet()) {
            String k = e.getKey().toLowerCase(Locale.ROOT);
            String v = String.join(",", e.getValue());
            if (PAGINATION.contains(k)) {
                pagination.put(e.getKey(), v);
            } else if (SORT.contains(k)) {
                sort.put(e.getKey(), v);
            } else {
                filters.put(e.getKey(), v);
            }
        }
        if (!filters.isEmpty()) {
            basis.add("filtros: parâmetros de query que não são de paginação/ordenação");
        }
        if (!pagination.isEmpty()) {
            basis.add("paginação: nomes de parâmetro conhecidos (" + String.join(",", pagination.keySet()) + ")");
        }
        return new NavigationContext(observed, new NavigationContext.Inferred(screen, filters, pagination, sort, basis, CAVEAT));
    }

    private static String screenOf(String path, String fragment, List<String> basis) {
        if (fragment != null && fragment.startsWith("/")) {
            String s = lastMeaningfulSegment(fragment.split("\\?")[0]);
            if (s != null) {
                basis.add("tela: último segmento significativo da rota no fragmento (hash routing)");
                return s;
            }
        }
        String s = lastMeaningfulSegment(path);
        if (s != null) {
            basis.add("tela: último segmento não numérico do caminho");
            return s;
        }
        basis.add("tela: caminho raiz");
        return "root";
    }

    private static String lastMeaningfulSegment(String path) {
        String[] parts = path.split("/");
        for (int i = parts.length - 1; i >= 0; i--) {
            String p = parts[i];
            if (p.isBlank() || p.matches("\\d+") || p.matches("[0-9a-fA-F-]{16,}")) {
                continue;
            }
            return p.replaceAll("\\.html?$", "");
        }
        return null;
    }

    static Map<String, List<String>> parseQuery(String raw) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String k = decode(eq < 0 ? pair : pair.substring(0, eq));
            String v = eq < 0 ? "" : decode(pair.substring(eq + 1));
            out.computeIfAbsent(k, x -> new ArrayList<>()).add(v);
        }
        out.replaceAll((k, v) -> List.copyOf(v));
        return out;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8); // "+" = espaço, como URLSearchParams
        } catch (IllegalArgumentException e) {
            return s;
        }
    }
}
