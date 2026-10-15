package com.nekkora.extension.core.mutation;

import com.nekkora.extension.core.context.ContextGraph;
import com.nekkora.extension.core.identity.IdentitySignature;
import com.nekkora.extension.core.identity.SignatureExtractor;
import com.nekkora.extension.core.semantic.Classification;
import com.nekkora.extension.core.semantic.ElementCategory;
import com.nekkora.extension.core.semantic.ElementKind;
import com.nekkora.extension.core.snapshot.DocumentInfo;
import com.nekkora.extension.core.snapshot.NodeState;
import com.nekkora.extension.core.snapshot.PageModel;
import com.nekkora.extension.core.snapshot.RawNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Compara duas capturas da mesma página. Dentro de uma mesma sessão de página, ids temporários de nós
 * são comparáveis (o content script mantém o mesmo id enquanto o nó DOM existir). Re-renderizações
 * aparecem como remoção + adição e são reconciliadas como "substituição" por assinatura lógica.
 */
public final class SnapshotComparator {

    private static final int LIST_LIMIT = 50;
    private static final Set<String> RELEVANT_ATTRS = Set.of("id", "name", "role", "type", "disabled", "hidden", "href",
            "value", "checked", "selected", "readonly", "required", "for", "form", "contenteditable", "tabindex", "open",
            "placeholder", "title", "alt", "label", "multiple");

    private final SignatureExtractor extractor = new SignatureExtractor();

    public MutationRecord compare(ContextGraph before, ContextGraph after, String mode, int rawMutations, int batches) {
        PageModel a = before.model();
        PageModel b = after.model();
        List<String> reasons = new ArrayList<>();
        DocumentInfo da = a.snapshot().document();
        DocumentInfo db = b.snapshot().document();
        boolean sameSession = da != null && db != null && Objects.equals(da.pageSessionId(), db.pageSessionId());
        if (!sameSession) {
            reasons.add("sessões de página diferentes: ids de nós não são comparáveis (comparação apenas lógica)");
        }
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<NodeChange> changed = new ArrayList<>();
        int changedCount = 0;
        boolean relevant = false;

        if (sameSession) {
            for (RawNode n : b.nodes()) {
                if (!a.contains(n.id())) {
                    added.add(n.id());
                }
            }
            for (RawNode n : a.nodes()) {
                if (!b.contains(n.id())) {
                    removed.add(n.id());
                }
            }
            for (RawNode nb : b.nodes()) {
                RawNode na = a.find(nb.id()).orElse(null);
                if (na == null) {
                    continue;
                }
                NodeChange ch = diff(na, nb);
                if (!ch.noChanges()) {
                    changedCount++;
                    if (isRelevantChange(ch, after.semantics().get(nb.id()))) {
                        relevant = true;
                        if (changed.size() < LIST_LIMIT) {
                            changed.add(ch);
                        }
                    }
                }
            }
        }
        int relevantAdded = (int) added.stream().filter(id -> isRelevantNode(after.semantics().get(id))).count();
        int relevantRemoved = (int) removed.stream().filter(id -> isRelevantNode(before.semantics().get(id))).count();
        if (relevantAdded > 0) {
            relevant = true;
            reasons.add(relevantAdded + " elemento(s) significativos adicionados");
        }
        if (relevantRemoved > 0) {
            relevant = true;
            reasons.add(relevantRemoved + " elemento(s) significativos removidos");
        }
        if (!changed.isEmpty()) {
            reasons.add(changed.size() + " elemento(s) com alteração de atributo/texto/estado relevante");
        }
        if (da != null && db != null && !Objects.equals(da.url(), db.url())) {
            relevant = true;
            reasons.add("URL mudou: " + da.url() + " → " + db.url());
        }

        List<List<String>> replaced = new ArrayList<>();
        if (sameSession) {
            replaced = reconcile(before, after, removed, added);
            if (!replaced.isEmpty()) {
                reasons.add(replaced.size() + " elemento(s) re-renderizado(s) reconciliado(s) por identidade lógica");
            }
        } else {
            Map<String, Integer> ka = logicalKeys(before);
            Map<String, Integer> kb = logicalKeys(after);
            Set<String> all = new LinkedHashSet<>(ka.keySet());
            all.addAll(kb.keySet());
            for (String k : all) {
                int diff = kb.getOrDefault(k, 0) - ka.getOrDefault(k, 0);
                for (int i = 0; i < Math.abs(diff); i++) {
                    (diff > 0 ? added : removed).add("logical:" + k);
                }
            }
            if (!added.isEmpty() || !removed.isEmpty()) {
                relevant = true;
            }
        }
        if (!relevant) {
            reasons.add("nenhuma alteração relevante (somente mudanças irrelevantes ou nenhuma)");
        }
        return new MutationRecord(a.snapshot().captureId(), b.snapshot().captureId(), mode, rawMutations, batches,
                limit(added), added.size(), limit(removed), removed.size(), changed, changedCount, replaced, relevant,
                reasons, List.of(), 0);
    }

    private static List<String> limit(List<String> l) {
        return l.size() > LIST_LIMIT ? l.subList(0, LIST_LIMIT) : l;
    }

    static NodeChange diff(RawNode a, RawNode b) {
        Map<String, List<String>> attrs = new LinkedHashMap<>();
        Set<String> keys = new LinkedHashSet<>(a.attributes().keySet());
        keys.addAll(b.attributes().keySet());
        for (String k : keys) {
            String va = a.attr(k);
            String vb = b.attr(k);
            if (!Objects.equals(va, vb)) {
                List<String> pair = new ArrayList<>();
                pair.add(va);
                pair.add(vb);
                attrs.put(k, pair);
            }
        }
        String tb = null;
        String ta = null;
        if (!a.ownText().equals(b.ownText())) {
            tb = a.ownText();
            ta = b.ownText();
        }
        List<String> states = new ArrayList<>();
        NodeState sa = a.state();
        NodeState sb = b.state();
        state(states, "visible", sa.visible(), sb.visible());
        state(states, "disabled", sa.disabled(), sb.disabled());
        state(states, "checked", sa.checked(), sb.checked());
        state(states, "selected", sa.selected(), sb.selected());
        state(states, "readOnly", sa.readOnly(), sb.readOnly());
        state(states, "open", sa.open(), sb.open());
        state(states, "modal", sa.modal(), sb.modal());
        state(states, "inert", sa.inert(), sb.inert());
        state(states, "hasValue", sa.hasValue(), sb.hasValue());
        return new NodeChange(a.id(), attrs, tb, ta, states, !a.childIds().equals(b.childIds()));
    }

    private static void state(List<String> out, String name, Object a, Object b) {
        if (!Objects.equals(a, b)) {
            out.add(name + ": " + a + "→" + b);
        }
    }

    private static boolean isRelevantChange(NodeChange ch, Classification c) {
        if (ch.textBefore() != null || !ch.stateChanges().isEmpty()) {
            return true;
        }
        for (String attr : ch.attributeChanges().keySet()) {
            if (RELEVANT_ATTRS.contains(attr) || attr.startsWith("aria-") || attr.startsWith("data-")) {
                return true;
            }
        }
        return ch.childrenChanged() && c.kind() != ElementKind.GENERIC_CONTAINER;
    }

    private static boolean isRelevantNode(Classification c) {
        return c.category() == ElementCategory.INTERACTIVE || c.category() == ElementCategory.TEXT
                || c.category() == ElementCategory.STRUCTURAL;
    }

    private List<List<String>> reconcile(ContextGraph before, ContextGraph after, List<String> removed, List<String> added) {
        Map<String, List<String>> addedByKey = new HashMap<>();
        int budget = 400;
        for (String id : added) {
            Classification c = after.semantics().get(id);
            if (c.category() != ElementCategory.INTERACTIVE || budget-- <= 0) {
                continue;
            }
            addedByKey.computeIfAbsent(key(extractor.extract(id, after)), k -> new ArrayList<>()).add(id);
        }
        List<List<String>> pairs = new ArrayList<>();
        budget = 400;
        for (String id : removed) {
            Classification c = before.semantics().get(id);
            if (c.category() != ElementCategory.INTERACTIVE || budget-- <= 0) {
                continue;
            }
            List<String> match = addedByKey.get(key(extractor.extract(id, before)));
            if (match != null && match.size() == 1) {
                pairs.add(List.of(id, match.get(0)));
            }
        }
        return pairs;
    }

    private Map<String, Integer> logicalKeys(ContextGraph g) {
        Map<String, Integer> out = new HashMap<>();
        for (Classification c : g.semantics().all()) {
            if (c.category() == ElementCategory.INTERACTIVE) {
                out.merge(key(extractor.extract(c.nodeId(), g)), 1, Integer::sum);
            }
        }
        return out;
    }

    private static String key(IdentitySignature s) {
        return s.kind() + "|" + s.name() + "|" + s.rowKey() + "|" + s.form() + "|" + s.table() + "|" + s.dialog()
                + "|" + s.containerHeading() + "|" + s.label();
    }
}
