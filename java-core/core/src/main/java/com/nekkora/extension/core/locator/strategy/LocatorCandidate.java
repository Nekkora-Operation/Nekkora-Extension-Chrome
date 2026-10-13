package com.nekkora.extension.core.locator.strategy;

import com.nekkora.extension.core.locator.LocatorStrategyKind;
import com.nekkora.extension.core.locator.query.LocatorQuery;

import java.util.List;

/** Proposta de localizador feita por uma estratégia, ainda não avaliada. */
public record LocatorCandidate(LocatorStrategyKind kind, LocatorQuery query, List<String> notes) {

    public LocatorCandidate {
        notes = List.copyOf(notes);
    }

    public static LocatorCandidate of(LocatorStrategyKind kind, LocatorQuery query, String... notes) {
        return new LocatorCandidate(kind, query, List.of(notes));
    }
}
