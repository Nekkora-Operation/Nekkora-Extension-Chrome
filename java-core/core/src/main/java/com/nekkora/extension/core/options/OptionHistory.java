package com.nekkora.extension.core.options;

import com.nekkora.extension.core.semantic.Classification;
import com.nekkora.extension.core.snapshot.RawNode;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Opções já observadas por controle, entre capturas da mesma página. Permite distinguir
 * "opções observadas" (acumuladas) de "opções materializadas agora".
 */
public final class OptionHistory {

    private final Map<String, Set<String>> observed = new HashMap<>();

    /** Chave lógica do controle (não usa o id temporário do nó). */
    public static String keyOf(RawNode control, Classification cls) {
        String id = control.attr("id");
        String name = control.attr("name");
        return control.tag() + "|" + (id == null ? "" : id) + "|" + (name == null ? "" : name) + "|" + cls.name().name();
    }

    public synchronized Set<String> observed(String key) {
        return Set.copyOf(observed.getOrDefault(key, Set.of()));
    }

    public synchronized void record(String key, Set<String> values) {
        observed.computeIfAbsent(key, k -> new LinkedHashSet<>()).addAll(values);
    }
}
