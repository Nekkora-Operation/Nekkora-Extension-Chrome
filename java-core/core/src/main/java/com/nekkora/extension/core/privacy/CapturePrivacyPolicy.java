package com.nekkora.extension.core.privacy;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CHROME-SECURITY-001 — política de captura {@code nekkora-privacy/2}. Espelho exato de
 * {@code chrome-extension/src/shared/capture-policy.js}, verificado pelos vetores
 * {@code contracts/privacy/privacy-v2-vectors.json}. É a 3ª barreira (Native Host e motor): reaplica no que chega a
 * mesma regra da página, mesmo que o produtor seja uma extensão antiga.
 *
 * <p>Três categorias: <b>estrutural</b> (tag, papel, estado, posição, hierarquia, atributos de vocabulário fechado) —
 * permitida; <b>texto da página</b> — só atravessa o METADADO DE INTERFACE elegível (nome de controle, rótulo,
 * identificador do desenvolvedor, segmento de URL, título da aba) cujos termos TODOS pertençam ao vocabulário
 * controlado ({@code nekkora-ui-vocabulary}); <b>valor do usuário</b> — nunca. Conteúdo de negócio, tabelas, mensagens
 * e texto livre não atravessam mesmo quando feitos de palavras do vocabulário: a elegibilidade é estrutural.
 * Não há detecção de dado pessoal, supressão parcial, expansão automática nem exceção por cliente.
 */
public final class CapturePrivacyPolicy {

    public static final String VERSION = "nekkora-privacy/2";
    /** Marcador de valor retido: houve conteúdo/mudança, mas o valor não sai. Não é recuperável nem derivado do valor. */
    public static final String WITHHELD = "[omitido]";
    /** Lacuna de um modelo de URL (segmento ou valor que não sai). */
    public static final String HOLE = "{\u2026}";
    public static final int MAX_LEN = 80;
    private static final int MAX_TERMS = 8;
    public static final String VOCABULARY_RESOURCE = "contracts/privacy/ui-vocabulary.json";

    private static final Pattern SPACES = Pattern.compile(
            "[\\t\\n\\x0B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]+");
    private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9\\u00C0-\\u00D6\\u00D8-\\u00F6\\u00F8-\\u00FF \\-_.:/()',!?]+$");
    private static final Pattern IDENT = Pattern.compile("^[A-Za-z0-9\\-_:.]{1,80}$");
    private static final Pattern CAMEL = Pattern.compile("([a-z\\u00DF-\\u00F6\\u00F8-\\u00FF])([A-Z\\u00C0-\\u00D6\\u00D8-\\u00DE])");
    private static final Pattern LETTER_DIGIT = Pattern.compile("([A-Za-z\\u00C0-\\u00D6\\u00D8-\\u00F6\\u00F8-\\u00FF])([0-9])");
    private static final Pattern DIGIT_LETTER = Pattern.compile("([0-9])([A-Za-z\\u00C0-\\u00D6\\u00D8-\\u00F6\\u00F8-\\u00FF])");
    private static final Pattern SPLIT = Pattern.compile("[^A-Za-z0-9\\u00C0-\\u00D6\\u00D8-\\u00F6\\u00F8-\\u00FF]+");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern SMALL_NUMBER = Pattern.compile("\\d{1,2}");

    /**
     * Atributos cujos valores pertencem a um vocabulário fechado ou são numéricos (estado, tipo, papel): o valor fica
     * se tiver forma técnica. O VALOR de qualquer outro atributo segue a regra da sua categoria.
     */
    public static final Set<String> STRUCTURAL_ATTRIBUTES = Set.of("type", "role", "tabindex", "disabled", "checked", "readonly",
            "required", "hidden", "open", "multiple", "selected", "contenteditable", "autocomplete", "inputmode", "method", "target",
            "scope", "colspan", "rowspan", "draggable", "spellcheck", "dir", "lang", "popover", "maxlength", "minlength", "min", "max",
            "step", "size", "rows", "cols", "aria-expanded", "aria-pressed", "aria-checked", "aria-selected", "aria-disabled",
            "aria-hidden", "aria-invalid", "aria-required", "aria-readonly", "aria-busy", "aria-modal", "aria-haspopup", "aria-live",
            "aria-current", "aria-level", "aria-multiselectable", "aria-orientation", "aria-sort", "aria-autocomplete", "aria-atomic",
            "aria-relevant", "aria-valuenow", "aria-valuemin", "aria-valuemax", "aria-colcount", "aria-rowcount", "aria-colindex",
            "aria-rowindex", "aria-posinset", "aria-setsize", "sandbox", "loading", "referrerpolicy", "crossorigin", "charset", "rel", "accept", "enctype", "novalidate", "autofocus", "wrap", "width", "height", "translate", "autocapitalize", "enterkeyhint", "decoding", "fetchpriority", "async", "defer", "nomodule", "frameborder", "scrolling", "allowfullscreen", "allow", "controls", "autoplay", "loop", "muted", "playsinline", "preload", "reversed", "start", "span", "shape", "kind", "hreflang", "srclang");
    private static final Pattern STRUCTURAL_VALUE = Pattern.compile("^[A-Za-z0-9\\-_. ]{0,80}$");
    /** Identificadores escritos pelo desenvolvedor. */
    public static final Set<String> IDENTIFIERS = Set.of("id", "name", "data-testid", "data-test", "data-test-id", "data-qa", "data-cy", "slot",
            "part", "is");
    /** Referências entre elementos: o valor (ids) não sai; a relação vai como id temporário de nó ({@code refs}). */
    public static final Set<String> IDREFS = Set.of("for", "aria-labelledby", "aria-describedby", "aria-controls", "aria-owns",
            "aria-activedescendant", "aria-errormessage", "aria-details", "aria-flowto", "list", "form", "headers");
    /** Atributos de NOME (metadado de interface). */
    public static final Set<String> NAMING = Set.of("aria-label", "title", "placeholder", "alt", "aria-roledescription", "aria-placeholder");
    /** Texto descritivo ou valor: nunca. */
    public static final Set<String> DESCRIPTIVE = Set.of("aria-description", "aria-valuetext", "label");
    public static final Set<String> URLS = Set.of("href", "src", "action", "formaction", "poster", "data", "cite", "background", "longdesc",
            "srcset", "imagesrcset", "ping", "manifest", "codebase", "archive", "usemap", "xlink:href");
    /** Só a PRESENÇA (o valor é conteúdo da página: HTML de srcdoc, nome de arquivo de download). */
    public static final Set<String> PRESENCE = Set.of("srcdoc", "download");
    /** Elementos cujo src é a URL de um documento/recurso analisado (frame, script): vai como MODELO (D3). */
    private static final Set<String> URL_HOSTS = Set.of("iframe", "frame", "script");
    private static final Set<String> BUTTON_TYPES = Set.of("submit", "button", "reset");
    private static final Set<String> CARRIER_TAGS = Set.of("button", "summary", "label", "legend");
    private static final Set<String> CARRIER_ROLES = Set.of("button", "link", "tab", "menuitem", "menuitemcheckbox", "menuitemradio",
            "checkbox", "radio", "switch", "treeitem");

    private static final Set<String> VOCABULARY = loadVocabulary();
    private static final String VOCABULARY_VERSION = readVersion();

    private CapturePrivacyPolicy() {
    }

    // ------------------------------------------------------------------------------------------ vocabulário

    private static String readVocabulary() {
        try (InputStream in = CapturePrivacyPolicy.class.getClassLoader().getResourceAsStream(VOCABULARY_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("vocabulário controlado ausente: " + VOCABULARY_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A lista é lida uma vez e é imutável: não há expansão em execução. */
    private static Set<String> loadVocabulary() {
        String json = readVocabulary();
        int start = json.indexOf("\"terms\"");
        int open = json.indexOf('[', start);
        int close = json.indexOf(']', open);
        if (start < 0 || open < 0 || close < 0) {
            throw new IllegalStateException("vocabulário controlado ilegível");
        }
        Set<String> out = new java.util.HashSet<>();
        Matcher m = Pattern.compile("\"([^\"]+)\"").matcher(json.substring(open, close));
        while (m.find()) {
            out.add(m.group(1));
        }
        return Collections.unmodifiableSet(out);
    }

    private static String readVersion() {
        Matcher m = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"").matcher(readVocabulary());
        return m.find() ? m.group(1) : "?";
    }

    public static String vocabularyVersion() {
        return VOCABULARY_VERSION;
    }

    public static int vocabularySize() {
        return VOCABULARY.size();
    }

    // ------------------------------------------------------------------------------------------ texto e identificadores

    /** NFC + espaços colapsados (inclusive os Unicode). */
    public static String clean(String v) {
        String s = SPACES.matcher(Normalizer.normalize(v, Normalizer.Form.NFC)).replaceAll(" ");
        // só espaços nas pontas (String.trim() também removeria controles como U+0000, que precisam RECUSAR o valor)
        int a = 0;
        int b = s.length();
        while (a < b && s.charAt(a) == ' ') {
            a++;
        }
        while (b > a && s.charAt(b - 1) == ' ') {
            b--;
        }
        return s.substring(a, b);
    }

    private static String fold(String t) {
        return MARKS.matcher(Normalizer.normalize(t, Normalizer.Form.NFD)).replaceAll("").toLowerCase(Locale.ROOT);
    }

    static List<String> terms(String s) {
        String spaced = DIGIT_LETTER.matcher(LETTER_DIGIT.matcher(CAMEL.matcher(s).replaceAll("$1 $2")).replaceAll("$1 $2")).replaceAll("$1 $2");
        List<String> out = new ArrayList<>();
        for (String t : SPLIT.split(spaced)) {
            if (!t.isEmpty()) {
                out.add(fold(t));
            }
        }
        return out;
    }

    /** O valor inteiro é metadado de interface do vocabulário controlado? */
    public static boolean vocabularyOnly(String v) {
        if (v == null) {
            return false;
        }
        String s = clean(v);
        if (s.isEmpty() || s.length() > MAX_LEN || !SAFE.matcher(s).matches()) {
            return false;
        }
        List<String> t = terms(s);
        if (t.isEmpty() || t.size() > MAX_TERMS) {
            return false;
        }
        for (String x : t) {
            if (!SMALL_NUMBER.matcher(x).matches() && !VOCABULARY.contains(x)) {
                return false;
            }
        }
        return true;
    }

    /** Texto de interface transportável (forma limpa) ou {@code null}. */
    public static String interfaceText(String v) {
        return vocabularyOnly(v) ? clean(v) : null;
    }

    /** Título da aba/documento (decisão D4): só se passar no vocabulário. */
    public static String title(String v) {
        return interfaceText(v);
    }

    /** Identificador do desenvolvedor (id, name, atributo de teste): ASCII técnico E termos do vocabulário; ou {@code null}. */
    public static String identifier(String v) {
        return v != null && IDENT.matcher(v).matches() && vocabularyOnly(v) ? v : null;
    }

    /** Classes CSS: cada classe é um identificador independente; ficam só as que passam. */
    public static String classes(String v) {
        if (v == null) {
            return null;
        }
        List<String> kept = new ArrayList<>();
        for (String c : v.split("[\\t\\n\\f\\r ]+")) {
            if (!c.isEmpty() && identifier(c) != null) {
                kept.add(c);
            }
        }
        return kept.isEmpty() ? null : String.join(" ", kept);
    }

    // ------------------------------------------------------------------------------------------ URLs (decisão D3)

    private static final Pattern ABS = Pattern.compile("^(https?)://([^/?#]*)([^?#]*)(\\?[^#]*)?(#.*)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NET = Pattern.compile("^//([^/?#]*)([^?#]*)(\\?[^#]*)?(#.*)?$");
    private static final Pattern REL = Pattern.compile("^([^?#]*)(\\?[^#]*)?(#.*)?$");
    private static final Pattern SCHEME = Pattern.compile("^([a-zA-Z][a-zA-Z0-9+.-]*):");

    /** Lacuna codificada para {@link java.net.URI} (que recusa chaves). */
    private static final String HOLE_ENCODED = "%7B%E2%80%A6%7D";

    /** URL (possivelmente um modelo com lacunas) pronta para {@code URI.create}. */
    public static String forUri(String url) {
        return url == null ? null : url.replace(" ", "%20").replace(HOLE, HOLE_ENCODED);
    }

    /** Desfaz {@link #forUri} num pedaço de URL obtido de {@code URI}. */
    public static String fromUri(String part) {
        return part == null ? null : part.replace(HOLE_ENCODED, HOLE);
    }

    private static String safeDecode(String s) {
        try {
            return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String templatePath(String p) {
        if (p == null || p.isEmpty()) {
            return "";
        }
        String[] segs = p.split("/", -1);
        List<String> out = new ArrayList<>(segs.length);
        for (String seg : segs) {
            if (seg.isEmpty()) {
                out.add("");
                continue;
            }
            String d = safeDecode(seg);
            out.add(d != null && !d.contains("/") && vocabularyOnly(d) ? seg : HOLE);
        }
        return String.join("/", out);
    }

    private static String templateQuery(String q) {
        if (q == null || q.isEmpty() || q.equals("?")) {
            return "";
        }
        List<String> kept = new ArrayList<>();
        for (String pair : q.substring(1).split("&", -1)) {
            int eq = pair.indexOf('=');
            String k = safeDecode(eq < 0 ? pair : pair.substring(0, eq));
            if (k != null && !k.isEmpty() && identifier(k) != null && !kept.contains(k)) {
                kept.add(k);
            }
        }
        if (kept.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("?");
        for (int i = 0; i < kept.size(); i++) {
            sb.append(i == 0 ? "" : "&").append(kept.get(i)).append('=').append(HOLE);
        }
        return sb.toString();
    }

    private static String authority(String a) {
        int at = a.lastIndexOf('@');
        return (at >= 0 ? a.substring(at + 1) : a).toLowerCase(Locale.ROOT);
    }

    private static String cap(String s) {
        return s.length() > 2000 ? s.substring(0, 2000) : s;
    }

    /**
     * URL mínima: origem + MODELO do caminho (segmento do vocabulário fica, os demais viram {@link #HOLE}); da consulta só
     * os NOMES de parâmetros do vocabulário (valor sempre lacuna); fragmento nunca. Não executável. Outros esquemas: só o
     * esquema.
     */
    public static String urlTemplate(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.equals("about:blank") || lower.equals("about:srcdoc")) {
            return lower;
        }
        Matcher m = ABS.matcher(s);
        if (m.matches()) {
            return cap(m.group(1).toLowerCase(Locale.ROOT) + "://" + authority(m.group(2)) + templatePath(m.group(3)) + templateQuery(m.group(4)));
        }
        Matcher sc = SCHEME.matcher(s);
        if (sc.find()) {
            return sc.group(1).toLowerCase(Locale.ROOT) + ":" + HOLE;
        }
        m = NET.matcher(s);
        if (m.matches()) {
            return cap("//" + authority(m.group(1)) + templatePath(m.group(2)) + templateQuery(m.group(3)));
        }
        m = REL.matcher(s);
        if (!m.matches()) {
            return null;
        }
        String out = templatePath(m.group(1)) + templateQuery(m.group(2));
        return out.isEmpty() ? null : cap(out);
    }

    // ------------------------------------------------------------------------------------------ expressões de localizador

    private static final Pattern QUOTED = Pattern.compile("'([^']*)'|\"([^\"]*)\"");
    private static final Pattern CSS_TOKEN = Pattern.compile("[#.](-?[A-Za-z_][A-Za-z0-9_-]*)");
    private static final Pattern PAIR = Pattern.compile("\\b[A-Za-z][A-Za-z0-9-]*=([^\\s,}\\]\"'()/\\[][^\\s,}\\]\"'()]*)");
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    private static boolean literalOk(String v) {
        return v.isEmpty() || DIGITS.matcher(v).matches() || v.equals(HOLE) || vocabularyOnly(v);
    }

    /** Uma expressão de localizador (CSS, XPath ou descrição) só é transportável se TODO literal passar na política. */
    public static boolean expressionSafe(String expr) {
        if (expr == null || expr.isEmpty()) {
            return true;
        }
        List<String> lits = new ArrayList<>();
        Matcher m = QUOTED.matcher(expr);
        while (m.find()) {
            lits.add(m.group(1) != null ? m.group(1) : m.group(2));
        }
        String unquoted = QUOTED.matcher(expr).replaceAll(" ");
        m = CSS_TOKEN.matcher(unquoted);
        while (m.find()) {
            lits.add(m.group(1));
        }
        m = PAIR.matcher(unquoted);
        while (m.find()) {
            lits.add(m.group(1));
        }
        for (String l : lits) {
            if (!literalOk(l)) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------------------------------ atributos e portadores

    /**
     * Valor transportável de um atributo: string (valor, ou {@code ""} = só a presença) ou {@code null} (não sai).
     */
    public static String attribute(String name, String value, String tag, String type) {
        String n = name.toLowerCase(Locale.ROOT);
        String v = value == null ? "" : value;
        if (n.equals("style")) {
            return null;
        }
        if (n.startsWith("on")) {
            return "[manipulador declarado]";
        }
        if (STRUCTURAL_ATTRIBUTES.contains(n)) {
            return STRUCTURAL_VALUE.matcher(v).matches() ? v : "";
        }
        if (IDENTIFIERS.contains(n)) {
            return identifier(v);
        }
        if (n.equals("class")) {
            return classes(v);
        }
        if (n.equals("src") && tag != null && URL_HOSTS.contains(tag.toLowerCase(Locale.ROOT))) {
            String t = urlTemplate(v);
            return t == null ? "" : t;
        }
        if (IDREFS.contains(n) || URLS.contains(n) || PRESENCE.contains(n)) {
            return "";
        }
        if (NAMING.contains(n)) {
            // retido: a PRESENÇA da fonte de nome fica marcada (HOLE), para o motor não calcular o nome por uma fonte de
            // prioridade menor; vazio continua vazio (alt="" decorativo)
            String t = interfaceText(v);
            return t != null ? t : v.isBlank() ? "" : HOLE;
        }
        if (n.equals("value")) {
            String t = tag == null ? "" : tag.toLowerCase(Locale.ROOT);
            String ty = type == null ? "" : type.toLowerCase(Locale.ROOT);
            if (!t.equals("input") || !BUTTON_TYPES.contains(ty)) {
                return null;
            }
            String text = interfaceText(v);
            return text != null ? text : v.isBlank() ? "" : HOLE; // retido: presença marcada (senão o nome viraria "Submit")
        }
        if (DESCRIPTIVE.contains(n)) {
            return null;
        }
        if (n.equals("data-sitekey")) {
            return "[redacted]";
        }
        return identifier(n) != null ? "" : null;
    }

    /** Todos os atributos de um elemento pela política (ordem preservada; os que não saem são omitidos). */
    public static Map<String, String> attributes(Map<String, String> attrs, String tag) {
        if (attrs == null || attrs.isEmpty()) {
            return Map.of();
        }
        String type = attrs.get("type");
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : attrs.entrySet()) {
            if (e.getKey() == null) {
                continue;
            }
            String v = e.getKey().equals("data-nekkora-simulated-src") ? urlTemplate(e.getValue())
                    : attribute(e.getKey(), e.getValue(), tag, type);
            if (v != null) {
                out.put(e.getKey(), v);
            }
        }
        return Collections.unmodifiableMap(out);
    }

    /** Elemento que CARREGA nome de interface: o texto dentro dele pode ser metadado elegível. */
    public static boolean isCarrier(String tag, String role, boolean hasHref) {
        String t = tag == null ? "" : tag.toLowerCase(Locale.ROOT);
        if (CARRIER_TAGS.contains(t) || t.equals("a") && hasHref) {
            return true;
        }
        String r = role == null ? "" : role.trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
        return CARRIER_ROLES.contains(r);
    }

    // ------------------------------------------------------------------------------------------ mudanças (fase 1)

    public static boolean structural(String attribute) {
        return attribute != null && STRUCTURAL_ATTRIBUTES.contains(attribute.toLowerCase(Locale.ROOT));
    }

    /** Texto (antes/depois) de uma mudança: o fato fica, o conteúdo não. */
    public static String withheldText(String text) {
        return text == null ? null : WITHHELD;
    }

    /**
     * Mudanças de atributos {@code nome → [antes, depois]}: o NOME do atributo fica; o valor só fica quando o atributo é
     * estrutural ({@code aria-expanded: false → true}); senão vira {@link #WITHHELD} (ausência continua {@code null}).
     */
    public static Map<String, List<String>> attributeChanges(Map<String, List<String>> changes) {
        if (changes == null || changes.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : changes.entrySet()) {
            List<String> pair = e.getValue() == null ? List.of() : e.getValue();
            if (structural(e.getKey())) {
                out.put(e.getKey(), Collections.unmodifiableList(new ArrayList<>(pair)));
                continue;
            }
            List<String> w = new ArrayList<>(pair.size());
            for (String v : pair) {
                w.add(v == null ? null : WITHHELD);
            }
            out.put(e.getKey(), Collections.unmodifiableList(w));
        }
        return Collections.unmodifiableMap(out);
    }
}
