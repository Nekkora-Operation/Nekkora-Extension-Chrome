package com.nekkora.extension.core.locator.strategy;

import com.nekkora.extension.core.context.ContextGraph;
import com.nekkora.extension.core.context.ElementFamily;
import com.nekkora.extension.core.locator.query.EvalContext;
import com.nekkora.extension.core.locator.query.LocatorEvaluator;
import com.nekkora.extension.core.locator.query.LocatorQuery;
import com.nekkora.extension.core.locator.query.LocatorStep;
import com.nekkora.extension.core.semantic.Classification;
import com.nekkora.extension.core.snapshot.RawNode;

import java.util.List;
import java.util.Optional;

/** Tudo o que uma estratégia precisa saber sobre o alvo. */
public record TargetContext(RawNode node, Classification classification, ElementFamily family, ContextGraph graph,
                            EvalContext eval, LocatorEvaluator evaluator) {

    public List<RawNode> evaluate(LocatorQuery q) {
        return evaluator.evaluate(q, node.scopeId(), eval);
    }

    public boolean uniqueForTarget(LocatorQuery q) {
        List<RawNode> r = evaluate(q);
        return r.size() == 1 && r.get(0).id().equals(node.id());
    }

    public boolean includesTarget(LocatorQuery q) {
        return evaluate(q).stream().anyMatch(n -> n.id().equals(node.id()));
    }

    /** Primeiro passo simples que, prefixado por {@code anchor}, é único para o alvo; senão o primeiro que o inclui. */
    public Optional<LocatorQuery> bestWithin(List<LocatorStep> anchor) {
        LocatorQuery fallback = null;
        for (LocatorStep inner : StepFactory.simpleSteps(node, graph)) {
            LocatorQuery q = LocatorQuery.of(inner).prefixedBy(anchor);
            List<RawNode> r = evaluate(q);
            if (r.stream().noneMatch(n -> n.id().equals(node.id()))) {
                continue;
            }
            if (r.size() == 1) {
                return Optional.of(q);
            }
            if (fallback == null) {
                fallback = q;
            }
        }
        return Optional.ofNullable(fallback);
    }
}
