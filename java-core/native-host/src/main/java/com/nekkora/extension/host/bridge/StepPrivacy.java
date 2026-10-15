package com.nekkora.extension.host.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nekkora.extension.core.privacy.CapturePrivacyPolicy;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Terceira barreira de privacidade das etapas gravadas. CHROME-SECURITY-001 ({@code nekkora-privacy/2}): antes de uma
 * etapa sair do host para o Studio, a política é reaplicada — o host não confia no content script nem no service worker:
 *
 * <ul>
 *   <li>nome, rótulos do caminho de acesso e nomes de contexto: só se o valor INTEIRO for do vocabulário, senão vazio;</li>
 *   <li>{@code htmlId}/{@code htmlName}: só identificadores do vocabulário;</li>
 *   <li>localizador com qualquer literal fora da política (CSS, XPath ou descrição): descartado, com
 *       {@code LOCATORS_WITHHELD}; sem localizador seguro e único: {@code SAFE_LOCATOR_UNAVAILABLE} — nunca inventado;</li>
 *   <li>opção escolhida: nunca ({@code OPTION_WITHHELD});</li>
 *   <li>URL como modelo; título só se for do vocabulário.</li>
 * </ul>
 *
 * A validação estrita do schema ({@code additionalProperties: false}) completa a barreira.
 */
final class StepPrivacy {

    private StepPrivacy() {
    }

    static void reapply(JsonNode payload) {
        if (!(payload instanceof ObjectNode p)) {
            return;
        }
        Set<String> notes = new LinkedHashSet<>();
        if (p.path("element") instanceof ObjectNode e) {
            name(e, "name");
            if (e.path("sensitive").asBoolean(false)) {
                e.putNull("htmlId");
                e.putNull("htmlName");
            } else {
                identifier(e, "htmlId");
                identifier(e, "htmlName");
            }
            for (JsonNode s : e.path("accessPath")) {
                if (s instanceof ObjectNode o) {
                    name(o, "label");
                    if (o.path("locator").isTextual() && !CapturePrivacyPolicy.expressionSafe(o.path("locator").asText())) {
                        o.putNull("locator");
                    }
                }
            }
            for (JsonNode c : e.path("context")) {
                if (c instanceof ObjectNode o) {
                    name(o, "name");
                }
            }
            locators(e, notes);
        }
        if (p.path("parameters") instanceof ObjectNode params && params.path("option") instanceof ObjectNode) {
            params.putNull("option"); // valor do usuário / conteúdo de negócio: nunca vai ao Studio
            notes.add("OPTION_WITHHELD");
        }
        if (p.path("context") instanceof ObjectNode c) {
            url(c, "url");
            title(c, "title");
            if (c.path("after") instanceof ObjectNode a) {
                url(a, "url");
            }
        }
        if (!notes.isEmpty()) {
            ArrayNode arr = p.path("notes") instanceof ArrayNode n ? n : p.putArray("notes");
            Set<String> have = new LinkedHashSet<>();
            arr.forEach(x -> have.add(x.asText()));
            for (String n : notes) {
                if (!have.contains(n) && arr.size() < 12) {
                    arr.add(n);
                }
            }
        }
    }

    private static void locators(ObjectNode e, Set<String> notes) {
        if (!(e.path("locators") instanceof ArrayNode ls)) {
            return;
        }
        int preferred = e.path("preferredLocator").asInt(-1);
        ArrayNode kept = e.arrayNode();
        int newPreferred = -1;
        boolean unique = false;
        for (int i = 0; i < ls.size(); i++) {
            JsonNode l = ls.get(i);
            boolean ok = CapturePrivacyPolicy.expressionSafe(l.path("css").isTextual() ? l.path("css").asText() : null)
                    && CapturePrivacyPolicy.expressionSafe(l.path("xpath").isTextual() ? l.path("xpath").asText() : null)
                    && CapturePrivacyPolicy.expressionSafe(l.path("description").isTextual() ? l.path("description").asText() : null);
            if (!ok) {
                continue;
            }
            if (i == preferred) {
                newPreferred = kept.size();
            }
            unique |= l.path("matchCount").asInt() == 1 && l.path("matchesTarget").asBoolean(false);
            kept.add(l);
        }
        if (kept.size() < ls.size()) {
            notes.add("LOCATORS_WITHHELD");
            e.set("locators", kept);
            e.put("preferredLocator", newPreferred);
        }
        if (!unique && "RESOLVED".equals(e.path("resolution").asText())) {
            notes.add("SAFE_LOCATOR_UNAVAILABLE");
        }
    }

    private static void name(ObjectNode o, String field) {
        JsonNode v = o.get(field);
        if (v != null && v.isTextual()) {
            String t = CapturePrivacyPolicy.interfaceText(v.asText());
            o.put(field, t == null ? "" : t);
        }
    }

    private static void identifier(ObjectNode o, String field) {
        JsonNode v = o.get(field);
        if (v != null && v.isTextual() && CapturePrivacyPolicy.identifier(v.asText()) == null) {
            o.putNull(field);
        }
    }

    private static void title(ObjectNode o, String field) {
        JsonNode v = o.get(field);
        if (v != null && v.isTextual()) {
            String t = CapturePrivacyPolicy.title(v.asText());
            if (t == null) {
                o.putNull(field);
            } else {
                o.put(field, t);
            }
        }
    }

    private static void url(ObjectNode o, String field) {
        JsonNode v = o.get(field);
        if (v != null && v.isTextual()) {
            String s = CapturePrivacyPolicy.urlTemplate(v.asText());
            if (s == null) {
                o.putNull(field);
            } else {
                o.put(field, s);
            }
        }
    }
}
