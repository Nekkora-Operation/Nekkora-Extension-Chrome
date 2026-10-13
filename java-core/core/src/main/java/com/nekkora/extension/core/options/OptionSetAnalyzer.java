package com.nekkora.extension.core.options;

import com.nekkora.extension.core.context.ContextGraph;
import com.nekkora.extension.core.semantic.Classification;
import com.nekkora.extension.core.semantic.ElementKind;
import com.nekkora.extension.core.snapshot.NodeState;
import com.nekkora.extension.core.snapshot.PageModel;
import com.nekkora.extension.core.snapshot.RawNode;
import com.nekkora.extension.core.text.TextNormalizer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Identifica controles de escolha, associa-os às suas listas e descreve as opções observadas. */
public final class OptionSetAnalyzer {

    private static final Pattern LOADING = Pattern.compile("(carregando|loading|buscando|aguarde)", Pattern.CASE_INSENSITIVE);

    /** Analisa todos os controles de escolha do snapshot. */
    public List<OptionSet> analyzeAll(ContextGraph graph, OptionHistory history) {
        List<OptionSet> out = new ArrayList<>();
        Set<String> listsOwnedByControls = new HashSet<>();
        for (Classification c : graph.semantics().all()) {
            if (isChoiceControl(c.kind()) && c.kind() != ElementKind.LISTBOX) {
                OptionSet set = analyze(c.nodeId(), graph, history);
                out.add(set);
                if (set.listNodeId() != null) {
                    listsOwnedByControls.add(set.listNodeId());
                }
            }
        }
        for (Classification c : graph.semantics().all()) {
            // Listbox controlado por um combobox já foi descrito pelo controle.
            if (c.kind() == ElementKind.LISTBOX && !listsOwnedByControls.contains(c.nodeId())) {
                out.add(analyze(c.nodeId(), graph, history));
            }
        }
        out.sort(java.util.Comparator.comparingInt(o -> graph.model().orderOf(o.controlNodeId())));
        return out;
    }

    public static boolean isChoiceControl(ElementKind k) {
        return k == ElementKind.SELECT || k == ElementKind.COMBOBOX || k == ElementKind.CUSTOM_DROPDOWN
                || k == ElementKind.LISTBOX;
    }

    public OptionSet analyze(String controlNodeId, ContextGraph graph, OptionHistory history) {
        PageModel m = graph.model();
        RawNode control = m.node(controlNodeId);
        Classification cls = graph.semantics().get(controlNodeId);
        List<String> evidence = new ArrayList<>();
        RawNode list = null;
        String association = "NOT_FOUND";

        if (control.tag().equals("select")) {
            list = control;
            association = "NATIVE";
            evidence.add("<select> nativo: todas as opções estão na DOM");
        } else if (cls.kind() == ElementKind.LISTBOX) {
            list = control;
            association = "SELF";
        } else {
            for (String attr : List.of("aria-controls", "aria-owns")) {
                String ref = control.attr(attr);
                if (ref != null && !ref.isBlank()) {
                    List<RawNode> targets = m.resolveIdRefAll(control, ref.trim().split("\\s+")[0]);
                    if (targets.size() == 1) {
                        list = targets.get(0);
                        association = attr.equals("aria-controls") ? "ARIA_CONTROLS" : "ARIA_OWNS";
                        evidence.add(attr + "=" + ref + " → " + list.id());
                    } else if (targets.size() > 1) {
                        evidence.add(attr + "=" + ref + " é ambíguo (" + targets.size() + " elementos com o mesmo id)");
                    } else {
                        evidence.add(attr + "=" + ref + " não encontrado na DOM (lista não materializada)");
                    }
                    break;
                }
            }
            if (list == null) {
                for (RawNode d : m.descendants(control)) {
                    if (graph.semantics().get(d.id()).kind() == ElementKind.LISTBOX) {
                        list = d;
                        association = "DESCENDANT";
                        break;
                    }
                }
            }
        }

        // Portal: a lista está fora do contêiner imediato do controle (ancestral comum além do avô).
        // Uma lista irmã do controle no mesmo contêiner NÃO é portal.
        boolean outside = false;
        if (list != null && list != control && !m.isAncestor(control, list) && !m.isAncestor(list, control)) {
            RawNode common = commonAncestor(m, control, list);
            RawNode parent = m.parent(control).orElse(null);
            RawNode grandparent = parent == null ? null : m.parent(parent).orElse(null);
            outside = common == null || (common != parent && common != grandparent);
            evidence.add(outside
                    ? "lista renderizada fora do contêiner do controle (portal); ancestral comum: <" + (common == null ? "?" : common.tag()) + ">"
                    : "lista adjacente ao controle no mesmo contêiner <" + common.tag() + ">");
        }

        List<OptionSet.OptionItem> items = new ArrayList<>();
        List<OptionSet.OptionGroup> groups = new ArrayList<>();
        Integer setSize = null;
        boolean sensitive = cls.sensitive();
        if (list != null) {
            for (RawNode d : m.descendants(list)) {
                ElementKind k = graph.semantics().get(d.id()).kind();
                if (k == ElementKind.OPTGROUP || (k == ElementKind.GROUP && "group".equals(d.attr("role")))) {
                    int count = (int) m.descendants(d).stream()
                            .filter(x -> graph.semantics().get(x.id()).kind() == ElementKind.OPTION).count();
                    String label = d.attr("label") != null ? d.attr("label") : graph.semantics().get(d.id()).name().name();
                    groups.add(new OptionSet.OptionGroup(d.id(), TextNormalizer.normalize(label),
                            d.hasAttr("disabled") || NodeState.isTrue(d.state().disabled()), count));
                }
                if (k != ElementKind.OPTION) {
                    continue;
                }
                String group = null;
                for (RawNode a : m.ancestors(d)) {
                    if (a == list) {
                        break;
                    }
                    if (a.tag().equals("optgroup")) {
                        group = a.attr("label");
                        break;
                    }
                    if ("group".equals(a.attr("role"))) {
                        group = graph.semantics().get(a.id()).name().name();
                        break;
                    }
                }
                Integer pos = parseInt(d.attr("aria-posinset"));
                Integer size = parseInt(d.attr("aria-setsize"));
                if (size != null) {
                    setSize = setSize == null ? size : Math.max(setSize, size);
                }
                boolean disabled = NodeState.isTrue(d.state().disabled()) || "true".equals(d.attr("aria-disabled")) || d.hasAttr("disabled");
                Boolean selected = sensitive ? null
                        : d.tag().equals("option") ? d.state().selected() : Boolean.valueOf("true".equals(d.attr("aria-selected")));
                String text = m.textContent(d);
                if (text.isEmpty() && d.attr("label") != null) {
                    text = TextNormalizer.normalize(d.attr("label"));
                }
                items.add(new OptionSet.OptionItem(d.id(), text, d.tag().equals("option") ? d.attr("value") : d.attr("data-value"),
                        disabled, selected, group, pos));
            }
        }
        if (sensitive) {
            evidence.add("controle sensível: estado de seleção não exportado");
        }

        int materialized = items.size();
        Set<String> observed = new LinkedHashSet<>();
        items.forEach(i -> observed.add(TextNormalizer.fold(i.text())));
        String key = OptionHistory.keyOf(control, cls);
        boolean seenBefore = history != null && history.seen(key);
        Set<String> previous = history == null ? Set.of() : history.observed(key);
        boolean countChanged = seenBefore && !new HashSet<>(previous).equals(observed);
        observed.addAll(previous);
        if (history != null) {
            history.record(key, observed);
        }

        boolean dynamic = false;
        boolean virtualized = false;
        if (countChanged) {
            dynamic = true;
            evidence.add("conjunto de opções mudou entre capturas (" + previous.size() + " → " + materialized + " materializadas)");
        }
        if (list != null && ("true".equals(list.attr("aria-busy")) || LOADING.matcher(m.visibleText(list)).find() && materialized == 0)) {
            dynamic = true;
            evidence.add("lista indica carregamento em andamento (aria-busy/texto de carregamento)");
        }
        if (list == null && !"NATIVE".equals(association)) {
            evidence.add("nenhuma lista materializada: opções podem ser criadas somente ao abrir o controle");
        }
        if (setSize != null && setSize > materialized) {
            virtualized = true;
            evidence.add("aria-setsize=" + setSize + " maior que as " + materialized + " opções materializadas");
        }
        if (list != null && list.scroll() != null && materialized > 0) {
            double sh = list.scroll().scrollHeight();
            double avg = 0;
            int measured = 0;
            for (OptionSet.OptionItem it : items) {
                RawNode o = m.node(it.nodeId());
                if (o.rect() != null && o.rect().height() > 0) {
                    avg += o.rect().height();
                    measured++;
                }
            }
            if (measured > 0) {
                avg /= measured;
                if (sh > avg * materialized * 2.5) {
                    virtualized = true;
                    evidence.add("altura rolável (" + Math.round(sh) + "px) muito maior que as opções materializadas ("
                            + Math.round(avg * materialized) + "px): espaçadores de virtualização");
                }
            }
        }

        String completeness;
        if ("NATIVE".equals(association)) {
            completeness = "COMPLETE";
        } else if (virtualized || (setSize != null && setSize > materialized)) {
            completeness = "INCOMPLETE";
        } else {
            completeness = "UNKNOWN";
            evidence.add("componente personalizado: completude não comprovável apenas pela DOM");
        }
        boolean multiple = control.hasAttr("multiple") || "true".equals(control.attr("aria-multiselectable"))
                || (list != null && "true".equals(list.attr("aria-multiselectable")));
        return new OptionSet(controlNodeId, list == null ? null : list.id(), association, outside, multiple, items, groups,
                observed.size(), materialized, setSize, dynamic, virtualized, completeness, evidence);
    }

    private static RawNode commonAncestor(PageModel m, RawNode a, RawNode b) {
        Set<String> anc = new HashSet<>();
        for (RawNode x : m.ancestors(a)) {
            anc.add(x.id());
        }
        for (RawNode y : m.ancestors(b)) {
            if (anc.contains(y.id())) {
                return y;
            }
        }
        return null;
    }

    private static Integer parseInt(String s) {
        if (s == null) {
            return null;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
