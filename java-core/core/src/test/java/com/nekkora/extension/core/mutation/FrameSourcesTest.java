package com.nekkora.extension.core.mutation;

import com.nekkora.extension.core.analysis.PageAnalysis;
import com.nekkora.extension.core.snapshot.CaptureStats;
import com.nekkora.extension.core.snapshot.FrameInfo;
import com.nekkora.extension.core.snapshot.NodeState;
import com.nekkora.extension.core.snapshot.RawNode;
import com.nekkora.extension.core.snapshot.RawSnapshot;
import com.nekkora.extension.core.testing.Engine;
import com.nekkora.extension.core.testing.HtmlSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameSourcesTest {

    private static RawNode n(String id, String parent, List<String> children, String tag, String frame, String text) {
        return new RawNode(id, parent, children, tag, frame, "doc:" + frame, Map.of(), text,
                new NodeState(true, null, true, false, null, null, null, null, null, false, false, false, false, null, null, null, null, false),
                null, List.of(), null, null);
    }

    private static RawSnapshot childFrame(String prefix, String buttonText) {
        String f = "c" + prefix.substring(1);
        List<RawNode> nodes = List.of(
                n(prefix + "n1", null, List.of(prefix + "n2"), "html", f, ""),
                n(prefix + "n2", prefix + "n1", List.of(prefix + "n3"), "body", f, ""),
                n(prefix + "n3", prefix + "n2", List.of(), "button", f, buttonText));
        return new RawSnapshot("cap-child", "t", null, List.of(new FrameInfo(f, null, null, "http://localhost:18766/x.html", true, null)),
                List.of(), nodes, List.of(), new CaptureStats(3, 3, 1, false, 100));
    }

    @Test
    void sourcesAreDerivedFromIds() {
        assertEquals("top", Sources.sourceOf("n42"));
        assertEquals("x12", Sources.sourceOf("x12n42"));
        assertEquals("x12", Sources.frameOwner("c12"));
        assertEquals("x12", Sources.frameOwner("c12f1"));
        assertEquals("top", Sources.frameOwner("f1"));
    }

    @Test
    void attachCrossOriginFrameAndKeepItAcrossTopDeltas() {
        HtmlSnapshot h = HtmlSnapshot.of("<p>topo</p><iframe id=cross data-fixture-frame=missing></iframe>");
        RawSnapshot top = h.capture();
        String host = h.idOf("#cross");
        assertTrue(top.frames().stream().anyMatch(f -> host.equals(f.hostNodeId()) && !f.accessible()));

        RawSnapshot merged = new FrameMerger().attach(top, host, childFrame("x12", "Pagar"), "x12", "cap-m1");
        PageAnalysis a = Engine.analyze(merged);
        assertEquals("BUTTON", a.semantics().get("x12n3").kind().name());
        assertEquals("cross", a.graph().family("x12n3").scopeChain().get(0).idAttr());
        assertFalse(merged.frames().stream().anyMatch(f -> host.equals(f.hostNodeId()) && !f.accessible()),
                "marcador 'inacessível' substituído pelo frame anexado");

        // Delta do documento principal re-descreve o iframe sem conhecer o filho de outra fonte.
        RawNode iframe = merged.nodes().stream().filter(x -> x.id().equals(host)).findFirst().orElseThrow();
        RawNode topView = iframe.withChildIds(List.of());
        SnapshotDelta d = new SnapshotDelta(null, "cap-m2", "t", merged.document(), List.of(topView), List.of(),
                top.frames(), null, List.of(), 1, 1, null, "s1", "top", 1, 1);
        RawSnapshot after = new DeltaApplier().apply(merged, d);
        assertTrue(after.nodes().stream().anyMatch(x -> x.id().equals("x12n3")), "nós do frame preservados");
        assertTrue(after.frames().stream().anyMatch(f -> f.frameId().equals("c12") && f.accessible()));
        assertFalse(after.frames().stream().anyMatch(f -> host.equals(f.hostNodeId()) && !f.accessible()));

        // Re-anexar (documento do frame substituído) troca o conteúdo sem duplicar.
        RawSnapshot again = new FrameMerger().attach(after, host, childFrame("x12", "Confirmar"), "x12", "cap-m3");
        assertEquals(1, again.nodes().stream().filter(x -> x.tag().equals("button") && x.id().startsWith("x12")).count());
        assertEquals("Confirmar", Engine.analyze(again).semantics().get("x12n3").name().name());

        // Remoção do iframe pelo documento principal remove a subárvore inteira, inclusive a outra fonte.
        SnapshotDelta rm = new SnapshotDelta(null, "cap-m4", "t", merged.document(), List.of(), List.of(host),
                null, null, List.of(), 1, 1, null, "s1", "top", 1, 2);
        RawSnapshot removed = new DeltaApplier().apply(again, rm);
        assertFalse(removed.nodes().stream().anyMatch(x -> x.id().startsWith("x12")));
    }
}
