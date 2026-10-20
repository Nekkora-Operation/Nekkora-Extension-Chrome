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

    /**
     * Primeiro passo simples que, prefixado por {@code anchor}, é único para o alvo; senão o primeiro que o inclui.
     *
     * <p>NEKKORA-E2E-002: só passos TRANSPORTÁVEIS pela política (um passo com o texto do próprio alvo fora do
     * vocabulário seria descartado depois e levaria a âncora junto); e, para alvo NÃO interativo (célula, texto,
     * descrição), dentro de um contêiner o próprio conteúdo vem por último — ele é o DADO a ler: localizá-lo por ele
     * seria circular e quebraria na página seguinte.
     */
    public Optional<LocatorQuery> bestWithin(List<LocatorStep> anchor) {
        LocatorQuery fallback = null;
        List<LocatorStep> steps = new java.util.ArrayList<>(StepFactory.simpleSteps(node, graph));
        steps.removeIf(s -> !com.nekkora.extension.core.locator.LocatorPrivacy.transportable(LocatorQuery.of(s)));
        if (!anchor.isEmpty() && classification.category() != com.nekkora.extension.core.semantic.ElementCategory.INTERACTIVE) {
            steps.sort(java.util.Comparator.comparingInt(s -> s.predicates().stream()
                    .anyMatch(p -> p instanceof com.nekkora.extension.core.locator.query.Predicate.TextEquals) ? 1 : 0));
        }
        for (LocatorStep inner : steps) {
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
