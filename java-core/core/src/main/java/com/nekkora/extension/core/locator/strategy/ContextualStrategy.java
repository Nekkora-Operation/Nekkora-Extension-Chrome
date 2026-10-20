package com.nekkora.extension.core.locator.strategy;

import com.nekkora.extension.core.context.ElementFamily;
import com.nekkora.extension.core.context.NodeRef;
import com.nekkora.extension.core.locator.LocatorPrivacy;
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

        // 2b) NEKKORA-E2E-002: descrição de uma lista de definição pelo seu TERMO (dt → dd), nunca pela posição
        if (t.node().tag().equals("dd")) {
            RawNode parent = t.graph().model().parent(t.node()).orElse(null);
            RawNode term = null;
            if (parent != null) {
                for (RawNode c : t.graph().model().children(parent)) {
                    if (c.id().equals(t.node().id())) {
                        break;
                    }
                    if (c.tag().equals("dt")) {
                        term = c;
                    }
                }
            }
            String text = term == null ? "" : t.graph().model().textContent(term);
            if (!text.isEmpty() && text.length() <= 80) {
                LocatorQuery q = LocatorQuery.of(LocatorStep.descendant("dd", new com.nekkora.extension.core.locator.query.Predicate.AfterTerm(text)));
                if (t.includesTarget(q)) {
                    out.add(LocatorCandidate.of(LocatorStrategyKind.ROW_CONTEXT, q, "descrição do termo"));
                }
            }
        }

        // 3) NEKKORA-E2E-002: regiões semânticas (cabeçalho da página, navegação, busca...) do mais próximo ao mais
        // distante. A região não precisa ser única na página (há mais de um <header>, mais de uma busca): vale quando
        // REGIÃO + ALVO seleciona só o alvo — ex.: o campo de busca do cabeçalho contra a cópia oculta do cabeçalho
        // fixo, que tem o mesmo name e fica fora do <header>. Nada de posição; cada passo passa pela privacidade.
        for (RawNode lm : t.graph().model().ancestorsInScope(t.node())) {
            for (LocatorStep step : landmarkSteps(lm)) {
                if (!LocatorPrivacy.transportable(LocatorQuery.of(step))) {
                    continue;
                }
                Optional<LocatorQuery> q = t.bestWithin(List.of(step)).filter(t::uniqueForTarget);
                if (q.isPresent()) {
                    out.add(LocatorCandidate.of(LocatorStrategyKind.CONTEXTUAL, q.get(), "região semântica: " + step.describe()));
                    return out;
                }
            }
        }
        return out;
    }

    private static final Set<String> LANDMARK_TAGS = Set.of("header", "nav", "main", "aside", "footer", "search");
    private static final Set<String> LANDMARK_ROLES = Set.of("banner", "navigation", "main", "complementary", "contentinfo", "search",
            "region", "form");

    /** Passos que nomeiam uma região semântica pelo elemento ou pelo papel declarado (sem id, sem classe, sem texto). */
    static List<LocatorStep> landmarkSteps(RawNode n) {
        List<LocatorStep> out = new ArrayList<>();
        String tag = n.locatorTag();
        String role = n.attr("role") == null ? "" : n.attr("role").trim().toLowerCase(java.util.Locale.ROOT);
        if (LANDMARK_ROLES.contains(role)) {
            out.add(LocatorStep.descendant(tag, new com.nekkora.extension.core.locator.query.Predicate.AttrEquals("role", role)));
        }
        if (tag != null && LANDMARK_TAGS.contains(tag)) {
            out.add(LocatorStep.descendant(tag));
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
