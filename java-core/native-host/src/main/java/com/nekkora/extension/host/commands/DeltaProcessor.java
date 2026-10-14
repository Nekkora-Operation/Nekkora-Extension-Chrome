package com.nekkora.extension.host.commands;

import com.nekkora.extension.core.analysis.PageAnalysis;
import com.nekkora.extension.core.identity.TargetIdentity;
import com.nekkora.extension.core.mutation.MutationRecord;
import com.nekkora.extension.core.mutation.SnapshotDelta;
import com.nekkora.extension.core.resolve.ResolutionResult;
import com.nekkora.extension.core.resolve.ResolutionStatus;
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

/**
 * Aplica um delta sobre a última captura da aba: reanálise incremental, registro da mutação e reencontro
 * automático dos alvos cuja referência foi removida/alterada. Usado pelo comando legado {@code snapshot.delta}
 * e pelo comando sequenciado {@code capture.session.delta}.
 */
final class DeltaProcessor {

    private final EngineServices s;

    DeltaProcessor(EngineServices services) {
        this.s = services;
    }

    Map<String, Object> apply(TabState tab, PageAnalysis base, SnapshotDelta delta, boolean autoResolve) {
        long t0 = System.nanoTime();
        RawSnapshot applied = s.deltas.apply(base.model().snapshot(), delta);
        Set<String> changed = new HashSet<>();
        delta.upserts().forEach(n -> changed.add(n.id()));
        PageAnalysis analysis = s.session.analyzer().reanalyze(applied, base, changed);
        long t1 = System.nanoTime();
        tab.putCapture(analysis);
        MutationRecord record = s.comparator.compare(base.graph(), analysis.graph(), "INCREMENTAL",
                delta.rawMutationCount(), delta.batchCount());

        Set<String> removedNow = new HashSet<>();
        for (RawNode n : base.model().nodes()) {
            if (!analysis.model().contains(n.id())) {
                removedNow.add(n.id());
            }
        }
        String pageSession = applied.document() == null ? null : applied.document().pageSessionId();
        List<String> affected = new ArrayList<>();
        List<ResolutionResult> auto = new ArrayList<>();
        if (autoResolve) {
            for (TargetIdentity t : s.session.targets().ownedByTab(tab.tabKey())) { // somente alvos desta aba
                if (!Objects.equals(t.lastPageSessionId(), pageSession) || t.lastNodeId() == null) {
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
        out.put("timings", Map.of("applyMs", round((t1 - t0) / 1e6), "classificationMs", analysis.classificationMs(),
                "upserts", delta.upserts().size(), "removed", delta.removed().size(), "hostTotalMs", processing));
        return out;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
