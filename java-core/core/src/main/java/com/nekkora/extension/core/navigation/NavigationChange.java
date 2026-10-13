package com.nekkora.extension.core.navigation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Diferença entre dois contextos de navegação.
 *
 * @param kind          {@code NONE}, {@code QUERY_CHANGE}, {@code FRAGMENT_CHANGE}, {@code ROUTE_CHANGE}, {@code ORIGIN_CHANGE}
 * @param fromHref      URL anterior
 * @param toHref        URL atual
 * @param queryChanges  parâmetro → [antes, depois] ({@code null} = ausente)
 * @param titleChanged  título mudou
 */
public record NavigationChange(String kind, String fromHref, String toHref, Map<String, List<String>> queryChanges,
                               boolean titleChanged) {

    public NavigationChange {
        queryChanges = Map.copyOf(queryChanges);
    }

    public static NavigationChange between(NavigationContext before, NavigationContext after) {
        NavigationContext.Observed a = before.observed();
        NavigationContext.Observed b = after.observed();
        Map<String, List<String>> changes = new LinkedHashMap<>();
        java.util.Set<String> keys = new java.util.LinkedHashSet<>(a.query().keySet());
        keys.addAll(b.query().keySet());
        for (String k : keys) {
            String va = a.query().containsKey(k) ? String.join(",", a.query().get(k)) : null;
            String vb = b.query().containsKey(k) ? String.join(",", b.query().get(k)) : null;
            if (!Objects.equals(va, vb)) {
                List<String> pair = new ArrayList<>();
                pair.add(va);
                pair.add(vb);
                changes.put(k, pair);
            }
        }
        String kind;
        if (!Objects.equals(a.origin(), b.origin())) {
            kind = "ORIGIN_CHANGE";
        } else if (!Objects.equals(a.pathname(), b.pathname())) {
            kind = "ROUTE_CHANGE";
        } else if (!changes.isEmpty()) {
            kind = "QUERY_CHANGE";
        } else if (!Objects.equals(a.fragment(), b.fragment())) {
            kind = "FRAGMENT_CHANGE";
        } else {
            kind = "NONE";
        }
        return new NavigationChange(kind, a.href(), b.href(), changes, !Objects.equals(a.title(), b.title()));
    }
}
