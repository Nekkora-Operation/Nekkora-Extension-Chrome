package com.nekkora.extension.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nekkora.extension.it.support.BrowserLab.Page;
import com.nekkora.extension.it.support.FrameIT;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Desempenho da CHROME-003 (§30): descoberta de frames, grafo, trajetórias entre documentos, reencontro, atualização
 * após navegação, diagnóstico de proteções, memória, observadores, PageMap e exportação — com 1/5/10/25 iframes e
 * diferentes profundidades — e comparação com o baseline da CHROME-002 (reports/chrome-003/baseline).
 */
class Chrome003PerformanceIT extends FrameIT {

    private static final ArrayNode FRAMES_ROWS = M.createArrayNode();
    private static final ObjectNode REPORT = M.createObjectNode();

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double ms(long t0) {
        return round((System.nanoTime() - t0) / 1e6);
    }

    /** Botão "Confirmar i" do frame mais profundo do bloco i. */
    private String deepButton(Page p, int block, int depth) {
        Object[] chain = new Object[depth * 2];
        chain[0] = "FRAME";
        chain[1] = "section[aria-label='Bloco " + block + "'] iframe";
        for (int l = 1; l < depth; l++) {
            chain[l * 2] = "FRAME";
            chain[l * 2 + 1] = "iframe";
        }
        return idIn(p, "button", chain);
    }

    @Test
    @DisplayName("D003-001 — 1/5/10/25 iframes e profundidades 1–5: descoberta, grafo, trajetória, reencontro, navegação, diagnóstico, memória e exportação")
    void frames() throws Exception {
        int[][] cases = {{1, 1}, {5, 1}, {10, 1}, {25, 1}, {5, 3}, {3, 5}, {10, 3}};
        for (int[] c : cases) {
            int count = c[0];
            int depth = c[1];
            int expectedFrames = count * depth;
            Page p = open("/generated/frames?count=" + count + "&depth=" + depth);
            ObjectNode row = FRAMES_ROWS.addObject();
            row.put("iframes", count);
            row.put("depth", depth);
            row.put("frames", expectedFrames);
            // Descoberta: ativação → sessão ACTIVE → todos os frames inventariados.
            long t0 = System.nanoTime();
            lab.call("inspection.activate", p);
            waitSessionActive(p);
            row.put("activationToActiveMs", ms(t0));
            waitGraph(p, g -> g.at("/counts/frames").asInt() == expectedFrames && g.at("/counts/inspected").asInt() == expectedFrames + 1,
                    expectedFrames + " frames inventariados");
            row.put("discoveryEndToEndMs", ms(t0));
            JsonNode s = session(p);
            row.put("nodes", s.get("nodes").asInt());
            row.put("fullCaptures", s.get("fullCaptures").asInt());
            row.put("captureMs", s.at("/lastCapture/captureMs").asDouble());
            row.put("hostCaptureMs", s.at("/lastCapture/hostMs").asDouble());
            // Grafo + proteções + prontidão (motor) e ida e volta completa (barreira + metadados + motor).
            long d0 = System.nanoTime();
            JsonNode d = diagnose(p);
            row.put("diagnoseEndToEndMs", ms(d0));
            row.put("graphMs", d.at("/timings/graphMs").asDouble());
            row.put("protectionMs", d.at("/timings/protectionMs").asDouble());
            row.put("readinessMs", d.at("/timings/readinessMs").asDouble());
            row.put("engineDiagnoseMs", d.at("/timings/totalMs").asDouble());
            assertEquals(expectedFrames + 1, d.at("/counts/documents").asInt());
            // Trajetória e reencontro do elemento mais profundo.
            String id = deepButton(p, count, depth);
            long a0 = System.nanoTime();
            JsonNode path = accessPath(p, id);
            row.put("accessPathEndToEndMs", ms(a0));
            assertEquals(depth, path.get("frameDepth").asInt());
            long q0 = System.nanoTime();
            JsonNode sel = selectNode(p, id);
            row.put("selectMs", ms(q0));
            long r0 = System.nanoTime();
            JsonNode r = resolve(p, sel.at("/identity/targetId").asText());
            row.put("resolveMs", ms(r0));
            assertEquals("RESOLVED", r.get("status").asText());
            // Atualização após navegação interna de um frame (nível 1 do bloco 1).
            long n0 = System.nanoTime();
            lab.js(p, "document.querySelector(\"section[aria-label='Bloco 1'] iframe\").contentWindow.location.href = '/generated/frame?i=99&level=1&depth=1'");
            waitGraph(p, g -> frameTitled(g, "Frame 1 nível 1") != null && "Frame 99 nível 1".equals(frameTitled(g, "Frame 1 nível 1").path("documentTitle").asText()),
                    "navegação refletida no grafo");
            row.put("navigationUpdateMs", ms(n0));
            row.put("fullCapturesAfterNavigation", session(p).get("fullCaptures").asInt());
            // Observadores, memória e exportação.
            JsonNode diag = lab.call("page.diagnostics", p);
            row.put("observers", diag.get("observers").asInt());
            row.put("registrySize", diag.get("registrySize").asInt());
            JsonNode mem = lab.call("host.memory", Map.of("gc", true));
            row.put("hostHeapUsedMb", mem.get("heapUsedMb").asDouble());
            Object pageHeap = lab.js(p, "return performance.memory ? Math.round(performance.memory.usedJSHeapSize / 1048576 * 10) / 10 : null");
            if (pageHeap != null) {
                row.put("pageHeapUsedMb", ((Number) pageHeap).doubleValue());
            }
            long x0 = System.nanoTime();
            JsonNode exp = lab.call("pagemap.export", p, "returnPageMap", false);
            row.put("exportEndToEndMs", ms(x0));
            row.put("exportMs", exp.get("exportMs").asDouble());
            row.put("exportBytes", exp.get("bytes").asLong());
            deactivate(p);
            lab.close(p);
            assertTrue(row.get("diagnoseEndToEndMs").asDouble() < 5000, row.toString());
            assertEquals(1, row.get("fullCaptures").asInt(), "uma única captura completa por sessão");
            assertEquals(1, row.get("fullCapturesAfterNavigation").asInt(), "navegação de frame não recaptura a página");
        }
    }

    @Test
    @DisplayName("D003-002 — Comparação com o baseline CHROME-002 (mesmo procedimento): 100 a 10.000 elementos")
    void baselineComparison() throws Exception {
        JsonNode baseline = M.readTree(lab.repo().resolve("reports/chrome-003/baseline/performance.json").toFile());
        ArrayNode rows = M.createArrayNode();
        for (int n : new int[]{100, 1000, 5000, 10000}) {
            JsonNode before = null;
            for (JsonNode b : baseline.get("sizes")) {
                if (b.get("requestedElements").asInt() == n) {
                    before = b;
                }
            }
            // Procedimento idêntico ao C002-038: captura síncrona + export anterior, depois sessão cooperativa,
            // seleção reutilizada e exportação em fluxo pela finalização da gravação.
            Page p = open("/generated/large?n=" + n);
            JsonNode legacy = capture(p);
            JsonNode legacyExport = lab.call("pagemap.export", p, "returnPageMap", false);
            long s0 = System.nanoTime();
            lab.call("session.acquire", p, "consumer", "perf", "cooperative", true);
            double start = ms(s0);
            JsonNode lc = session(p).get("lastCapture");
            long q0 = System.nanoTime();
            JsonNode sel = select(p, "#grande tr:last-child button");
            double select = ms(q0);
            long d0 = System.nanoTime();
            JsonNode d = diagnose(p);
            double diagnose = ms(d0);
            recording("started", "perf3-" + n, p);
            JsonNode stream = recording("finished", "perf3-" + n, p);
            assertEquals("EXPORTED", stream.get("status").asText(), stream.toString());
            ObjectNode row = rows.addObject();
            row.put("requestedElements", n);
            row.put("nodes", lc.get("nodes").asInt());
            row.put("legacyCaptureMs", legacy.at("/browser/captureMs").asDouble());
            row.put("baselineLegacyCaptureMs", before.get("legacyCaptureMs").asDouble());
            row.put("legacyExportMs", legacyExport.get("exportMs").asDouble());
            row.put("baselineLegacyExportMs", before.get("legacyExportMs").asDouble());
            row.put("legacyExportBytes", legacyExport.get("bytes").asLong());
            row.put("baselineLegacyExportBytes", before.get("legacyExportBytes").asLong());
            row.put("sessionStartEndToEndMs", start);
            row.put("baselineSessionStartEndToEndMs", before.get("sessionStartEndToEndMs").asDouble());
            row.put("cooperativeCaptureMs", lc.get("captureMs").asDouble());
            row.put("baselineCooperativeCaptureMs", before.get("cooperativeCaptureMs").asDouble());
            row.put("hostStartMs", lc.get("hostMs").asDouble());
            row.put("baselineHostStartMs", before.get("hostStartMs").asDouble());
            row.put("selectMs", select);
            row.put("baselineSelectReusedMs", before.get("selectReusedMs").asDouble());
            row.put("selectReusedSnapshot", sel.at("/sync/reusedSnapshot").asBoolean());
            row.put("streamExportMs", stream.get("exportMs").asDouble());
            row.put("baselineStreamExportMs", before.get("streamExportMs").asDouble());
            row.put("streamExportBytes", stream.get("bytes").asLong());
            row.put("baselineStreamExportBytes", before.get("streamExportBytes").asLong());
            row.put("diagnoseEndToEndMs", diagnose);
            row.put("engineDiagnoseMs", d.at("/timings/totalMs").asDouble());
            lab.close(p);
        }
        REPORT.set("baselineComparison", rows);
        for (JsonNode r : rows) {
            assertTrue(r.get("selectReusedSnapshot").asBoolean(), "seleção continua reutilizando o snapshot");
        }
    }

    @AfterAll
    static void writeReport() throws Exception {
        if (lab == null || (FRAMES_ROWS.isEmpty() && REPORT.isEmpty())) {
            return;
        }
        Path dir = lab.repo().resolve("reports").resolve("chrome-003");
        Files.createDirectories(dir);
        Path json = dir.resolve("performance.json");
        ObjectNode report = Files.exists(json) ? (ObjectNode) M.readTree(json.toFile()) : M.createObjectNode();
        report.put("generatedAt", Instant.now().toString());
        report.put("chrome", lab.chromeVersion());
        report.put("java", System.getProperty("java.version"));
        report.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        if (!FRAMES_ROWS.isEmpty()) {
            report.set("frames", FRAMES_ROWS);
        }
        REPORT.fields().forEachRemaining(f -> report.set(f.getKey(), f.getValue()));
        Files.writeString(json, M.writerWithDefaultPrettyPrinter().writeValueAsString(report), StandardCharsets.UTF_8);

        StringBuilder md = new StringBuilder("# CHROME-003 — desempenho medido\n\n")
                .append("Gerado em ").append(report.get("generatedAt").asText()).append(" · Chrome for Testing ").append(lab.chromeVersion())
                .append(" · Java ").append(System.getProperty("java.version")).append(" · ").append(report.get("os").asText()).append("\n\n")
                .append("Páginas geradas `/generated/frames?count=N&depth=D` (mesma origem). Tempos de ida e volta medidos pelo WebDriver ")
                .append("(incluem o harness); tempos do motor medidos no Java.\n\n");
        if (report.has("frames")) {
            List<String> cols = List.of("iframes", "depth", "frames", "nodes", "discoveryEndToEndMs", "captureMs", "graphMs", "protectionMs",
                    "readinessMs", "diagnoseEndToEndMs", "accessPathEndToEndMs", "resolveMs", "navigationUpdateMs", "observers",
                    "hostHeapUsedMb", "pageHeapUsedMb", "exportMs", "exportBytes");
            md.append("| ").append(String.join(" | ", cols)).append(" |\n|").append("---|".repeat(cols.size())).append('\n');
            for (JsonNode r : report.get("frames")) {
                List<String> v = new ArrayList<>();
                cols.forEach(c -> v.add(r.path(c).asText("—")));
                md.append("| ").append(String.join(" | ", v)).append(" |\n");
            }
        }
        if (report.has("baselineComparison")) {
            md.append("\n## Comparação com o baseline CHROME-002 (mesmo procedimento do C002-038)\n\nValores no formato agora / baseline.\n\n")
                    .append("| Elementos | Nós | Captura síncrona (ms) | Export anterior (ms) | Bytes export anterior | Ciclo sessão (ms) | Captura cooperativa (ms) | ")
                    .append("Motor na base (ms) | Seleção (ms) | Export em fluxo (ms) | Bytes export em fluxo | Diagnóstico ida e volta (ms) |\n")
                    .append("|---|---|---|---|---|---|---|---|---|---|---|---|\n");
            for (JsonNode r : report.get("baselineComparison")) {
                md.append("| ").append(r.get("requestedElements")).append(" | ").append(r.get("nodes"));
                for (String[] k : new String[][]{{"legacyCaptureMs", "baselineLegacyCaptureMs"}, {"legacyExportMs", "baselineLegacyExportMs"},
                        {"legacyExportBytes", "baselineLegacyExportBytes"}, {"sessionStartEndToEndMs", "baselineSessionStartEndToEndMs"},
                        {"cooperativeCaptureMs", "baselineCooperativeCaptureMs"}, {"hostStartMs", "baselineHostStartMs"},
                        {"selectMs", "baselineSelectReusedMs"}, {"streamExportMs", "baselineStreamExportMs"},
                        {"streamExportBytes", "baselineStreamExportBytes"}}) {
                    md.append(" | ").append(r.get(k[0])).append(" / ").append(r.get(k[1]));
                }
                md.append(" | ").append(r.get("diagnoseEndToEndMs")).append(" |\n");
            }
        }
        Files.writeString(dir.resolve("performance.md"), md.toString(), StandardCharsets.UTF_8);
    }
}
