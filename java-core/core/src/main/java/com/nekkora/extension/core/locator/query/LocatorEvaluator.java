package com.nekkora.extension.core.locator.query;

import com.nekkora.extension.core.snapshot.RawNode;
import com.nekkora.extension.core.text.TextNormalizer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Avalia um {@link LocatorQuery} sobre o snapshot, dentro de um escopo de consulta (documento de um
 * frame ou shadow root). A semântica espelha {@code querySelectorAll}/{@code document.evaluate}:
 * a busca não atravessa fronteiras de shadow root nem de iframe.
 *
 * <p>Plano de execução: para passos após o primeiro, escolhe entre (a) filtrar os candidatos do
 * índice verificando ancestrais e (b) percorrer as subárvores dos contextos — o que for menor.
 */
public final class LocatorEvaluator {

    public List<RawNode> evaluate(LocatorQuery query, String scopeId, EvalContext ctx) {
        List<RawNode> current = null;
        for (LocatorStep step : query.steps()) {
            if (current == null) {
                current = candidatesFor(step, scopeId, ctx);
                continue;
            }
            current = next(current, step, scopeId, ctx);
            if (current.isEmpty()) {
                break;
            }
        }
        List<RawNode> out = new ArrayList<>(current == null ? List.of() : current);
        out.sort(Comparator.comparingInt(n -> ctx.model().orderOf(n.id())));
        return out;
    }

    private List<RawNode> next(List<RawNode> contexts, LocatorStep step, String scopeId, EvalContext ctx) {
        long subtreeWork = 0;
        for (RawNode c : contexts) {
            subtreeWork += step.axis() == LocatorStep.Axis.CHILD ? c.childIds().size() : ctx.model().subtreeSize(c);
        }
        long indexWork = estimatedCandidates(step, scopeId, ctx);
        if (subtreeWork < indexWork) {
            // (b) percorre a partir dos contextos
            Map<String, RawNode> found = new LinkedHashMap<>();
            for (RawNode c : contexts) {
                List<RawNode> pool = step.axis() == LocatorStep.Axis.CHILD ? ctx.model().children(c) : ctx.model().descendants(c);
                for (RawNode n : pool) {
                    if (n.scopeId().equals(scopeId) && matches(step, n, ctx)) {
                        found.putIfAbsent(n.id(), n);
                    }
                }
            }
            return new ArrayList<>(found.values());
        }
        // (a) filtra candidatos do índice pelos ancestrais
        Set<String> contextIds = new LinkedHashSet<>();
        for (RawNode c : contexts) {
            contextIds.add(c.id());
        }
        List<RawNode> out = new ArrayList<>();
        for (RawNode cand : candidatesFor(step, scopeId, ctx)) {
            if (step.axis() == LocatorStep.Axis.CHILD) {
                if (contextIds.contains(cand.parentId())) {
                    out.add(cand);
                }
            } else {
                for (RawNode a : ctx.model().ancestorsInScope(cand)) {
                    if (contextIds.contains(a.id())) {
                        out.add(cand);
                        break;
                    }
                }
            }
        }
        return out;
    }

    private static long estimatedCandidates(LocatorStep step, String scopeId, EvalContext ctx) {
        for (Predicate p : step.predicates()) {
            if (p instanceof Predicate.AttrEquals eq && eq.name().equals("id")) {
                return ctx.model().byIdAttribute(scopeId, eq.value()).size();
            }
        }
        if (step.tag() == null) {
            for (Predicate p : step.predicates()) {
                if (p instanceof Predicate.RoleIs r) {
                    return ctx.byRole(scopeId, r.role()).size();
                }
            }
        }
        return step.tag() != null ? ctx.model().countByTag(step.tag()) : ctx.model().size();
    }

    private List<RawNode> candidatesFor(LocatorStep step, String scopeId, EvalContext ctx) {
        List<RawNode> base = null;
        for (Predicate p : step.predicates()) {
            if (p instanceof Predicate.AttrEquals eq && eq.name().equals("id")) {
                base = ctx.model().byIdAttribute(scopeId, eq.value());
                break;
            }
        }
        if (base == null && step.tag() == null) {
            String role = null;
            String name = null;
            for (Predicate p : step.predicates()) {
                if (p instanceof Predicate.RoleIs r) {
                    role = r.role();
                } else if (p instanceof Predicate.NameEquals ne) {
                    name = TextNormalizer.normalize(ne.name());
                }
            }
            if (role != null) {
                base = name != null ? ctx.byRoleAndName(scopeId, role, name) : ctx.byRole(scopeId, role);
            }
        }
        if (base == null) {
            base = step.tag() != null ? ctx.model().byTag(step.tag()) : ctx.model().inScope(scopeId);
        }
        List<RawNode> out = new ArrayList<>();
        for (RawNode n : base) {
            if (n.scopeId().equals(scopeId) && matches(step, n, ctx)) {
                out.add(n);
            }
        }
        return out;
    }

    private static boolean matches(LocatorStep step, RawNode n, EvalContext ctx) {
        if (step.tag() != null && !step.tag().equals(n.tag())) {
            return false;
        }
        for (Predicate p : step.predicates()) {
            if (!p.test(n, ctx)) {
                return false;
            }
        }
        return true;
    }
}
