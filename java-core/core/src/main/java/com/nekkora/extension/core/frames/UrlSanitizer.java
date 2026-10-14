package com.nekkora.extension.core.frames;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * URLs são tratadas como potencialmente sensíveis: somente esquema, host, porta e caminho são mantidos (sem query,
 * fragmento ou credenciais). Os NOMES dos parâmetros podem ser registrados à parte; os valores, nunca.
 */
public final class UrlSanitizer {

    private static final int MAX = 300;

    private UrlSanitizer() {
    }

    /** {@code https://host:porta/caminho}; {@code about:blank}/{@code about:srcdoc} preservados; {@code null} se vazio. */
    public static String sanitize(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String u = url.trim();
        if (u.startsWith("about:")) {
            int cut = indexOfAny(u, '?', '#');
            return cut < 0 ? u : u.substring(0, cut);
        }
        try {
            URI uri = URI.create(u.replace(" ", "%20"));
            if (uri.getScheme() == null || uri.getHost() == null) {
                int cut = indexOfAny(u, '?', '#');
                String s = cut < 0 ? u : u.substring(0, cut);
                return s.length() > MAX ? s.substring(0, MAX) : s;
            }
            String s = uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getHost().toLowerCase(Locale.ROOT)
                    + (uri.getPort() > 0 ? ":" + uri.getPort() : "")
                    + (uri.getRawPath() == null ? "" : com.nekkora.extension.core.privacy.Privacy.sanitizePathOnly(uri.getRawPath()));
            return s.length() > MAX ? s.substring(0, MAX) : s;
        } catch (IllegalArgumentException e) {
            int cut = indexOfAny(u, '?', '#');
            String s = cut < 0 ? u : u.substring(0, cut);
            return s.length() > MAX ? s.substring(0, MAX) : s;
        }
    }

    /** Somente os nomes dos parâmetros da query (valores descartados). */
    public static List<String> queryKeys(String url) {
        List<String> out = new ArrayList<>();
        if (url == null) {
            return out;
        }
        int q = url.indexOf('?');
        if (q < 0) {
            return out;
        }
        int h = url.indexOf('#', q);
        String query = h < 0 ? url.substring(q + 1) : url.substring(q + 1, h);
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            if (!k.isEmpty() && !out.contains(k) && out.size() < 20) {
                out.add(k);
            }
        }
        return out;
    }

    /** Origem ({@code esquema://host[:porta]}) ou {@code null} quando não determinável a partir da URL. */
    public static String origin(String url) {
        if (url == null || url.startsWith("about:") || url.startsWith("data:") || url.startsWith("javascript:")) {
            return null;
        }
        try {
            URI uri = URI.create(url.replace(" ", "%20"));
            if (uri.getScheme() == null || uri.getHost() == null) {
                return null;
            }
            return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getHost().toLowerCase(Locale.ROOT)
                    + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Resolve uma URL declarada (possivelmente relativa) contra a URL do documento pai. */
    public static String resolve(String base, String declared) {
        if (declared == null || declared.isBlank()) {
            return null;
        }
        try {
            if (base == null || base.startsWith("about:")) {
                return URI.create(declared.trim().replace(" ", "%20")).toString();
            }
            return URI.create(base.replace(" ", "%20")).resolve(declared.trim().replace(" ", "%20")).toString();
        } catch (IllegalArgumentException e) {
            return declared.trim();
        }
    }

    /** Caminho da URL (sem query). */
    public static String path(String url) {
        if (url == null || url.startsWith("about:")) {
            return url;
        }
        try {
            URI uri = URI.create(url.replace(" ", "%20"));
            return uri.getRawPath();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static int indexOfAny(String s, char a, char b) {
        int i = s.indexOf(a);
        int j = s.indexOf(b);
        if (i < 0) {
            return j;
        }
        return j < 0 ? i : Math.min(i, j);
    }
}
