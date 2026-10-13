package com.nekkora.extension.core.semantic;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/** Classificações de todos os elementos de um snapshot. */
public final class SemanticIndex {

    private final Map<String, Classification> byNode;

    SemanticIndex(Map<String, Classification> byNode) {
        this.byNode = Map.copyOf(byNode);
    }

    public Classification get(String nodeId) {
        Classification c = byNode.get(nodeId);
        if (c == null) {
            throw new IllegalArgumentException("Nó sem classificação: " + nodeId);
        }
        return c;
    }

    public Optional<Classification> find(String nodeId) {
        return Optional.ofNullable(nodeId == null ? null : byNode.get(nodeId));
    }

    public Collection<Classification> all() {
        return byNode.values();
    }

    public int size() {
        return byNode.size();
    }
}
