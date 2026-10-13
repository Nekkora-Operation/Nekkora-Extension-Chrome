package com.nekkora.extension.host.commands;

import com.nekkora.extension.core.analysis.PageAnalysis;
import com.nekkora.extension.core.coverage.CoverageAnalyzer;
import com.nekkora.extension.core.coverage.CoverageReport;
import com.nekkora.extension.core.identity.TargetIdentity;
import com.nekkora.extension.core.mutation.MutationRecord;
import com.nekkora.extension.core.mutation.SnapshotDelta;
import com.nekkora.extension.core.navigation.UrlContextParser;
import com.nekkora.extension.core.resolve.ResolutionResult;
import com.nekkora.extension.core.resolve.ResolutionStatus;
import com.nekkora.extension.core.semantic.Classification;
import com.nekkora.extension.core.semantic.ElementCategory;
import com.nekkora.extension.core.snapshot.RawNode;
import com.nekkora.extension.core.snapshot.RawSnapshot;
import com.nekkora.extension.host.session.TabState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Captura completa, atualização incremental (delta) e comparação de capturas. */
public final class CaptureCommands implements CommandRouter.CommandGroup {

    private final EngineServices s;
    private final CoverageAnalyzer coverage = new CoverageAnalyzer();
    private final UrlContextParser urls = new UrlContextParser();

    public CaptureCommands(EngineServices services) {
        this.s = services;
    }

    @Override
    public void register(CommandRouter router) {
        router.register("snapshot.submit", p -> {
            long t0 = System.nanoTime();
            TabState tab = s.session.tab(Payloads.required(p, "tabKey"));
            RawSnapshot snap = s.json.read(Payloads.node(p, "snapshot"), RawSnapshot.class);
            long t1 = System.nanoTime();
            PageAnalysis previous = tab.latest().orElse(null);
            PageAnalysis analysis = s.session.analyzer().analyze(snap);
            tab.putCapture(analysis);
            Map<String, Object> out = summary(analysis);
            if (Payloads.flag(p, "compareWithPrevious", false) && previous != null) {
                MutationRecord r = s.comparator.compare(previous.graph(), analysis.graph(), "FULL", 0, 0);
                tab.addMutation(r);
                out.put("mutation", r);
            }
            out.put("timings", Map.of("deserializeMs", round((t1 - t0) / 1e6), "indexMs", analysis.indexMs(),
                    "classificationMs", analysis.classificationMs(), "hostTotalMs", EngineServices.ms(t0)));
            return out;
        });

        router.register("snapshot.delta", p -> {
            long t0 = System.nanoTime();
            TabState tab = s.session.tab(Payloads.required(p, "tabKey"));
            SnapshotDelta delta = s.json.read(Payloads.node(p, "delta"), SnapshotDelta.class);
            PageAnalysis base = s.capture(tab, delta.baseCaptureId());
            long t1 = System.nanoTime();
            RawSnapshot applied = s.deltas.apply(base.model().snapshot(), delta);
            Set<String> changed = new HashSet<>();
            delta.upserts().forEach(n -> changed.add(n.id()));
            PageAnalysis analysis = s.session.analyzer().reanalyze(applied, base, changed);
            long t2 = System.nanoTime();
            tab.putCapture(analysis);
            MutationRecord record = s.comparator.compare(base.graph(), analysis.graph(), "INCREMENTAL",
                    delta.rawMutationCount(), delta.batchCount());

            // Alvos afetados: a última referência foi removida ou alterada.
            Set<String> removedNow = new HashSet<>();
            for (RawNode n : base.model().nodes()) {
                if (!analysis.model().contains(n.id())) {
                    removedNow.add(n.id());
                }
            }
            String sessionId = applied.document() == null ? null : applied.document().pageSessionId();
            List<String> affected = new ArrayList<>();
            List<ResolutionResult> auto = new ArrayList<>();
            if (Payloads.flag(p, "autoResolve", true)) {
                for (TargetIdentity t : s.session.targets().all()) {
                    if (!Objects.equals(t.lastPageSessionId(), sessionId) || t.lastNodeId() == null) {
                        continue;
                    }
                    if (removedNow.contains(t.lastNodeId()) || changed.contains(t.lastNodeId())) {
                        affected.add(t.targetId());
                        ResolutionResult r = s.resolver.resolve(t, analysis.graph(), analysis.locators());
                        if (r.status() == ResolutionStatus.RESOLVED && r.updatedIdentity() != null) {
                            s.session.targets().put(r.updatedIdentity());
                        }
                        auto.add(r);
                    }
                }
            }
            double processing = EngineServices.ms(t0);
            record = record.withTargets(affected, processing);
            tab.addMutation(record);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("captureId", applied.captureId());
            out.put("nodes", analysis.model().size());
            out.put("mutation", record);
            out.put("autoResolutions", auto);
            out.put("timings", Map.of("applyMs", round((t2 - t1) / 1e6), "classificationMs", analysis.classificationMs(),
                    "upserts", delta.upserts().size(), "removed", delta.removed().size(), "hostTotalMs", processing));
            return out;
        });

        router.register("snapshot.compare", p -> {
            TabState tab = s.session.tab(Payloads.required(p, "tabKey"));
            PageAnalysis a = s.capture(tab, Payloads.required(p, "baseCaptureId"));
            PageAnalysis b = s.capture(tab, Payloads.optional(p, "captureId"));
            MutationRecord r = s.comparator.compare(a.graph(), b.graph(), "FULL", 0, 0);
            tab.addMutation(r);
            return r;
        });

        router.register("session.reset", p -> {
            String tabKey = Payloads.required(p, "tabKey");
            s.session.resetTab(tabKey);
            if (Payloads.flag(p, "clearTargets", false)) {
                s.session.targets().clear();
            }
            return Map.of("reset", tabKey);
        });
    }

    private Map<String, Object> summary(PageAnalysis a) {
        RawSnapshot snap = a.model().snapshot();
        int interactive = 0;
        int text = 0;
        for (Classification c : a.semantics().all()) {
            if (c.category() == ElementCategory.INTERACTIVE) {
                interactive++;
            } else if (c.category() == ElementCategory.TEXT) {
                text++;
            }
        }
        CoverageReport cov = coverage.analyze(a.model(), a.semantics());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("captureId", snap.captureId());
        out.put("pageSessionId", snap.document() == null ? null : snap.document().pageSessionId());
        out.put("nodes", a.model().size());
        out.put("interactive", interactive);
        out.put("text", text);
        out.put("coverage", cov);
        out.put("navigation", snap.document() == null ? null : urls.parse(snap.document().url(), snap.document().title()));
        return out;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
