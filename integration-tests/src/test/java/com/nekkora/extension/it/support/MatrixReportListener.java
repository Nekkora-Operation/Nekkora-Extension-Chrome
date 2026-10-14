package com.nekkora.extension.it.support;

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Registra o resultado de cada caso da matriz (nomes iniciados por "T0xx —", "N-x —", "C-x —" ou "C002-xxx —") em
 * {@code reports/test-matrix-<fase>.md} e {@code .json}. O relatório é gerado a partir da EXECUÇÃO
 * real; nada é escrito para testes não executados.
 */
public final class MatrixReportListener implements TestExecutionListener {

    private static final Pattern ID = Pattern.compile("^((?:T\\d{3})|(?:N-[A-E])|(?:C-\\d+)|(?:C002-(?:\\d{3}|[A-Z]{3})(?: / C002-\\d{3})?))\\s+—\\s+(.*)$");
    private final Map<String, Long> started = new ConcurrentHashMap<>();
    private final List<String[]> rows = java.util.Collections.synchronizedList(new ArrayList<>());

    @Override
    public void executionStarted(TestIdentifier id) {
        if (id.isTest()) {
            started.put(id.getUniqueId(), System.nanoTime());
        }
    }

    @Override
    public void executionSkipped(TestIdentifier id, String reason) {
        Matcher m = ID.matcher(id.getDisplayName());
        if (m.matches()) {
            rows.add(new String[]{m.group(1), m.group(2), "NÃO EXECUTADO", "0", reason});
        }
    }

    @Override
    public void executionFinished(TestIdentifier id, TestExecutionResult result) {
        if (!id.isTest()) {
            return;
        }
        Matcher m = ID.matcher(id.getDisplayName());
        if (!m.matches()) {
            return;
        }
        long ms = (System.nanoTime() - started.getOrDefault(id.getUniqueId(), System.nanoTime())) / 1_000_000;
        String status = switch (result.getStatus()) {
            case SUCCESSFUL -> "APROVADO";
            case FAILED -> "REPROVADO";
            case ABORTED -> "ABORTADO";
        };
        String detail = result.getThrowable().map(t -> t.getClass().getSimpleName() + ": " + oneLine(t.getMessage())).orElse("");
        rows.add(new String[]{m.group(1), m.group(2), status, Long.toString(ms), detail});
    }

    @Override
    public void testPlanExecutionFinished(TestPlan plan) {
        if (rows.isEmpty()) {
            return;
        }
        Path repo = Paths.get(System.getProperty("nekkora.repo", "..")).toAbsolutePath().normalize();
        String phase = System.getProperty("nekkora.phase", rows.stream().anyMatch(r -> r[0].startsWith("T") || r[0].startsWith("C002")) ? "integration" : "unit");
        List<String[]> sorted = new ArrayList<>(rows);
        sorted.sort((a, b) -> a[0].compareTo(b[0]));
        StringBuilder md = new StringBuilder("# Matriz de homologação — execução " + phase + "\n\n")
                .append("Gerado automaticamente em ").append(Instant.now()).append(" a partir da execução real dos testes.\n\n")
                .append("| ID | Cenário | Resultado | Duração (ms) | Detalhe |\n|---|---|---|---|---|\n");
        StringBuilder json = new StringBuilder("{\"generatedAt\":\"" + Instant.now() + "\",\"phase\":\"" + phase + "\",\"results\":[");
        long passed = 0;
        for (int i = 0; i < sorted.size(); i++) {
            String[] r = sorted.get(i);
            if (r[2].equals("APROVADO")) {
                passed++;
            }
            md.append("| ").append(r[0]).append(" | ").append(esc(r[1])).append(" | ").append(r[2]).append(" | ")
                    .append(r[3]).append(" | ").append(esc(r[4])).append(" |\n");
            json.append(i == 0 ? "" : ",").append("{\"id\":\"").append(r[0]).append("\",\"scenario\":\"").append(jsonEsc(r[1]))
                    .append("\",\"status\":\"").append(r[2]).append("\",\"durationMs\":").append(r[3])
                    .append(",\"detail\":\"").append(jsonEsc(r[4])).append("\"}");
        }
        md.append("\nTotal: ").append(sorted.size()).append(" · aprovados: ").append(passed)
                .append(" · não aprovados: ").append(sorted.size() - passed).append('\n');
        json.append("],\"total\":").append(sorted.size()).append(",\"passed\":").append(passed).append('}');
        try {
            Path dir = repo.resolve("reports");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("test-matrix-" + phase + ".md"), md, StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("test-matrix-" + phase + ".json"), json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Falha ao gravar relatório da matriz: " + e.getMessage());
        }
    }

    private static String oneLine(String s) {
        if (s == null) {
            return "";
        }
        String l = s.replaceAll("\\s+", " ").trim();
        return l.length() > 300 ? l.substring(0, 300) + "…" : l;
    }

    private static String esc(String s) {
        return s.replace("|", "\\|");
    }

    private static String jsonEsc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
