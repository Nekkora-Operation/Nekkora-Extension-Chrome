package com.nekkora.extension.core.semantic;

import com.nekkora.extension.core.snapshot.NodeState;
import com.nekkora.extension.core.snapshot.PageModel;
import com.nekkora.extension.core.snapshot.RawNode;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Classifica a finalidade de um elemento combinando semântica nativa, papel ARIA, estrutura e
 * evidências observadas. Um papel ARIA isolado NÃO basta para considerar um elemento funcional:
 * sem evidência de suporte a confiança é rebaixada e a incerteza é registrada.
 */
public final class SemanticClassifier {

    private static final Set<String> KNOWN_ROLES = Set.of(
            "alert", "alertdialog", "application", "article", "banner", "button", "cell", "checkbox", "columnheader",
            "combobox", "complementary", "contentinfo", "definition", "dialog", "directory", "document", "feed",
            "figure", "form", "grid", "gridcell", "group", "heading", "img", "link", "list", "listbox", "listitem",
            "log", "main", "marquee", "math", "menu", "menubar", "menuitem", "menuitemcheckbox", "menuitemradio",
            "navigation", "none", "note", "option", "paragraph", "presentation", "progressbar", "radio", "radiogroup",
            "region", "row", "rowgroup", "rowheader", "scrollbar", "search", "searchbox", "separator", "slider",
            "spinbutton", "status", "switch", "tab", "table", "tablist", "tabpanel", "term", "textbox", "timer",
            "toolbar", "tooltip", "tree", "treegrid", "treeitem", "caption", "generic");

    private static final Set<String> WIDGET_ROLES = Set.of(
            "button", "link", "checkbox", "radio", "switch", "tab", "menuitem", "menuitemcheckbox", "menuitemradio",
            "option", "combobox", "textbox", "searchbox", "slider", "spinbutton", "treeitem", "gridcell");

    private static final Set<String> COMPOSITE_PARENTS = Set.of(
            "listbox", "menu", "menubar", "tablist", "radiogroup", "tree", "grid", "treegrid", "toolbar");

    private static final Pattern ERROR_HINT = Pattern.compile("(error|erro|invalid|inv[aá]lid|feedback)", Pattern.CASE_INSENSITIVE);
    private static final Pattern HELP_HINT = Pattern.compile("(hint|help|ajuda|dica|instru)", Pattern.CASE_INSENSITIVE);

    private final AccessibleNameCalculator names = new AccessibleNameCalculator();
    private final SensitivityPolicy sensitivity = new SensitivityPolicy();

    /** Referências cruzadas pré-calculadas de um snapshot (alvos de describedby/errormessage). */
    /** Ids de NÓ referenciados por aria-describedby / aria-errormessage (de campo inválido). */
    public record CrossReferences(Set<String> describedBy, Set<String> errorMessages) {
    }

    public Classification classify(RawNode node, PageModel model, CrossReferences refs) {
        List<String> evidence = new ArrayList<>();
        List<String> uncertainties = new ArrayList<>();
        String explicitRole = explicitRole(node);
        String tag = node.tag();

        ElementKind kind;
        boolean nativeSemantics = true;

        ElementKind nativeKind = nativeKind(node, model, evidence);
        ElementKind roleKind = explicitRole == null ? null : roleKind(explicitRole, node);

        if (explicitRole != null && (explicitRole.equals("presentation") || explicitRole.equals("none"))
                && !isFocusableNative(node)) {
            kind = node.hasText() ? ElementKind.TEXT : ElementKind.GENERIC_CONTAINER; // texto retido continua sendo texto
            evidence.add("role=" + explicitRole + " remove a semântica nativa");
            nativeSemantics = false;
        } else if (roleKind != null && (nativeKind == null || overridesNative(nativeKind, roleKind))) {
            kind = roleKind;
            nativeSemantics = nativeKind != null && nativeKind == roleKind;
            evidence.add("role=" + explicitRole);
        } else if (nativeKind != null) {
            kind = nativeKind;
        } else {
            nativeSemantics = false;
            kind = textOrContainer(node, model, refs, evidence);
        }

        // Promoções textuais por referências cruzadas e indícios (somente para elementos não interativos).
        if (kind.category() != ElementCategory.INTERACTIVE && kind.category() != ElementCategory.STRUCTURAL) {
            kind = refineText(node, kind, refs, evidence);
        }

        // Componentes personalizados: gatilhos com aria-haspopup sem papel combobox.
        if ((kind == ElementKind.BUTTON || kind == ElementKind.GENERIC_CONTAINER || kind == ElementKind.TEXT)
                && node.attr("aria-haspopup") != null && !"false".equals(node.attr("aria-haspopup"))) {
            kind = ElementKind.CUSTOM_DROPDOWN;
            evidence.add("aria-haspopup=" + node.attr("aria-haspopup"));
            if (node.attr("aria-controls") != null) {
                evidence.add("aria-controls=" + node.attr("aria-controls"));
            }
        }

        // Elemento editável por contenteditable sem outro papel.
        if (NodeState.isTrue(node.state().editable()) && node.hasAttr("contenteditable")
                && !"false".equalsIgnoreCase(node.attr("contenteditable"))
                && (kind == ElementKind.GENERIC_CONTAINER || kind == ElementKind.TEXT || kind == ElementKind.PARAGRAPH)) {
            kind = ElementKind.CONTENTEDITABLE;
            evidence.add("contenteditable=" + (node.attr("contenteditable").isEmpty() ? "true" : node.attr("contenteditable")));
            evidence.add("isContentEditable=true");
        }

        Confidence confidence = nativeSemantics ? Confidence.HIGH : Confidence.MEDIUM;
        if (!nativeSemantics && explicitRole != null && WIDGET_ROLES.contains(explicitRole)) {
            List<String> support = widgetSupport(node, model);
            if (support.isEmpty()) {
                confidence = Confidence.LOW;
                uncertainties.add("Papel ARIA '" + explicitRole + "' sem evidência de operabilidade (sem tabindex, sem manipulador declarado, sem widget composto pai, cursor não indica ação)");
            } else {
                evidence.addAll(support);
            }
        }
        if (!nativeSemantics && explicitRole == null && kind.category() == ElementCategory.INTERACTIVE
                && kind != ElementKind.CONTENTEDITABLE) {
            confidence = Confidence.LOW;
            uncertainties.add("Interatividade inferida por heurística");
        }
        if (kind == ElementKind.UNKNOWN) {
            confidence = Confidence.LOW;
            uncertainties.add("Elemento sem semântica reconhecida: <" + tag + ">");
        }

        String role = explicitRole != null && roleKind != null && kind == roleKind ? explicitRole : kind.defaultRole();
        if (kind == ElementKind.LANDMARK && role != null && role.equals("region")) {
            role = landmarkRole(node, explicitRole);
        }
        AccessibleName name = names.compute(node, kind, role, model);
        if (!name.name().isEmpty() && incompleteName(node, name, model)) {
            // CHROME-SECURITY-001: parte das fontes do nome é texto da página retido pela política — um nome PARCIAL
            // enganaria (e contraria a validação integral): o nome inteiro é retido e a limitação registrada
            evidence.add("nome acessível retido: parte das fontes ficou fora da política de privacidade");
            // as FONTES (relação estrutural com rótulos e referências) continuam; só o texto do nome não sai
            name = new AccessibleName("", name.source(), name.sourceNodeIds(), "", List.of(), "NONE");
        }
        if (kind.category() == ElementCategory.INTERACTIVE && name.name().isEmpty() && kind != ElementKind.OPTION) {
            uncertainties.add("Controle sem nome acessível");
        }

        boolean sensitive = sensitivity.isSensitive(node);
        if (sensitive) {
            evidence.add("dado sensível: valor nunca exportado");
        }
        return new Classification(node.id(), kind, role, nativeSemantics, confidence, name,
                conditions(node, kind, model), sensitive, evidence, uncertainties);
    }

    private static boolean overridesNative(ElementKind nativeKind, ElementKind roleKind) {
        // A semântica nativa de controles de formulário prevalece sobre papéis conflitantes
        // (ex.: <input type=text role=button> continua sendo campo), exceto papéis compatíveis.
        if (nativeKind.isTextEntry()) {
            return roleKind == ElementKind.COMBOBOX || roleKind == ElementKind.SEARCH_INPUT;
        }
        if (nativeKind == ElementKind.CHECKBOX) {
            return roleKind == ElementKind.SWITCH;
        }
        if (nativeKind == ElementKind.SELECT || nativeKind == ElementKind.OPTION) {
            return false;
        }
        return true;
    }

    private static String landmarkRole(RawNode node, String explicitRole) {
        if (explicitRole != null) {
            return explicitRole;
        }
        return switch (node.tag()) {
            case "nav" -> "navigation";
            case "main" -> "main";
            case "header" -> "banner";
            case "footer" -> "contentinfo";
            case "aside" -> "complementary";
            case "search" -> "search";
            case "article" -> "article";
            default -> "region";
        };
    }

    static String explicitRole(RawNode node) {
        String role = node.attr("role");
        if (role == null || role.isBlank()) {
            return null;
        }
        for (String token : role.trim().toLowerCase(Locale.ROOT).split("\\s+")) {
            if (KNOWN_ROLES.contains(token)) {
                return token;
            }
        }
        return null;
    }

    private static boolean isFocusableNative(RawNode node) {
        return switch (node.tag()) {
            case "button", "input", "select", "textarea" -> true;
            case "a" -> node.hasAttr("href");
            default -> false;
        };
    }

    private ElementKind nativeKind(RawNode node, PageModel model, List<String> evidence) {
        String tag = node.tag();
        switch (tag) {
            case "input": {
                String type = node.attr("type") == null ? "text" : node.attr("type").toLowerCase(Locale.ROOT);
                evidence.add("<input type=" + type + ">");
                return switch (type) {
                    case "text" -> ElementKind.TEXT_INPUT;
                    case "email" -> ElementKind.EMAIL_INPUT;
                    case "password" -> ElementKind.PASSWORD_INPUT;
                    case "number" -> ElementKind.NUMBER_INPUT;
                    case "date", "datetime-local", "month", "week", "time" -> ElementKind.DATE_INPUT;
                    case "search" -> ElementKind.SEARCH_INPUT;
                    case "tel" -> ElementKind.TEL_INPUT;
                    case "url" -> ElementKind.URL_INPUT;
                    case "checkbox" -> ElementKind.CHECKBOX;
                    case "radio" -> ElementKind.RADIO;
                    case "file" -> ElementKind.FILE_INPUT;
                    case "submit", "button", "reset", "image" -> ElementKind.BUTTON;
                    case "range" -> ElementKind.SLIDER;
                    case "hidden" -> ElementKind.HIDDEN_INPUT;
                    default -> ElementKind.OTHER_INPUT;
                };
            }
            case "textarea":
                evidence.add("<textarea>");
                return ElementKind.TEXTAREA;
            case "select":
                evidence.add("<select" + (node.hasAttr("multiple") ? " multiple" : "") + ">");
                return ElementKind.SELECT;
            case "option":
                evidence.add("<option>");
                return ElementKind.OPTION;
            case "optgroup":
                evidence.add("<optgroup>");
                return ElementKind.OPTGROUP;
            case "button":
                evidence.add("<button>");
                return ElementKind.BUTTON;
            case "a":
                if (node.hasAttr("href")) {
                    evidence.add("<a href>");
                    return ElementKind.LINK;
                }
                return null;
            case "form":
                evidence.add("<form>");
                return ElementKind.FORM;
            case "fieldset":
                evidence.add("<fieldset>");
                return ElementKind.FIELDSET;
            case "legend":
                return ElementKind.LEGEND;
            case "label":
                return ElementKind.LABEL;
            case "table":
                evidence.add("<table>");
                return ElementKind.TABLE;
            case "thead":
                return ElementKind.TABLE_HEAD;
            case "tbody":
                return ElementKind.TABLE_BODY;
            case "tfoot":
                return ElementKind.TABLE_FOOT;
            case "tr":
                return ElementKind.TABLE_ROW;
            case "th":
                return ElementKind.TABLE_HEADER_CELL;
            case "td":
                return ElementKind.TABLE_CELL;
            case "caption":
                return ElementKind.CAPTION;
            case "ul", "ol", "menu", "dl":
                return ElementKind.LIST;
            case "li":
                return ElementKind.LIST_ITEM;
            case "dialog":
                evidence.add("<dialog>");
                if (NodeState.isTrue(node.state().modal()) || "true".equals(node.attr("aria-modal"))) {
                    evidence.add(NodeState.isTrue(node.state().modal()) ? ":modal" : "aria-modal=true");
                    return ElementKind.MODAL;
                }
                return ElementKind.DIALOG;
            case "h1", "h2", "h3", "h4", "h5", "h6":
                evidence.add("<" + tag + ">");
                return ElementKind.HEADING;
            case "p":
                return ElementKind.PARAGRAPH;
            case "nav", "main", "header", "footer", "aside", "search":
                evidence.add("<" + tag + "> (landmark)");
                return ElementKind.LANDMARK;
            case "section", "article":
                // <section> só é landmark quando possui nome acessível.
                if (node.hasAttr("aria-label") || node.hasAttr("aria-labelledby") || tag.equals("article")) {
                    evidence.add("<" + tag + "> nomeado (landmark)");
                    return ElementKind.LANDMARK;
                }
                return ElementKind.GENERIC_CONTAINER;
            case "img", "svg", "picture":
                return ElementKind.IMAGE;
            case "iframe", "frame":
                return ElementKind.IFRAME;
            case "summary":
                evidence.add("<summary>");
                return ElementKind.BUTTON;
            case "html", "body", "div", "span", "details", "figure", "figcaption", "small", "strong", "em", "b",
                 "i", "u", "code", "pre", "blockquote", "time", "mark", "abbr", "cite", "address", "dt", "dd", "br",
                 "hr", "template", "slot", "font", "center", "sup", "sub", "q", "s", "del", "ins", "kbd", "samp",
                 "var", "data", "output", "meter", "progress", "canvas", "video", "audio", "source", "track",
                 "object", "embed", "map", "area", "noscript", "script", "style", "link", "meta", "head", "title",
                 "base":
                return null;
            default:
                return null;
        }
    }

    private static ElementKind roleKind(String role, RawNode node) {
        return switch (role) {
            case "button" -> ElementKind.BUTTON;
            case "link" -> ElementKind.LINK;
            case "checkbox", "menuitemcheckbox" -> role.equals("checkbox") ? ElementKind.CHECKBOX : ElementKind.MENUITEM;
            case "radio" -> ElementKind.RADIO;
            case "menuitemradio", "menuitem" -> ElementKind.MENUITEM;
            case "switch" -> ElementKind.SWITCH;
            case "combobox" -> ElementKind.COMBOBOX;
            case "listbox" -> ElementKind.LISTBOX;
            case "option" -> ElementKind.OPTION;
            case "menu", "menubar" -> ElementKind.MENU;
            case "tab" -> ElementKind.TAB;
            case "tablist" -> ElementKind.TABLIST;
            case "tabpanel" -> ElementKind.TABPANEL;
            case "dialog" -> "true".equals(node.attr("aria-modal")) ? ElementKind.MODAL : ElementKind.DIALOG;
            case "alertdialog" -> ElementKind.MODAL;
            case "alert" -> ElementKind.ALERT;
            case "status", "log", "timer" -> ElementKind.STATUS;
            case "heading" -> ElementKind.HEADING;
            case "paragraph" -> ElementKind.PARAGRAPH;
            case "textbox" -> "true".equals(node.attr("aria-multiline")) ? ElementKind.TEXTAREA : ElementKind.TEXT_INPUT;
            case "searchbox" -> ElementKind.SEARCH_INPUT;
            case "spinbutton" -> ElementKind.NUMBER_INPUT;
            case "slider" -> ElementKind.SLIDER;
            case "table", "grid", "treegrid" -> ElementKind.TABLE;
            case "row" -> ElementKind.TABLE_ROW;
            case "rowgroup" -> ElementKind.TABLE_BODY;
            case "cell", "gridcell" -> ElementKind.TABLE_CELL;
            case "columnheader", "rowheader" -> ElementKind.TABLE_HEADER_CELL;
            case "list", "directory", "feed" -> ElementKind.LIST;
            case "listitem", "treeitem" -> ElementKind.LIST_ITEM;
            case "form" -> ElementKind.FORM;
            case "group", "radiogroup", "toolbar" -> ElementKind.GROUP;
            case "region", "navigation", "main", "banner", "contentinfo", "complementary", "search", "article" ->
                    ElementKind.LANDMARK;
            case "img", "figure" -> ElementKind.IMAGE;
            case "caption" -> ElementKind.CAPTION;
            case "tooltip", "note", "definition", "term" -> ElementKind.DESCRIPTION;
            default -> null;
        };
    }

    private static ElementKind textOrContainer(RawNode node, PageModel model, CrossReferences refs, List<String> evidence) {
        if (node.hasText()) {
            evidence.add("texto próprio");
            return ElementKind.TEXT;
        }
        return ElementKind.GENERIC_CONTAINER;
    }

    /** O nome veio (também) de alguma fonte com texto retido? Conteúdo do próprio nó, rótulos e referências. */
    private static boolean incompleteName(RawNode node, AccessibleName name, PageModel model) {
        if (name.source() == NameSource.CONTENT && model.exposedTextSuppressedWithin(node)) {
            return true;
        }
        for (String id : name.sourceNodeIds()) {
            RawNode s = model.find(id).orElse(null);
            if (s != null && model.textSuppressedWithin(s)) {
                return true;
            }
        }
        return false;
    }

    private static ElementKind refineText(RawNode node, ElementKind kind, CrossReferences refs, List<String> evidence) {
        String idAttr = node.attr("id");
        boolean referencedAsError = refs.errorMessages().contains(node.id());
        boolean referencedAsDescription = refs.describedBy().contains(node.id());
        String hints = (node.attr("class") == null ? "" : node.attr("class")) + " " + (idAttr == null ? "" : idAttr);
        if (kind == ElementKind.ALERT || kind == ElementKind.STATUS) {
            if (referencedAsError || ERROR_HINT.matcher(hints).find()) {
                evidence.add(referencedAsError ? "referenciado como mensagem de erro de um campo" : "indício de erro em class/id");
                return ElementKind.VALIDATION_MESSAGE;
            }
            return kind;
        }
        if (referencedAsError) {
            evidence.add("referenciado por aria-errormessage/aria-describedby de campo inválido");
            return ElementKind.VALIDATION_MESSAGE;
        }
        if ("assertive".equals(node.attr("aria-live"))) {
            evidence.add("aria-live=assertive");
            return ElementKind.ALERT;
        }
        if ("polite".equals(node.attr("aria-live"))) {
            evidence.add("aria-live=polite");
            return ElementKind.STATUS;
        }
        boolean textual = kind == ElementKind.TEXT || kind == ElementKind.PARAGRAPH || kind == ElementKind.DESCRIPTION;
        if (textual && ERROR_HINT.matcher(hints).find() && node.hasText()) {
            evidence.add("indício de erro em class/id (heurística)");
            return ElementKind.VALIDATION_MESSAGE;
        }
        if (textual && referencedAsDescription) {
            evidence.add("referenciado por aria-describedby");
            return ElementKind.DESCRIPTION;
        }
        if (textual && HELP_HINT.matcher(hints).find() && node.hasText()) {
            evidence.add("indício de instrução em class/id (heurística)");
            return ElementKind.INSTRUCTION;
        }
        return kind;
    }

    private static List<String> widgetSupport(RawNode node, PageModel model) {
        List<String> support = new ArrayList<>();
        String tabindex = node.attr("tabindex");
        if (tabindex != null) {
            try {
                if (Integer.parseInt(tabindex.trim()) >= 0) {
                    support.add("tabindex=" + tabindex.trim());
                }
            } catch (NumberFormatException ignored) {
                // tabindex inválido não conta como evidência
            }
        } else if (NodeState.isTrue(node.state().focusable())) {
            support.add("focável");
        }
        if (node.hasAttr("onclick")) {
            support.add("manipulador onclick declarado");
        }
        if (NodeState.isTrue(node.state().cursorPointer())) {
            support.add("cursor:pointer");
        }
        for (RawNode a : model.ancestorsInScope(node)) {
            String r = explicitRole(a);
            if (r != null && COMPOSITE_PARENTS.contains(r)) {
                support.add("dentro de widget composto role=" + r);
                break;
            }
            if (a.tag().equals("select") || a.tag().equals("datalist")) {
                support.add("dentro de <" + a.tag() + ">");
                break;
            }
        }
        if (node.hasAttr("aria-controls") || node.hasAttr("aria-expanded")) {
            support.add("estado de widget declarado (aria-controls/aria-expanded)");
        }
        return support;
    }

    private static Set<ElementCondition> conditions(RawNode node, ElementKind kind, PageModel model) {
        EnumSet<ElementCondition> c = EnumSet.of(ElementCondition.PRESENT);
        NodeState s = node.state();
        if (s.visible() == null) {
            c.add(ElementCondition.UNKNOWN_STATE);
        } else if (s.visible()) {
            c.add(ElementCondition.VISIBLE);
        } else {
            c.add(ElementCondition.HIDDEN);
        }
        if (NodeState.isFalse(s.inViewport()) && !NodeState.isFalse(s.visible())) {
            c.add(ElementCondition.OUT_OF_VIEWPORT);
        }
        boolean interactive = kind.category() == ElementCategory.INTERACTIVE;
        c.add(interactive ? ElementCondition.INTERACTIVE : ElementCondition.NON_INTERACTIVE);
        if (interactive) {
            boolean formControl = switch (node.tag()) {
                case "input", "select", "textarea", "button", "option", "optgroup", "fieldset" -> true;
                default -> false;
            };
            if (NodeState.isTrue(s.disabled()) || "true".equals(node.attr("aria-disabled"))) {
                c.add(ElementCondition.DISABLED);
            } else if (NodeState.isFalse(s.disabled()) || !formControl) {
                // Links, contenteditable e widgets ARIA não têm estado :disabled nativo: habilitados salvo aria-disabled.
                c.add(ElementCondition.ENABLED);
            } else {
                c.add(ElementCondition.UNKNOWN_STATE);
            }
            if (NodeState.isTrue(s.readOnly()) || "true".equals(node.attr("aria-readonly"))) {
                c.add(ElementCondition.READ_ONLY);
            }
            if (NodeState.isTrue(s.required()) || "true".equals(node.attr("aria-required"))) {
                c.add(ElementCondition.REQUIRED);
            }
        }
        if (NodeState.isTrue(s.inert())) {
            c.add(ElementCondition.INACCESSIBLE);
        }
        return c;
    }
}
