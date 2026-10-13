package com.nekkora.extension.core.testing;

import com.nekkora.extension.core.analysis.PageAnalysis;
import com.nekkora.extension.core.analysis.PageAnalyzer;
import com.nekkora.extension.core.identity.IdentityFactory;
import com.nekkora.extension.core.identity.TargetIdentity;
import com.nekkora.extension.core.resolve.ResolutionResult;
import com.nekkora.extension.core.resolve.TargetResolver;
import com.nekkora.extension.core.snapshot.RawSnapshot;

import java.util.concurrent.atomic.AtomicInteger;

/** Atalhos de teste para o fluxo analisar → registrar → resolver. */
public final class Engine {

    private static final PageAnalyzer ANALYZER = new PageAnalyzer();
    private static final AtomicInteger IDS = new AtomicInteger();
    private static final IdentityFactory IDENTITIES = new IdentityFactory(
            () -> "tgt-test-" + IDS.incrementAndGet(), () -> "2026-10-13T00:00:00Z");
    private static final TargetResolver RESOLVER = new TargetResolver();

    private Engine() {
    }

    public static PageAnalysis analyze(RawSnapshot s) {
        return ANALYZER.analyze(s);
    }

    public static PageAnalysis analyze(HtmlSnapshot h) {
        return ANALYZER.analyze(h.capture());
    }

    public static TargetIdentity register(PageAnalysis a, String nodeId) {
        return IDENTITIES.register(nodeId, a.graph(), a.locators(), a.model().snapshot().captureId());
    }

    public static ResolutionResult resolve(TargetIdentity id, PageAnalysis a) {
        return RESOLVER.resolve(id, a.graph(), a.locators());
    }
}
