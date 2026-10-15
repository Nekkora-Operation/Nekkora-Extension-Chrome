package com.nekkora.extension.core.semantic;

import com.nekkora.extension.core.snapshot.NodeState;
import com.nekkora.extension.core.snapshot.PageModel;
import com.nekkora.extension.core.snapshot.RawNode;
import com.nekkora.extension.core.text.TextNormalizer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Nome e descrição acessíveis segundo o algoritmo de alternativa textual do AccName 1.2 (passos 2A–2I),
 * com as regras de HTML-AAM para elementos nativos.
 *
 * <p>Coberto: {@code aria-labelledby} (múltiplas referências, recursão com proteção contra ciclos,
 * referências a elementos ocultos), {@code aria-label}, labels nativos (for/envolvente), {@code alt},
 * {@code <title>} de SVG, value de botões, legend/caption, nome por conteúdo (inclui {@code alt} e
 * {@code aria-label} de descendentes — botões de ícone), controles embutidos, {@code aria-hidden},
 * {@code title}/{@code placeholder}, {@code aria-describedby} e {@code aria-description}.
 *
 * <p>Não coberto (declarado): conteúdo CSS de pseudo-elementos e a ordem exata entre nós de texto e
 * elementos irmãos (o snapshot guarda o texto próprio agregado), espaçamento por display CSS.
 */
public final class AccessibleNameCalculator {

    private static final Set<String> NAME_FROM_CONTENT_ROLES = Set.of(
            "button", "link", "option", "menuitem", "menuitemcheckbox", "menuitemradio", "tab", "heading",
            "cell", "gridcell", "columnheader", "rowheader", "checkbox", "radio", "switch", "treeitem",
            "tooltip", "row", "caption", "legend", "label", "sectionhead", "comment");
    private static final int MAX_DEPTH = 64;

    /** Estado de uma computação (um nome). */
    private static final class Walk {
        final Set<String> visited = new HashSet<>();
        final List<String> sources = new ArrayList<>();
    }

    public AccessibleName compute(RawNode node, ElementKind kind, String role, PageModel model) {
        Walk w = new Walk();
        w.visited.add(node.id());
        String name = "";
        NameSource source = NameSource.NONE;

        // 2B — aria-labelledby (não seguido recursivamente a partir de um nó já referenciado)
        String labelledBy = node.attr("aria-labelledby");
        if (labelledBy != null && !labelledBy.isBlank()) {
            String text = fromReferences(node, labelledBy, model, w, true);
            if (!text.isEmpty()) {
                name = text;
                source = NameSource.ARIA_LABELLEDBY;
            }
        }
        // 2C — aria-label
        if (name.isEmpty()) {
            String ariaLabel = TextNormalizer.normalize(node.attr("aria-label"));
            if (!ariaLabel.isEmpty()) {
                name = ariaLabel;
                source = NameSource.ARIA_LABEL;
            }
        }
        // 2D — semântica nativa (HTML-AAM)
        if (name.isEmpty() && isLabelable(node)) {
            w.sources.clear();
            NameSource[] labelSource = new NameSource[1];
            String text = labelText(node, model, w, labelSource);
            if (!text.isEmpty()) {
                name = text;
                source = labelSource[0];
            }
        }
        if (name.isEmpty()) {
            w.sources.clear();
            String nativeName = nativeName(node, model, w);
            if (!nativeName.isEmpty()) {
                name = nativeName;
                source = nativeSource(node);
            }
        }
        // 2F — nome por conteúdo
        if (name.isEmpty() && allowsNameFromContent(node, kind, role)) {
            String text = TextNormalizer.normalize(fromContent(node, model, w, false, 0));
            if (!text.isEmpty()) {
                name = TextNormalizer.truncate(text, 160);
                source = NameSource.CONTENT;
            }
        }
        // 2I — dica (tooltip) e placeholder
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

        // Descrição: aria-describedby → aria-description → title (quando não usado como nome)
        Walk dw = new Walk();
        dw.visited.add(node.id());
        String description = "";
        String describedBy = node.attr("aria-describedby");
        if (describedBy != null && !describedBy.isBlank()) {
            description = fromReferences(node, describedBy, model, dw, true);
        }
        if (description.isEmpty()) {
            description = TextNormalizer.normalize(node.attr("aria-description"));
        }
        if (description.isEmpty() && source != NameSource.TITLE) {
            description = TextNormalizer.normalize(node.attr("title"));
        }
        return new AccessibleName(name, source, source == NameSource.NONE ? List.of() : List.copyOf(new LinkedHashSet<>(w.sources)),
                description, dw.sources, confidenceOf(source));
    }

    static String confidenceOf(NameSource s) {
        return switch (s) {
            case ARIA_LABELLEDBY, ARIA_LABEL, LABEL_ELEMENT, WRAPPING_LABEL, ALT, LEGEND, CAPTION, VALUE -> "HIGH";
            case CONTENT -> "MEDIUM";
            case TITLE, PLACEHOLDER -> "LOW";
            case NONE -> "NONE";
        };
    }

    // ------------------------------------------------------------------------------ passos

    /** 2B: texto das referências (IDREFs), na ordem declarada; ocultos INCLUÍDOS por estarem referenciados. */
    private String fromReferences(RawNode from, String refs, PageModel model, Walk w, boolean recordSources) {
        List<String> parts = new ArrayList<>();
        for (String ref : refs.trim().split("\\s+")) {
            RawNode target = model.resolveIdRef(from, ref).orElse(null);
            if (target != null && target.id().equals(from.id())) {
                // Autorreferência (ex.: aria-labelledby="eu outro" + aria-label): o próprio nó contribui com a
                // alternativa textual SEM reaplicar o passo 2B — aria-label (2C), nativa ou conteúdo.
                String self = referencedText(target, model, w, 1);
                if (!self.isEmpty()) {
                    parts.add(self);
                    if (recordSources) {
                        w.sources.add(target.id());
                    }
                }
                continue;
            }
            if (target == null || !w.visited.add(target.id())) {
                continue; // ausente, ambíguo ou já visitado (referência circular)
            }
            String t = referencedText(target, model, w, 1);
            if (!t.isEmpty()) {
                parts.add(t);
                if (recordSources) {
                    w.sources.add(target.id());
                }
            }
        }
        return TextNormalizer.normalize(String.join(" ", parts));
    }

    /** Alternativa textual de um nó referenciado: aria-label/native/conteúdo, incluindo ocultos. */
    private String referencedText(RawNode n, PageModel model, Walk w, int depth) {
        if (depth > MAX_DEPTH) {
            return "";
        }
        String aria = TextNormalizer.normalize(n.attr("aria-label"));
        if (!aria.isEmpty()) {
            return aria;
        }
        String embedded = embeddedControlValue(n, model);
        if (embedded != null) {
            return embedded;
        }
        String nat = nativeName(n, model, new Walk());
        if (!nat.isEmpty() && !n.tag().equals("fieldset") && !n.tag().equals("table")) {
            return nat;
        }
        return TextNormalizer.normalize(fromContent(n, model, w, true, depth));
    }

    /** 2F: conteúdo dos filhos. {@code includeHidden} vale dentro de travessias de referência. */
    private String fromContent(RawNode n, PageModel model, Walk w, boolean includeHidden, int depth) {
        if (depth > MAX_DEPTH) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (n.tag().equals("svg")) {
            sb.append(' ').append(n.ownText()); // <title> do SVG (capturado como texto próprio)
            return sb.toString();
        }
        List<String> segments = n.textSegments();
        if (segments == null && !n.ownText().isEmpty()) {
            sb.append(' ').append(n.ownText()); // sem segmentos (CHROME-001): texto próprio antes dos filhos
        }
        List<String> children = n.childIds();
        for (int i = 0; i < children.size(); i++) {
            if (segments != null && !segments.get(i).isEmpty()) {
                sb.append(' ').append(segments.get(i)); // texto antes do i-ésimo filho (ordem preservada)
            }
            String id = children.get(i);
            RawNode c = model.find(id).orElse(null);
            if (c == null || nonTextual(c.tag())) {
                continue;
            }
            if (!includeHidden && hidden(c, model)) {
                continue;
            }
            if ("true".equals(c.attr("aria-hidden")) && !includeHidden) {
                continue;
            }
            if (!w.visited.add(c.id())) {
                continue;
            }
            // 2B/2C dentro do conteúdo: aria-labelledby e aria-label do descendente prevalecem.
            String lb = c.attr("aria-labelledby");
            if (lb != null && !lb.isBlank()) {
                String t = fromReferences(c, lb, model, w, false);
                if (!t.isEmpty()) {
                    sb.append(' ').append(t);
                    continue;
                }
            }
            String aria = TextNormalizer.normalize(c.attr("aria-label"));
            if (!aria.isEmpty()) {
                sb.append(' ').append(aria);
                continue;
            }
            String embedded = embeddedControlValue(c, model);
            if (embedded != null) {
                sb.append(' ').append(embedded);
                continue;
            }
            if (c.tag().equals("img") || c.tag().equals("area") || ("image".equals(c.attr("type")) && c.tag().equals("input"))) {
                sb.append(' ').append(TextNormalizer.normalize(c.attr("alt")));
                continue;
            }
            String inner = fromContent(c, model, w, includeHidden, depth + 1);
            if (inner.isBlank() && c.attr("title") != null) {
                inner = c.attr("title"); // 2I aplicado ao descendente
            }
            sb.append(' ').append(inner);
        }
        if (segments != null && !segments.get(children.size()).isEmpty()) {
            sb.append(' ').append(segments.get(children.size()));
        }
        return sb.toString();
    }

    /** 2E: valor de controle embutido em nome (textbox, select, range). Valores de campos não são capturados. */
    private static String embeddedControlValue(RawNode c, PageModel model) {
        String role = c.attr("role");
        if (c.tag().equals("select")) {
            for (RawNode o : model.descendants(c)) {
                if (o.tag().equals("option") && NodeState.isTrue(o.state().selected())) {
                    return model.textContent(o);
                }
            }
            return "";
        }
        if ("slider".equals(role) || "spinbutton".equals(role) || "range".equals(c.attr("type"))) {
            String vt = c.attr("aria-valuetext");
            return vt != null ? TextNormalizer.normalize(vt) : TextNormalizer.normalize(c.attr("aria-valuenow"));
        }
        if (c.tag().equals("input") && !Set.of("button", "submit", "reset", "image", "checkbox", "radio", "hidden").contains(type(c))) {
            return ""; // o valor digitado nunca compõe o nome (CHROME-SECURITY-001)
        }
        return null;
    }

    private static boolean hidden(RawNode n, PageModel model) {
        return NodeState.isFalse(n.state().visible()) || "true".equals(n.attr("aria-hidden"));
    }

    private static boolean nonTextual(String tag) {
        return switch (tag) {
            case "script", "style", "noscript", "template", "iframe", "frame" -> true;
            default -> false;
        };
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
        return t == null ? "text" : t.toLowerCase(Locale.ROOT);
    }

    private String labelText(RawNode node, PageModel model, Walk w, NameSource[] src) {
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
            if (hidden(label, model)) {
                continue;
            }
            Walk lw = new Walk();
            lw.visited.add(node.id()); // o próprio controle não contribui com seu nome
            lw.visited.add(label.id());
            String t = TextNormalizer.normalize(fromContent(label, model, lw, false, 1));
            if (!t.isEmpty()) {
                parts.add(t);
                w.sources.add(label.id());
            }
        }
        return TextNormalizer.normalize(String.join(" ", parts));
    }

    private String nativeName(RawNode node, PageModel model, Walk w) {
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
            case "svg" -> {
                return TextNormalizer.normalize(node.ownText()); // <title> do SVG
            }
            case "fieldset" -> {
                return childText(node, "legend", model, w);
            }
            case "table" -> {
                return childText(node, "caption", model, w);
            }
            case "figure" -> {
                return childText(node, "figcaption", model, w);
            }
            case "dialog" -> {
                // Diálogos sem rótulo explícito: primeiro título descendente (convenção comum, declarada como heurística).
                for (RawNode d : model.descendants(node)) {
                    if (d.tag().matches("h[1-6]")) {
                        w.sources.add(d.id());
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
            case "img", "area", "svg" -> NameSource.ALT;
            case "fieldset" -> NameSource.LEGEND;
            case "table", "figure" -> NameSource.CAPTION;
            default -> NameSource.CONTENT;
        };
    }

    private static String childText(RawNode node, String tag, PageModel model, Walk w) {
        for (RawNode c : model.children(node)) {
            if (c.tag().equals(tag)) {
                w.sources.add(c.id());
                return model.visibleText(c);
            }
        }
        return "";
    }
}
