package com.nekkora.extension.core.locator.query;

import com.nekkora.extension.core.semantic.SemanticIndex;
import com.nekkora.extension.core.snapshot.PageModel;
import com.nekkora.extension.core.snapshot.RawNode;

import java.util.HashMap;
import java.util.Map;

/** Contexto de avaliação de localizadores sobre um snapshot. */
public final class EvalContext {

    private final PageModel model;
    private final SemanticIndex semantics;
    private final Map<String, Integer> nthCache = new HashMap<>();
    private Map<String, java.util.List<RawNode>> roleIndex;
    private Map<String, java.util.List<RawNode>> roleNameIndex;

    public EvalContext(PageModel model, SemanticIndex semantics) {
        this.model = model;
        this.semantics = semantics;
    }

    public PageModel model() {
        return model;
    }

    public SemanticIndex semantics() {
        return semantics;
    }

    /** Nós de um papel acessível dentro de um escopo (índice construído sob demanda). */
    public java.util.List<RawNode> byRole(String scopeId, String role) {
        if (roleIndex == null) {
            roleIndex = new HashMap<>();
            for (RawNode n : model.nodes()) {
                semantics.find(n.id()).ifPresent(c -> {
                    if (c.role() != null) {
                        roleIndex.computeIfAbsent(n.scopeId() + "|" + c.role(), k -> new java.util.ArrayList<>()).add(n);
                    }
                });
            }
        }
        return roleIndex.getOrDefault(scopeId + "|" + role, java.util.List.of());
    }

    /** Nós com papel E nome acessível exatos dentro de um escopo. */
    public java.util.List<RawNode> byRoleAndName(String scopeId, String role, String name) {
        if (roleNameIndex == null) {
            roleNameIndex = new HashMap<>();
            for (RawNode n : model.nodes()) {
                semantics.find(n.id()).ifPresent(c -> {
                    if (c.role() != null) {
                        roleNameIndex.computeIfAbsent(n.scopeId() + "|" + c.role() + "|" + c.name().name(), k -> new java.util.ArrayList<>()).add(n);
                    }
                });
            }
        }
        return roleNameIndex.getOrDefault(scopeId + "|" + role + "|" + name, java.util.List.of());
    }

    /** Posição base 1 do nó entre os irmãos de mesma tag no mesmo escopo. */
    public int nthOfType(RawNode node) {
        return nthCache.computeIfAbsent(node.id(), k -> {
            RawNode parent = model.find(node.parentId()).orElse(null);
            if (parent == null || !parent.scopeId().equals(node.scopeId())) {
                // Topo do escopo (html do documento ou filhos diretos de shadow root).
                int pos = 0;
                for (RawNode s : model.inScope(node.scopeId())) {
                    if (s.parentId() == null || !model.find(s.parentId()).map(p -> p.scopeId().equals(node.scopeId())).orElse(false)) {
                        if (s.tag().equals(node.tag())) {
                            pos++;
                        }
                        if (s.id().equals(node.id())) {
                            return pos;
                        }
                    }
                }
                return 1;
            }
            int pos = 0;
            for (RawNode s : model.children(parent)) {
                if (!s.scopeId().equals(node.scopeId())) {
                    continue;
                }
                if (s.tag().equals(node.tag())) {
                    pos++;
                }
                if (s.id().equals(node.id())) {
                    return pos;
                }
            }
            return 1;
        });
    }
}
