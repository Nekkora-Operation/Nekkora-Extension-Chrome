package com.nekkora.extension.core.privacy;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Política de privacidade de URLs e textos (NEKKORA-REAL-001) — SEGUNDA barreira, espelho exato de
 * {@code chrome-extension/src/shared/privacy.js} (a primeira, aplicada no navegador). As duas implementações são
 * verificadas contra os mesmos vetores ({@code contracts/privacy/privacy-vectors.json}). Idempotente.
 *
 * <ul>
 *   <li>URLs http/https: sem credenciais; segmentos de caminho com aparência de identificador/segredo viram
 *       {@code :redacted}; valores de parâmetros sensíveis (pelo nome) ou com aparência de segredo/dado pessoal viram
 *       {@code REDACTED} (nomes mantidos); forma original preservada (absoluta/relativa).</li>
 *   <li>mailto:/tel:/sms:, data:, blob: e demais esquemas: conteúdo omitido; about:blank/about:srcdoc mantidos.</li>
 *   <li>Textos: e-mail, CPF, CNPJ, cartão (Luhn), telefone em formato explícito, JWT e Bearer mascarados.</li>
 * </ul>
 */
public final class Privacy {

    public static final String VERSION = "nekkora-privacy/1";
    public static final String REDACTED = "REDACTED";
    public static final String SEGMENT = ":redacted";
    private static final int MAX_URL = 2000;
    private static final int MAX_VALUE = 64;

    private static final Set<String> SENSITIVE_WORDS = Set.of(
            "token", "auth", "authorization", "bearer", "jwt", "session", "sess", "sessid", "sid", "jsessionid", "phpsessid",
            "aspsessionid", "cookie", "key", "apikey", "secret", "password", "passwd", "pwd", "pass", "senha", "signature",
            "sig", "hmac", "hash", "code", "state", "nonce", "ticket", "otp", "pin", "credential", "credentials", "cred",
            "saml", "samlrequest", "samlresponse", "relaystate", "assertion", "email", "mail", "cpf", "cnpj", "rg", "ssn",
            "phone", "telefone", "fone", "celular", "mobile", "tel", "user", "username", "usuario", "uid", "login", "account",
            "conta", "card", "cartao", "cvv", "cvc", "iban", "redirect", "return", "returnurl", "next", "callback", "continue",
            "goto", "url", "uri", "ref", "expires", "policy", "access", "refresh", "verifier", "challenge", "oauth",
            "x", "amz", "goog");
    private static final List<String> SENSITIVE_PARTS = List.of("token", "secret", "passw", "senha", "session", "sessid", "auth",
            "apikey", "signature", "jwt", "bearer", "cookie", "credential", "sitekey");
    private static final Set<String> FRAGMENT_KEPT = Set.of("frame");

    private static final Pattern UUID = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern HEX = Pattern.compile("^[0-9a-fA-F]{16,}$");
    private static final Pattern JWT = Pattern.compile("^[A-Za-z0-9_-]{6,}\\.[A-Za-z0-9_-]{6,}\\.[A-Za-z0-9_-]{6,}$");
    private static final Pattern OPAQUE = Pattern.compile("^[A-Za-z0-9_\\-+/=~.%]{24,}$");
    private static final Pattern DIGITS = Pattern.compile("\\d{6,}");
    private static final Pattern CAMEL = Pattern.compile("([a-z0-9])([A-Z])");
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");
    private static final Pattern SCHEME = Pattern.compile("^([a-zA-Z][a-zA-Z0-9+.-]*):");
    private static final Pattern AUTHORITY = Pattern.compile("^((?:[a-zA-Z][a-zA-Z0-9+.-]*:)?//)([^/?#]*)");
    private static final Pattern JS_VOID = Pattern.compile("^javascript:\\s*(void\\s*\\(\\s*0\\s*\\)\\s*;?|;)?\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ABOUT = Pattern.compile("^about:([^?#]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATA = Pattern.compile("^data:([^,;]*)", Pattern.CASE_INSENSITIVE);

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}");
    private static final Pattern CPF = Pattern.compile("\\b\\d{3}\\.\\d{3}\\.\\d{3}-\\d{2}\\b");
    private static final Pattern CNPJ = Pattern.compile("\\b\\d{2}\\.\\d{3}\\.\\d{3}/\\d{4}-\\d{2}\\b");
    private static final Pattern CARD = Pattern.compile("\\b\\d(?:[ -]?\\d){12,18}\\b");
    private static final Pattern PHONE = Pattern.compile(
            "(?:\\+\\d{1,3}[\\s.-]?)?\\(\\d{2,3}\\)\\s?\\d{4,5}[\\s.-]?\\d{4}\\b|\\+\\d{1,3}[\\s.-]?\\d{2,3}[\\s.-]?\\d{4,5}[\\s.-]?\\d{4}\\b");
    private static final Pattern JWT_TEXT = Pattern.compile("\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{5,}");
    private static final Pattern BEARER = Pattern.compile("\\b(Bearer|Basic)\\s+[A-Za-z0-9._~+/=-]{16,}");
    private static final Pattern TEXT_PRECHECK = Pattern.compile("[@\\d]|eyJ|Bearer|Basic");
    private static final Pattern MASK_TOKEN = Pattern.compile("\\[(email|cpf|cnpj|cartão|telefone|token)]");

    private Privacy() {
    }

    // ------------------------------------------------------------------------------------------- nomes/valores

    /** Nome de parâmetro sensível: alguma palavra (separadores ou camelCase) ou trecho conhecido. */
    public static boolean sensitiveKey(String name) {
        String k = name == null ? "" : name;
        String lower = k.toLowerCase(Locale.ROOT);
        for (String p : SENSITIVE_PARTS) {
            if (lower.contains(p)) {
                return true;
            }
        }
        String words = CAMEL.matcher(k).replaceAll("$1 $2").toLowerCase(Locale.ROOT);
        for (String w : NON_ALNUM.split(words)) {
            if (!w.isEmpty() && SENSITIVE_WORDS.contains(w)) {
                return true;
            }
        }
        return false;
    }

    /** Valor com aparência de identificador ou segredo. */
    public static boolean looksSecret(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        if (s.contains("@") || UUID.matcher(s).matches() || HEX.matcher(s).matches() || JWT.matcher(s).matches() || DIGITS.matcher(s).find()) {
            return true;
        }
        if (OPAQUE.matcher(s).matches()) {
            int digits = 0;
            int letters = 0;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c >= '0' && c <= '9') {
                    digits++;
                } else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                    letters++;
                }
            }
            return digits >= 3 && letters >= 3;
        }
        return false;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s;
        }
    }

    private static String sanitizeSegment(String seg) {
        if (seg.isEmpty() || SEGMENT.equals(seg)) {
            return seg;
        }
        String base = seg;
        String matrix = "";
        int semi = seg.indexOf(';');
        if (semi >= 0) {
            base = seg.substring(0, semi);
            List<String> parts = new ArrayList<>();
            for (String p : seg.substring(semi + 1).split(";", -1)) {
                int eq = p.indexOf('=');
                parts.add(eq < 0 ? p : p.substring(0, eq) + "=" + REDACTED);
            }
            matrix = ";" + String.join(";", parts);
        }
        String d = decode(base);
        if (looksSecret(d) || !maskText(d).equals(d)) {
            base = SEGMENT;
        }
        return base + matrix;
    }

    private static String sanitizePath(String path) {
        if (path.isEmpty()) {
            return path;
        }
        String[] segs = path.split("/", -1);
        for (int i = 0; i < segs.length; i++) {
            segs[i] = sanitizeSegment(segs[i]);
        }
        return String.join("/", segs);
    }

    private static boolean keepValue(String key, String value) {
        if (REDACTED.equals(value) || value.isEmpty()) {
            return true;
        }
        if (sensitiveKey(decode(key))) {
            return false;
        }
        String d = decode(value);
        return d.length() <= MAX_VALUE && !looksSecret(d) && maskText(d).equals(d);
    }

    private static String sanitizeQuery(String q) {
        if (q.isEmpty()) {
            return q;
        }
        String[] pairs = q.split("&", -1);
        for (int i = 0; i < pairs.length; i++) {
            String pair = pairs[i];
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String key = looksSecret(decode(k)) ? SEGMENT : (k.length() > 100 ? k.substring(0, 100) : k);
            if (eq < 0) {
                pairs[i] = key;
                continue;
            }
            String v = pair.substring(eq + 1);
            pairs[i] = key + "=" + (!SEGMENT.equals(key) && keepValue(k, v) ? v : REDACTED);
        }
        return String.join("&", pairs);
    }

    private static String sanitizeFragment(String f) {
        if (f.isEmpty()) {
            return f;
        }
        if (f.startsWith("/") || f.startsWith("!/")) {
            int q = f.indexOf('?');
            return q < 0 ? sanitizePath(f) : sanitizePath(f.substring(0, q)) + "?" + sanitizeQuery(f.substring(q + 1));
        }
        if (f.contains("=")) {
            String[] pairs = f.split("&", -1);
            for (int i = 0; i < pairs.length; i++) {
                String pair = pairs[i];
                int eq = pair.indexOf('=');
                if (eq >= 0 && FRAGMENT_KEPT.contains(pair.substring(0, eq)) && pair.length() <= 40) {
                    continue;
                }
                pairs[i] = sanitizeQuery(pair);
            }
            return String.join("&", pairs);
        }
        String d = decode(f);
        return d.length() > 120 || looksSecret(d) || !maskText(d).equals(d) ? SEGMENT : f;
    }

    private static String special(String scheme, String s) {
        switch (scheme) {
            case "about": {
                Matcher m = ABOUT.matcher(s);
                return "about:" + (m.find() ? m.group(1) : "");
            }
            case "javascript":
                return JS_VOID.matcher(s).matches() ? s : "javascript:[omitido]";
            case "mailto", "tel", "sms", "callto":
                return scheme + ":[redacted]";
            case "data": {
                Matcher m = DATA.matcher(s);
                return "data:" + (m.find() ? m.group(1) : "") + ",[omitido]";
            }
            default:
                return scheme + ":[omitido]";
        }
    }

    // ------------------------------------------------------------------------------------------------- URLs

    /** URL sanitizada preservando a forma original; {@code null}/vazio devolvidos como estão. */
    public static String sanitizeUrl(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.strip();
        if (s.isEmpty()) {
            return s;
        }
        Matcher sm = SCHEME.matcher(s);
        if (sm.find()) {
            String scheme = sm.group(1).toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                return special(scheme, s);
            }
        }
        String prefix = "";
        String rest = s;
        Matcher am = AUTHORITY.matcher(s);
        if (am.find()) {
            prefix = am.group(1) + am.group(2).replaceFirst("^[^@]*@", "");
            rest = s.substring(am.end());
        }
        String frag = null;
        int h = rest.indexOf('#');
        if (h >= 0) {
            frag = rest.substring(h + 1);
            rest = rest.substring(0, h);
        }
        String query = null;
        int q = rest.indexOf('?');
        if (q >= 0) {
            query = rest.substring(q + 1);
            rest = rest.substring(0, q);
        }
        StringBuilder out = new StringBuilder(prefix).append(sanitizePath(rest));
        if (query != null) {
            out.append('?').append(sanitizeQuery(query));
        }
        if (frag != null) {
            out.append('#').append(sanitizeFragment(frag));
        }
        return out.length() > MAX_URL ? out.substring(0, MAX_URL) : out.toString();
    }

    /** Consulta {@code chave=valor&…} (sem '?') sob as regras de parâmetros. */
    public static String sanitizeQueryString(String q) {
        return q == null ? null : sanitizeQuery(q);
    }

    /** Caminho (sem consulta/fragmento) com segmentos sensíveis mascarados. */
    public static String sanitizePathOnly(String path) {
        return path == null ? null : sanitizePath(path);
    }

    // ------------------------------------------------------------------------------------------------ textos

    private static boolean luhn(String digits) {
        int sum = 0;
        boolean alt = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int n = digits.charAt(i) - '0';
            if (alt) {
                n *= 2;
                if (n > 9) {
                    n -= 9;
                }
            }
            sum += n;
            alt = !alt;
        }
        return sum % 10 == 0;
    }

    /** Mascara dados pessoais e segredos com formato reconhecível. */
    public static String maskText(String s) {
        if (s == null || s.isEmpty() || !TEXT_PRECHECK.matcher(s).find()) {
            return s;
        }
        String out = s;
        if (out.indexOf('@') >= 0) {
            out = EMAIL.matcher(out).replaceAll("[email]");
        }
        out = JWT_TEXT.matcher(out).replaceAll("[token]");
        out = BEARER.matcher(out).replaceAll("$1 [token]");
        out = CNPJ.matcher(out).replaceAll("[cnpj]");
        out = CPF.matcher(out).replaceAll("[cpf]");
        Matcher card = CARD.matcher(out);
        StringBuilder sb = new StringBuilder();
        while (card.find()) {
            String d = card.group().replaceAll("\\D", "");
            card.appendReplacement(sb, Matcher.quoteReplacement(d.length() >= 13 && d.length() <= 19 && luhn(d) ? "[cartão]" : card.group()));
        }
        card.appendTail(sb);
        out = PHONE.matcher(sb.toString()).replaceAll("[telefone]");
        return out;
    }

    /**
     * Origem do laboratório local (127.0.0.1/localhost). Dados derivados de OUTRAS origens (sites autorizados pelo
     * usuário) não são persistidos automaticamente pelo motor: só uma exportação explícita grava em disco.
     */
    public static boolean isLabOrigin(String originOrUrl) {
        if (originOrUrl == null) {
            return false;
        }
        try {
            String host = java.net.URI.create(CapturePrivacyPolicy.forUri(originOrUrl.strip())).getHost();
            return "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Valor produzido por esta política: não corresponde à página e não deve virar localizador. */
    public static boolean isMasked(String v) {
        return v != null && (v.contains(REDACTED) || v.contains(SEGMENT) || v.contains("[omitido]") || v.contains("[redacted]")
                || MASK_TOKEN.matcher(v).find());
    }
}
