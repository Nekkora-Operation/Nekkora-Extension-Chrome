package com.nekkora.extension.core.mutation;

import com.nekkora.extension.core.snapshot.RawNode;
import com.nekkora.extension.core.snapshot.RawSnapshot;
import com.nekkora.extension.core.testing.HtmlSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Atualizações compactas de geometria (NEKKORA-REAL-001): rolagem sem redescrever nós. */
class GeometryUpdateTest {

    @Test
    void geometryUpdatesChangeOnlyRectAndViewportState() {
        HtmlSnapshot h = HtmlSnapshot.of("<main><button id=a>A</button><button id=b>B</button></main>");
        RawSnapshot base = h.capture();
        String a = h.idOf("#a");
        String b = h.idOf("#b");
        RawNode before = base.nodes().stream().filter(n -> n.id().equals(a)).findFirst().orElseThrow();
        SnapshotDelta delta = new SnapshotDelta(null, "cap-geo", base.capturedAt(), base.document(), List.of(), List.of(), null, null,
                List.of(), 0, 0, null, "s1", "top", 1, 1, null,
                List.of(new GeometryUpdate(a, 10, -900, 80, 20, false), new GeometryUpdate("n-inexistente", 0, 0, 1, 1, true)));
        RawSnapshot after = new DeltaApplier().apply(base, delta);
        RawNode moved = after.nodes().stream().filter(n -> n.id().equals(a)).findFirst().orElseThrow();
        assertEquals(-900, moved.rect().y());
        assertFalse(moved.state().inViewport());
        assertEquals(before.ownText(), moved.ownText());
        assertEquals(before.attributes(), moved.attributes());
        assertEquals(before.state().visible(), moved.state().visible(), "visibilidade não muda por geometria");
        assertEquals(base.nodes().size(), after.nodes().size(), "id desconhecido é ignorado; nenhum nó criado");
        assertTrue(after.nodes().stream().anyMatch(n -> n.id().equals(b)));
    }

    @Test
    void upsertInTheSameDeltaWinsOverTheCompactUpdate() {
        HtmlSnapshot h = HtmlSnapshot.of("<button id=a>A</button>");
        RawSnapshot base = h.capture();
        String a = h.idOf("#a");
        RawNode full = base.nodes().stream().filter(n -> n.id().equals(a)).findFirst().orElseThrow();
        SnapshotDelta delta = new SnapshotDelta(null, "cap-geo2", base.capturedAt(), base.document(), List.of(full), List.of(), null, null,
                List.of(), 0, 0, null, "s1", "top", 1, 1, null, List.of(new GeometryUpdate(a, 1, 2, 3, 4, false)));
        RawNode r = new DeltaApplier().apply(base, delta).nodes().stream().filter(n -> n.id().equals(a)).findFirst().orElseThrow();
        assertEquals(full.rect(), r.rect());
    }
}
