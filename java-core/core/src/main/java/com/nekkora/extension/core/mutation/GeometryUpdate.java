package com.nekkora.extension.core.mutation;

import com.nekkora.extension.core.snapshot.Rect;

/**
 * Atualização compacta de geometria (NEKKORA-REAL-001): rolagem/redimensionamento mudam o retângulo (relativo ao
 * viewport) de muitos elementos sem mudar o DOM; o delta leva somente id + retângulo + presença no viewport.
 */
public record GeometryUpdate(String id, double x, double y, double width, double height, boolean inViewport) {

    public Rect rect() {
        return new Rect(x, y, width, height);
    }
}
