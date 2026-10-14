package com.nekkora.extension.core.privacy;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Atributos de nós sob a política de privacidade (espelho de {@code attributes()} em
 * {@code chrome-extension/src/content/capture.js}): atributos de URL sanitizados preservando a forma, atributos de
 * texto livre com dados pessoais mascarados e {@code data-*} com aparência de segredo redigidos. Devolve o MESMO
 * mapa quando nada muda (custo mínimo no caminho de captura).
 */
public final class AttributePrivacy {

    static final Set<String> URL_ATTRIBUTES = Set.of("href", "src", "action", "formaction", "poster", "data", "cite", "background",
            "longdesc", "xlink:href", "manifest", "codebase", "data-nekkora-simulated-src");
    static final Set<String> SRCSET_ATTRIBUTES = Set.of("srcset", "imagesrcset");
    static final Set<String> TEXT_ATTRIBUTES = Set.of("title", "alt", "aria-label", "aria-description", "aria-valuetext",
            "aria-roledescription", "aria-placeholder", "label", "content", "value", "summary", "abbr", "download");

    private AttributePrivacy() {
    }

    public static String sanitize(String name, String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        if (URL_ATTRIBUTES.contains(name)) {
            return Privacy.sanitizeUrl(value);
        }
        if ("ping".equals(name)) {
            String[] parts = value.split("\s+");
            for (int i = 0; i < parts.length; i++) {
                parts[i] = Privacy.sanitizeUrl(parts[i]);
            }
            return String.join(" ", parts);
        }
        if (SRCSET_ATTRIBUTES.contains(name)) {
            String[] parts = value.split(",", -1);
            for (int i = 0; i < parts.length; i++) {
                String t = parts[i].strip();
                int sp = indexOfWhitespace(t);
                parts[i] = sp < 0 ? Privacy.sanitizeUrl(t) : Privacy.sanitizeUrl(t.substring(0, sp)) + t.substring(sp);
            }
            return String.join(", ", parts);
        }
        if (TEXT_ATTRIBUTES.contains(name)) {
            return Privacy.maskText(value);
        }
        if (name.startsWith("data-")) {
            return value.length() >= 16 && Privacy.looksSecret(value) ? "[redacted]" : Privacy.maskText(value);
        }
        return value;
    }

    private static int indexOfWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    /** Mapa sanitizado; o próprio {@code attributes} quando nenhum valor muda. */
    public static Map<String, String> sanitize(Map<String, String> attributes) {
        Map<String, String> out = null;
        for (Map.Entry<String, String> e : attributes.entrySet()) {
            String v = e.getValue();
            String s = sanitize(e.getKey(), v);
            if (s != null && !s.equals(v)) {
                if (out == null) {
                    out = new HashMap<>(attributes);
                }
                out.put(e.getKey(), s);
            }
        }
        return out == null ? attributes : Map.copyOf(out);
    }
}
