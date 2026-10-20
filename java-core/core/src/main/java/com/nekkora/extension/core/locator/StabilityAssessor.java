package com.nekkora.extension.core.locator;

import com.nekkora.extension.core.locator.query.LocatorQuery;
import com.nekkora.extension.core.locator.query.LocatorStep;
import com.nekkora.extension.core.locator.query.Predicate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Estima a estabilidade de um localizador a partir dos elementos que ele utiliza. */
public final class StabilityAssessor {

    private static final Pattern DIGITS = Pattern.compile("\\d");

    private final IdentifierHeuristics heuristics = new IdentifierHeuristics();

    public record Result(StabilityLevel level, String basis, List<String> reasons) {
    }

    /**
     * @param query        localizador
     * @param targetStepIx índice do passo que seleciona o próprio alvo (o último); somente os atributos
     *                     deste passo podem ser confrontados com o histórico do alvo
     * @param observations histórico de valores do alvo
     */
    public Result assess(LocatorQuery query, StabilityObservations observations) {
        StabilityLevel level = null;
        boolean observed = false;
        List<String> reasons = new ArrayList<>();
        List<LocatorStep> steps = query.steps();
        for (int i = 0; i < steps.size(); i++) {
            boolean targetStep = i == steps.size() - 1;
            for (Predicate p : steps.get(i).predicates()) {
                StabilityLevel partial;
                if (p instanceof Predicate.AttrEquals eq) {
                    String attr = eq.name();
                    var hist = targetStep ? observations.history(attr) : java.util.Optional.<List<String>>empty();
                    if (hist.isPresent() && hist.get().size() >= 2) {
                        Set<String> distinct = new HashSet<>(hist.get());
                        if (distinct.size() == 1 && Objects.equals(distinct.iterator().next(), eq.value())) {
                            partial = StabilityLevel.OBSERVED_STABLE;
                            reasons.add(attr + " idêntico em " + hist.get().size() + " capturas");
                        } else {
                            partial = StabilityLevel.OBSERVED_UNSTABLE;
                            reasons.add(attr + " mudou entre capturas: " + String.join(" → ", hist.get().stream().map(String::valueOf).toList()));
                        }
                        observed = true;
                    } else if (attr.equals("id") || attr.equals("name")) {
                        IdentifierHeuristics.Assessment a = heuristics.assess(eq.value());
                        partial = a.level();
                        for (String r : a.reasons()) {
                            reasons.add(attr + "=\"" + eq.value() + "\": " + r);
                        }
                    } else if (attr.startsWith("data-test") || attr.equals("data-qa") || attr.equals("data-cy")) {
                        partial = StabilityLevel.HEURISTIC_STABLE;
                        reasons.add(attr + ": atributo dedicado a testes");
                    } else if (attr.startsWith("data-")) {
                        partial = StabilityLevel.HEURISTIC_STABLE;
                        reasons.add(attr + ": chave de dados da aplicação (valor de negócio presumido)");
                    } else {
                        partial = StabilityLevel.HEURISTIC_STABLE;
                    }
                } else if (p instanceof Predicate.TextEquals t) {
                    partial = DIGITS.matcher(t.text()).find() ? StabilityLevel.UNKNOWN : StabilityLevel.HEURISTIC_STABLE;
                    if (partial == StabilityLevel.UNKNOWN) {
                        reasons.add("texto contém números (pode ser conteúdo dinâmico)");
                    }
                } else if (p instanceof Predicate.HasChildText t) {
                    partial = StabilityLevel.HEURISTIC_STABLE;
                    reasons.add("âncora por conteúdo textual \"" + t.text() + "\"");
                } else if (p instanceof Predicate.AfterTerm t) {
                    partial = StabilityLevel.HEURISTIC_STABLE;
                    reasons.add("âncora pelo termo \"" + t.text() + "\"");
                } else if (p instanceof Predicate.NthOfType) {
                    partial = StabilityLevel.HEURISTIC_UNSTABLE;
                    reasons.add("depende de posição (nth-of-type)");
                } else if (p instanceof Predicate.NthChild) {
                    partial = StabilityLevel.HEURISTIC_UNSTABLE;
                    reasons.add("depende de posição (nth-child)");
                } else {
                    partial = StabilityLevel.HEURISTIC_STABLE;
                }
                level = level == null ? partial : StabilityLevel.weakest(level, partial);
            }
        }
        return new Result(level == null ? StabilityLevel.HEURISTIC_STABLE : level, observed ? "OBSERVED" : "HEURISTIC", reasons);
    }
}
