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
    public static final String STRUCTURAL_RESOURCE = "contracts/privacy/structural-attributes.json";
    /**
     * Revisão dos PRs #7, P1-01: regra FECHADA por atributo estrutural (fonte única {@value #STRUCTURAL_RESOURCE}, a mesma
     * do JS). A forma técnica do valor não basta — role="cliente-joao" ou autocomplete="joao oliveira" vão só como presença.
     */
    private static final String STRUCTURAL_JSON = readResource(STRUCTURAL_RESOURCE);
    private static final Map<String, String> STRUCTURAL_RULES = structuralRules(STRUCTURAL_JSON);
    private static final List<String> LANGUAGES = jsonStrings(STRUCTURAL_JSON, "languages");
    private static final List<String> SCRIPTS = jsonStrings(STRUCTURAL_JSON, "scripts");
    public static final Set<String> STRUCTURAL_ATTRIBUTES = STRUCTURAL_RULES.keySet();
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
        return readResource(VOCABULARY_RESOURCE);
    }

    private static String readResource(String name) {
        try (InputStream in = CapturePrivacyPolicy.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("contrato da política ausente: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, String> structuralRules(String json) {
        int start = json.indexOf("\"rules\"");
        int open = json.indexOf('{', start);
        int close = json.indexOf('}', open);
        if (start < 0 || open < 0 || close < 0) {
            throw new IllegalStateException("regras dos atributos estruturais ilegíveis");
        }
        Map<String, String> out = new java.util.LinkedHashMap<>();
        Matcher m = Pattern.compile("\"([a-z-]+)\"\\s*:\\s*\"([^\"]*)\"").matcher(json.substring(open, close));
        while (m.find()) {
            out.put(m.group(1), m.group(2));
        }
        return Collections.unmodifiableMap(out);
    }

    private static List<String> jsonStrings(String json, String key) {
        int start = json.indexOf("\"" + key + "\"");
        int open = json.indexOf('[', start);
        int close = json.indexOf(']', open);
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("\"([^\"]+)\"").matcher(json.substring(open, close));
        while (m.find()) {
            out.add(m.group(1));
        }
        return List.copyOf(out);
    }

    // ------------------------------------------------------------------------------------------ atributos estruturais

    private static boolean inList(String list, String value) {
        for (String w : list.split(" ")) {
            if (w.equals(value) || w.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean langOk(String s) {
        String[] parts = s.split("-", -1);
        if (parts.length > 3 || !LANGUAGES.contains(parts[0].toLowerCase(Locale.ROOT))) {
            return false;
        }
        int k = 1;
        if (k < parts.length) {
            String p = parts[k];
            if (SCRIPTS.stream().anyMatch(x -> x.equalsIgnoreCase(p))) {
                k++;
            }
        }
        if (k < parts.length && parts[k].matches("[A-Za-z]{2}|\\d{3}")) {
            k++;
        }
        return k == parts.length;
    }

    private static boolean ruleOk(String kind, String arg, String s, String name) {
        return switch (kind) {
            case "boolean" -> s.isEmpty() || s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false") || s.equalsIgnoreCase(name);
            case "enum" -> !s.isEmpty() && inList(arg, s);
            case "tokens" -> {
                String[] t = s.trim().split("[\\s,]+");
                boolean any = false;
                boolean all = true;
                for (String x : t) {
                    if (x.isEmpty()) {
                        continue;
                    }
                    any = true;
                    all &= inList(arg, x);
                }
                yield any && all;
            }
            case "integer" -> s.matches("-?\\d{1," + Integer.parseInt(arg) + "}");
            case "decimal" -> s.matches("-?\\d{1," + Integer.parseInt(arg) + "}(\\.\\d{1,4})?");
            case "dimension" -> s.matches("\\d{1," + Integer.parseInt(arg) + "}(px|%)?");
            case "lang" -> langOk(s);
            default -> false; // presence e regra desconhecida: nunca o valor
        };
    }

    /** Valor de atributo estrutural pela regra fechada do atributo; fora dela (ou atributo não estrutural), "". */
    public static String structuralValueOf(String name, String value) {
        return structuralValue(name, value);
    }

    /** Literal de localizador (valor, não posição) aceito pela política: vazio, até 2 dígitos, {…} ou vocabulário. */
    public static boolean literalSafe(String v) {
        return v != null && literalOk(v);
    }

    /** Valor de atributo estrutural pela regra fechada do atributo; fora dela, só a presença (""). */
    static String structuralValue(String name, String value) {
        String rule = STRUCTURAL_RULES.get(name);
        String s = value == null ? "" : value.trim();
        if (rule == null || s.length() > MAX_LEN * 4) {
            return "";
        }
        for (String alt : rule.split(";")) {
            int i = alt.indexOf(':');
            if (ruleOk(i < 0 ? alt : alt.substring(0, i), i < 0 ? "" : alt.substring(i + 1), s, name)) {
                return s;
            }
        }
        return "";
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

    /** NEKKORA-STUDIO-012: atributos com identificador ESCRITO PELO DESENVOLVEDOR (lista técnica aplicável). */
    public static final Set<String> DEV_IDENTIFIERS = Set.of("id", "name", "data-testid", "data-test", "data-test-id", "data-qa", "data-cy");
    public static final String IDENTIFIER_TERMS_RESOURCE = "contracts/privacy/identifier-terms.json";
    private static final Set<String> IDENTIFIER_TERMS = Set.copyOf(jsonStrings(readResource(IDENTIFIER_TERMS_RESOURCE), "terms"));

    /**
     * NEKKORA-STUDIO-012 ({@code nekkora-identifier-terms/1}, aprovado pela usuária): id/name/data-testid do desenvolvedor.
     * Passa como em {@link #identifier(String)} ou, além disso, quando cada termo é do vocabulário, número de até 2 dígitos
     * ou termo técnico da lista fechada (ds, txt, hdn, temp…), com PELO MENOS UM termo do vocabulário. Sem supressão parcial.
     */
    public static String developerIdentifier(String v) {
        String plain = identifier(v);
        if (plain != null || v == null || !IDENT.matcher(v).matches()) {
            return plain;
        }
        List<String> t = terms(v);
        if (t.isEmpty() || t.size() > MAX_TERMS) {
            return null;
        }
        boolean word = false;
        for (String x : t) {
            if (VOCABULARY.contains(x)) {
                word = true;
            } else if (!SMALL_NUMBER.matcher(x).matches() && !IDENTIFIER_TERMS.contains(x)) {
                return null;
            }
        }
        return word ? v : null;
    }

    /** Valor na posição de identificador do desenvolvedor num localizador (#id, [name=…], @id, {id=…}). */
    private static boolean developerLiteralOk(String v) {
        return literalOk(v) || developerIdentifier(v) != null;
    }

    public static int identifierTermsSize() {
        return IDENTIFIER_TERMS.size();
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

    /** P1-02: número como VALOR (literal) só até 2 dígitos; posições (nth-of-type(12), [12], {nth-of-type 12}) não são literais. */
    private static final Pattern DIGITS = Pattern.compile("\\d{1,2}");

    private static boolean literalOk(String v) {
        return v.isEmpty() || DIGITS.matcher(v).matches() || v.equals(HOLE) || vocabularyOnly(v);
    }

    // ------------------------------------------------------------------------------ nomes de elemento (2ª revisão dos PRs #7)

    public static final String ELEMENTS_RESOURCE = "contracts/privacy/html-elements.json";
    /** Marcador neutro de um nome de elemento retido (nunca é o nome real e nunca entra em localizador). */
    public static final String NEUTRAL_TAG = "custom-element";
    private static final Set<String> ELEMENTS = Set.copyOf(jsonStrings(readResource(ELEMENTS_RESOURCE), "elements"));
    private static final Pattern CUSTOM_TAG = Pattern.compile("[a-z][a-z0-9._-]*-[a-z0-9._-]*");

    private static String asciiLower(String v) {
        StringBuilder sb = new StringBuilder(v.length());
        for (int k = 0; k < v.length(); k++) {
            char c = v.charAt(k);
            sb.append(c >= 'A' && c <= 'Z' ? (char) (c + 32) : c);
        }
        return sb.toString();
    }

    /**
     * Nome de elemento transportável. O nome de um componente é escolhido pelo desenvolvedor e pode carregar dado de
     * negócio ({@code <ficha-cliente-joao>}): só atravessa se for do catálogo fechado HTML/SVG/MathML (contrato
     * {@value #ELEMENTS_RESOURCE}) ou se o nome personalizado INTEIRO for identificador do vocabulário (como id e classe).
     * Senão vai o marcador neutro {@value #NEUTRAL_TAG}: o motor sabe que há um elemento, sem o nome.
     */
    public static String tagName(String raw) {
        if (raw == null) {
            return null;
        }
        String s = asciiLower(raw);
        if (s.isEmpty()) {
            return null;
        }
        if (!s.equals(NEUTRAL_TAG) && (ELEMENTS.contains(s) || (CUSTOM_TAG.matcher(s).matches() && identifier(s) != null))) {
            return s;
        }
        return NEUTRAL_TAG;
    }

    /** O nome foi retido pela política (marcador neutro): não existe na página com esse nome. */
    public static boolean tagWithheld(String tag) {
        return NEUTRAL_TAG.equals(tag);
    }

    /** Nome de elemento aceito DENTRO de um localizador: "*", ou um nome que a política mantém (nunca o marcador neutro). */
    public static boolean tagSafe(String t) {
        if (t == null) {
            return false;
        }
        String s = asciiLower(t);
        return t.equals("*") || (!s.equals(NEUTRAL_TAG) && s.equals(tagName(s)));
    }

    private static final Pattern ATTR_NAME = Pattern.compile("[a-z_:][a-z0-9_:.-]*");
    private static final Set<String> KNOWN_ATTRIBUTES = Set.of("class", "value", "style", "data-sitekey");

    /** Nome de atributo aceito num localizador: nome que a política conhece, ou personalizado inteiro do vocabulário. */
    public static boolean attributeNameSafe(String name) {
        if (name == null) {
            return false;
        }
        String s = asciiLower(name);
        if (s.length() > MAX_LEN || !ATTR_NAME.matcher(s).matches()) {
            return false;
        }
        return STRUCTURAL_ATTRIBUTES.contains(s) || IDENTIFIERS.contains(s) || IDREFS.contains(s) || NAMING.contains(s)
                || DESCRIPTIVE.contains(s) || URLS.contains(s) || PRESENCE.contains(s) || KNOWN_ATTRIBUTES.contains(s)
                || identifier(s) != null;
    }

    // ------------------------------------------------------------------------------ expressões (2ª revisão dos PRs #7)
    /*
     * Análise ESTRITA da expressão pronta (CSS, XPath ou notação semântica). As barreiras que só recebem a expressão
     * serializada (service worker, host, Studio) não podem depender de o produtor ter gerado o localizador certo: a
     * expressão inteira precisa ser reconhecida pela gramática, e todo nome de elemento, nome de atributo e literal
     * (depois de decodificar os escapes) precisa passar na política. O que não for analisável é recusado inteiro.
     */
    private static final int MAX_EXPR = 4000;

    /** Uma expressão de localizador só é transportável se for INTEIRAMENTE analisada e tudo nela passar na política. */
    public static boolean expressionSafe(String expr) {
        if (expr == null || expr.isEmpty()) {
            return true;
        }
        if (expr.length() > MAX_EXPR || EXPR_CONTROL.matcher(expr).find()) {
            return false;
        }
        return new Css(expr).safe() || new XPath(expr).safe() || semanticSafe(expr);
    }

    private static final String CSS_WS = " \t\n\r\f";
    private static final Set<String> CSS_PSEUDO = Set.of("first-child", "last-child", "only-child", "first-of-type", "last-of-type",
            "only-of-type", "checked", "disabled", "enabled", "required", "optional", "read-only", "read-write", "empty", "root");
    private static final Set<String> CSS_NTH = Set.of("nth-child", "nth-last-child", "nth-of-type", "nth-last-of-type");
    private static final Pattern NTH_ARG = Pattern.compile(" *(odd|even|[+-]?\\d+|[+-]?\\d*n( *[+-] *\\d+)?) *");
    /** Controle, separador de linha e DEL: nenhum produtor os emite; a expressão inteira é recusada. */
    private static final Pattern EXPR_CONTROL = Pattern.compile("[\\u0000-\\u001f\\u007f\\u0085\\u2028\\u2029]");

    /** Seletor CSS (compostos com combinadores; sem vírgula, comentário, namespace, ::, nem :not/:is/:has). */
    private static final class Css {
        private final String s;
        private final int n;
        private int i;

        Css(String s) {
            this.s = s;
            this.n = s.length();
        }

        private char ch(int k) {
            return k < n ? s.charAt(k) : '\0';
        }

        private static boolean hex(char c) {
            return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
        }

        private static boolean alpha(char c) {
            return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_';
        }

        private boolean ws() {
            int st = i;
            while (i < n && CSS_WS.indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            return i > st;
        }

        private String escape() { // em "\"
            i++;
            char c = ch(i);
            if (i >= n || c == '\n' || c == '\r' || c == '\f') {
                return null;
            }
            if (hex(c)) {
                StringBuilder h = new StringBuilder();
                while (i < n && h.length() < 6 && hex(s.charAt(i))) {
                    h.append(s.charAt(i++));
                }
                if (ch(i) == '\r' && ch(i + 1) == '\n') {
                    i += 2;
                } else if (i < n && CSS_WS.indexOf(ch(i)) >= 0) {
                    i++;
                }
                int cp = Integer.parseInt(h.toString(), 16);
                return cp == 0 || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF) ? "�" : new String(Character.toChars(cp));
            }
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            return new String(Character.toChars(cp));
        }

        private boolean identStart(int k) {
            if (k >= n) {
                return false;
            }
            char c = s.charAt(k);
            if (c == '\\' || alpha(c) || c >= 0x80) {
                return true;
            }
            if (c != '-' || k + 1 >= n) {
                return false;
            }
            char d = s.charAt(k + 1);
            return d == '-' || d == '\\' || alpha(d) || d >= 0x80;
        }

        private String ident() { // valor DECODIFICADO ou null
            if (!identStart(i)) {
                return null;
            }
            StringBuilder out = new StringBuilder();
            while (i < n) {
                char c = s.charAt(i);
                if (c == '\\') {
                    String e = escape();
                    if (e == null) {
                        return null;
                    }
                    out.append(e);
                } else if (alpha(c) || (c >= '0' && c <= '9') || c == '-' || c >= 0x80) {
                    out.append(c);
                    i++;
                } else {
                    break;
                }
            }
            return out.toString();
        }

        private String string() { // valor DECODIFICADO ou null
            char q = s.charAt(i++);
            StringBuilder out = new StringBuilder();
            while (i < n) {
                char c = s.charAt(i);
                if (c == q) {
                    i++;
                    return out.toString();
                }
                if (c == '\n' || c == '\r' || c == '\f') {
                    return null;
                }
                if (c == '\\') {
                    if (ch(i + 1) == '\n' && i + 1 < n) {
                        i += 2;
                        continue;
                    }
                    String e = escape();
                    if (e == null) {
                        return null;
                    }
                    out.append(e);
                    continue;
                }
                out.append(c);
                i++;
            }
            return null;
        }

        private boolean attributeSelector() { // em "["
            i++;
            ws();
            String name = ident();
            if (name == null || !attributeNameSafe(name)) {
                return false;
            }
            ws();
            if (i < n && ch(i) == ']') {
                i++;
                return true;
            }
            if (i < n && ch(i) == '=') {
                i++;
            } else if (i + 1 < n && "~|^$*".indexOf(ch(i)) >= 0 && ch(i + 1) == '=') {
                i += 2;
            } else {
                return false;
            }
            ws();
            String v = i < n && (ch(i) == '"' || ch(i) == '\'') ? string() : ident();
            if (v == null || !(DEV_IDENTIFIERS.contains(asciiLower(name)) ? developerLiteralOk(v) : literalOk(v))) {
                return false;
            }
            ws();
            if (i < n && "iIsS".indexOf(ch(i)) >= 0) {
                i++;
                ws();
            }
            if (i >= n || ch(i) != ']') {
                return false;
            }
            i++;
            return true;
        }

        private boolean pseudo() { // em ":"
            i++;
            if (i < n && ch(i) == ':') {
                return false;
            }
            String p = ident();
            if (p == null) {
                return false;
            }
            String name = asciiLower(p);
            if (i < n && ch(i) == '(') {
                if (!CSS_NTH.contains(name)) {
                    return false;
                }
                int close = s.indexOf(')', i);
                if (close < 0 || !NTH_ARG.matcher(asciiLower(s.substring(i + 1, close))).matches()) {
                    return false;
                }
                i = close + 1;
                return true;
            }
            return CSS_PSEUDO.contains(name);
        }

        private boolean compound() {
            boolean any = false;
            if (i < n && ch(i) == '*') {
                i++;
                any = true;
            } else if (identStart(i)) {
                String t = ident();
                if (t == null || !tagSafe(t)) {
                    return false;
                }
                any = true;
            }
            for (;;) {
                char c = i < n ? ch(i) : '\0';
                if (c == '#' || c == '.') {
                    i++;
                    String v = ident();
                    if (v == null || !(c == '#' ? developerLiteralOk(v) : literalOk(v))) {
                        return false;
                    }
                } else if (c == '[') {
                    if (!attributeSelector()) {
                        return false;
                    }
                } else if (c == ':') {
                    if (!pseudo()) {
                        return false;
                    }
                } else {
                    break;
                }
                any = true;
            }
            return any;
        }

        boolean safe() {
            ws();
            if (!compound()) {
                return false;
            }
            for (;;) {
                boolean hadWs = ws();
                if (i >= n) {
                    return true;
                }
                char c = s.charAt(i);
                if (c == '>' || c == '+' || c == '~') {
                    i++;
                    ws();
                    if (!compound()) {
                        return false;
                    }
                } else if (hadWs) {
                    if (!compound()) {
                        return false;
                    }
                } else {
                    return false;
                }
            }
        }
    }

    private static final Pattern XPATH_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9._-]*");
    private static final List<String> XPATH_TEXT = List.of("normalize-space(.)", "normalize-space()", "text()");

    /** Subconjunto de XPath 1.0 que o motor produz (caminhos, posição, @atributo, texto normalizado, concat, ancestor::). */
    private static final class XPath {
        private final String s;
        private final int n;
        private int i;

        XPath(String s) {
            this.s = s;
            this.n = s.length();
        }

        private char ch(int k) {
            return k < n ? s.charAt(k) : '\0';
        }

        private void skip() {
            while (i < n && s.charAt(i) == ' ') {
                i++;
            }
        }

        private String name() {
            Matcher m = XPATH_NAME.matcher(s).region(i, n);
            if (!m.lookingAt()) {
                return null;
            }
            i = m.end();
            return m.group();
        }

        private boolean nodeTest() {
            if (i < n && ch(i) == '*') {
                i++;
                return true;
            }
            String t = name();
            return t != null && tagSafe(t);
        }

        private String quoted() {
            char q = ch(i);
            int e = s.indexOf(q, i + 1);
            if (e < 0) {
                return null;
            }
            String v = s.substring(i + 1, e);
            i = e + 1;
            return v;
        }

        private String literal() { // valor (concat: a concatenação, julgada inteira) ou null
            if (ch(i) == '"' || ch(i) == '\'') {
                return quoted();
            }
            i += "concat(".length();
            StringBuilder out = new StringBuilder();
            for (;;) {
                skip();
                if (i >= n || (ch(i) != '"' && ch(i) != '\'')) {
                    return null;
                }
                String v = quoted();
                if (v == null) {
                    return null;
                }
                out.append(v);
                skip();
                if (i < n && ch(i) == ',') {
                    i++;
                } else if (i < n && ch(i) == ')') {
                    i++;
                    return out.toString();
                } else {
                    return null;
                }
            }
        }

        private boolean step(boolean axis) {
            if (axis && s.startsWith("ancestor::", i)) {
                i += "ancestor::".length();
            }
            if (i < n && ch(i) == '@') {
                i++;
                String a = name();
                return a != null && attributeNameSafe(a);
            }
            if (!nodeTest()) {
                return false;
            }
            while (i < n && ch(i) == '[') {
                if (!predicate()) {
                    return false;
                }
            }
            return true;
        }

        private boolean steps(boolean first) { // passos separados por / ou //
            if (first && !step(true)) {
                return false;
            }
            while (i < n && ch(i) == '/') {
                i += ch(i + 1) == '/' ? 2 : 1;
                if (!step(false)) {
                    return false;
                }
            }
            return true;
        }

        // "ok", "lit:<valor>" (julgado no predicado), "attr:<nome>" (só "@nome"), "num:<dígitos>" ou null
        private String operand() {
            skip();
            char c = ch(i);
            if (i < n && (c == '"' || c == '\'' || s.startsWith("concat(", i))) {
                String v = literal();
                return v != null && developerLiteralOk(v) ? "lit:" + v : null;
            }
            if (i < n && c == '@') {
                Matcher m = XPATH_NAME.matcher(s).region(i + 1, n);
                if (m.lookingAt()) {
                    char after = ch(m.end());
                    if (after != '/' && after != '[' && attributeNameSafe(m.group())) {
                        i = m.end();
                        return "attr:" + asciiLower(m.group());
                    }
                }
            }
            if (i < n && c >= '0' && c <= '9') {
                int st = i;
                while (i < n && ch(i) >= '0' && ch(i) <= '9') {
                    i++;
                }
                return "num:" + s.substring(st, i);
            }
            for (String fn : XPATH_TEXT) {
                if (s.startsWith(fn, i)) {
                    i += fn.length();
                    return "ok";
                }
            }
            if (i < n && c == '/') {
                return steps(false) ? "ok" : null;
            }
            return steps(true) ? "ok" : null;
        }

        private boolean predicate() { // em "["
            i++;
            String a = operand();
            if (a == null) {
                return false;
            }
            skip();
            if (i < n && ch(i) == '=') {
                i++;
                String b = operand();
                if (b == null) {
                    return false;
                }
                String[][] pairs = {{a, b}, {b, a}};
                for (String[] p : pairs) {
                    String x = p[0];
                    if (x.startsWith("num:") && !literalOk(x.substring(4))) {
                        return false; // número comparado é VALOR (P1-02)
                    }
                    // literal: vocabulário; a lista técnica só quando comparado a @id/@name/@data-testid (STUDIO-012)
                    if (x.startsWith("lit:") && !literalOk(x.substring(4))
                            && !(p[1].startsWith("attr:") && DEV_IDENTIFIERS.contains(p[1].substring(5)))) {
                        return false;
                    }
                }
            } else if (a.startsWith("lit:")) {
                return false;
            }
            skip();
            if (i >= n || ch(i) != ']') {
                return false;
            }
            i++;
            return true;
        }

        boolean safe() {
            if (n == 0 || s.charAt(0) != '/') {
                return false;
            }
            while (i < n) {
                if (s.charAt(i) != '/') {
                    return false;
                }
                i += ch(i + 1) == '/' ? 2 : 1;
                if (!nodeTest()) {
                    return false;
                }
                while (i < n && ch(i) == '[') {
                    if (!predicate()) {
                        return false;
                    }
                }
            }
            return true;
        }
    }

    private static final Pattern SEM_TAG = Pattern.compile("[A-Za-z][A-Za-z0-9._-]*");
    private static final Pattern SEM_NTH = Pattern.compile("nth-(of-type|child) \\d+");
    private static final Pattern SEM_CHILD = Pattern.compile("has child <([^>]*)> \"(.*)\"");
    private static final Pattern SEM_HAS = Pattern.compile("has (\\S+)");
    private static final Pattern SEM_PAIR = Pattern.compile("([A-Za-z_:][A-Za-z0-9_:.-]*)=(.*)");
    private static final Set<String> SEM_KEYS = Set.of("text", "label", "name", "role");

    private static boolean semanticPredicate(String p) {
        if (p.indexOf('{') >= 0) {
            return false;
        }
        if (SEM_NTH.matcher(p).matches()) {
            return true;
        }
        Matcher m = SEM_CHILD.matcher(p);
        if (m.matches()) {
            return tagSafe(m.group(1)) && literalOk(m.group(2));
        }
        m = SEM_HAS.matcher(p);
        if (m.matches()) {
            return attributeNameSafe(m.group(1));
        }
        m = SEM_PAIR.matcher(p);
        if (m.matches()) {
            String v = m.group(2);
            boolean quoted = v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"");
            if (quoted) {
                v = v.substring(1, v.length() - 1);
            }
            // sem aspas = valor de ATRIBUTO ({id=…}, {name=…}); name="…" entre aspas é o NOME ACESSÍVEL (texto de interface)
            boolean developer = !quoted && DEV_IDENTIFIERS.contains(m.group(1));
            return (SEM_KEYS.contains(m.group(1)) || attributeNameSafe(m.group(1))) && (developer ? developerLiteralOk(v) : literalOk(v));
        }
        return false;
    }

    /** Notação semântica do motor: "tag {predicado} … >> tag {…}". Valor sem aspas é julgado INTEIRO, até o "}". */
    private static boolean semanticSafe(String s) {
        int n = s.length();
        int[] i = {0};
        java.util.function.BooleanSupplier sstep = () -> {
            if (i[0] < n && s.charAt(i[0]) == '*') {
                i[0]++;
            } else {
                Matcher m = SEM_TAG.matcher(s).region(i[0], n);
                if (!m.lookingAt() || !tagSafe(m.group())) {
                    return false;
                }
                i[0] = m.end();
            }
            while (s.startsWith(" {", i[0])) {
                int e = s.indexOf('}', i[0] + 2);
                if (e < 0 || !semanticPredicate(s.substring(i[0] + 2, e))) {
                    return false;
                }
                i[0] = e + 1;
            }
            return true;
        };
        if (!sstep.getAsBoolean()) {
            return false;
        }
        while (i[0] < n) {
            if (!s.startsWith(" >> ", i[0])) {
                return false;
            }
            i[0] += 4;
            if (!sstep.getAsBoolean()) {
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
            return structuralValue(n, v);
        }
        if (IDENTIFIERS.contains(n)) {
            return DEV_IDENTIFIERS.contains(n) ? developerIdentifier(v) : identifier(v);
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
