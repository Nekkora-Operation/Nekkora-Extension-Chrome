package com.nekkora.extension.core.locator.strategy;

import com.nekkora.extension.core.context.ContextGraph;
import com.nekkora.extension.core.locator.query.LocatorStep;
import com.nekkora.extension.core.locator.query.Predicate;
import com.nekkora.extension.core.semantic.Classification;
import com.nekkora.extension.core.semantic.ElementCategory;
import com.nekkora.extension.core.semantic.ElementKind;
import com.nekkora.extension.core.snapshot.RawNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Constrói passos simples (um elemento) e passos-âncora (contêineres). */
public final class StepFactory {

    static final List<String> TEST_ATTRIBUTES = List.of("data-testid", "data-test-id", "data-test", "data-qa", "data-cy");
    private static final Set<String> TEXT_LOCATABLE = Set.of(
            "BUTTON", "LINK", "OPTION", "MENUITEM", "TAB", "HEADING", "TEXT", "PARAGRAPH", "LABEL", "LEGEND",
            "TABLE_CELL", "TABLE_HEADER_CELL", "ALERT", "STATUS", "VALIDATION_MESSAGE", "DESCRIPTION", "INSTRUCTION",
            "CUSTOM_DROPDOWN", "CAPTION", "LIST_ITEM", "COMBOBOX", "SWITCH");
    private static final int MAX_TEXT = 80;

    private StepFactory() {
    }

    /** Passos que selecionam o próprio elemento, do mais específico ao mais genérico. */
    public static List<LocatorStep> simpleSteps(RawNode node, ContextGraph graph) {
        List<LocatorStep> out = new ArrayList<>();
        String tag = node.tag();
        for (String ta : TEST_ATTRIBUTES) {
            if (nonBlank(node.attr(ta))) {
                out.add(LocatorStep.descendant(tag, new Predicate.AttrEquals(ta, node.attr(ta))));
            }
        }
        if (nonBlank(node.attr("id"))) {
            out.add(LocatorStep.descendant(tag, new Predicate.AttrEquals("id", node.attr("id"))));
        }
        if (nonBlank(node.attr("name")) && isFormControl(tag)) {
            String type = node.attr("type");
            if (("radio".equals(type) || "checkbox".equals(type)) && nonBlank(node.attr("value"))) {
                out.add(LocatorStep.descendant(tag, new Predicate.AttrEquals("name", node.attr("name")),
                        new Predicate.AttrEquals("value", node.attr("value"))));
            }
            out.add(LocatorStep.descendant(tag, new Predicate.AttrEquals("name", node.attr("name"))));
        }
        Classification c = graph.semantics().get(node.id());
        String text = graph.model().textContent(node);
        if (TEXT_LOCATABLE.contains(c.kind().name()) && !text.isEmpty() && text.length() <= MAX_TEXT) {
            out.add(LocatorStep.descendant(tag, new Predicate.TextEquals(text)));
        }
        for (String attr : List.of("aria-label", "placeholder", "title", "alt")) {
            if (nonBlank(node.attr(attr))) {
                out.add(LocatorStep.descendant(tag, new Predicate.AttrEquals(attr, node.attr(attr))));
            }
        }
        if (tag.equals("input") && nonBlank(node.attr("type"))) {
            out.add(LocatorStep.descendant(tag, new Predicate.AttrEquals("type", node.attr("type"))));
        }
        if (nonBlank(node.attr("role"))) {
            out.add(LocatorStep.descendant(tag, new Predicate.AttrEquals("role", node.attr("role"))));
        }
        out.add(LocatorStep.descendant(tag));
        return out;
    }

    /** Passos que identificam um contêiner (âncoras de contexto). */
    public static List<LocatorStep> anchorSteps(RawNode container, ContextGraph graph) {
        List<LocatorStep> out = new ArrayList<>();
        String tag = container.tag();
        for (String ta : TEST_ATTRIBUTES) {
            if (nonBlank(container.attr(ta))) {
                out.add(LocatorStep.descendant(tag, new Predicate.AttrEquals(ta, container.attr(ta))));
            }
        }
        if (nonBlank(container.attr("id"))) {
            out.add(LocatorStep.descendant(tag, new Predicate.AttrEquals("id", container.attr("id"))));
        }
        for (Map.Entry<String, String> e : container.attributes().entrySet()) {
            String k = e.getKey();
            if (k.startsWith("data-") && !k.startsWith("data-nekkora") && !TEST_ATTRIBUTES.contains(k) && nonBlank(e.getValue())) {
                out.add(LocatorStep.descendant(tag, new Predicate.AttrEquals(k, e.getValue())));
            }
        }
        if (nonBlank(container.attr("name"))) {
            out.add(LocatorStep.descendant(tag, new Predicate.AttrEquals("name", container.attr("name"))));
        }
        if (nonBlank(container.attr("aria-label"))) {
            out.add(LocatorStep.descendant(tag, new Predicate.AttrEquals("aria-label", container.attr("aria-label"))));
        }
        // Âncora por conteúdo: filho direto cujo texto identifica o contêiner (título do card, célula-chave da linha).
        ElementKind kind = graph.semantics().get(container.id()).kind();
        for (RawNode child : graph.model().children(container)) {
            if (!child.scopeId().equals(container.scopeId())) {
                continue;
            }
            ElementKind ck = graph.semantics().get(child.id()).kind();
            boolean headingChild = ck == ElementKind.HEADING || ck == ElementKind.LEGEND || ck == ElementKind.CAPTION;
            boolean keyCell = kind == ElementKind.TABLE_ROW && (ck == ElementKind.TABLE_CELL || ck == ElementKind.TABLE_HEADER_CELL);
            if (headingChild || keyCell) {
                String t = graph.model().textContent(child);
                if (!t.isEmpty() && t.length() <= MAX_TEXT && !containsInteractive(child, graph)) {
                    out.add(LocatorStep.descendant(tag, new Predicate.HasChildText(child.tag(), t)));
                    if (keyCell && out.size() > 6) {
                        break;
                    }
                }
            }
        }
        if (tag.equals("dialog") || tag.equals("form") || tag.equals("table") || tag.equals("fieldset")) {
            out.add(LocatorStep.descendant(tag));
        }
        return out;
    }

    private static boolean containsInteractive(RawNode n, ContextGraph graph) {
        for (RawNode d : graph.model().descendants(n)) {
            if (graph.semantics().get(d.id()).category() == ElementCategory.INTERACTIVE) {
                return true;
            }
        }
        return false;
    }

    static boolean isFormControl(String tag) {
        return tag.equals("input") || tag.equals("select") || tag.equals("textarea") || tag.equals("button");
    }

    static boolean nonBlank(String s) {
        return s != null && !s.isBlank();
    }
}
