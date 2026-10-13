package com.nekkora.extension.core.semantic;

import com.nekkora.extension.core.snapshot.RawNode;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Política determinística de dados sensíveis. Um elemento sensível nunca tem valor exportado — e o
 * valor também não é enviado pela extensão. Esta classe é a segunda barreira (defesa em profundidade).
 */
public final class SensitivityPolicy {

    private static final Pattern SENSITIVE_NAME = Pattern.compile(
            "(senha|password|passwd|pwd|secret|segredo|token|cpf|cnpj|rg\\b|ssn|cartao|cartão|card|cc-?num|cvv|cvc|pin\\b|otp|iban|conta)",
            Pattern.CASE_INSENSITIVE);

    public boolean isSensitive(RawNode node) {
        String type = node.attr("type");
        if (type != null && type.equalsIgnoreCase("password")) {
            return true;
        }
        String autocomplete = lower(node.attr("autocomplete"));
        if (autocomplete.contains("password") || autocomplete.startsWith("cc-") || autocomplete.contains(" cc-")
                || autocomplete.contains("one-time-code")) {
            return true;
        }
        if (Boolean.parseBoolean(node.attr("data-nekkora-sensitive"))) {
            return true;
        }
        return matches(node.attr("name")) || matches(node.attr("id")) || matches(node.attr("autocomplete"));
    }

    private static boolean matches(String s) {
        return s != null && SENSITIVE_NAME.matcher(s).find();
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
