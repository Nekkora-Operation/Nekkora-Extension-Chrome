package com.nekkora.extension.it.support;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CHROME-SECURITY-001: marcadores fictícios do laboratório {@code test-lab/pages/chrome-security-001} e a varredura dos
 * destinos reais (eventos ao Studio, armazenamento da extensão, log do service worker, arquivos do host, snapshot
 * transportado, resultados do motor).
 *
 * <p>Cada superfície da página usa um nome inventado e ÚNICO, então um achado indica de onde o dado veio. A busca
 * considera transformações comuns (acentos removidos, minúsculas, {@code %xx}, {@code \\uXXXX}, entidades HTML, hífen e
 * sublinhado como espaço). Ela não promete cobrir toda transformação possível: é a parte adversarial da verificação; a
 * outra parte é a arquitetura, que não deve transportar texto livre por padrão.
 */
public final class PrivacyCanaries {

    /** Marcador (já normalizado) → casos SEC001 e superfície. */
    public static final Map<String, String> CANARIES = new LinkedHashMap<>();

    static {
        // página 1 — texto livre
        c("cliente joao ja existe", "SEC001-001/022 texto próprio sem formato reconhecível");
        c("tavolaro", "SEC001-001/022 texto próprio");
        c("calabouco", "SEC001-023 texto descendente");
        c("pitanguy", "SEC001-024 segmentos de texto");
        c("mondego", "SEC001-021 título de SVG");
        // página 2 — formulários (valores digitados pelo teste e repetidos pela página)
        c("valadares", "SEC001-004 valor de pesquisa");
        c("brandao", "SEC001-005/033 valor de campo de texto (e eco após remoção)");
        c("kq9zt", "SEC001-006 senha");
        c("peixoto", "SEC001-007 e-mail");
        c("98877", "SEC001-008 telefone");
        c("483192", "SEC001-009 número");
        c("amarilis", "SEC001-010 contenteditable");
        c("quintanilha", "SEC001-011 opção de lista selecionada");
        c("carrasqueira", "SEC001-012 opção de combobox");
        c("florisbela", "SEC001-032 eco do valor depois de limpar o campo");
        // página 3 — elementos
        c("quixada", "SEC001-002/013 aria-label");
        c("ubirajara", "SEC001-002/014 aria-labelledby");
        c("zanatta", "SEC001-015 placeholder");
        c("vasconi", "SEC001-016 title");
        c("arantes", "SEC001-002 texto de <label>");
        c("bonfim", "SEC001-002 texto de link");
        // página 4 — atributos
        c("marangoni", "SEC001-017 data-*");
        c("prudencio", "SEC001-018 id");
        c("hermengarda", "SEC001-019 name");
        c("ventania", "SEC001-020 atributo personalizado");
        c("teixeira", "SEC001-081 classe CSS");
        c("siqueira", "SEC001-082 aria-description");
        c("malaquias", "SEC001-083 aria-valuetext");
        c("bastiao", "SEC001-084 alt");
        // página 5 — URLs
        c("marcondes", "SEC001-049/050/085 caminho de URL (href, novo documento)");
        c("ataulfo", "SEC001-049/050/085 parâmetro e fragmento de URL");
        c("lacerda", "SEC001-053/085 caminho de requisição, action, formaction");
        c("sinfronio", "SEC001-053 parâmetro de requisição");
        c("oliveira", "SEC001-003 caminho de rota (History API)");
        c("bonifacio", "SEC001-050 fragmento de rota");
        c("guaraciaba", "SEC001-050 rota em fragmento");
        c("merces", "SEC001-052 URL de recurso");
        c("gervasio", "SEC001-051 URL de iframe");
        c("furtado", "SEC001-026/027 conteúdo de iframe");
        // página 6 — tabela
        c("sarmento", "SEC001-038 legenda/cabeçalho de tabela");
        c("barbacena", "SEC001-037 rowTexts");
        c("pontual", "SEC001-037 rowTexts");
        c("55821", "SEC001-037 identificador na linha");
        // página 7 — Shadow DOM
        c("pestana", "SEC001-025 Shadow DOM");
        c("valdomiro", "SEC001-025 rótulo em Shadow DOM");
        // página 9 — dinâmica
        c("paranhos", "SEC001-029/030 delta de texto");
        c("tremembe", "SEC001-031 delta de atributo");
        c("laranjeira", "SEC001-029/034 texto inserido");
        // página 11 — exportação
        c("assumpcao", "SEC001-039 âncora de contexto (seção, diálogo)");
        c("esperanca", "SEC001-039 nome de formulário");
        c("saraiva", "SEC001-086 título do documento");
        // página 12 — recuperação
        c("cavalcanti", "SEC001-061/062/063 recuperação");
    }

    private static void c(String token, String surface) {
        CANARIES.put(token, surface);
    }

    private PrivacyCanaries() {
    }

    /** Um achado: marcador, superfície de origem, destino e trecho (normalizado) em volta. */
    public record Finding(String canary, String surface, String destination, String excerpt) {
    }

    private static final Pattern UNICODE_ESCAPE = Pattern.compile("\\\\u([0-9a-fA-F]{4})");
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x?)([0-9a-fA-F]+);");

    /** Forma comparável: escapes desfeitos, sem acentos, minúsculas, separadores como espaço. */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw;
        Matcher m = UNICODE_ESCAPE.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf((char) Integer.parseInt(m.group(1), 16))));
        }
        m.appendTail(sb);
        s = sb.toString();
        Matcher e = NUMERIC_ENTITY.matcher(s);
        sb = new StringBuilder();
        while (e.find()) {
            int cp = Integer.parseInt(e.group(2), e.group(1).isEmpty() ? 10 : 16);
            e.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(cp))));
        }
        e.appendTail(sb);
        s = sb.toString().replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'");
        if (s.contains("%")) {
            try {
                s = s + " " + URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8); // original + decodificado
            } catch (IllegalArgumentException ignored) {
                // sequência % inválida: fica só o original
            }
        }
        s = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        s = s.toLowerCase(Locale.ROOT).replaceAll("[\\s_\\-+.]+", " ");
        return s;
    }

    /** Procura todos os marcadores num destino. */
    public static List<Finding> scan(String destination, String content) {
        List<Finding> out = new ArrayList<>();
        String n = normalize(content);
        for (Map.Entry<String, String> c : CANARIES.entrySet()) {
            String token = c.getKey().replaceAll("[\\s_\\-+.]+", " ");
            int at = n.indexOf(token);
            if (at >= 0) {
                out.add(new Finding(c.getKey(), c.getValue(), destination, n.substring(Math.max(0, at - 90), Math.min(n.length(), at + token.length() + 40))));
            }
        }
        return out;
    }
}
