package com.nekkora.extension.core.locator.strategy;

import com.nekkora.extension.core.locator.IdentifierHeuristics;
import com.nekkora.extension.core.locator.LocatorStrategyKind;
import com.nekkora.extension.core.locator.StabilityLevel;
import com.nekkora.extension.core.locator.query.LocatorQuery;
import com.nekkora.extension.core.locator.query.LocatorStep;
import com.nekkora.extension.core.locator.query.Predicate;
import com.nekkora.extension.core.snapshot.PageModel;
import com.nekkora.extension.core.snapshot.RawNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Estratégias estruturais: caminho relativo a uma âncora estável e caminho posicional (último recurso). */
public final class StructuralStrategies {

    private StructuralStrategies() {
    }

    /** Caminho de filhos a partir do ancestral mais próximo com id único e aparentemente estável. */
    public static final class RelativePathStrategy implements LocatorStrategy {
        private final IdentifierHeuristics heuristics = new IdentifierHeuristics();

        @Override
        public List<LocatorCandidate> propose(TargetContext t) {
            PageModel m = t.graph().model();
            List<RawNode> chain = new ArrayList<>();
            chain.add(t.node());
            for (RawNode a : m.ancestorsInScope(t.node())) {
                String id = a.attr("id");
                if (StepFactory.nonBlank(id) && m.byIdAttribute(a.scopeId(), id).size() == 1
                        && heuristics.assess(id).level() != StabilityLevel.HEURISTIC_UNSTABLE) {
                    List<LocatorStep> steps = new ArrayList<>();
                    steps.add(LocatorStep.descendant(a.locatorTag(), new Predicate.AttrEquals("id", id)));
                    Collections.reverse(chain);
                    for (RawNode n : chain) {
                        steps.add(childStep(n, t, false));
                    }
                    if (chain.size() > 8) {
                        return List.of();
                    }
                    return List.of(LocatorCandidate.of(LocatorStrategyKind.XPATH_RELATIVE, new LocatorQuery(steps),
                            "relativo à âncora #" + id));
                }
                chain.add(a);
            }
            return List.of();
        }
    }

    /** Caminho posicional completo desde a raiz do escopo. Sempre marcado como frágil. */
    public static final class PositionalStrategy implements LocatorStrategy {
        @Override
        public List<LocatorCandidate> propose(TargetContext t) {
            PageModel m = t.graph().model();
            List<RawNode> chain = new ArrayList<>();
            chain.add(t.node());
            chain.addAll(m.ancestorsInScope(t.node()));
            Collections.reverse(chain);
            List<LocatorStep> steps = new ArrayList<>();
            for (int i = 0; i < chain.size(); i++) {
                RawNode n = chain.get(i);
                LocatorStep s = childStep(n, t, true);
                if (i == 0) {
                    s = new LocatorStep(LocatorStep.Axis.DESCENDANT, s.tag(), s.predicates());
                }
                steps.add(s);
            }
            return List.of(LocatorCandidate.of(LocatorStrategyKind.POSITIONAL, new LocatorQuery(steps),
                    "último recurso: depende da posição de cada ancestral"));
        }
    }

    static LocatorStep childStep(RawNode n, TargetContext t, boolean alwaysPosition) {
        if (n.locatorTag() == null) {
            // nome retido (custom-element): qualquer elemento; a posição é a real entre TODOS os irmãos (:nth-child),
            // informada pela página — :nth-of-type exigiria o nome. Sem a posição informada, nenhuma é inventada.
            int k = t.eval().nthChild(n);
            RawNode p = t.graph().model().find(n.parentId()).orElse(null);
            long siblings = p == null ? 0 : t.graph().model().children(p).stream().filter(s -> s.scopeId().equals(n.scopeId())).count();
            return k > 0 && (siblings > 1 || (alwaysPosition && p != null)) ? LocatorStep.child(null, new Predicate.NthChild(k)) : LocatorStep.child(null);
        }
        if (n.tag().equals("html") || n.tag().equals("body")) {
            return LocatorStep.child(n.tag());
        }
        int sameTag = 0;
        RawNode parent = t.graph().model().find(n.parentId()).orElse(null);
        if (parent != null) {
            for (RawNode s : t.graph().model().children(parent)) {
                if (s.tag().equals(n.tag()) && s.scopeId().equals(n.scopeId())) {
                    sameTag++;
                }
            }
        }
        if (sameTag > 1 || (alwaysPosition && parent != null)) {
            return LocatorStep.child(n.tag(), new Predicate.NthOfType(t.eval().nthOfType(n)));
        }
        return LocatorStep.child(n.tag());
    }
}
