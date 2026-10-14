package com.nekkora.extension.core.frames;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Identidade LÓGICA de um frame, independente do {@code frameId} do Chrome, do índice do iframe e da ordem de
 * carregamento. Comparada como os localizadores de elementos: atributos estáveis, contêiner, hierarquia, URL e
 * origem.
 *
 * @param idAttr          {@code id} do iframe (somente quando não parece gerado dinamicamente)
 * @param name            atributo {@code name}
 * @param title           atributo {@code title} (ou {@code aria-label})
 * @param stableAttributes outros atributos estáveis ({@code data-testid}, {@code aria-label}…)
 * @param container       contêiner do iframe no documento pai (formulário, região, diálogo, título ou ancestral com id)
 * @param urlPath         caminho da URL (declarada ou efetiva), sem query
 * @param origin          origem efetiva quando conhecida
 * @param depth           profundidade (1 = filho do documento principal)
 * @param parentKey       chave da identidade do frame pai ({@code main} para o documento principal)
 * @param insideShadowRoot o iframe está dentro de um shadow root
 */
public record FrameIdentity(String idAttr, String name, String title, Map<String, String> stableAttributes,
                            String container, String urlPath, String origin, int depth, String parentKey,
                            boolean insideShadowRoot) {

    public FrameIdentity {
        stableAttributes = stableAttributes == null ? Map.of() : Map.copyOf(stableAttributes);
    }

    /** Chave legível (diagnóstico e detecção de irmãos indistinguíveis). */
    public String key() {
        List<String> parts = new ArrayList<>();
        parts.add(parentKey == null ? "main" : parentKey);
        parts.add("id=" + Objects.toString(idAttr, ""));
        parts.add("name=" + Objects.toString(name, ""));
        parts.add("title=" + Objects.toString(title, ""));
        parts.add("in=" + Objects.toString(container, ""));
        parts.add("path=" + Objects.toString(urlPath, ""));
        parts.add("origin=" + Objects.toString(origin, ""));
        return String.join("|", parts);
    }

    /**
     * Pontuação de compatibilidade com outra identidade (0..1). Conflitos em características presentes nos dois
     * lados reduzem fortemente; ausências são neutras.
     */
    public double score(FrameIdentity other) {
        if (other == null) {
            return 0;
        }
        double sum = 0;
        double weight = 0;
        double[] r;
        r = feature(idAttr, other.idAttr, 3);
        sum += r[0];
        weight += r[1];
        r = feature(name, other.name, 2.5);
        sum += r[0];
        weight += r[1];
        r = feature(title, other.title, 1.5);
        sum += r[0];
        weight += r[1];
        r = feature(container, other.container, 2.5);
        sum += r[0];
        weight += r[1];
        r = feature(urlPath, other.urlPath, 1.5);
        sum += r[0];
        weight += r[1];
        r = feature(origin, other.origin, 1);
        sum += r[0];
        weight += r[1];
        r = feature(parentKey, other.parentKey, 2);
        sum += r[0];
        weight += r[1];
        for (Map.Entry<String, String> e : stableAttributes.entrySet()) {
            r = feature(e.getValue(), other.stableAttributes.get(e.getKey()), 1);
            sum += r[0];
            weight += r[1];
        }
        if (depth != other.depth) {
            weight += 2; // profundidade diferente: hierarquia incompatível
        }
        return weight == 0 ? 0.5 : Math.round(sum / weight * 1000) / 1000.0;
    }

    private static double[] feature(String a, String b, double w) {
        if (a == null || a.isEmpty() || b == null || b.isEmpty()) {
            return new double[]{0, 0};
        }
        return new double[]{a.equals(b) ? w : 0, w};
    }
}
