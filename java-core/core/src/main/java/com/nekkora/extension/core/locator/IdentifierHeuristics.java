package com.nekkora.extension.core.locator;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Heurísticas de aparência para identificadores ({@code id}, {@code name}). O resultado é SEMPRE uma
 * inferência — nunca rejeita o identificador, apenas reduz sua estabilidade presumida. A estabilidade
 * comprovada vem da observação em múltiplas capturas.
 */
public final class IdentifierHeuristics {

    private static final Pattern UUID = Pattern.compile("[0-9a-fA-F]{8}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{12}");
    private static final Pattern LONG_DIGITS = Pattern.compile("\\d{4,}");
    private static final Pattern TIMESTAMP = Pattern.compile("(?<!\\d)(\\d{10}|\\d{13})(?!\\d)");
    private static final Pattern HEX_HASH = Pattern.compile("(?=[0-9a-fA-F]*\\d)(?=[0-9a-fA-F]*[a-fA-F])[0-9a-fA-F]{8,}");
    private static final Pattern MIXED_RANDOM = Pattern.compile("(?=[A-Za-z0-9]*\\d)(?=[A-Za-z0-9]*[A-Z])(?=[A-Za-z0-9]*[a-z])[A-Za-z0-9]{10,}");
    private static final Pattern FRAMEWORK = Pattern.compile(
            "^(:r[0-9a-z]+:|ember\\d+|mat-[a-z-]+-\\d+|react-select-\\d+.*|headlessui-.*\\d+|radix-.*|ng-\\d+|yui_.*|ext-gen\\d+|j_idt\\d+.*|__BVID__\\d+|input-\\d+|cdk-.*-\\d+|mui-\\d+|downshift-\\d+.*|el-id-\\d+.*)$");

    public record Assessment(StabilityLevel level, List<String> reasons) {
    }

    public Assessment assess(String value) {
        List<String> reasons = new ArrayList<>();
        if (value == null || value.isBlank()) {
            return new Assessment(StabilityLevel.UNKNOWN, List.of("identificador vazio"));
        }
        if (UUID.matcher(value).find()) {
            reasons.add("contém UUID");
        }
        if (TIMESTAMP.matcher(value).find()) {
            reasons.add("contém sequência com aparência de timestamp");
        } else if (LONG_DIGITS.matcher(value).find()) {
            reasons.add("contém sequência numérica longa (>= 4 dígitos)");
        }
        if (HEX_HASH.matcher(value).find() && !UUID.matcher(value).find()) {
            reasons.add("contém sequência hexadecimal com aparência de hash");
        }
        if (MIXED_RANDOM.matcher(value).find()) {
            reasons.add("contém sequência alfanumérica com aparência aleatória");
        }
        if (FRAMEWORK.matcher(value).matches()) {
            reasons.add("padrão de id gerado por framework");
        }
        if (reasons.isEmpty()) {
            return new Assessment(StabilityLevel.HEURISTIC_STABLE, List.of("sem indícios de geração dinâmica (inferência)"));
        }
        return new Assessment(StabilityLevel.HEURISTIC_UNSTABLE, reasons);
    }
}
