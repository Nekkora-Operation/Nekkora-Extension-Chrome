package com.nekkora.extension.core.semantic;


/** Finalidade identificada de um elemento, com o papel ARIA equivalente. */
public enum ElementKind {
    TEXT_INPUT(ElementCategory.INTERACTIVE, "textbox"),
    EMAIL_INPUT(ElementCategory.INTERACTIVE, "textbox"),
    PASSWORD_INPUT(ElementCategory.INTERACTIVE, "textbox"),
    NUMBER_INPUT(ElementCategory.INTERACTIVE, "spinbutton"),
    DATE_INPUT(ElementCategory.INTERACTIVE, "textbox"),
    SEARCH_INPUT(ElementCategory.INTERACTIVE, "searchbox"),
    TEL_INPUT(ElementCategory.INTERACTIVE, "textbox"),
    URL_INPUT(ElementCategory.INTERACTIVE, "textbox"),
    OTHER_INPUT(ElementCategory.INTERACTIVE, "textbox"),
    TEXTAREA(ElementCategory.INTERACTIVE, "textbox"),
    CONTENTEDITABLE(ElementCategory.INTERACTIVE, "textbox"),
    FILE_INPUT(ElementCategory.INTERACTIVE, "button"),
    BUTTON(ElementCategory.INTERACTIVE, "button"),
    LINK(ElementCategory.INTERACTIVE, "link"),
    CHECKBOX(ElementCategory.INTERACTIVE, "checkbox"),
    RADIO(ElementCategory.INTERACTIVE, "radio"),
    SWITCH(ElementCategory.INTERACTIVE, "switch"),
    SLIDER(ElementCategory.INTERACTIVE, "slider"),
    SELECT(ElementCategory.INTERACTIVE, "combobox"),
    OPTION(ElementCategory.INTERACTIVE, "option"),
    OPTGROUP(ElementCategory.STRUCTURAL, "group"),
    COMBOBOX(ElementCategory.INTERACTIVE, "combobox"),
    LISTBOX(ElementCategory.INTERACTIVE, "listbox"),
    /** Gatilho de lista/menu personalizado sem papel combobox (ex.: botão com aria-haspopup). */
    CUSTOM_DROPDOWN(ElementCategory.INTERACTIVE, "button"),
    MENU(ElementCategory.STRUCTURAL, "menu"),
    MENUITEM(ElementCategory.INTERACTIVE, "menuitem"),
    TAB(ElementCategory.INTERACTIVE, "tab"),
    TABLIST(ElementCategory.STRUCTURAL, "tablist"),
    TABPANEL(ElementCategory.STRUCTURAL, "tabpanel"),
    FORM(ElementCategory.STRUCTURAL, "form"),
    FIELDSET(ElementCategory.STRUCTURAL, "group"),
    GROUP(ElementCategory.STRUCTURAL, "group"),
    LEGEND(ElementCategory.TEXT, null),
    LABEL(ElementCategory.TEXT, null),
    TABLE(ElementCategory.STRUCTURAL, "table"),
    TABLE_HEAD(ElementCategory.STRUCTURAL, "rowgroup"),
    TABLE_BODY(ElementCategory.STRUCTURAL, "rowgroup"),
    TABLE_FOOT(ElementCategory.STRUCTURAL, "rowgroup"),
    TABLE_ROW(ElementCategory.STRUCTURAL, "row"),
    TABLE_HEADER_CELL(ElementCategory.TEXT, "columnheader"),
    TABLE_CELL(ElementCategory.TEXT, "cell"),
    CAPTION(ElementCategory.TEXT, "caption"),
    LIST(ElementCategory.STRUCTURAL, "list"),
    LIST_ITEM(ElementCategory.STRUCTURAL, "listitem"),
    DIALOG(ElementCategory.STRUCTURAL, "dialog"),
    MODAL(ElementCategory.STRUCTURAL, "dialog"),
    LANDMARK(ElementCategory.STRUCTURAL, "region"),
    HEADING(ElementCategory.TEXT, "heading"),
    PARAGRAPH(ElementCategory.TEXT, "paragraph"),
    TEXT(ElementCategory.TEXT, null),
    ALERT(ElementCategory.TEXT, "alert"),
    STATUS(ElementCategory.TEXT, "status"),
    VALIDATION_MESSAGE(ElementCategory.TEXT, null),
    DESCRIPTION(ElementCategory.TEXT, null),
    INSTRUCTION(ElementCategory.TEXT, null),
    IMAGE(ElementCategory.OTHER, "img"),
    IFRAME(ElementCategory.OTHER, null),
    HIDDEN_INPUT(ElementCategory.OTHER, null),
    GENERIC_CONTAINER(ElementCategory.CONTAINER, "generic"),
    UNKNOWN(ElementCategory.OTHER, null);

    private final ElementCategory category;
    private final String defaultRole;

    ElementKind(ElementCategory category, String defaultRole) {
        this.category = category;
        this.defaultRole = defaultRole;
    }

    public ElementCategory category() {
        return category;
    }

    public String defaultRole() {
        return defaultRole;
    }

    public boolean isTextEntry() {
        return switch (this) {
            case TEXT_INPUT, EMAIL_INPUT, PASSWORD_INPUT, NUMBER_INPUT, DATE_INPUT, SEARCH_INPUT, TEL_INPUT,
                 URL_INPUT, OTHER_INPUT, TEXTAREA, CONTENTEDITABLE -> true;
            default -> false;
        };
    }

    /**
     * Família de compatibilidade usada na resolução: dois elementos só podem representar o mesmo alvo
     * se pertencem à mesma família (um campo de texto pode virar textarea; um botão não pode virar campo).
     */
    public String compatibilityFamily() {
        if (isTextEntry()) {
            return "text-entry";
        }
        return switch (this) {
            case BUTTON, CUSTOM_DROPDOWN, FILE_INPUT -> "button";
            case CHECKBOX, SWITCH -> "toggle";
            case SELECT, COMBOBOX, LISTBOX -> "choice";
            case DIALOG, MODAL -> "dialog";
            case HEADING, PARAGRAPH, TEXT, ALERT, STATUS, VALIDATION_MESSAGE, DESCRIPTION, INSTRUCTION, LABEL, LEGEND,
                 CAPTION -> "text";
            case TABLE_CELL, TABLE_HEADER_CELL -> "cell";
            default -> name().toLowerCase(java.util.Locale.ROOT);
        };
    }
}
