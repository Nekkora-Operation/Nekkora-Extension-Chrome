package com.nekkora.extension.host.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nekkora.extension.core.privacy.Privacy;

/**
 * Terceira barreira de privacidade das etapas gravadas (STUDIO-003): antes de uma etapa sair do host para o Studio, todo
 * texto livre é reaplicado à política Java (mesma regra da extensão) e toda URL é sanitizada de novo. O host não confia
 * na sanitização do content script nem na do service worker. A validação estrita do schema (sem campos de valor,
 * {@code additionalProperties: false}) completa a barreira.
 */
final class StepPrivacy {

    private StepPrivacy() {
    }

    static void reapply(JsonNode payload) {
        if (!(payload instanceof ObjectNode p)) {
            return;
        }
        if (p.path("element") instanceof ObjectNode e) {
            mask(e, "name");
            if (e.path("sensitive").asBoolean(false)) {
                e.putNull("htmlId");
                e.putNull("htmlName");
            } else {
                attr(e, "htmlId");
                attr(e, "htmlName");
            }
            for (JsonNode s : e.path("accessPath")) {
                if (s instanceof ObjectNode o) {
                    mask(o, "label");
                }
            }
            for (JsonNode c : e.path("context")) {
                if (c instanceof ObjectNode o) {
                    mask(o, "name");
                }
            }
            for (JsonNode l : e.path("locators")) {
                if (l instanceof ObjectNode o) {
                    mask(o, "description");
                }
            }
        }
        if (p.path("parameters").path("option") instanceof ObjectNode o) {
            mask(o, "label");
            mask(o, "value");
        }
        if (p.path("context") instanceof ObjectNode c) {
            url(c, "url");
            mask(c, "title");
            if (c.path("after") instanceof ObjectNode a) {
                url(a, "url");
            }
        }
    }

    private static void mask(ObjectNode o, String field) {
        JsonNode v = o.get(field);
        if (v != null && v.isTextual()) {
            o.put(field, Privacy.maskText(v.asText()));
        }
    }

    private static void attr(ObjectNode o, String field) {
        JsonNode v = o.get(field);
        if (v != null && v.isTextual() && (Privacy.looksSecret(v.asText()) || !Privacy.maskText(v.asText()).equals(v.asText()))) {
            o.putNull(field); // id/name com aparência de segredo ou com dado pessoal: não sai
        }
    }

    private static void url(ObjectNode o, String field) {
        JsonNode v = o.get(field);
        if (v != null && v.isTextual()) {
            String s = Privacy.sanitizeUrl(v.asText());
            if (s == null) {
                o.putNull(field);
            } else {
                o.put(field, s.length() > 2000 ? s.substring(0, 2000) : s);
            }
        }
    }
}
