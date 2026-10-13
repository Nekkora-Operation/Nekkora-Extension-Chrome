package com.nekkora.extension.core.analysis;

import com.nekkora.extension.core.context.ContextGraph;
import com.nekkora.extension.core.locator.LocatorEngine;
import com.nekkora.extension.core.semantic.SemanticAnalyzer;
import com.nekkora.extension.core.semantic.SemanticIndex;
import com.nekkora.extension.core.snapshot.PageModel;
import com.nekkora.extension.core.snapshot.RawSnapshot;

import java.util.Set;

/** Fachada do motor: snapshot → análise completa. */
public final class PageAnalyzer {

    private final SemanticAnalyzer semantic = new SemanticAnalyzer();
    private final LocatorEngine locatorEngine;

    public PageAnalyzer() {
        this(new LocatorEngine());
    }

    public PageAnalyzer(LocatorEngine locatorEngine) {
        this.locatorEngine = locatorEngine;
    }

    public PageAnalysis analyze(RawSnapshot snapshot) {
        long t0 = System.nanoTime();
        PageModel model = PageModel.of(snapshot);
        long t1 = System.nanoTime();
        SemanticIndex semantics = semantic.analyze(model);
        long t2 = System.nanoTime();
        ContextGraph graph = new ContextGraph(model, semantics);
        return new PageAnalysis(model, semantics, graph, locatorEngine.session(graph), ms(t0, t1), ms(t1, t2));
    }

    /** Reanálise incremental: só reclassifica nós alterados, reaproveitando as demais classificações. */
    public PageAnalysis reanalyze(RawSnapshot snapshot, PageAnalysis previous, Set<String> changedNodeIds) {
        long t0 = System.nanoTime();
        PageModel model = PageModel.of(snapshot);
        long t1 = System.nanoTime();
        SemanticIndex semantics = semantic.reanalyze(model, previous.semantics(), changedNodeIds);
        long t2 = System.nanoTime();
        ContextGraph graph = new ContextGraph(model, semantics);
        return new PageAnalysis(model, semantics, graph, locatorEngine.session(graph), ms(t0, t1), ms(t1, t2));
    }

    public LocatorEngine locatorEngine() {
        return locatorEngine;
    }

    static double ms(long a, long b) {
        return Math.round((b - a) / 10_000.0) / 100.0;
    }
}
