package com.nekkora.extension.core.mutation;

import com.nekkora.extension.core.analysis.PageAnalysis;
import com.nekkora.extension.core.snapshot.RawNode;
import com.nekkora.extension.core.snapshot.RawSnapshot;
import com.nekkora.extension.core.testing.Engine;
import com.nekkora.extension.core.testing.Fixtures;
import com.nekkora.extension.core.testing.HtmlSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeltaAndDiffTest {

    @Test
    void deltaApplicationEqualsFullCapture() {
        HtmlSnapshot h = HtmlSnapshot.of(Fixtures.SAME_TABLE_ROWS);
        RawSnapshot base = h.capture();
        h.dom().selectFirst("tr[data-client=202]").remove();
        h.dom().selectFirst("tbody").appendElement("tr").attr("data-client", "404").html("<td>Lia</td><td>Ativo</td><td><button>Excluir</button></td>");
        RawSnapshot full = h.capture();

        Map<String, RawNode> before = base.nodes().stream().collect(Collectors.toMap(RawNode::id, n -> n));
        Map<String, RawNode> after = full.nodes().stream().collect(Collectors.toMap(RawNode::id, n -> n));
        List<RawNode> upserts = new ArrayList<>();
        for (RawNode n : full.nodes()) {
            RawNode old = before.get(n.id());
            if (old == null || !old.equals(n)) {
                upserts.add(n);
            }
        }
        List<String> removed = base.nodes().stream().map(RawNode::id).filter(id -> !after.containsKey(id)).toList();
        SnapshotDelta delta = new SnapshotDelta(base.captureId(), "cap-delta", full.capturedAt(), full.document(), upserts,
                removed, null, null, List.of(), 7, 2, full.stats());
        RawSnapshot applied = new DeltaApplier().apply(base, delta);

        Set<String> a = applied.nodes().stream().map(RawNode::id).collect(Collectors.toSet());
        Set<String> f = full.nodes().stream().map(RawNode::id).collect(Collectors.toSet());
        assertEquals(f, a);
        assertEquals(full.nodes().stream().map(RawNode::id).toList(), applied.nodes().stream().map(RawNode::id).toList(),
                "ordem de documento preservada");
    }

    @Test
    void deltaRejectsWrongBase() {
        HtmlSnapshot h = HtmlSnapshot.of("<p>x</p>");
        RawSnapshot base = h.capture();
        SnapshotDelta d = new SnapshotDelta("outra", "c2", null, null, List.of(), List.of(), null, null, List.of(), 0, 0, null);
        assertThrows(IllegalArgumentException.class, () -> new DeltaApplier().apply(base, d));
    }

    @Test
    void comparatorReportsRelevantChangesAndReplacements() {
        HtmlSnapshot h = HtmlSnapshot.of("<form><label for=c>Cidade</label><input id=c><button id=s>Salvar</button></form><div id=clock>10:00</div>");
        PageAnalysis a = Engine.analyze(h);
        h.dom().selectFirst("#s").replaceWith(org.jsoup.Jsoup.parse("<button id=s>Salvar</button>").selectFirst("button"));
        h.dom().selectFirst("#c").attr("disabled", "");
        PageAnalysis b = Engine.analyze(h);
        MutationRecord r = new SnapshotComparator().compare(a.graph(), b.graph(), "FULL", 0, 0);
        assertTrue(r.relevant());
        assertEquals(1, r.replaced().size(), "botão re-renderizado reconciliado");
        assertTrue(r.changed().stream().anyMatch(c -> c.attributeChanges().containsKey("disabled")));
    }

    @Test
    void irrelevantChangesAreFiltered() {
        HtmlSnapshot h = HtmlSnapshot.of("<div id=box class=a><span>x</span></div>");
        PageAnalysis a = Engine.analyze(h);
        h.dom().selectFirst("#box").attr("class", "b");
        MutationRecord r = new SnapshotComparator().compare(a.graph(), Engine.analyze(h).graph(), "FULL", 0, 0);
        assertFalse(r.relevant());
        assertEquals(1, r.changedCount());
    }
}
