package com.nekkora.extension.core.privacy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CHROME-SECURITY-001 — política de captura: o que pode sair de cada superfície. Separa três categorias:
 *
 * <ul>
 *   <li><b>estrutural</b> (tag, papel, estado, posição, hierarquia, atributos de vocabulário fechado): permitido;</li>
 *   <li><b>texto da página</b> (nome, aviso, título, conteúdo): não sai livremente;</li>
 *   <li><b>valor do usuário</b> (digitado ou selecionado): nunca sai automaticamente.</li>
 * </ul>
 *
 * <p>A segurança vem de restrição estrutural (o que não está na lista fechada não sai), não de reconhecer dado pessoal.
 * Esta classe começa pelas superfícies cuja correção independe da decisão do ponto de controle da fase 2 (mudanças de
 * mutação e de resolução); as demais regras entram nela depois da decisão.
 */
public final class CapturePrivacyPolicy {

    /** Marcador de valor retido: houve conteúdo/mudança, mas o valor não sai. Não é recuperável nem derivado do valor. */
    public static final String WITHHELD = "[omitido]";

    /**
     * Atributos cujos valores pertencem a um vocabulário fechado ou são numéricos (estado, tipo, papel): não carregam
     * texto livre da página. O VALOR de qualquer outro atributo é retido.
     */
    public static final Set<String> STRUCTURAL_ATTRIBUTES = Set.of("type", "role", "tabindex", "disabled", "checked", "readonly",
            "required", "hidden", "open", "multiple", "selected", "contenteditable", "autocomplete", "inputmode", "method", "target",
            "scope", "colspan", "rowspan", "draggable", "spellcheck", "dir", "popover", "maxlength", "minlength", "min", "max", "step",
            "size", "rows", "cols", "aria-expanded", "aria-pressed", "aria-checked", "aria-selected", "aria-disabled", "aria-hidden",
            "aria-invalid", "aria-required", "aria-readonly", "aria-busy", "aria-modal", "aria-haspopup", "aria-live", "aria-current",
            "aria-level", "aria-multiselectable", "aria-orientation", "aria-sort", "aria-autocomplete", "aria-atomic", "aria-relevant",
            "aria-valuenow", "aria-valuemin", "aria-valuemax", "aria-colcount", "aria-rowcount", "aria-colindex", "aria-rowindex",
            "aria-posinset", "aria-setsize");

    private CapturePrivacyPolicy() {
    }

    public static boolean structural(String attribute) {
        return attribute != null && STRUCTURAL_ATTRIBUTES.contains(attribute.toLowerCase(java.util.Locale.ROOT));
    }

    /** Texto (antes/depois) de uma mudança: o fato fica, o conteúdo não. */
    public static String withheldText(String text) {
        return text == null ? null : WITHHELD;
    }

    /**
     * Mudanças de atributos {@code nome → [antes, depois]}: o NOME do atributo fica; o valor só fica quando o atributo é
     * estrutural ({@code aria-expanded: false → true}); senão vira {@link #WITHHELD} (ausência continua {@code null}).
     */
    public static Map<String, List<String>> attributeChanges(Map<String, List<String>> changes) {
        if (changes == null || changes.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : changes.entrySet()) {
            List<String> pair = e.getValue() == null ? List.of() : e.getValue();
            if (structural(e.getKey())) {
                out.put(e.getKey(), Collections.unmodifiableList(new ArrayList<>(pair)));
                continue;
            }
            List<String> w = new ArrayList<>(pair.size());
            for (String v : pair) {
                w.add(v == null ? null : WITHHELD);
            }
            out.put(e.getKey(), Collections.unmodifiableList(w));
        }
        return Collections.unmodifiableMap(out);
    }
}
