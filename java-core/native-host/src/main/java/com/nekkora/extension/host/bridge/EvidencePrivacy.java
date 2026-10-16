package com.nekkora.extension.host.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nekkora.extension.core.privacy.Privacy;

/**
 * Terceira barreira de privacidade das EVIDÊNCIAS observadas (STUDIO-005), no host, antes de sair para o Studio. O host
 * não confia na sanitização do content script nem na do service worker:
 * <ul>
 *   <li>METADADOS (revisão final do PR #6): região e campo só seguem se forem uma DESCRIÇÃO CONTROLADA do vocabulário
 *       ({@link #REGION_LABELS}, {@link #FIELD_LABELS}); o título do documento nunca segue — tudo isso é texto da página;</li>
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
    /** Descrições controladas de região (as mesmas da extensão e do Studio). */
    static final java.util.Set<String> REGION_LABELS = java.util.Set.of("mensagem de erro", "mensagem de status", "alerta", "região de validação",
            "indicador de carregamento", "região de conteúdo", "elemento acionado", "formulário");
    /** Descrições controladas do TIPO de campo (nunca o rótulo). */
    static final java.util.Set<String> FIELD_LABELS = java.util.Set.of("campo de texto", "campo de e-mail", "campo de senha", "campo de telefone",
            "campo numérico", "campo de pesquisa", "campo de endereço", "campo de data e hora", "caixa de seleção", "opção de escolha", "lista de seleção",
            "área de texto", "campo editável", "campo");

    private EvidencePrivacy() {
    }

    static void reapply(JsonNode payload) {
        if (!(payload instanceof ObjectNode p)) {
            return;
        }
        if (p.path("document") instanceof ObjectNode d) {
            if (d.has("title")) {
                d.putNull("title"); // texto da página: nunca segue
            }
            url(d, "url");
        }
        if (p.path("details") instanceof ObjectNode det) {
            controlled(det, "region", REGION_LABELS);
            controlled(det, "field", FIELD_LABELS);
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

    private static void controlled(ObjectNode o, String field, java.util.Set<String> allowed) {
        JsonNode v = o.get(field);
        if (v != null && !v.isNull() && !(v.isTextual() && allowed.contains(v.asText()))) {
            o.putNull(field); // fora do vocabulário = texto da página
        }
    }

    private static void url(ObjectNode o, String field) {
        JsonNode v = o.get(field);
        if (v != null && v.isTextual()) {
            String s = com.nekkora.extension.core.privacy.CapturePrivacyPolicy.urlTemplate(v.asText()); // CHROME-SECURITY-001: modelo
            if (s == null) {
                o.putNull(field);
            } else {
                o.put(field, s.length() > 2000 ? s.substring(0, 2000) : s);
            }
        }
    }
}
