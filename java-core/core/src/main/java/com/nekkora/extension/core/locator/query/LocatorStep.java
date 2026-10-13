package com.nekkora.extension.core.locator.query;

import java.util.ArrayList;
import java.util.List;

/**
 * Passo de um localizador estruturado.
 *
 * @param axis       relação com o passo anterior (o primeiro passo parte da raiz do escopo)
 * @param tag        tag exigida ou {@code null} para qualquer elemento
 * @param predicates predicados (conjunção)
 */
public record LocatorStep(Axis axis, String tag, List<Predicate> predicates) {

    public enum Axis { DESCENDANT, CHILD }

    public LocatorStep {
        predicates = List.copyOf(predicates);
    }

    public static LocatorStep descendant(String tag, Predicate... predicates) {
        return new LocatorStep(Axis.DESCENDANT, tag, List.of(predicates));
    }

    public static LocatorStep child(String tag, Predicate... predicates) {
        return new LocatorStep(Axis.CHILD, tag, List.of(predicates));
    }

    public String css() {
        StringBuilder sb = new StringBuilder(tag == null ? "" : tag);
        for (Predicate p : predicates) {
            String c = p.css();
            if (c == null) {
                return null;
            }
            sb.append(c);
        }
        return sb.isEmpty() ? "*" : sb.toString();
    }

    public String xpath() {
        StringBuilder sb = new StringBuilder(axis == Axis.DESCENDANT ? "//" : "/");
        sb.append(tag == null ? "*" : tag);
        // Posição precisa ser o primeiro predicado para manter a semântica de :nth-of-type.
        List<Predicate> ordered = new ArrayList<>();
        predicates.stream().filter(p -> p instanceof Predicate.NthOfType).forEach(ordered::add);
        predicates.stream().filter(p -> !(p instanceof Predicate.NthOfType)).forEach(ordered::add);
        for (Predicate p : ordered) {
            String x = p.xpath();
            if (x == null) {
                return null;
            }
            sb.append('[').append(x).append(']');
        }
        return sb.toString();
    }

    public String describe() {
        StringBuilder sb = new StringBuilder(tag == null ? "*" : tag);
        for (Predicate p : predicates) {
            sb.append(" {").append(p.describe()).append('}');
        }
        return sb.toString();
    }
}
