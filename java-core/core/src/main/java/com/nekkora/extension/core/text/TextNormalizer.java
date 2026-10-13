package com.nekkora.extension.core.text;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Normalização de texto em camadas.
 *
 * <ul>
 *   <li>{@link #normalize}: só espaços/quebras/NFC. Preserva caixa, acentos e pontuação — é o valor
 *       armazenado na identidade.</li>
 *   <li>{@link #fold}: comparação tolerante (minúsculas, sem acentos, aspas/traços unificados).</li>
 *   <li>{@link #maskDigits}: tolerância a conteúdo dinâmico numérico ("Pesquisar (3)" ≈ "Pesquisar (12)").</li>
 * </ul>
 *
 * O valor original nunca é substituído pelo dobrado; o dobrado é apenas chave de comparação.
 */
public final class TextNormalizer {

    private static final Pattern WS = Pattern.compile("[\\s\\u00A0\\u2007\\u202F\\u200B\\uFEFF]+");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^\\p{L}\\p{N}#]+");

    private TextNormalizer() {
    }

    public static String normalize(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        String nfc = Normalizer.normalize(s, Normalizer.Form.NFC);
        return WS.matcher(nfc).replaceAll(" ").trim();
    }

    public static String fold(String s) {
        String n = normalize(s);
        if (n.isEmpty()) {
            return n;
        }
        String noMarks = MARKS.matcher(Normalizer.normalize(n, Normalizer.Form.NFD)).replaceAll("");
        return noMarks.toLowerCase(Locale.ROOT)
                .replace('‘', '\'').replace('’', '\'')
                .replace('“', '"').replace('”', '"')
                .replace('–', '-').replace('—', '-');
    }

    public static String maskDigits(String s) {
        return DIGITS.matcher(fold(s)).replaceAll("#");
    }

    public static boolean isBlank(String s) {
        return normalize(s).isEmpty();
    }

    /** Similaridade de Jaccard entre tokens dobrados, em [0,1]. */
    public static double similarity(String a, String b) {
        Set<String> ta = tokens(a);
        Set<String> tb = tokens(b);
        if (ta.isEmpty() && tb.isEmpty()) {
            return 1.0;
        }
        if (ta.isEmpty() || tb.isEmpty()) {
            return 0.0;
        }
        Set<String> inter = new HashSet<>(ta);
        inter.retainAll(tb);
        Set<String> union = new HashSet<>(ta);
        union.addAll(tb);
        return (double) inter.size() / union.size();
    }

    private static Set<String> tokens(String s) {
        String f = fold(s);
        if (f.isEmpty()) {
            return Set.of();
        }
        Set<String> out = new HashSet<>(Arrays.asList(TOKEN_SPLIT.split(f)));
        out.remove("");
        return out;
    }

    /** Compara um texto esperado com um observado e devolve o nível de concordância. */
    public static TextMatch compare(String expected, String actual) {
        String e = normalize(expected);
        String a = normalize(actual);
        if (e.isEmpty() && a.isEmpty()) {
            return TextMatch.BOTH_EMPTY;
        }
        if (e.isEmpty() || a.isEmpty()) {
            return TextMatch.NONE;
        }
        if (e.equals(a)) {
            return TextMatch.EXACT;
        }
        if (fold(e).equals(fold(a))) {
            return TextMatch.FOLDED;
        }
        if (maskDigits(e).equals(maskDigits(a))) {
            return TextMatch.DIGITS_VARIED;
        }
        if (similarity(e, a) >= 0.6) {
            return TextMatch.PARTIAL;
        }
        return TextMatch.NONE;
    }

    /** Recorta texto longo para uso em evidências e localizadores, sem quebrar palavras no meio quando possível. */
    public static String truncate(String s, int max) {
        String n = normalize(s);
        if (n.length() <= max) {
            return n;
        }
        int cut = n.lastIndexOf(' ', max);
        return (cut > max / 2 ? n.substring(0, cut) : n.substring(0, max)) + "…";
    }
}
