package com.nekkora.extension.core.locator.query;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Localizador estruturado: sequência de passos avaliada a partir da raiz de um escopo. */
public record LocatorQuery(List<LocatorStep> steps) {

    public LocatorQuery {
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("Localizador sem passos");
        }
        steps = List.copyOf(steps);
    }

    public static LocatorQuery of(LocatorStep... steps) {
        return new LocatorQuery(List.of(steps));
    }

    public LocatorQuery then(LocatorStep step) {
        List<LocatorStep> s = new ArrayList<>(steps);
        s.add(step);
        return new LocatorQuery(s);
    }

    public LocatorQuery prefixedBy(List<LocatorStep> prefix) {
        List<LocatorStep> s = new ArrayList<>(prefix);
        s.addAll(steps);
        return new LocatorQuery(s);
    }

    /** CSS equivalente ou {@code null}. */
    public String css() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < steps.size(); i++) {
            LocatorStep step = steps.get(i);
            String c = step.css();
            if (c == null) {
                return null;
            }
            if (i > 0) {
                sb.append(step.axis() == LocatorStep.Axis.CHILD ? " > " : " ");
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** XPath 1.0 equivalente ou {@code null}. */
    public String xpath() {
        StringBuilder sb = new StringBuilder();
        for (LocatorStep step : steps) {
            String x = step.xpath();
            if (x == null) {
                return null;
            }
            sb.append(x);
        }
        return sb.toString();
    }

    /** Notação semântica legível (sempre disponível), ex.: {@code role=button {name="Excluir"}}. */
    public String semantic() {
        return steps.stream().map(LocatorStep::describe).collect(Collectors.joining(" >> "));
    }
}
