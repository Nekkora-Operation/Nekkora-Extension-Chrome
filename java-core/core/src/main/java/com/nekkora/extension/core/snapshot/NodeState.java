package com.nekkora.extension.core.snapshot;

/**
 * Estados observados de um elemento. {@code null} significa ESTADO DESCONHECIDO (não observado),
 * o que é diferente de {@code false}.
 *
 * <p>As condições podem coexistir: um elemento pode estar presente, oculto e desabilitado ao mesmo
 * tempo.
 *
 * @param visible       resultado de {@code checkVisibility()} (CSS display/visibility/opacity/content-visibility)
 * @param hiddenReason  motivo da ocultação quando conhecido (ex.: {@code display:none}, {@code hidden-attribute})
 * @param inViewport    interseção do retângulo com o viewport no momento da captura
 * @param zeroSize      retângulo com largura ou altura zero
 * @param disabled      {@code :disabled} (inclui herança de fieldset desabilitado) ou {@code aria-disabled=true}
 * @param readOnly      {@code :read-only} em controles editáveis ou {@code aria-readonly=true}
 * @param required      {@code required} ou {@code aria-required=true}
 * @param checked       estado marcado (checkbox, radio, switch) quando aplicável
 * @param selected      estado selecionado (option) quando aplicável
 * @param editable      {@code isContentEditable}
 * @param focusable     elemento alcançável por teclado (tabIndex &gt;= 0)
 * @param cursorPointer cursor computado {@code pointer} (evidência fraca de interatividade)
 * @param boxed         possui borda, fundo ou sombra própria (evidência de contêiner visual)
 * @param hasValue      controle possui valor não vazio (o valor em si NÃO é exportado)
 * @param valueLength   comprimento do valor, quando permitido
 * @param open          diálogo/detalhes aberto, quando aplicável
 * @param modal         diálogo aberto via {@code showModal()} ({@code :modal})
 * @param inert         elemento inerte (dentro de região {@code inert} ou bloqueada por modal)
 */
public record NodeState(
        Boolean visible,
        String hiddenReason,
        Boolean inViewport,
        Boolean zeroSize,
        Boolean disabled,
        Boolean readOnly,
        Boolean required,
        Boolean checked,
        Boolean selected,
        Boolean editable,
        Boolean focusable,
        Boolean cursorPointer,
        Boolean boxed,
        Boolean hasValue,
        Integer valueLength,
        Boolean open,
        Boolean modal,
        Boolean inert) {

    public static final NodeState UNKNOWN = new NodeState(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

    public static boolean isTrue(Boolean b) {
        return Boolean.TRUE.equals(b);
    }

    public static boolean isFalse(Boolean b) {
        return Boolean.FALSE.equals(b);
    }
}
