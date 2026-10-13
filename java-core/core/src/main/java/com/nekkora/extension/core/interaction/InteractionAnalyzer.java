package com.nekkora.extension.core.interaction;

import com.nekkora.extension.core.context.ContextGraph;
import com.nekkora.extension.core.mutation.MutationRecord;
import com.nekkora.extension.core.mutation.SnapshotComparator;
import com.nekkora.extension.core.navigation.NavigationChange;
import com.nekkora.extension.core.navigation.UrlContextParser;
import com.nekkora.extension.core.semantic.Classification;
import com.nekkora.extension.core.semantic.ElementKind;
import com.nekkora.extension.core.snapshot.DocumentInfo;
import com.nekkora.extension.core.snapshot.ObservedRequest;
import com.nekkora.extension.core.snapshot.PageModel;
import com.nekkora.extension.core.snapshot.RawNode;
import com.nekkora.extension.core.text.TextNormalizer;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Compara estados antes/depois de uma interação e descreve as consequências observadas. */
public final class InteractionAnalyzer {

    private final SnapshotComparator comparator = new SnapshotComparator();
    private final UrlContextParser urls = new UrlContextParser();

    public InteractionObservation analyze(ContextGraph before, ContextGraph after, Map<String, Object> instrumentation) {
        PageModel a = before.model();
        PageModel b = after.model();
        List<String> notes = new ArrayList<>();
        DocumentInfo da = a.snapshot().document();
        DocumentInfo db = b.snapshot().document();
        NavigationChange url = NavigationChange.between(urls.parse(da.url(), da.title()), urls.parse(db.url(), db.title()));
        if (!url.queryChanges().isEmpty()) {
            notes.add("A query da URL mudou; isso por si só não comprova consulta ao backend.");
        }

        List<InteractionObservation.UiQuery> ui = new ArrayList<>();
        for (RawNode nb : b.nodes()) {
            Classification c = after.semantics().get(nb.id());
            if (!(c.kind().isTextEntry() || c.kind() == ElementKind.SELECT)) {
                continue;
            }
            RawNode na = a.find(nb.id()).orElse(null);
            String vb = valueOf(nb, b, after);
            String va = na == null ? null : valueOf(na, a, before);
            if (vb != null && !Objects.equals(va, vb) || (va != null && !Objects.equals(va, vb))) {
                ui.add(new InteractionObservation.UiQuery(nb.id(), c.kind().name(), c.name().name(), va, vb, "DOM"));
            }
        }

        List<InteractionObservation.ApiRequest> api = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ObservedRequest r : a.snapshot().requests()) {
            seen.add(r.url() + "@" + r.startTime());
        }
        for (ObservedRequest r : b.snapshot().requests()) {
            if (seen.contains(r.url() + "@" + r.startTime())) {
                continue;
            }
            String path = null;
            Map<String, List<String>> q = Map.of();
            try {
                URI u = URI.create(r.url());
                path = u.getPath();
                q = urls.parse(r.url(), null).observed().query();
            } catch (IllegalArgumentException ignored) {
                // URL não interpretável: mantém somente a URL bruta
            }
            api.add(new InteractionObservation.ApiRequest(r.url(), path, q, r.initiatorType(), r.startTime(), "RESOURCE_TIMING"));
        }
        if (api.isEmpty()) {
            notes.add("Nenhuma requisição de rede nova observada via Resource Timing entre as capturas.");
        }

        List<InteractionObservation.ResultRegionChange> regions = new ArrayList<>();
        for (RawNode nb : b.nodes()) {
            Classification c = after.semantics().get(nb.id());
            if (c.kind() != ElementKind.TABLE && c.kind() != ElementKind.LIST && c.kind() != ElementKind.LISTBOX) {
                continue;
            }
            int itemsAfter = items(nb, b, after);
            RawNode na = a.find(nb.id()).orElse(null);
            if (na == null && nb.attr("id") != null) {
                na = a.byIdAttribute(nb.scopeId(), nb.attr("id")).stream().findFirst().orElse(null);
            }
            int itemsBefore = na == null ? 0 : items(na, a, before);
            boolean contentChanged = na == null || !TextNormalizer.normalize(a.textContent(na)).equals(b.textContent(nb));
            if (itemsAfter != itemsBefore || contentChanged) {
                regions.add(new InteractionObservation.ResultRegionChange(nb.id(), c.kind().name(),
                        name(nb, c), itemsBefore, itemsAfter, sample(nb, b, after), "DOM"));
            }
        }
        MutationRecord dom = comparator.compare(before, after, "FULL", 0, 0);
        if (instrumentation != null && !instrumentation.isEmpty()) {
            notes.add("Valores de instrumentação vieram de hooks de teste declarados pela página local (não da DOM).");
        }
        return new InteractionObservation(a.snapshot().captureId(), b.snapshot().captureId(), url, ui, api, regions, dom,
                instrumentation, notes);
    }

    private static String valueOf(RawNode n, PageModel m, ContextGraph g) {
        if (n.tag().equals("select")) {
            List<String> sel = new ArrayList<>();
            for (RawNode d : m.descendants(n)) {
                if (d.tag().equals("option") && Boolean.TRUE.equals(d.state().selected())) {
                    sel.add(m.textContent(d));
                }
            }
            return g.semantics().get(n.id()).sensitive() ? null : String.join(",", sel);
        }
        return n.exposedValue();
    }

    private static String name(RawNode n, Classification c) {
        if (!c.name().name().isEmpty()) {
            return c.name().name();
        }
        return n.attr("id") == null ? n.tag() : "#" + n.attr("id");
    }

    private static int items(RawNode container, PageModel m, ContextGraph g) {
        int count = 0;
        for (RawNode d : m.descendants(container)) {
            ElementKind k = g.semantics().get(d.id()).kind();
            if (container.tag().equals("table") || g.semantics().get(container.id()).kind() == ElementKind.TABLE) {
                if (k == ElementKind.TABLE_ROW && !isHeaderRow(d, m)) {
                    count++;
                }
            } else if (k == ElementKind.LIST_ITEM || k == ElementKind.OPTION) {
                count++;
            }
        }
        return count;
    }

    private static boolean isHeaderRow(RawNode row, PageModel m) {
        return m.parent(row).map(p -> p.tag().equals("thead")).orElse(false);
    }

    private static List<String> sample(RawNode container, PageModel m, ContextGraph g) {
        List<String> out = new ArrayList<>();
        for (RawNode d : m.descendants(container)) {
            ElementKind k = g.semantics().get(d.id()).kind();
            if ((k == ElementKind.TABLE_ROW && !isHeaderRow(d, m)) || k == ElementKind.LIST_ITEM || k == ElementKind.OPTION) {
                out.add(TextNormalizer.truncate(m.visibleText(d), 80));
                if (out.size() >= 5) {
                    break;
                }
            }
        }
        return out;
    }
}
