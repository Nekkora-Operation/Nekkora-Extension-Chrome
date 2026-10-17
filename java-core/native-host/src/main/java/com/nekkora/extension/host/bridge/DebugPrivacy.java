package com.nekkora.extension.host.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nekkora.extension.core.privacy.CapturePrivacyPolicy;

/**
 * STUDIO-009 (guided-debug/1) — barreira de privacidade das respostas e eventos do debug guiado, no host, antes do Studio.
 * Mesma política das etapas gravadas ({@code nekkora-privacy/2}); o host não confia no service worker:
 *
 * <ul>
 *   <li>{@code element} do evento {@code studio.debug.picked}: exatamente a barreira de uma etapa gravada
 *       ({@link StepPrivacy});</li>
 *   <li>resumo {@code element} e nomes dos candidatos de uma inspeção: só se o valor INTEIRO for do vocabulário; tag pelo
 *       catálogo; senão vazio/neutro;</li>
 *   <li>tudo o mais é código fechado, número ou identificador técnico — a validação estrita do schema
 *       ({@code additionalProperties: false}, enums) completa a barreira: frase livre não tem onde entrar.</li>
 * </ul>
 */
final class DebugPrivacy {

    private DebugPrivacy() {
    }

    static void reapply(String type, JsonNode payload) {
        if (!(payload instanceof ObjectNode p)) {
            return;
        }
        if ("studio.debug.picked".equals(type)) {
            StepPrivacy.reapply(p); // element = referência compacta de etapa: mesmas regras
            return;
        }
        if (p.path("element") instanceof ObjectNode e) {
            name(e, "name");
            tag(e);
        }
        for (JsonNode c : p.path("candidates")) {
            if (c instanceof ObjectNode o) {
                name(o, "name");
            }
        }
    }

    private static void name(ObjectNode o, String field) {
        JsonNode v = o.get(field);
        if (v != null && v.isTextual()) {
            String t = CapturePrivacyPolicy.interfaceText(v.asText());
            o.put(field, t == null ? "" : t);
        }
    }

    private static void tag(ObjectNode e) {
        if (e.path("tag").isTextual() && !"unknown".equals(e.path("tag").asText())) {
            String t = CapturePrivacyPolicy.tagName(e.path("tag").asText());
            e.put("tag", t == null ? "unknown" : t);
        }
    }
}
