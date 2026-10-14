package com.nekkora.extension.core.privacy;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Atributos de nós sob a política de privacidade — espelho exato de {@code sanitizeAttribute} em
 * {@code chrome-extension/src/shared/privacy.js} (vetores em {@code contracts/privacy/privacy-vectors.json}):
 * <ul>
 *   <li>atributos de URL sanitizados preservando a forma; de texto livre com dados pessoais mascarados;</li>
 *   <li>atributos estruturais (id, class, name, type, role, aria-* de referência…) mantidos;</li>
 *   <li>TODO outro atributo (data-*, personalizados) — revisão do PR #3: nome sensível ⇒ {@code [redacted]}; segredo
 *       longo ⇒ {@code [redacted]}; URL inteira ou embutida ⇒ sanitizada; pares chave=valor/JSON com chave sensível ⇒
 *       valor {@code REDACTED}; dados pessoais ⇒ mascarados.</li>
 * </ul>
 * Devolve o MESMO mapa quando nada muda (custo mínimo no caminho de captura).
 */
public final class AttributePrivacy {

    static final Set<String> URL_ATTRIBUTES = Set.of("href", "src", "action", "formaction", "poster", "data", "cite", "background",
            "longdesc", "xlink:href", "manifest", "codebase", "data-nekkora-simulated-src");
    static final Set<String> SRCSET_ATTRIBUTES = Set.of("srcset", "imagesrcset");
    static final Set<String> TEXT_ATTRIBUTES = Set.of("title", "alt", "aria-label", "aria-description", "aria-valuetext",
            "aria-roledescription", "aria-placeholder", "label", "content", "value", "summary", "abbr", "download");
    static final Set<String> STRUCTURAL_ATTRIBUTES = Set.of("id", "class", "name", "type", "role", "for", "tabindex", "lang", "dir",
            "hidden", "disabled", "checked", "selected", "readonly", "required", "multiple", "rel", "target", "method", "enctype",
            "autocomplete", "inputmode", "enterkeyhint", "maxlength", "minlength", "min", "max", "step", "pattern", "size", "rows",
            "cols", "colspan", "rowspan", "scope", "headers", "width", "height", "slot", "part", "is", "draggable",
            "contenteditable", "spellcheck", "translate", "accesskey", "sandbox", "allow", "loading", "decoding",
            "referrerpolicy", "crossorigin", "charset", "xmlns", "open", "wrap", "form", "list", "accept", "autofocus",
            "novalidate", "start", "reversed", "span", "shape", "coords", "media", "sizes", "srclang", "kind", "default",
            "async", "defer", "nomodule", "popover", "popovertarget", "popovertargetaction", "inert", "datetime",
            "viewbox", "d", "points", "transform", "fill", "stroke", "stroke-width", "cx", "cy", "r", "rx", "ry", "x", "y",
            "x1", "x2", "y1", "y2", "focusable", "preserveaspectratio", "version");
    /** Palavras que tornam o NOME de um atributo sensível (mais estreita que a lista de parâmetros de URL). */
    static final Set<String> ATTRIBUTE_SENSITIVE_WORDS = Set.of("token", "auth", "authorization", "bearer", "jwt", "session", "sess",
            "sessid", "sid", "jsessionid", "phpsessid", "cookie", "key", "apikey", "secret", "password", "passwd", "pwd", "pass",
            "senha", "signature", "sig", "hmac", "otp", "pin", "credential", "credentials", "cred", "nonce", "cpf", "cnpj", "rg",
            "ssn", "email", "mail", "phone", "telefone", "fone", "celular", "mobile", "tel", "cvv", "cvc", "card", "cartao", "iban",
            "user", "username", "usuario", "uid", "login", "account", "conta", "ticket", "saml", "private", "privatekey");
    private static final List<String> SENSITIVE_PARTS = List.of("token", "secret", "passw", "senha", "session", "sessid", "auth",
            "apikey", "signature", "jwt", "bearer", "cookie", "credential", "sitekey");

    private static final Pattern URL_VALUE = Pattern.compile(
            "^\\s*(?:(?:https?|mailto|tel|sms|javascript|data|blob|ftp|file|wss?|intent):|//|/|\\.\\.?/|\\?)\\S*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMBEDDED_URL = Pattern.compile("(?:\\bhttps?://|(?<![\\w:])//)[^\\s\"'<>()]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUOTED_PAIR = Pattern.compile("([\"'])([A-Za-z_][\\w.-]*)\\1(\\s*[:=]\\s*)([\"'])((?:(?!\\4).)*)\\4");
    private static final Pattern UNQUOTED_PAIR = Pattern.compile("([\"'])([A-Za-z_][\\w.-]*)\\1(\\s*:\\s*)(-?[\\w.+-]+)");
    private static final Pattern QUERY_VALUE = Pattern.compile("^[^\\s=&?#]+=[^\\s&]*(?:&[^\\s=&]+=[^\\s&]*)*$");
    private static final Pattern DATA_ARIA = Pattern.compile("^(data|aria)-", Pattern.CASE_INSENSITIVE);
    private static final Pattern CAMEL = Pattern.compile("([a-z0-9])([A-Z])");
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");
    private static final Pattern LITERAL = Pattern.compile("^(true|false|null)$");

    private AttributePrivacy() {
    }

    /** Nome de atributo sensível (prefixos data-/aria- ignorados; palavras por separadores e camelCase). */
    public static boolean sensitiveAttributeName(String name) {
        String k = DATA_ARIA.matcher(name == null ? "" : name).replaceFirst("");
        String lower = k.toLowerCase(Locale.ROOT);
        for (String p : SENSITIVE_PARTS) {
            if (lower.contains(p)) {
                return true;
            }
        }
        for (String w : NON_ALNUM.split(CAMEL.matcher(k).replaceAll("$1 $2").toLowerCase(Locale.ROOT))) {
            if (!w.isEmpty() && ATTRIBUTE_SENSITIVE_WORDS.contains(w)) {
                return true;
            }
        }
        return false;
    }

    private static String sanitizeFree(String name, String v, boolean mask) {
        if (!"placeholder".equals(name) && sensitiveAttributeName(name)) {
            return "[redacted]";
        }
        if (v.length() >= 16 && Privacy.looksSecret(v.strip())) {
            return "[redacted]";
        }
        if (URL_VALUE.matcher(v).matches()) {
            return Privacy.sanitizeUrl(v);
        }
        String out = EMBEDDED_URL.matcher(v).replaceAll(m -> Matcher.quoteReplacement(Privacy.sanitizeUrl(m.group())));
        if (QUERY_VALUE.matcher(out).matches()) {
            out = Privacy.sanitizeQueryString(out);
        }
        out = QUOTED_PAIR.matcher(out).replaceAll(m -> {
            String val = m.group(5);
            boolean redact = !val.isEmpty() && !Privacy.REDACTED.equals(val) && Privacy.sensitiveKey(m.group(2));
            return Matcher.quoteReplacement(redact ? m.group(1) + m.group(2) + m.group(1) + m.group(3) + m.group(4) + Privacy.REDACTED + m.group(4)
                    : m.group());
        });
        out = UNQUOTED_PAIR.matcher(out).replaceAll(m -> {
            String val = m.group(4);
            boolean redact = !Privacy.REDACTED.equals(val) && Privacy.sensitiveKey(m.group(2)) && !LITERAL.matcher(val).matches();
            return Matcher.quoteReplacement(redact ? m.group(1) + m.group(2) + m.group(1) + m.group(3) + Privacy.REDACTED : m.group());
        });
        return mask ? Privacy.maskText(out) : out;
    }

    public static String sanitize(String name, String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        String n = name.toLowerCase(Locale.ROOT);
        if (URL_ATTRIBUTES.contains(n)) {
            return Privacy.sanitizeUrl(value);
        }
        if ("ping".equals(n)) {
            String[] parts = value.split("\\s+");
            for (int i = 0; i < parts.length; i++) {
                parts[i] = Privacy.sanitizeUrl(parts[i]);
            }
            return String.join(" ", parts);
        }
        if (SRCSET_ATTRIBUTES.contains(n)) {
            String[] parts = value.split(",", -1);
            for (int i = 0; i < parts.length; i++) {
                String t = parts[i].strip();
                int sp = indexOfWhitespace(t);
                parts[i] = sp < 0 ? Privacy.sanitizeUrl(t) : Privacy.sanitizeUrl(t.substring(0, sp)) + t.substring(sp);
            }
            return String.join(", ", parts);
        }
        if (TEXT_ATTRIBUTES.contains(n)) {
            return Privacy.maskText(value);
        }
        if (STRUCTURAL_ATTRIBUTES.contains(n) || (n.startsWith("aria-") && !sensitiveAttributeName(n))) {
            return value;
        }
        return sanitizeFree(n, value, !"placeholder".equals(n));
    }

    private static int indexOfWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    /** Mapa sanitizado; o próprio {@code attributes} quando nenhum valor muda. */
    public static Map<String, String> sanitize(Map<String, String> attributes) {
        Map<String, String> out = null;
        for (Map.Entry<String, String> e : attributes.entrySet()) {
            String v = e.getValue();
            String s = sanitize(e.getKey(), v);
            if (s != null && !s.equals(v)) {
                if (out == null) {
                    out = new HashMap<>(attributes);
                }
                out.put(e.getKey(), s);
            }
        }
        return out == null ? attributes : Map.copyOf(out);
    }
}
