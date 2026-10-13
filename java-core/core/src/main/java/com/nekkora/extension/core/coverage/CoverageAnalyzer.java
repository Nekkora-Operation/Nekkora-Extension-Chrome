package com.nekkora.extension.core.coverage;

import com.nekkora.extension.core.semantic.Classification;
import com.nekkora.extension.core.semantic.Confidence;
import com.nekkora.extension.core.semantic.ElementCondition;
import com.nekkora.extension.core.semantic.ElementKind;
import com.nekkora.extension.core.semantic.SemanticIndex;
import com.nekkora.extension.core.snapshot.CaptureStats;
import com.nekkora.extension.core.snapshot.FrameInfo;
import com.nekkora.extension.core.snapshot.PageModel;
import com.nekkora.extension.core.snapshot.ShadowRootInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Calcula o {@link CoverageReport} de um snapshot. */
public final class CoverageAnalyzer {

    public CoverageReport analyze(PageModel model, SemanticIndex semantics) {
        Map<String, Integer> byCategory = new TreeMap<>();
        Map<String, Integer> byKind = new TreeMap<>();
        int visible = 0;
        int hidden = 0;
        int unknown = 0;
        int low = 0;
        int unclassified = 0;
        for (Classification c : semantics.all()) {
            byCategory.merge(c.category().name(), 1, Integer::sum);
            byKind.merge(c.kind().name(), 1, Integer::sum);
            if (c.has(ElementCondition.VISIBLE)) {
                visible++;
            }
            if (c.has(ElementCondition.HIDDEN)) {
                hidden++;
            }
            if (c.has(ElementCondition.UNKNOWN_STATE)) {
                unknown++;
            }
            if (c.confidence() == Confidence.LOW) {
                low++;
            }
            if (c.kind() == ElementKind.UNKNOWN) {
                unclassified++;
            }
        }
        List<String> limitations = new ArrayList<>();
        List<CoverageReport.ScopeCoverage> frames = new ArrayList<>();
        for (FrameInfo f : model.snapshot().frames()) {
            if (f.frameId().equals("f0")) {
                continue;
            }
            int count = model.inScope("doc:" + f.frameId()).size();
            frames.add(new CoverageReport.ScopeCoverage(f.frameId(), f.hostNodeId(), "FRAME", f.accessible(), count, f.reason(), f.url()));
            if (!f.accessible()) {
                limitations.add("Iframe " + f.hostNodeId() + " não inspecionável: " + f.reason()
                        + (f.url() == null ? "" : " (" + f.url() + ")"));
            }
        }
        List<CoverageReport.ScopeCoverage> shadows = new ArrayList<>();
        for (ShadowRootInfo s : model.snapshot().shadowRoots()) {
            int count = model.inScope("shadow:" + s.hostNodeId()).size();
            String kind = "closed".equals(s.mode()) ? "SHADOW_ROOT_CLOSED" : "SHADOW_ROOT_OPEN";
            shadows.add(new CoverageReport.ScopeCoverage("shadow:" + s.hostNodeId(), s.hostNodeId(), kind, s.inspected(), count, s.reason(), null));
            if (!s.inspected()) {
                limitations.add("Shadow root " + s.mode() + " em " + s.hostNodeId() + " não inventariado: "
                        + (s.reason() == null ? "conteúdo encapsulado" : s.reason()) + " (detecção: " + s.detection() + ")");
            }
        }
        CaptureStats st = model.snapshot().stats();
        boolean truncated = st != null && st.truncated();
        if (truncated) {
            limitations.add("Captura truncada no limite de " + st.maxNodes() + " nós: elementos restantes não inventariados");
        }
        if (unknown > 0) {
            limitations.add(unknown + " elemento(s) com estado não observável");
        }
        return new CoverageReport(model.size(), byCategory, byKind, visible, hidden, unknown, low, unclassified,
                frames, shadows, truncated, st == null ? 0 : st.maxNodes(), st == null ? 0 : st.durationMs(), limitations);
    }
}
