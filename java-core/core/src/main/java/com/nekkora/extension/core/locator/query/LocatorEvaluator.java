package com.nekkora.extension.core.locator.query;

import com.nekkora.extension.core.snapshot.RawNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Avalia um {@link LocatorQuery} sobre o snapshot, dentro de um escopo de consulta (documento de um
 * frame ou shadow root). A semântica espelha {@code querySelectorAll}/{@code document.evaluate}:
 * a busca não atravessa fronteiras de shadow root nem de iframe.
 */
public final class LocatorEvaluator {

    public List<RawNode> evaluate(LocatorQuery query, String scopeId, EvalContext ctx) {
        List<RawNode> current = null;
        for (LocatorStep step : query.steps()) {
            List<RawNode> candidates = candidatesFor(step, scopeId, ctx);
            if (current == null) {
                current = candidates;
                continue;
            }
            Set<String> contextIds = new LinkedHashSet<>();
            for (RawNode c : current) {
                contextIds.add(c.id());
            }
            List<RawNode> next = new ArrayList<>();
            for (RawNode cand : candidates) {
                if (step.axis() == LocatorStep.Axis.CHILD) {
                    if (contextIds.contains(cand.parentId())) {
                        next.add(cand);
                    }
                } else {
                    for (RawNode a : ctx.model().ancestorsInScope(cand)) {
                        if (contextIds.contains(a.id())) {
                            next.add(cand);
                            break;
                        }
                    }
                }
            }
            current = next;
            if (current.isEmpty()) {
                break;
            }
        }
        List<RawNode> out = new ArrayList<>(current == null ? List.of() : current);
        out.sort(Comparator.comparingInt(n -> ctx.model().orderOf(n.id())));
        return out;
    }

    private List<RawNode> candidatesFor(LocatorStep step, String scopeId, EvalContext ctx) {
        List<RawNode> base = null;
        for (Predicate p : step.predicates()) {
            if (p instanceof Predicate.AttrEquals eq && eq.name().equals("id")) {
                base = ctx.model().byIdAttribute(scopeId, eq.value());
                break;
            }
        }
        if (base == null) {
            base = step.tag() != null
                    ? ctx.model().byTag(step.tag()).stream().filter(n -> n.scopeId().equals(scopeId)).toList()
                    : ctx.model().inScope(scopeId);
        }
        List<RawNode> out = new ArrayList<>();
        for (RawNode n : base) {
            if (step.tag() != null && !step.tag().equals(n.tag())) {
                continue;
            }
            boolean ok = true;
            for (Predicate p : step.predicates()) {
                if (!p.test(n, ctx)) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                out.add(n);
            }
        }
        return out;
    }
}
