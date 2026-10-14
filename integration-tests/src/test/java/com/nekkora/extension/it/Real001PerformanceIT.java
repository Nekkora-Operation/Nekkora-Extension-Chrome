package com.nekkora.extension.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nekkora.extension.it.support.BrowserLab.Page;
import com.nekkora.extension.it.support.SessionIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NEKKORA-REAL-001 §7 — investigação da oscilação da seleção em ~10.000 elementos (86 × 127 ms entre rodadas da
 * CHROME-003): medições REPETIDAS, separando o cenário frio (primeira seleção numa página recém-capturada) do aquecido
 * (seleções seguintes na mesma sessão). Nenhuma conclusão de regressão é tirada de uma medição isolada.
 */
class Real001PerformanceIT extends SessionIT {

    static final int RUNS = 7;
    static final int WARM = 10;
    static final ObjectMapper M = new ObjectMapper();

    record Stats(int n, double median, double p95, double mean, double stdev, double cv, double min, double max) {
        static Stats of(List<Double> values) {
            List<Double> v = new ArrayList<>(values);
            Collections.sort(v);
            int n = v.size();
            double median = n % 2 == 1 ? v.get(n / 2) : (v.get(n / 2 - 1) + v.get(n / 2)) / 2;
            double p95 = v.get(Math.max(0, (int) Math.ceil(0.95 * n) - 1)); // posto mais próximo
            double mean = v.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double var = v.stream().mapToDouble(x -> (x - mean) * (x - mean)).sum() / Math.max(1, n - 1);
            double sd = Math.sqrt(var);
            return new Stats(n, r(median), r(p95), r(mean), r(sd), mean == 0 ? 0 : r(100 * sd / mean), r(v.get(0)), r(v.get(n - 1)));
        }

        ObjectNode json() {
            ObjectNode o = M.createObjectNode();
            o.put("n", n).put("median", median).put("p95", p95).put("mean", mean).put("stdev", stdev).put("cvPercent", cv).put("min", min).put("max", max);
            return o;
        }

        String row(String label) {
            return String.format(Locale.ROOT, "| %s | %d | %.1f | %.1f | %.1f | %.1f | %.1f%% | %.1f | %.1f |", label, n, median, p95, mean, stdev, cv, min, max);
        }
    }

    static double r(double v) {
        return Math.round(v * 10) / 10.0;
    }

    static double ms(long t0) {
        return (System.nanoTime() - t0) / 1e6;
    }

    @Test
    @DisplayName("RP-01 — Seleção em 5.000 e 10.000 elementos: frio × aquecido, 7 execuções, mediana/p95/variabilidade")
    void selectionVariability() throws Exception {
        ObjectNode report = M.createObjectNode();
        report.put("runs", RUNS).put("warmSelectionsPerRun", WARM).put("procedure",
                "Por execução: página nova /generated/large?n=N; sessão de captura cooperativa (session.acquire); 1ª seleção = FRIA;"
                        + " " + WARM + " seleções seguintes (5 linhas alternadas) = AQUECIDAS; tempo de ponta a ponta medido no teste.");
        ArrayNode sizes = report.putArray("sizes");
        StringBuilder md = new StringBuilder();
        md.append("# NEKKORA-REAL-001 — variabilidade da seleção (frio × aquecido)\n\n")
                .append("Gerado por `Real001PerformanceIT`. ").append(RUNS).append(" execuções independentes por tamanho (página nova a cada execução);")
                .append(" ").append(WARM).append(" seleções aquecidas por execução. Tempos de ponta a ponta (teste → service worker → motor → teste), em ms.\n\n")
                .append("| Métrica | amostras | mediana | p95 | média | desvio | CV | mín. | máx. |\n|---|---|---|---|---|---|---|---|---|\n");
        for (int size : new int[]{5000, 10000}) {
            List<Double> start = new ArrayList<>();
            List<Double> cold = new ArrayList<>();
            List<Double> warm = new ArrayList<>();
            List<Double> coldSync = new ArrayList<>();
            ArrayNode perRun = M.createArrayNode();
            for (int run = 0; run < RUNS; run++) {
                Page p = open("/generated/large?n=" + size);
                long s0 = System.nanoTime();
                lab.call("session.acquire", p, "consumer", "perf", "cooperative", true);
                start.add(ms(s0));
                long c0 = System.nanoTime();
                JsonNode first = select(p, "#grande tr:last-child button");
                cold.add(ms(c0));
                coldSync.add(first.at("/sync/ms").asDouble(Double.NaN));
                List<Double> w = new ArrayList<>();
                for (int i = 0; i < WARM; i++) {
                    long w0 = System.nanoTime();
                    select(p, "#grande tr:nth-child(" + (2 + (i % 5) * 7) + ") button");
                    w.add(ms(w0));
                }
                warm.addAll(w);
                ObjectNode rr = perRun.addObject();
                rr.put("run", run + 1).put("sessionStartMs", r(start.get(run))).put("coldSelectMs", r(cold.get(run)))
                        .put("warmMedianMs", Stats.of(w).median()).put("nodes", session(p).get("nodes").asInt())
                        .put("fullCaptures", session(p).get("fullCaptures").asInt());
                assertEquals(1, session(p).get("fullCaptures").asInt(), "seleções sem nova captura completa");
                lab.call("session.release", p, "consumer", "perf", "reason", "PERF_DONE");
                lab.close(p);
            }
            Stats sStart = Stats.of(start);
            Stats sCold = Stats.of(cold);
            Stats sWarm = Stats.of(warm);
            ObjectNode o = sizes.addObject();
            o.put("requestedElements", size);
            o.set("sessionStart", sStart.json());
            o.set("coldSelection", sCold.json());
            o.set("warmSelection", sWarm.json());
            o.set("runs", perRun);
            md.append(sStart.row(size + " · início da sessão")).append('\n')
                    .append(sCold.row(size + " · seleção FRIA (1ª)")).append('\n')
                    .append(sWarm.row(size + " · seleção AQUECIDA")).append('\n');
            if (size == 10000) {
                assertTrue(sWarm.median() <= 1500 && sCold.median() <= 1500, "limite da CHROME-002 (≤ 1,5 s): frio " + sCold + " aquecido " + sWarm);
            }
        }
        md.append("\nReferência: CHROME-003, rodadas únicas do C002-038 (seleção após captura síncrona prévia): 86 ms (baseline CHROME-002),")
                .append(" 92 ms e 127 ms (rodadas da CHROME-003).\n");
        Path dir = lab.repo().resolve("reports").resolve("real-001");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("performance.json"), M.writerWithDefaultPrettyPrinter().writeValueAsString(report), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("performance.md"), md.toString(), StandardCharsets.UTF_8);
    }
}
