package com.nekkora.extension.host.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nekkora.extension.core.privacy.Privacy;

/**
 * Terceira barreira de privacidade das EVIDÊNCIAS observadas (STUDIO-005), no host, antes de sair para o Studio. O host
 * não confia na sanitização do content script nem na do service worker:
 * <ul>
 *   <li>título, região e campo passam pela política Java ({@link Privacy#maskText});</li>
 *   <li>URLs são sanitizadas de novo;</li>
 *   <li>MODO ESTRITO (revisão final do PR #6): o texto livre de uma mensagem da página NUNCA segue — havendo texto, ele
 *       é removido (availability=SUPPRESSED, text=null) com a limitação MESSAGE_SUPPRESSED, qualquer que seja o conteúdo.
 *       A evidência continua dizendo que houve mensagem (tipo, classificação, sinais, região segura).</li>
 * </ul>
 * A validação estrita do schema ({@code additionalProperties: false}, enums fechados) completa a barreira: HTML, valores
 * de campos e PageMaps não têm onde entrar.
 */
final class EvidencePrivacy {
    static final int MESSAGE_MAX = 160;

    private EvidencePrivacy() {
    }

    static void reapply(JsonNode payload) {
        if (!(payload instanceof ObjectNode p)) {
            return;
        }
        if (p.path("document") instanceof ObjectNode d) {
            mask(d, "title");
            url(d, "url");
        }
        if (p.path("details") instanceof ObjectNode det) {
            mask(det, "region");
            mask(det, "field");
            if (det.path("navigation") instanceof ObjectNode n) {
                url(n, "url");
            }
            if (det.path("message") instanceof ObjectNode m) {
                JsonNode t = m.get("text");
                if (t != null && !t.isNull()) { // modo estrito: nenhum texto de mensagem atravessa o host
                    m.putNull("text");
                    m.put("availability", "SUPPRESSED");
                    if (p.path("limitations") instanceof ArrayNode l && !contains(l, "MESSAGE_SUPPRESSED") && l.size() < 8) {
                        l.add("MESSAGE_SUPPRESSED");
                    }
                }
            }
        }
    }

    private static boolean contains(ArrayNode a, String v) {
        for (JsonNode n : a) {
            if (v.equals(n.asText())) {
                return true;
            }
        }
        return false;
    }

    private static void mask(ObjectNode o, String field) {
        JsonNode v = o.get(field);
        if (v != null && v.isTextual()) {
            o.put(field, Privacy.maskText(v.asText()));
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
