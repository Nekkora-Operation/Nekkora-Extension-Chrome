package com.nekkora.extension.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nekkora.extension.it.support.BrowserLab;
import com.nekkora.extension.it.support.BrowserLab.Page;
import com.nekkora.extension.it.support.LabIT;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fase 11 — desempenho medido no navegador real.
 *
 * <p>Orçamento provisório definido ANTES das medições (docs/test-plan.md §Desempenho):
 * captura de 10.000 elementos ≤ 3.000 ms; classificação Java de 10.000 ≤ 2.000 ms; ciclo completo
 * de captura (navegador + transporte + host) ≤ 8.000 ms; reencontro ≤ 1.500 ms; geração de
 * localizadores ≤ 25 ms por elemento interativo em média; processamento de delta no host ≤ 200 ms.
 */
class PerformanceIT extends LabIT {

    static final ObjectMapper M = new ObjectMapper();
    static final ArrayNode SIZES = M.createArrayNode();
    static ObjectNode mutations;

    @Test
    @DisplayName("T036 — Página grande: desempenho mensurado")
    void largePages() throws InterruptedException {
        for (int n : new int[]{100, 1000, 5000, 10000}) {
            Page p = open("/generated/large?n=" + n);
            // Batimento no mundo da página: o maior intervalo entre ticks mede o bloqueio da thread principal.
            String heartbeat = "window.__hb = {max: 0, last: performance.now()};"
                    + " window.__hb.id = setInterval(() => { const n = performance.now(); window.__hb.max = Math.max(window.__hb.max, n - window.__hb.last); window.__hb.last = n; }, 5);";
            lab.js(p, heartbeat);
            Thread.sleep(300);
            Object baselineGap = lab.js(p, "const m = window.__hb.max; clearInterval(window.__hb.id); return m;");
            lab.js(p, heartbeat);
            long t0 = System.nanoTime();
            JsonNode cap = capture(p);
            double e2eMs = (System.nanoTime() - t0) / 1e6;
            Thread.sleep(100);
            Object longTasks = lab.js(p, "const m = window.__hb.max; clearInterval(window.__hb.id); return m;");
            Object pageHeap = lab.js(p, "return performance.memory ? Math.round(performance.memory.usedJSHeapSize / 1048576 * 10) / 10 : null");

            long s0 = System.nanoTime();
            JsonNode sel = select(p, "#grande tr:last-child button");
            double selectMs = (System.nanoTime() - s0) / 1e6;
            JsonNode res = lab.call("target.resolve", p, "targetId", sel.at("/identity/targetId").asText());
            // CHROME-SECURITY-001: as linhas da tabela grande só se distinguiam pelo texto (dado): ambiguidade declarada,
            // nunca outra linha
            String st = res.at("/result/status").asText();
            assertTrue("RESOLVED".equals(st) ? sel.at("/inspection/nodeId").asText().equals(res.at("/result/resolvedNodeId").asText())
                    : java.util.Set.of("AMBIGUOUS", "INSUFFICIENT_EVIDENCE").contains(st) && res.at("/result/resolvedNodeId").isNull(), res.toString());
            JsonNode exp = lab.call("pagemap.export", p, "returnPageMap", false);
            JsonNode metrics = lab.call("pagemap.export", p, "returnPageMap", true).at("/pageMap/metrics");
            JsonNode ping = lab.call("host.ping", Map.of()).get("ping");

            ObjectNode row = M.createObjectNode();
            row.put("requestedElements", n);
            row.put("nodesCaptured", cap.get("nodes").asInt());
            row.put("interactive", cap.get("interactive").asInt());
            row.put("captureMs", cap.at("/browser/captureMs").asDouble());
            row.put("snapshotBytes", cap.at("/browser/snapshotBytes").asLong());
            row.put("transportChunked", cap.at("/browser/chunked").asBoolean());
            row.put("hostIndexMs", cap.at("/timings/indexMs").asDouble());
            row.put("classificationMs", cap.at("/timings/classificationMs").asDouble());
            row.put("hostSubmitMs", cap.at("/timings/hostTotalMs").asDouble());
            row.put("captureEndToEndMs", round(e2eMs));
            row.put("baselineMaxGapMs", round(((Number) baselineGap).doubleValue()));
            row.put("maxMainThreadGapMs", round(((Number) longTasks).doubleValue()));
            row.put("selectEndToEndMs", round(selectMs));
            row.put("inspectFamilyMs", sel.at("/inspection/timings/familyMs").asDouble());
            row.put("inspectLocatorMs", sel.at("/inspection/timings/locatorMs").asDouble());
            row.put("resolveMs", res.at("/timings/resolveMs").asDouble());
            row.put("exportMs", exp.get("exportMs").asDouble());
            row.put("pageMapBytes", exp.get("bytes").asLong());
            row.put("locatorTargets", metrics.get("locatorTargets").asDouble());
            row.put("locatorTotalMs", metrics.get("locatorMs").asDouble());
            row.put("locatorAvgMs", round(metrics.get("locatorMs").asDouble() / Math.max(1, metrics.get("locatorTargets").asDouble())));
            row.put("familyTotalMs", metrics.get("familyMs").asDouble());
            row.put("hostHeapUsedMb", ping.get("heapUsedMb").asDouble());
            if (pageHeap != null) {
                row.put("pageHeapUsedMb", ((Number) pageHeap).doubleValue());
            }
            SIZES.add(row);
            lab.close(p);

            if (n == 10000) {
                assertTrue(row.get("captureMs").asDouble() <= 3000, "captura 10k: " + row.get("captureMs"));
                assertTrue(row.get("classificationMs").asDouble() <= 2000, "classificação 10k: " + row.get("classificationMs"));
                assertTrue(row.get("captureEndToEndMs").asDouble() <= 8000, "ciclo 10k: " + row.get("captureEndToEndMs"));
                assertTrue(row.get("resolveMs").asDouble() <= 1500, "reencontro 10k: " + row.get("resolveMs"));
                assertTrue(row.get("locatorAvgMs").asDouble() <= 25, "localizadores 10k: " + row.get("locatorAvgMs"));
                assertTrue(row.get("nodesCaptured").asInt() >= 9000, "página de 10k elementos inventariada");
            }
        }
    }

    @Test
    @DisplayName("T037 — Mutações frequentes: atualização incremental")
    void frequentMutations() throws Exception {
        Page p = open("/lab/complex/rerender.html");
        lab.call("tracking.start", p);
        lab.clearEvents();
        lab.click(p, By.id("iniciar-relogio"));
        Thread.sleep(3000);
        lab.click(p, By.id("parar-relogio"));
        lab.call("tracking.flush", p);
        BrowserLab.waitUntil(Duration.ofSeconds(10), () -> lab.call("page.diagnostics", p).at("/tracker/pending").asInt() == 0, "deltas pendentes enviados");
        Thread.sleep(800);
        JsonNode stats = lab.call("page.diagnostics", p).get("tracker");
        JsonNode deltas = lab.events("delta");
        int ticks = ((Number) lab.js(p, "return Number(document.getElementById('contador').textContent)")).intValue();
        assertTrue(ticks >= 50, "o relógio gerou mutações: " + ticks);
        int sent = stats.get("deltasSent").asInt();
        int raw = stats.get("rawMutations").asInt();
        assertTrue(sent >= 3, "deltas enviados: " + sent);
        assertTrue(raw >= sent * 5, "lotes agregam mutações (raw=" + raw + ", deltas=" + sent + ")");
        double maxHost = 0;
        double sumHost = 0;
        int upserts = 0;
        for (JsonNode d : deltas) {
            assertEquals("INCREMENTAL", d.at("/data/mutation/mode").asText());
            double ms = d.at("/data/mutation/processingMs").asDouble();
            maxHost = Math.max(maxHost, ms);
            sumHost += ms;
            upserts += d.at("/data/timings/upserts").asInt();
        }
        assertTrue(maxHost <= 200, "processamento de delta no host: " + maxHost);
        // Consistência: o estado incremental equivale a uma captura completa.
        int incrementalNodes = deltas.get(deltas.size() - 1).at("/data/nodes").asInt(-1);
        JsonNode full = capture(p);
        assertEquals(full.get("nodes").asInt(), incrementalNodes, "nós após deltas = nós de uma captura completa");
        mutations = M.createObjectNode();
        mutations.put("clockTicks", ticks);
        mutations.put("rawMutations", raw);
        mutations.put("batches", stats.get("batches").asInt());
        mutations.put("deltasSent", sent);
        mutations.put("deltaEvents", deltas.size());
        mutations.put("upsertedNodes", upserts);
        mutations.put("ignoredRecords", stats.get("ignored").asInt());
        mutations.put("browserMaxDeltaMs", stats.get("maxDeltaMs").asDouble());
        mutations.put("hostMaxDeltaMs", maxHost);
        mutations.put("hostAvgDeltaMs", round(sumHost / Math.max(1, deltas.size())));
        mutations.put("resyncs", stats.get("resyncs").asInt());
        lab.call("tracking.stop", p);
    }

    @AfterAll
    static void writeReport() throws Exception {
        if (SIZES.isEmpty() && mutations == null) {
            return;
        }
        ObjectNode report = M.createObjectNode();
        report.put("generatedAt", Instant.now().toString());
        report.put("chrome", lab.chromeVersion());
        report.put("java", System.getProperty("java.version"));
        report.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        report.put("headless", Boolean.getBoolean("nekkora.headless"));
        report.set("pages", SIZES);
        if (mutations != null) {
            report.set("frequentMutations", mutations);
        }
        Path dir = lab.repo().resolve("reports");
        Files.writeString(dir.resolve("performance.json"), M.writerWithDefaultPrettyPrinter().writeValueAsString(report), StandardCharsets.UTF_8);
        StringBuilder md = new StringBuilder("# Desempenho medido (T036 / T037)\n\n")
                .append("Gerado em ").append(report.get("generatedAt").asText()).append(" · Chrome for Testing ").append(lab.chromeVersion())
                .append(" · Java ").append(System.getProperty("java.version")).append(" · ").append(report.get("os").asText()).append("\n\n")
                .append("| Elementos | Nós | Interativos | Captura (ms) | Maior bloqueio da página (ms) [base] | Snapshot (KB) | Classificação Java (ms) | Ciclo captura (ms) | Seleção ponta a ponta (ms) | Reencontro (ms) | Localizadores média (ms) | Export PageMap (ms) | Heap host (MB) |\n")
                .append("|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (JsonNode r : SIZES) {
            md.append("| ").append(r.get("requestedElements")).append(" | ").append(r.get("nodesCaptured")).append(" | ").append(r.get("interactive"))
                    .append(" | ").append(r.get("captureMs")).append(" | ").append(r.get("maxMainThreadGapMs")).append(" [").append(r.get("baselineMaxGapMs")).append("]")
                    .append(" | ").append(r.get("snapshotBytes").asLong() / 1024).append(" | ").append(r.get("classificationMs"))
                    .append(" | ").append(r.get("captureEndToEndMs")).append(" | ").append(r.get("selectEndToEndMs"))
                    .append(" | ").append(r.get("resolveMs")).append(" | ").append(r.get("locatorAvgMs"))
                    .append(" | ").append(r.get("exportMs")).append(" | ").append(r.get("hostHeapUsedMb")).append(" |\n");
        }
        if (mutations != null) {
            md.append("\n## Mutações frequentes (relógio a cada 20 ms por 3 s)\n\n```json\n")
                    .append(M.writerWithDefaultPrettyPrinter().writeValueAsString(mutations)).append("\n```\n");
        }
        Files.writeString(dir.resolve("performance.md"), md.toString(), StandardCharsets.UTF_8);
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
