package com.nekkora.extension.lab;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.text.Normalizer;

/** API JSON fictícia do laboratório (dados inventados, sem relação com pessoas reais). */
final class LabApi {

    record Response(int status, String body, long delayMs) {
    }

    record Cliente(int id, String nome, String status, String cidade, String email) {
    }

    private static final List<Cliente> CLIENTES = List.of(
            new Cliente(101, "Ana", "ativo", "São Paulo", "ana@exemplo.test"),
            new Cliente(202, "Maria", "ativo", "Rio de Janeiro", "maria@exemplo.test"),
            new Cliente(303, "João", "inativo", "Curitiba", "joao@exemplo.test"),
            new Cliente(404, "Ana Paula", "ativo", "Recife", "anapaula@exemplo.test"),
            new Cliente(505, "Bruno", "inativo", "Salvador", "bruno@exemplo.test"),
            new Cliente(606, "Carla", "ativo", "Porto Alegre", "carla@exemplo.test"),
            new Cliente(707, "Diego", "ativo", "Belo Horizonte", "diego@exemplo.test"),
            new Cliente(808, "Eduarda", "inativo", "Fortaleza", "eduarda@exemplo.test"),
            new Cliente(909, "Fábio", "ativo", "Manaus", "fabio@exemplo.test"),
            new Cliente(1001, "Gabriela", "ativo", "Natal", "gabriela@exemplo.test"),
            new Cliente(1102, "Heitor", "inativo", "Vitória", "heitor@exemplo.test"),
            new Cliente(1203, "Isabela", "ativo", "Goiânia", "isabela@exemplo.test"),
            new Cliente(1304, "Mariana", "ativo", "Campinas", "mariana@exemplo.test"),
            new Cliente(1405, "Luana", "inativo", "Santos", "luana@exemplo.test"));

    private static final List<String> CIDADES = List.of("Campinas", "Santos", "Sorocaba", "Ribeirão Preto", "São José dos Campos",
            "Jundiaí", "Bauru", "Piracicaba", "Franca", "Limeira", "Americana", "Araraquara", "São Carlos", "Marília", "Presidente Prudente");

    Response handle(String path, Map<String, String> q) {
        long delay = Math.max(0, Math.min(LabServer.parseInt(q.get("delay"), 0), 5000));
        return switch (path) {
            case "/api/clientes" -> new Response(200, clientes(q), delay);
            case "/api/cidades" -> new Response(200, cidades(q), delay);
            // STUDIO-009: caso "Consulta de registro" — o ORÁCULO do laboratório vive aqui, no servidor (não na página)
            case "/api/studio009/consulta" -> new Response(200, Studio009.consulta(q.getOrDefault("codigo", "")), delay);
            case "/api/studio009/estado" -> new Response(200, Studio009.estado(), 0);
            case "/api/studio009/reset" -> new Response(200, Studio009.reset(), 0);
            default -> new Response(404, "{\"error\":\"rota inexistente\"}", 0);
        };
    }

    private String clientes(Map<String, String> q) {
        String nome = fold(q.getOrDefault("nome", ""));
        String status = q.getOrDefault("status", "");
        int page = Math.max(1, LabServer.parseInt(q.get("page"), 1));
        int size = Math.max(1, Math.min(LabServer.parseInt(q.get("size"), 5), 50));
        List<Cliente> filtered = new ArrayList<>();
        for (Cliente c : CLIENTES) {
            if (!nome.isEmpty() && !fold(c.nome()).contains(nome)) {
                continue;
            }
            if (!status.isEmpty() && !c.status().equals(status)) {
                continue;
            }
            filtered.add(c);
        }
        if ("nome".equals(q.get("sort"))) {
            filtered.sort(Comparator.comparing(c -> fold(c.nome())));
        } else if ("-nome".equals(q.get("sort"))) {
            filtered.sort(Comparator.comparing((Cliente c) -> fold(c.nome())).reversed());
        }
        int from = Math.min((page - 1) * size, filtered.size());
        int to = Math.min(from + size, filtered.size());
        StringBuilder sb = new StringBuilder("{\"total\":").append(filtered.size()).append(",\"page\":").append(page)
                .append(",\"size\":").append(size).append(",\"items\":[");
        for (int i = from; i < to; i++) {
            Cliente c = filtered.get(i);
            if (i > from) {
                sb.append(',');
            }
            sb.append("{\"id\":").append(c.id()).append(",\"nome\":").append(str(c.nome())).append(",\"status\":")
                    .append(str(c.status())).append(",\"cidade\":").append(str(c.cidade())).append(",\"email\":")
                    .append(str(c.email())).append('}');
        }
        return sb.append("]}").toString();
    }

    private String cidades(Map<String, String> q) {
        String term = fold(q.getOrDefault("q", ""));
        StringBuilder sb = new StringBuilder("{\"items\":[");
        boolean first = true;
        for (String c : CIDADES) {
            if (!term.isEmpty() && !fold(c).contains(term)) {
                continue;
            }
            if (!first) {
                sb.append(',');
            }
            sb.append(str(c));
            first = false;
        }
        return sb.append("]}").toString();
    }

    static String fold(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).trim();
    }

    static String str(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                default -> {
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
