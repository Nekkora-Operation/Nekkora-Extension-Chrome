package com.nekkora.extension.core.locator.strategy;

import com.nekkora.extension.core.context.ElementFamily;
import com.nekkora.extension.core.context.NodeRef;
import com.nekkora.extension.core.locator.LocatorStrategyKind;
import com.nekkora.extension.core.locator.query.LocatorQuery;
import com.nekkora.extension.core.locator.query.LocatorStep;
import com.nekkora.extension.core.snapshot.RawNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Localizadores que incorporam a FAMÍLIA do alvo: contêiner lógico, linha de tabela, formulário,
 * diálogo, região. É a estratégia que diferencia botões idênticos em linhas/tabelas distintas.
 */
public final class ContextualStrategy implements LocatorStrategy {

    @Override
    public List<LocatorCandidate> propose(TargetContext t) {
        List<LocatorCandidate> out = new ArrayList<>();
        ElementFamily f = t.family();
        String scope = t.node().scopeId();

        // 1) Tabela + linha + alvo (ROW_CONTEXT).
        if (f.row() != null) {
            RawNode row = t.graph().model().node(f.row().rowNodeId());
            Optional<LocatorStep> tableAnchor = f.table() == null ? Optional.empty()
                    : uniqueAnchor(t, t.graph().model().node(f.table().nodeId()), List.of());
            List<LocatorStep> prefix = tableAnchor.map(List::of).orElse(List.of());
            Optional<LocatorStep> rowStep = uniqueAnchor(t, row, prefix);
            if (rowStep.isPresent()) {
                List<LocatorStep> anchor = new ArrayList<>(prefix);
                anchor.add(rowStep.get());
                t.bestWithin(anchor).ifPresent(q -> out.add(LocatorCandidate.of(LocatorStrategyKind.ROW_CONTEXT, q,
                        "linha identificada por " + f.row().rowKeySource())));
                if (!prefix.isEmpty()) {
                    // Variante sem a tabela, útil quando a chave da linha já é única na página.
                    uniqueAnchor(t, row, List.of()).flatMap(rs -> t.bestWithin(List.of(rs)))
                            .ifPresent(q -> out.add(LocatorCandidate.of(LocatorStrategyKind.ROW_CONTEXT, q, "linha sem âncora de tabela")));
                }
            }
        }

        // 2) Demais contêineres da família, do mais próximo ao mais distante.
        Set<String> seen = new LinkedHashSet<>();
        List<NodeRef> containers = new ArrayList<>();
        for (NodeRef r : new NodeRef[]{f.logicalContainer(), f.listItem(), f.fieldGroup(), f.form(), f.dialog(), f.region(), f.table()}) {
            if (r != null && seen.add(r.nodeId())) {
                containers.add(r);
            }
        }
        for (NodeRef ref : containers) {
            RawNode container = t.graph().model().node(ref.nodeId());
            if (!container.scopeId().equals(scope) || (f.row() != null && container.id().equals(f.row().rowNodeId()))) {
                continue;
            }
            uniqueAnchor(t, container, List.of()).flatMap(a -> t.bestWithin(List.of(a))).ifPresent(q ->
                    out.add(LocatorCandidate.of(LocatorStrategyKind.CONTEXTUAL, q,
                            "escopo: " + ref.kind() + (ref.name().isEmpty() ? "" : " \"" + ref.name() + "\""))));
        }
        return out;
    }

    /** Primeiro passo-âncora que, sob {@code prefix}, seleciona exatamente {@code container}. */
    static Optional<LocatorStep> uniqueAnchor(TargetContext t, RawNode container, List<LocatorStep> prefix) {
        for (LocatorStep step : StepFactory.anchorSteps(container, t.graph())) {
            LocatorQuery q = LocatorQuery.of(step).prefixedBy(prefix);
            List<RawNode> r = t.evaluator().evaluate(q, container.scopeId(), t.eval());
            if (r.size() == 1 && r.get(0).id().equals(container.id())) {
                return Optional.of(step);
            }
        }
        return Optional.empty();
    }
}
