package com.nekkora.extension.core.semantic;

import com.nekkora.extension.core.snapshot.PageModel;
import com.nekkora.extension.core.snapshot.RawNode;
import com.nekkora.extension.core.text.TextNormalizer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Cálculo simplificado do nome e da descrição acessíveis, seguindo a ordem de precedência da
 * especificação AccName 1.2: aria-labelledby → aria-label → label nativo → conteúdo → title/placeholder.
 *
 * <p>Simplificações declaradas: não há recursão completa de aria-labelledby (um nível), não há
 * cálculo de CSS {@code ::before/::after} e controles embutidos no label não contribuem com seu valor.
 */
public final class AccessibleNameCalculator {

    private static final Set<String> NAME_FROM_CONTENT_ROLES = Set.of(
            "button", "link", "option", "menuitem", "menuitemcheckbox", "menuitemradio", "tab", "heading",
            "cell", "gridcell", "columnheader", "rowheader", "checkbox", "radio", "switch", "treeitem",
            "tooltip", "row", "caption", "legend", "label");

    public AccessibleName compute(RawNode node, ElementKind kind, String role, PageModel model) {
        List<String> sources = new ArrayList<>();
        String name = "";
        NameSource source = NameSource.NONE;

        String labelledBy = node.attr("aria-labelledby");
        if (labelledBy != null && !labelledBy.isBlank()) {
            String text = textOfRefs(node, labelledBy, model, sources);
            if (!text.isEmpty()) {
                name = text;
                source = NameSource.ARIA_LABELLEDBY;
            }
        }
        if (name.isEmpty()) {
            String ariaLabel = TextNormalizer.normalize(node.attr("aria-label"));
            if (!ariaLabel.isEmpty()) {
                name = ariaLabel;
                source = NameSource.ARIA_LABEL;
            }
        }
        if (name.isEmpty() && isLabelable(node)) {
            sources.clear();
            NameSource[] labelSource = new NameSource[1];
            String text = labelText(node, model, sources, labelSource);
            if (!text.isEmpty()) {
                name = text;
                source = labelSource[0];
            }
        }
        if (name.isEmpty()) {
            String nativeName = nativeName(node, model, sources);
            if (!nativeName.isEmpty()) {
                name = nativeName;
                source = nativeSource(node);
            }
        }
        if (name.isEmpty() && allowsNameFromContent(node, kind, role)) {
            String text = model.visibleText(node);
            if (!text.isEmpty()) {
                name = TextNormalizer.truncate(text, 160);
                source = NameSource.CONTENT;
            }
        }
        if (name.isEmpty()) {
            String title = TextNormalizer.normalize(node.attr("title"));
            if (!title.isEmpty()) {
                name = title;
                source = NameSource.TITLE;
            }
        }
        if (name.isEmpty()) {
            String placeholder = TextNormalizer.normalize(node.attr("placeholder"));
            if (!placeholder.isEmpty()) {
                name = placeholder;
                source = NameSource.PLACEHOLDER;
            }
        }

        List<String> descSources = new ArrayList<>();
        String description = "";
        String describedBy = node.attr("aria-describedby");
        if (describedBy != null && !describedBy.isBlank()) {
            description = textOfRefs(node, describedBy, model, descSources);
        }
        if (description.isEmpty() && source != NameSource.TITLE) {
            description = TextNormalizer.normalize(node.attr("title"));
        }
        return new AccessibleName(name, source, source == NameSource.NONE ? List.of() : sources, description, descSources);
    }

    private static boolean allowsNameFromContent(RawNode node, ElementKind kind, String role) {
        if (role != null && NAME_FROM_CONTENT_ROLES.contains(role)) {
            return true;
        }
        // Fallback declarado: gatilhos personalizados (ex.: div role=combobox com texto visível) e
        // controles interativos não textuais sem outra fonte de nome.
        return kind == ElementKind.COMBOBOX && !node.tag().equals("input")
                || kind == ElementKind.CUSTOM_DROPDOWN
                || kind == ElementKind.LABEL || kind == ElementKind.LEGEND;
    }

    private static boolean isLabelable(RawNode node) {
        return switch (node.tag()) {
            case "input" -> !"hidden".equals(type(node));
            case "select", "textarea", "meter", "output", "progress", "button" -> true;
            default -> false;
        };
    }

    private static String type(RawNode node) {
        String t = node.attr("type");
        return t == null ? "text" : t.toLowerCase(java.util.Locale.ROOT);
    }

    private String labelText(RawNode node, PageModel model, List<String> sources, NameSource[] src) {
        Set<RawNode> labels = new LinkedHashSet<>();
        for (String id : node.labelIds()) {
            model.find(id).ifPresent(labels::add);
        }
        if (labels.isEmpty()) {
            String idAttr = node.attr("id");
            if (idAttr != null && !idAttr.isEmpty()) {
                // label[for] só associa ao PRIMEIRO elemento com o id (regra do HTML).
                List<RawNode> sameId = model.byIdAttribute(node.scopeId(), idAttr);
                boolean firstWithId = !sameId.isEmpty() && sameId.get(0).id().equals(node.id());
                if (firstWithId) {
                    for (RawNode label : model.byTag("label")) {
                        if (label.scopeId().equals(node.scopeId()) && idAttr.equals(label.attr("for"))) {
                            labels.add(label);
                        }
                    }
                }
            }
        }
        src[0] = NameSource.LABEL_ELEMENT;
        if (!labels.isEmpty() && labels.stream().allMatch(l -> l.attr("for") == null && model.isAncestor(l, node))) {
            src[0] = NameSource.WRAPPING_LABEL; // element.labels inclui o label envolvente
        }
        if (labels.isEmpty()) {
            for (RawNode a : model.ancestorsInScope(node)) {
                if (a.tag().equals("label") && a.attr("for") == null) {
                    labels.add(a);
                    src[0] = NameSource.WRAPPING_LABEL;
                    break;
                }
            }
        }
        List<String> parts = new ArrayList<>();
        for (RawNode label : labels) {
            String t = model.visibleText(label);
            if (!t.isEmpty()) {
                parts.add(t);
                sources.add(label.id());
            }
        }
        return TextNormalizer.normalize(String.join(" ", parts));
    }

    private String nativeName(RawNode node, PageModel model, List<String> sources) {
        switch (node.tag()) {
            case "input" -> {
                String t = type(node);
                if (t.equals("submit") || t.equals("button") || t.equals("reset")) {
                    String v = TextNormalizer.normalize(node.attr("value"));
                    if (!v.isEmpty()) {
                        return v;
                    }
                    return t.equals("submit") ? "Submit" : t.equals("reset") ? "Reset" : "";
                }
                if (t.equals("image")) {
                    return TextNormalizer.normalize(node.attr("alt"));
                }
                return "";
            }
            case "img", "area" -> {
                return TextNormalizer.normalize(node.attr("alt"));
            }
            case "fieldset" -> {
                return childText(node, "legend", model, sources);
            }
            case "table" -> {
                return childText(node, "caption", model, sources);
            }
            case "figure" -> {
                return childText(node, "figcaption", model, sources);
            }
            case "dialog" -> {
                // Diálogos sem rótulo explícito: primeiro título descendente (convenção comum, declarada como heurística).
                for (RawNode d : model.descendants(node)) {
                    if (d.tag().matches("h[1-6]")) {
                        sources.add(d.id());
                        return model.visibleText(d);
                    }
                }
                return "";
            }
            default -> {
                return "";
            }
        }
    }

    private static NameSource nativeSource(RawNode node) {
        return switch (node.tag()) {
            case "input" -> "image".equals(node.attr("type")) ? NameSource.ALT : NameSource.VALUE;
            case "img", "area" -> NameSource.ALT;
            case "fieldset" -> NameSource.LEGEND;
            case "table", "figure" -> NameSource.CAPTION;
            default -> NameSource.CONTENT;
        };
    }

    private static String childText(RawNode node, String tag, PageModel model, List<String> sources) {
        for (RawNode c : model.children(node)) {
            if (c.tag().equals(tag)) {
                sources.add(c.id());
                return model.visibleText(c);
            }
        }
        return "";
    }

    private static String textOfRefs(RawNode node, String refs, PageModel model, List<String> sources) {
        List<String> parts = new ArrayList<>();
        for (String ref : refs.trim().split("\\s+")) {
            model.resolveIdRef(node, ref).ifPresent(target -> {
                String t = target.id().equals(node.id())
                        ? TextNormalizer.normalize(node.attr("aria-label") != null ? node.attr("aria-label") : node.ownText())
                        : model.textContent(target);
                if (!t.isEmpty()) {
                    parts.add(t);
                    sources.add(target.id());
                }
            });
        }
        return TextNormalizer.normalize(String.join(" ", parts));
    }
}
