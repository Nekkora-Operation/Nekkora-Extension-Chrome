package com.nekkora.extension.core.context;

import com.nekkora.extension.core.context.ContextRelation.Basis;
import com.nekkora.extension.core.semantic.Classification;
import com.nekkora.extension.core.semantic.ElementCategory;
import com.nekkora.extension.core.semantic.ElementKind;
import com.nekkora.extension.core.semantic.NameSource;
import com.nekkora.extension.core.semantic.SemanticIndex;
import com.nekkora.extension.core.snapshot.NodeState;
import com.nekkora.extension.core.snapshot.PageModel;
import com.nekkora.extension.core.snapshot.RawNode;
import com.nekkora.extension.core.text.TextNormalizer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Grafo de contexto: determina a família de cada elemento combinando hierarquia da DOM,
 * relações declaradas (ARIA/HTML) e regras heurísticas explícitas.
 *
 * <p>As famílias são calculadas sob demanda e memorizadas por snapshot.
 */
public final class ContextGraph {

    private static final int MAX_SIBLINGS = 10;
    private static final int MAX_CONTEXT_TEXTS = 8;
    private static final int MAX_ANCESTORS = 12;

    private final PageModel model;
    private final SemanticIndex semantics;
    private final Map<String, ElementFamily> cache = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, List<ContextRelation>> incoming = new HashMap<>();
    private final List<RawNode> headings;

    public ContextGraph(PageModel model, SemanticIndex semantics) {
        this.model = model;
        this.semantics = semantics;
        this.headings = model.inDocumentOrder().stream()
                .filter(n -> semantics.get(n.id()).kind() == ElementKind.HEADING)
                .toList();
        indexDeclaredRelations();
    }

    public PageModel model() {
        return model;
    }

    public SemanticIndex semantics() {
        return semantics;
    }

    public ElementFamily family(String nodeId) {
        ElementFamily cached = cache.get(nodeId);
        if (cached != null) {
            return cached;
        }
        ElementFamily f = build(model.node(nodeId));
        cache.put(nodeId, f);
        return f;
    }

    /** Todas as relações declaradas (ARIA/HTML) do snapshot. */
    public List<ContextRelation> declaredRelations() {
        List<ContextRelation> out = new ArrayList<>();
        for (RawNode n : model.nodes()) {
            out.addAll(outgoingDeclared(n));
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------

    private ElementFamily build(RawNode node) {
        Classification cls = semantics.get(node.id());
        List<RawNode> ancestors = model.ancestors(node);
        List<ContextRelation> relations = new ArrayList<>(outgoingDeclared(node));
        relations.addAll(incoming.getOrDefault(node.id(), List.of()));

        NodeRef parent = model.parent(node).map(this::ref).orElse(null);
        List<NodeRef> children = model.children(node).stream().map(this::ref).toList();
        List<NodeRef> relevantAncestors = new ArrayList<>();
        for (RawNode a : ancestors) {
            if (relevantAncestors.size() >= MAX_ANCESTORS || a.tag().equals("body") || a.tag().equals("html")) {
                break;
            }
            if (isRelevantAncestor(a)) {
                relevantAncestors.add(ref(a));
            }
        }
        List<NodeRef> siblings = new ArrayList<>();
        for (RawNode s : model.siblings(node)) {
            if (siblings.size() >= MAX_SIBLINGS) {
                break;
            }
            Classification sc = semantics.get(s.id());
            if (sc.category() == ElementCategory.INTERACTIVE
                    || (sc.category() == ElementCategory.TEXT && !model.visibleText(s).isEmpty())) {
                siblings.add(ref(s));
            }
        }

        RawNode form = formOf(node, ancestors);
        if (form != null) {
            relations.add(new ContextRelation(RelationType.IN_FORM, node.id(), form.id(),
                    node.hasAttr("form") ? Basis.HTML_ATTRIBUTE : Basis.DOM_STRUCTURE, "formulário associado"));
        }
        RawNode fieldGroup = firstAncestor(ancestors, a -> {
            ElementKind k = kind(a);
            return k == ElementKind.FIELDSET || k == ElementKind.GROUP;
        }, a -> kind(a) == ElementKind.FORM);
        if (fieldGroup != null) {
            relations.add(new ContextRelation(RelationType.IN_FIELD_GROUP, node.id(), fieldGroup.id(), Basis.DOM_STRUCTURE, fieldGroup.tag()));
        }

        List<NodeRef> labels = new ArrayList<>();
        NameSource src = cls.name().source();
        if (src == NameSource.LABEL_ELEMENT || src == NameSource.WRAPPING_LABEL || src == NameSource.ARIA_LABELLEDBY) {
            for (String id : cls.name().sourceNodeIds()) {
                model.find(id).ifPresent(l -> labels.add(ref(l)));
            }
            for (String id : cls.name().sourceNodeIds()) {
                relations.add(new ContextRelation(src == NameSource.ARIA_LABELLEDBY ? RelationType.LABELLED_BY : RelationType.LABELS,
                        id, node.id(), src == NameSource.ARIA_LABELLEDBY ? Basis.ARIA_ATTRIBUTE
                        : src == NameSource.WRAPPING_LABEL ? Basis.DOM_STRUCTURE : Basis.HTML_ATTRIBUTE, src.name()));
            }
        }
        String description = cls.name().description().isEmpty() ? null : cls.name().description();

        RawNode region = firstAncestor(ancestors, a -> kind(a) == ElementKind.LANDMARK || kind(a) == ElementKind.TABPANEL, a -> false);
        RawNode dialog = firstAncestor(ancestors, a -> kind(a) == ElementKind.DIALOG || kind(a) == ElementKind.MODAL, a -> false);
        RawNode table = firstAncestor(ancestors, a -> kind(a) == ElementKind.TABLE, a -> false);
        RawNode rowNode = table == null ? null : firstAncestorOrSelf(node, ancestors, a -> kind(a) == ElementKind.TABLE_ROW, a -> a == table);
        RawNode cell = table == null ? null : firstAncestorOrSelf(node, ancestors,
                a -> kind(a) == ElementKind.TABLE_CELL || kind(a) == ElementKind.TABLE_HEADER_CELL, a -> a == table);
        RowContext row = rowNode == null ? null : rowContext(rowNode, table, cell);
        ColumnContext column = cell == null ? null : columnContext(cell, rowNode, table);
        RawNode listItem = firstAncestor(ancestors, a -> kind(a) == ElementKind.LIST_ITEM, a -> false);

        if (region != null) {
            relations.add(new ContextRelation(RelationType.IN_REGION, node.id(), region.id(), Basis.DOM_STRUCTURE, region.tag()));
        }
        if (dialog != null) {
            relations.add(new ContextRelation(RelationType.IN_DIALOG, node.id(), dialog.id(), Basis.DOM_STRUCTURE, kind(dialog).name()));
        }
        if (table != null) {
            relations.add(new ContextRelation(RelationType.IN_TABLE, node.id(), table.id(), Basis.DOM_STRUCTURE, "tabela"));
        }
        if (rowNode != null) {
            relations.add(new ContextRelation(RelationType.IN_ROW, node.id(), rowNode.id(), Basis.DOM_STRUCTURE, "linha"));
        }
        if (column != null && column.headerNodeId() != null) {
            relations.add(new ContextRelation(RelationType.IN_COLUMN, node.id(), column.headerNodeId(),
                    "HEADERS_ATTRIBUTE".equals(column.basis()) ? Basis.HTML_ATTRIBUTE : Basis.DOM_STRUCTURE, column.headerText()));
        }
        if (listItem != null) {
            relations.add(new ContextRelation(RelationType.IN_LIST_ITEM, node.id(), listItem.id(), Basis.DOM_STRUCTURE, "item de lista"));
        }

        RawNode logical = logicalContainer(node, ancestors);
        if (logical != null) {
            relations.add(new ContextRelation(RelationType.IN_CONTAINER, node.id(), logical.id(), Basis.HEURISTIC, "contêiner lógico mais próximo"));
        }
        RawNode heading = nearestPrecedingHeading(node, ancestors);
        String containerHeading = null;
        if (heading != null) {
            containerHeading = model.visibleText(heading);
            if (containerHeading.isEmpty()) {
                containerHeading = model.textContent(heading);
            }
            relations.add(new ContextRelation(RelationType.HEADED_BY, node.id(), heading.id(), Basis.HEURISTIC, "título precedente mais próximo"));
        } else if (fieldGroup != null && kind(fieldGroup) == ElementKind.FIELDSET) {
            String legend = semantics.get(fieldGroup.id()).name().name();
            containerHeading = legend.isEmpty() ? null : legend;
        } else if (dialog != null) {
            String dn = semantics.get(dialog.id()).name().name();
            containerHeading = dn.isEmpty() ? null : dn;
        }
        if (containerHeading != null && containerHeading.isEmpty()) {
            containerHeading = null;
        }

        RawNode visual = firstAncestor(ancestors, a -> NodeState.isTrue(a.state().boxed()), a -> a.tag().equals("body"));
        List<String> contextual = contextualTexts(node, logical, row, containerHeading);
        List<NodeRef> scopeChain = scopeChain(node, ancestors);

        return new ElementFamily(node.id(), parent, children, relevantAncestors, siblings,
                form == null ? null : ref(form), fieldGroup == null ? null : ref(fieldGroup), labels, description,
                region == null ? null : ref(region), dialog == null ? null : ref(dialog),
                table == null ? null : ref(table), row, column, listItem == null ? null : ref(listItem),
                logical == null ? null : ref(logical), containerHeading, visual == null ? null : ref(visual),
                contextual, scopeChain, dedupe(relations));
    }

    private static List<ContextRelation> dedupe(List<ContextRelation> rels) {
        return new ArrayList<>(new LinkedHashSet<>(rels));
    }

    private ElementKind kind(RawNode n) {
        return semantics.get(n.id()).kind();
    }

    private boolean isRelevantAncestor(RawNode a) {
        ElementKind k = kind(a);
        return switch (k) {
            case FORM, FIELDSET, GROUP, TABLE, TABLE_ROW, LIST, LIST_ITEM, DIALOG, MODAL, LANDMARK, TABPANEL, LISTBOX,
                 MENU, TABLIST, TABLE_CELL, TABLE_HEADER_CELL, IFRAME -> true;
            default -> a.hasAttr("id") || a.hasAttr("data-testid") || hasDataKey(a);
        };
    }

    private static boolean hasDataKey(RawNode a) {
        for (String k : a.attributes().keySet()) {
            if (k.startsWith("data-") && !k.startsWith("data-nekkora")) {
                return true;
            }
        }
        return false;
    }

    private RawNode formOf(RawNode node, List<RawNode> ancestors) {
        List<List<RawNode>> formRef = model.refTargets(node, "form");
        if (!formRef.isEmpty() && formRef.get(0).size() == 1) {
            RawNode f = formRef.get(0).get(0);
            if (f.tag().equals("form")) {
                return f;
            }
        }
        // Um <form> continua dono dos controles mesmo com papel ARIA próprio (ex.: role=search).
        return firstAncestor(ancestors, a -> kind(a) == ElementKind.FORM || a.tag().equals("form"), a -> false);
    }

    private interface Test {
        boolean test(RawNode n);
    }

    private static RawNode firstAncestor(List<RawNode> ancestors, Test match, Test stop) {
        for (RawNode a : ancestors) {
            if (match.test(a)) {
                return a;
            }
            if (stop.test(a)) {
                return null;
            }
        }
        return null;
    }

    private static RawNode firstAncestorOrSelf(RawNode node, List<RawNode> ancestors, Test match, Test stop) {
        if (match.test(node)) {
            return node;
        }
        return firstAncestor(ancestors, match, stop);
    }

    // --------------------------------- tabelas -------------------------------------------------

    private List<RawNode> rowsOf(RawNode table) {
        List<RawNode> rows = new ArrayList<>();
        collectRows(table, table, rows);
        return rows;
    }

    private void collectRows(RawNode table, RawNode node, List<RawNode> rows) {
        for (RawNode c : model.children(node)) {
            ElementKind k = kind(c);
            if (k == ElementKind.TABLE) {
                continue; // tabelas aninhadas não pertencem a esta
            }
            if (k == ElementKind.TABLE_ROW) {
                rows.add(c);
            } else {
                collectRows(table, c, rows);
            }
        }
    }

    private List<RawNode> cellsOf(RawNode row) {
        List<RawNode> cells = new ArrayList<>();
        for (RawNode c : model.children(row)) {
            ElementKind k = kind(c);
            if (k == ElementKind.TABLE_CELL || k == ElementKind.TABLE_HEADER_CELL) {
                cells.add(c);
            }
        }
        return cells;
    }

    private RowContext rowContext(RawNode row, RawNode table, RawNode targetCell) {
        List<RawNode> rows = rowsOf(table);
        int position = rows.indexOf(row);
        Map<String, String> key = new LinkedHashMap<>();
        String source = "NONE";
        for (Map.Entry<String, String> e : row.attributes().entrySet()) {
            if (e.getKey().startsWith("data-") && !e.getKey().startsWith("data-nekkora") && !e.getValue().isBlank()) {
                key.put(e.getKey(), e.getValue());
            }
        }
        if (!key.isEmpty()) {
            source = "DATA_ATTRIBUTE";
        } else if (row.attr("id") != null && !row.attr("id").isBlank()) {
            key.put("id", row.attr("id"));
            source = "ROW_ID";
        }
        List<RawNode> cells = cellsOf(row);
        if (key.isEmpty()) {
            for (RawNode c : cells) {
                boolean rowHeader = c.tag().equals("th") && "row".equals(c.attr("scope")) || "rowheader".equals(c.attr("role"));
                if (rowHeader && !model.visibleText(c).isEmpty()) {
                    key.put("rowHeader", model.visibleText(c));
                    source = "ROW_HEADER";
                    break;
                }
            }
        }
        if (key.isEmpty()) {
            for (RawNode c : cells) {
                if (c == targetCell) {
                    continue;
                }
                String t = model.visibleText(c);
                if (!t.isEmpty()) {
                    key.put("firstCellText", TextNormalizer.truncate(t, 80));
                    source = "FIRST_CELL_TEXT";
                    break;
                }
            }
        }
        List<String> texts = new ArrayList<>();
        for (RawNode c : cells) {
            if (c == targetCell) {
                continue;
            }
            String t = model.visibleText(c);
            if (!t.isEmpty()) {
                texts.add(TextNormalizer.truncate(t, 80));
            }
        }
        return new RowContext(row.id(), position, key, source, texts);
    }

    private ColumnContext columnContext(RawNode cell, RawNode row, RawNode table) {
        List<List<RawNode>> headerRef = model.refTargets(cell, "headers");
        if (!headerRef.isEmpty() && headerRef.get(0).size() == 1) {
            RawNode h = headerRef.get(0).get(0);
            return new ColumnContext(columnIndex(cell, row), model.visibleText(h), h.id(), "HEADERS_ATTRIBUTE");
        }
        int index = row == null ? -1 : columnIndex(cell, row);
        if (index < 0) {
            return new ColumnContext(index, null, null, "NONE");
        }
        List<RawNode> rows = rowsOf(table);
        RawNode headerRow = null;
        String basis = "NONE";
        for (RawNode r : rows) {
            RawNode p = model.parent(r).orElse(null);
            if (p != null && p.tag().equals("thead")) {
                headerRow = r;
                basis = "THEAD";
                break;
            }
        }
        if (headerRow == null && !rows.isEmpty()) {
            List<RawNode> first = cellsOf(rows.get(0));
            if (!first.isEmpty() && first.stream().allMatch(c -> kind(c) == ElementKind.TABLE_HEADER_CELL)) {
                headerRow = rows.get(0);
                basis = "FIRST_ROW_TH";
            }
        }
        if (headerRow == null || headerRow == row) {
            return new ColumnContext(index, null, null, "NONE");
        }
        int col = 0;
        for (RawNode h : cellsOf(headerRow)) {
            int span = span(h);
            if (index >= col && index < col + span) {
                return new ColumnContext(index, model.visibleText(h), h.id(), basis);
            }
            col += span;
        }
        return new ColumnContext(index, null, null, "NONE");
    }

    private int columnIndex(RawNode cell, RawNode row) {
        int col = 0;
        for (RawNode c : cellsOf(row)) {
            if (c == cell) {
                return col;
            }
            col += span(c);
        }
        return -1;
    }

    private static int span(RawNode cell) {
        try {
            String s = cell.attr("colspan");
            return s == null ? 1 : Math.max(1, Integer.parseInt(s.trim()));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    // --------------------------------- contêineres e textos -----------------------------------

    private RawNode logicalContainer(RawNode node, List<RawNode> ancestors) {
        for (RawNode a : ancestors) {
            if (a.tag().equals("body") || a.tag().equals("html")) {
                return null;
            }
            ElementKind k = kind(a);
            switch (k) {
                case TABLE_ROW, LIST_ITEM, DIALOG, MODAL, FIELDSET, GROUP, FORM, LANDMARK, TABPANEL, LISTBOX, MENU -> {
                    return a;
                }
                default -> {
                    if (containsHeading(a, node) || (a.hasAttr("data-testid") || hasDataKey(a)) && model.children(a).size() >= 2) {
                        return a;
                    }
                }
            }
        }
        return null;
    }

    private boolean containsHeading(RawNode container, RawNode exclude) {
        int from = model.orderOf(container.id());
        int idx = firstHeadingAfter(from);
        for (int i = idx; i < headings.size(); i++) {
            RawNode h = headings.get(i);
            if (!model.inSubtree(container, h)) {
                break;
            }
            if (h != exclude && !model.isAncestor(h, exclude)) {
                return true;
            }
        }
        return false;
    }

    /** Índice do primeiro título com ordem &gt; {@code order}. */
    private int firstHeadingAfter(int order) {
        int lo = 0;
        int hi = headings.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (model.orderOf(headings.get(mid).id()) <= order) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private RawNode nearestPrecedingHeading(RawNode node, List<RawNode> ancestors) {
        int targetOrder = model.orderOf(node.id());
        int lastBefore = firstHeadingAfter(targetOrder - 1) - 1; // último título com ordem < alvo
        for (RawNode a : ancestors) {
            if (a.tag().equals("html")) {
                break;
            }
            RawNode best = null;
            for (int i = lastBefore; i >= 0; i--) {
                RawNode h = headings.get(i);
                if (model.orderOf(h.id()) <= model.orderOf(a.id())) {
                    break; // antes do início da subárvore do ancestral
                }
                if (model.inSubtree(a, h) && !model.isAncestor(h, node) && !model.isHidden(h)) {
                    best = h;
                    break;
                }
            }
            if (best != null) {
                return best;
            }
            if (kind(a) == ElementKind.DIALOG || kind(a) == ElementKind.MODAL) {
                return null; // títulos fora do diálogo não descrevem seu conteúdo
            }
        }
        return null;
    }

    private List<String> contextualTexts(RawNode node, RawNode logical, RowContext row, String heading) {
        Set<String> out = new LinkedHashSet<>();
        if (heading != null) {
            out.add(TextNormalizer.truncate(heading, 80));
        }
        if (row != null) {
            out.addAll(row.cellTexts());
        } else if (logical != null) {
            String own = model.visibleText(node);
            for (RawNode d : model.descendants(logical)) {
                if (out.size() >= MAX_CONTEXT_TEXTS) {
                    break;
                }
                if (d == node || model.isAncestor(node, d) || model.isAncestor(d, node) || model.isHidden(d)) {
                    continue;
                }
                Classification c = semantics.get(d.id());
                if (c.category() == ElementCategory.TEXT && !d.ownText().isBlank()) { // só texto que atravessou serve de contexto
                    String t = model.visibleText(d);
                    if (!t.isEmpty() && !t.equals(own)) {
                        out.add(TextNormalizer.truncate(t, 80));
                    }
                }
            }
        }
        List<String> list = new ArrayList<>(out);
        return list.size() > MAX_CONTEXT_TEXTS ? list.subList(0, MAX_CONTEXT_TEXTS) : list;
    }

    private List<NodeRef> scopeChain(RawNode node, List<RawNode> ancestors) {
        List<NodeRef> chain = new ArrayList<>();
        String scope = node.scopeId();
        for (RawNode a : ancestors) {
            if (!a.scopeId().equals(scope)) {
                // a é o host do escopo anterior (shadow host ou iframe)
                chain.add(ref(a));
                scope = a.scopeId();
            }
        }
        Collections.reverse(chain);
        return chain;
    }

    // --------------------------------- relações declaradas ------------------------------------

    private void indexDeclaredRelations() {
        for (RawNode n : model.nodes()) {
            for (ContextRelation r : outgoingDeclared(n)) {
                incoming.computeIfAbsent(r.toNodeId(), k -> new ArrayList<>()).add(r);
            }
            if (n.scopeId().startsWith("shadow:") && model.parent(n).map(p -> !p.scopeId().equals(n.scopeId())).orElse(false)) {
                String host = n.parentId();
                incoming.computeIfAbsent(n.id(), k -> new ArrayList<>())
                        .add(new ContextRelation(RelationType.HOSTS_SHADOW_ROOT, host, n.id(), Basis.DOM_STRUCTURE, "shadow root"));
            }
        }
    }

    private List<ContextRelation> outgoingDeclared(RawNode n) {
        List<ContextRelation> out = new ArrayList<>();
        idRefs(n, "aria-controls", RelationType.CONTROLS, Basis.ARIA_ATTRIBUTE, out);
        idRefs(n, "aria-owns", RelationType.OWNS, Basis.ARIA_ATTRIBUTE, out);
        idRefs(n, "aria-activedescendant", RelationType.ACTIVE_DESCENDANT, Basis.ARIA_ATTRIBUTE, out);
        idRefs(n, "aria-describedby", RelationType.DESCRIBED_BY, Basis.ARIA_ATTRIBUTE, out);
        idRefs(n, "aria-errormessage", RelationType.ERROR_MESSAGE, Basis.ARIA_ATTRIBUTE, out);
        idRefs(n, "aria-labelledby", RelationType.LABELLED_BY, Basis.ARIA_ATTRIBUTE, out);
        idRefs(n, "form", RelationType.FORM_OWNER, Basis.HTML_ATTRIBUTE, out);
        if (n.tag().equals("label") && (n.attr("for") != null || n.refs().containsKey("for"))) {
            List<List<RawNode>> groups = model.refTargets(n, "for");
            if (!groups.isEmpty() && !groups.get(0).isEmpty()) {
                // label[for] associa somente o primeiro elemento com o id
                out.add(new ContextRelation(RelationType.LABELS, n.id(), groups.get(0).get(0).id(), Basis.HTML_ATTRIBUTE,
                        PageModel.refLabel(n, "for", groups.get(0), 0)));
            }
        }
        Classification c = semantics.find(n.id()).orElse(null);
        if (c != null && c.kind() == ElementKind.OPTION) {
            for (RawNode a : model.ancestorsInScope(n)) {
                ElementKind k = kind(a);
                if (k == ElementKind.SELECT || k == ElementKind.LISTBOX || k == ElementKind.COMBOBOX) {
                    out.add(new ContextRelation(RelationType.OPTION_OF, n.id(), a.id(), Basis.DOM_STRUCTURE, a.tag()));
                    break;
                }
            }
        }
        if (n.tag().equals("iframe") || n.tag().equals("frame")) {
            for (String child : n.childIds()) {
                out.add(new ContextRelation(RelationType.HOSTS_FRAME, n.id(), child, Basis.DOM_STRUCTURE, "documento do frame"));
            }
        }
        return out;
    }

    private void idRefs(RawNode n, String attr, RelationType type, Basis basis, List<ContextRelation> out) {
        List<List<RawNode>> groups = model.refTargets(n, attr);
        for (int i = 0; i < groups.size(); i++) {
            List<RawNode> targets = groups.get(i);
            String label = PageModel.refLabel(n, attr, targets, i);
            if (targets.size() == 1) {
                out.add(new ContextRelation(type, n.id(), targets.get(0).id(), basis, label));
            } else if (targets.size() > 1) {
                for (RawNode t : targets) {
                    out.add(new ContextRelation(type, n.id(), t.id(), basis, label + " (IDREF ambíguo: " + targets.size() + " elementos com o mesmo id)"));
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------

    public NodeRef ref(RawNode n) {
        Classification c = semantics.get(n.id());
        String name = c.name().name();
        if (name.isEmpty() && (c.category() == ElementCategory.TEXT || c.kind() == ElementKind.TABLE_ROW
                || c.kind() == ElementKind.LIST_ITEM)) {
            name = TextNormalizer.truncate(model.visibleText(n), 60);
        }
        Map<String, String> keys = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : n.attributes().entrySet()) {
            String k = e.getKey().toLowerCase(Locale.ROOT);
            if ((k.startsWith("data-") && !k.startsWith("data-nekkora")) || k.equals("name") || k.equals("aria-label")
                    || k.equals("role")) {
                keys.put(e.getKey(), e.getValue());
            }
        }
        return new NodeRef(n.id(), n.tag(), c.kind(), TextNormalizer.truncate(name, 80), n.attr("id"), keys);
    }
}
