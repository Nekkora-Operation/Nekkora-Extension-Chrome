package com.nekkora.extension.lab;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * STUDIO-009 — laboratório "Consulta de registro" (dados 100% fictícios). Oráculo INDEPENDENTE: registra no servidor
 * cada consulta efetivamente recebida (código e instante), sem ler a página nem o navegador. O reset é verificável
 * ({@code total = 0} na resposta). Códigos válidos: {@code REG-0001} … {@code REG-0005}.
 */
final class Studio009 {

    private static final Map<String, String> REGISTROS = Map.of("REG-0001", "ATIVO", "REG-0002", "ATIVO", "REG-0003", "SUSPENSO",
            "REG-0004", "ENCERRADO", "REG-0005", "ATIVO");
    private static final List<String[]> CONSULTAS = new ArrayList<>();

    private Studio009() {
    }

    static synchronized String consulta(String codigo) {
        String c = codigo == null ? "" : codigo.strip();
        CONSULTAS.add(new String[] {c.length() > 40 ? c.substring(0, 40) : c, Instant.now().toString()});
        String situacao = REGISTROS.get(c);
        return situacao == null ? "{\"encontrado\":false}"
                : "{\"encontrado\":true,\"registro\":{\"codigo\":\"" + c + "\",\"situacao\":\"" + situacao + "\"}}";
    }

    static synchronized String estado() {
        StringBuilder b = new StringBuilder("{\"total\":").append(CONSULTAS.size()).append(",\"consultas\":[");
        for (int i = 0; i < CONSULTAS.size(); i++) {
            String[] x = CONSULTAS.get(i);
            b.append(i == 0 ? "" : ",").append("{\"codigo\":\"").append(x[0].replaceAll("[^A-Za-z0-9-]", "?")).append("\",\"em\":\"").append(x[1]).append("\"}");
        }
        return b.append("]}").toString();
    }

    static synchronized String reset() {
        CONSULTAS.clear();
        return "{\"ok\":true,\"total\":0}";
    }
}
