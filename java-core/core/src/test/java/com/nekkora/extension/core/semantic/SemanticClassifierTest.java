package com.nekkora.extension.core.semantic;

import com.nekkora.extension.core.analysis.PageAnalysis;
import com.nekkora.extension.core.testing.Engine;
import com.nekkora.extension.core.testing.HtmlSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticClassifierTest {

    private static Classification classify(String html, String css) {
        HtmlSnapshot h = HtmlSnapshot.of(html);
        PageAnalysis a = Engine.analyze(h);
        return a.semantics().get(h.idOf(css));
    }

    @ParameterizedTest(name = "{0} → {2}")
    @CsvSource(delimiter = '|', value = {
            "<input id=t>|#t|TEXT_INPUT",
            "<input id=t type=email>|#t|EMAIL_INPUT",
            "<input id=t type=password>|#t|PASSWORD_INPUT",
            "<input id=t type=number>|#t|NUMBER_INPUT",
            "<input id=t type=date>|#t|DATE_INPUT",
            "<input id=t type=search>|#t|SEARCH_INPUT",
            "<input id=t type=file>|#t|FILE_INPUT",
            "<input id=t type=checkbox>|#t|CHECKBOX",
            "<input id=t type=checkbox role=switch>|#t|SWITCH",
            "<input id=t type=radio>|#t|RADIO",
            "<input id=t type=submit value=Enviar>|#t|BUTTON",
            "<textarea id=t></textarea>|#t|TEXTAREA",
            "<div id=t contenteditable=true>Texto</div>|#t|CONTENTEDITABLE",
            "<button id=t>Ok</button>|#t|BUTTON",
            "<a id=t href='/x'>Link</a>|#t|LINK",
            "<select id=t><option>A</option></select>|#t|SELECT",
            "<select><optgroup id=t label=G><option>A</option></optgroup></select>|#t|OPTGROUP",
            "<select><option id=t>A</option></select>|#t|OPTION",
            "<div id=t role=combobox tabindex=0 aria-controls=l>Estado</div><div id=l role=listbox></div>|#t|COMBOBOX",
            "<div id=t role=listbox><div role=option>A</div></div>|#t|LISTBOX",
            "<button id=t aria-haspopup=listbox>Escolher</button>|#t|CUSTOM_DROPDOWN",
            "<ul id=t role=menu><li role=menuitem>A</li></ul>|#t|MENU",
            "<ul role=menu><li id=t role=menuitem>A</li></ul>|#t|MENUITEM",
            "<form id=t></form>|#t|FORM",
            "<fieldset id=t><legend>L</legend></fieldset>|#t|FIELDSET",
            "<fieldset><legend id=t>L</legend></fieldset>|#t|LEGEND",
            "<label id=t>Nome</label>|#t|LABEL",
            "<table id=t><tr><td>a</td></tr></table>|#t|TABLE",
            "<table><thead id=t><tr><th>a</th></tr></thead></table>|#t|TABLE_HEAD",
            "<table><tbody id=t><tr><td>a</td></tr></tbody></table>|#t|TABLE_BODY",
            "<table><tr id=t><td>a</td></tr></table>|#t|TABLE_ROW",
            "<table><tr><th id=t>a</th></tr></table>|#t|TABLE_HEADER_CELL",
            "<table><tr><td id=t>a</td></tr></table>|#t|TABLE_CELL",
            "<ul id=t><li>a</li></ul>|#t|LIST",
            "<ul><li id=t>a</li></ul>|#t|LIST_ITEM",
            "<dialog id=t open><p>x</p></dialog>|#t|DIALOG",
            "<dialog id=t open data-fixture-modal><p>x</p></dialog>|#t|MODAL",
            "<div id=t role=dialog aria-modal=true>x</div>|#t|MODAL",
            "<div role=tablist><button id=t role=tab>Aba</button></div>|#t|TAB",
            "<div id=t role=tabpanel>Painel</div>|#t|TABPANEL",
            "<h2 id=t>Título</h2>|#t|HEADING",
            "<p id=t>Parágrafo</p>|#t|PARAGRAPH",
            "<span id=t>Texto solto</span>|#t|TEXT",
            "<div id=t role=alert>Falha</div>|#t|ALERT",
            "<input aria-invalid=true aria-describedby=e><span id=e>Obrigatório</span>|#e|VALIDATION_MESSAGE",
            "<span id=t class=field-error>Inválido</span>|#t|VALIDATION_MESSAGE",
            "<p id=t class=hint>Use 8 caracteres</p>|#t|INSTRUCTION",
            "<input aria-describedby=d><span id=d>Formato DD/MM</span>|#d|DESCRIPTION",
    })
    void classifiesRequiredKinds(String html, String css, String expected) {
        assertEquals(ElementKind.valueOf(expected), classify(html, css).kind());
    }

    @Test
    void ariaRoleAloneIsNotEnoughEvidence() {
        Classification bare = classify("<div id=t role=button>Salvar</div>", "#t");
        assertEquals(ElementKind.BUTTON, bare.kind());
        assertEquals(Confidence.LOW, bare.confidence());
        assertFalse(bare.uncertainties().isEmpty(), "deve registrar incerteza");

        Classification supported = classify("<div id=t role=button tabindex=0>Salvar</div>", "#t");
        assertEquals(Confidence.MEDIUM, supported.confidence());
        assertTrue(supported.evidence().stream().anyMatch(e -> e.contains("tabindex")));
    }

    @Test
    void nativeSemanticsWinsOverConflictingRole() {
        assertEquals(ElementKind.TEXT_INPUT, classify("<input id=t role=button>", "#t").kind());
    }

    @Test
    void conditionsCoexist() {
        Classification c = classify("<div style='display:none'><button id=t disabled>X</button></div>", "#t");
        assertTrue(c.has(ElementCondition.PRESENT));
        assertTrue(c.has(ElementCondition.HIDDEN));
        assertTrue(c.has(ElementCondition.DISABLED));
        assertTrue(c.has(ElementCondition.INTERACTIVE));

        Classification ro = classify("<input id=t readonly required>", "#t");
        assertTrue(ro.has(ElementCondition.READ_ONLY));
        assertTrue(ro.has(ElementCondition.REQUIRED));
        assertTrue(ro.has(ElementCondition.ENABLED));
    }

    @Test
    void fieldsetDisabledPropagates() {
        Classification c = classify("<fieldset disabled><input id=t></fieldset>", "#t");
        assertTrue(c.has(ElementCondition.DISABLED));
    }

    @Test
    void sensitiveFieldsAreFlagged() {
        assertTrue(classify("<input id=t type=password>", "#t").sensitive());
        assertTrue(classify("<input id=t name=cpf>", "#t").sensitive());
        assertTrue(classify("<input id=t autocomplete=cc-number>", "#t").sensitive());
        assertFalse(classify("<input id=t name=cidade>", "#t").sensitive());
    }
}
