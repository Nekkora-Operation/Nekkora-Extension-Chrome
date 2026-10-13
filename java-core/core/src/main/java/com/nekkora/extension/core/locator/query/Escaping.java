package com.nekkora.extension.core.locator.query;

import java.util.regex.Pattern;

/** Escapes de literais CSS e XPath 1.0. */
public final class Escaping {

    private static final Pattern CSS_IDENT = Pattern.compile("^-?[A-Za-z_][A-Za-z0-9_-]*$");

    private Escaping() {
    }

    public static boolean isCssIdentifier(String s) {
        return s != null && CSS_IDENT.matcher(s).matches();
    }

    public static String cssString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c == '\n') {
                sb.append("\\a ");
            } else {
                sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    public static String xpathLiteral(String s) {
        if (!s.contains("\"")) {
            return "\"" + s + "\"";
        }
        if (!s.contains("'")) {
            return "'" + s + "'";
        }
        StringBuilder sb = new StringBuilder("concat(");
        String[] parts = s.split("\"", -1);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append(",'\"',");
            }
            sb.append('"').append(parts[i]).append('"');
        }
        return sb.append(')').toString();
    }
}
