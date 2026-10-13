package com.nekkora.extension.core.semantic;

import com.nekkora.extension.core.snapshot.PageModel;
import com.nekkora.extension.core.snapshot.RawNode;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Classifica todos os elementos de um {@link PageModel}. */
public final class SemanticAnalyzer {

    private final SemanticClassifier classifier = new SemanticClassifier();

    public SemanticIndex analyze(PageModel model) {
        SemanticClassifier.CrossReferences refs = crossReferences(model);
        Map<String, Classification> out = new HashMap<>(model.size() * 2);
        for (RawNode node : model.nodes()) {
            out.put(node.id(), classifier.classify(node, model, refs));
        }
        return new SemanticIndex(out);
    }

    /** Reclassifica apenas os nós informados, reaproveitando as demais classificações (atualização incremental). */
    public SemanticIndex reanalyze(PageModel model, SemanticIndex previous, Set<String> changedNodeIds) {
        SemanticClassifier.CrossReferences refs = crossReferences(model);
        Set<String> affected = expand(model, changedNodeIds);
        Map<String, Classification> out = new HashMap<>(model.size() * 2);
        for (RawNode node : model.nodes()) {
            Classification prev = previous.find(node.id()).orElse(null);
            if (prev == null || affected.contains(node.id())) {
                out.put(node.id(), classifier.classify(node, model, refs));
            } else {
                out.put(node.id(), prev);
            }
        }
        return new SemanticIndex(out);
    }

    /**
     * A classificação de um nó depende dele, de seus ancestrais (labels envolventes, widgets compostos),
     * de seus descendentes (nome por conteúdo) e de nós referenciados (labels, aria-labelledby/describedby).
     * Expande o conjunto alterado para todos os nós cuja classificação pode ter mudado.
     */
    static Set<String> expand(PageModel model, Set<String> changed) {
        Set<String> out = new HashSet<>();
        Set<String> changedIdAttrs = new HashSet<>();
        for (String id : changed) {
            RawNode n = model.find(id).orElse(null);
            if (n == null) {
                continue;
            }
            out.add(id);
            for (RawNode d : model.descendants(n)) {
                out.add(d.id());
            }
            for (RawNode a : model.ancestors(n)) {
                out.add(a.id()); // nome por conteúdo dos ancestrais pode mudar
            }
            if (n.attr("id") != null) {
                changedIdAttrs.add(n.attr("id"));
            }
            for (RawNode a : model.ancestors(n)) {
                if (a.attr("id") != null) {
                    changedIdAttrs.add(a.attr("id"));
                }
            }
        }
        for (RawNode n : model.nodes()) {
            if (out.contains(n.id())) {
                continue;
            }
            for (String l : n.labelIds()) {
                if (out.contains(l)) {
                    out.add(n.id());
                }
            }
            for (String attr : new String[]{"aria-labelledby", "aria-describedby", "for", "aria-controls"}) {
                String v = n.attr(attr);
                if (v != null) {
                    for (String ref : v.trim().split("\\s+")) {
                        if (changedIdAttrs.contains(ref)) {
                            out.add(n.id());
                        }
                    }
                }
            }
            if (n.tag().equals("label") && n.attr("for") != null && changedIdAttrs.contains(n.attr("for"))) {
                out.add(n.id());
            }
        }
        // Controles rotulados por labels alterados (label[for] aponta para o controle).
        for (RawNode n : model.nodes()) {
            if (n.tag().equals("label") && out.contains(n.id()) && n.attr("for") != null) {
                model.byIdAttribute(n.scopeId(), n.attr("for")).forEach(t -> out.add(t.id()));
            }
        }
        return out;
    }

    static SemanticClassifier.CrossReferences crossReferences(PageModel model) {
        Set<String> describedBy = new HashSet<>();
        Set<String> errors = new HashSet<>();
        for (RawNode n : model.nodes()) {
            String scope = n.scopeId();
            String desc = n.attr("aria-describedby");
            boolean invalid = "true".equals(n.attr("aria-invalid"));
            if (desc != null) {
                for (String ref : desc.trim().split("\\s+")) {
                    describedBy.add(scope + "|" + ref);
                    if (invalid) {
                        errors.add(scope + "|" + ref);
                    }
                }
            }
            String err = n.attr("aria-errormessage");
            if (err != null) {
                for (String ref : err.trim().split("\\s+")) {
                    errors.add(scope + "|" + ref);
                }
            }
        }
        return new SemanticClassifier.CrossReferences(describedBy, errors);
    }
}
