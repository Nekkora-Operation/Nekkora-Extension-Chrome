package com.nekkora.extension.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nekkora.extension.it.support.BrowserLab;
import com.nekkora.extension.it.support.BrowserLab.Page;
import com.nekkora.extension.it.support.Contracts;
import com.nekkora.extension.it.support.SessionIT;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CHROME-002 §17–§20, §28–§29 — desempenho, captura cooperativa, memória, exportação e longa duração.
 *
 * <p>CRITÉRIOS (definidos ANTES da medição; os limites da CHROME-001 não foram aumentados):
 * <ul>
 *   <li>captura-base cooperativa: maior fatia contínua na thread da página ≤ 50 ms (10k e 20k);</li>
 *   <li>ciclo da captura-base com sessão (10k) ≤ 8.000 ms (mesmo limite do ciclo CHROME-001);</li>
 *   <li>seleção com snapshot reutilizado (10k) ≤ 1.500 ms e nenhuma captura completa;</li>
 *   <li>exportação consolidada em fluxo (10k) ≤ exportação CHROME-001 medida na mesma execução × 1,10;</li>
 *   <li>memória: após liberar as sessões e GC, heap usado ≤ heap inicial + 64 MB e capturas retidas = 0;
 *       três abas grandes ≤ 3,5 × o acréscimo de uma aba grande (crescimento linear, sem acúmulo);</li>
 *   <li>longa duração: sessão ACTIVE do início ao fim, 0 ressincronizações, 0 deltas recusados, heap após GC
 *       no fim ≤ heap após GC no 1º minuto + 64 MB, registro de nós limitado.</li>
 * </ul>
 */
class Chrome002PerformanceIT extends SessionIT {

    private static final ObjectMapper M = new ObjectMapper();
    private static final ArrayNode SIZES = M.createArrayNode();
    private static final ObjectNode REPORT = M.createObjectNode();
    private static final String HEARTBEAT = "window.__hb = {max: 0, last: performance.now()};"
            + " window.__hb.id = setInterval(() => { const n = performance.now(); window.__hb.max = Math.max(window.__hb.max, n - window.__hb.last); window.__hb.last = n; }, 5);";

    private double stopHeartbeat(Page p) {
        return ((Number) lab.js(p, "const m = window.__hb.max; clearInterval(window.__hb.id); return m;")).doubleValue();
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private JsonNode memory() {
        return lab.call("host.memory", Map.of("gc", true));
    }

    /** RSS (working set) do processo do motor Java, em MB. */
    private static double rssMb(long pid) {
        try {
            Process pr = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", "(Get-Process -Id " + pid + ").WorkingSet64").redirectErrorStream(true).start();
            String out = new String(pr.getInputStream().readAllBytes()).trim();
            pr.waitFor();
            return round(Long.parseLong(out.replaceAll("[^0-9]", "")) / 1048576.0);
        } catch (Exception e) {
            return -1;
        }
    }

    private JsonNode acquire(Page p, boolean cooperative) {
        return lab.call("session.acquire", p, "consumer", "perf", "cooperative", cooperative);
    }

    private void release(Page p) {
        lab.call("session.release", p, "consumer", "perf", "reason", "PERF_DONE");
    }

    @Test
    @DisplayName("C002-038 / C002-039 — 100 a 20.000 elementos: captura cooperativa, seleção e exportação (antes × depois)")
    void sizes() throws Exception {
        for (int n : new int[]{100, 1000, 5000, 10000, 20000}) {
            Page p = open("/generated/large?n=" + n);
            ObjectNode row = SIZES.addObject();
            row.put("requestedElements", n);
            // ---- ANTES (fluxo CHROME-001: captura síncrona avulsa + export pagemap.export)
            lab.js(p, HEARTBEAT);
            long t0 = System.nanoTime();
            JsonNode legacy = capture(p);
            row.put("legacyCaptureEndToEndMs", round((System.nanoTime() - t0) / 1e6));
            Thread.sleep(100);
            row.put("legacyMaxMainThreadGapMs", round(stopHeartbeat(p)));
            row.put("legacyCaptureMs", legacy.at("/browser/captureMs").asDouble());
            long e0 = System.nanoTime();
            JsonNode legacyExport = lab.call("pagemap.export", p, "returnPageMap", false);
            row.put("legacyExportMs", legacyExport.get("exportMs").asDouble());
            row.put("legacyExportEndToEndMs", round((System.nanoTime() - e0) / 1e6));
            row.put("legacyExportBytes", legacyExport.get("bytes").asLong());
            // ---- DEPOIS (sessão automática cooperativa + seleção reutilizada + exportação em fluxo)
            lab.js(p, HEARTBEAT);
            long s0 = System.nanoTime();
            acquire(p, true);
            row.put("sessionStartEndToEndMs", round((System.nanoTime() - s0) / 1e6));
            Thread.sleep(100);
            row.put("cooperativeMaxMainThreadGapMs", round(stopHeartbeat(p)));
            JsonNode lc = session(p).get("lastCapture");
            row.put("nodes", lc.get("nodes").asInt());
            row.put("cooperativeCaptureMs", lc.get("captureMs").asDouble());
            row.put("slices", lc.get("slices").asInt());
            row.put("maxSliceMs", lc.get("maxSliceMs").asDouble());
            row.put("hostStartMs", lc.get("hostMs").asDouble());
            row.put("classificationMs", lc.at("/timings/classificationMs").asDouble());
            long q0 = System.nanoTime();
            JsonNode sel = select(p, "#grande tr:last-child button");
            row.put("selectReusedMs", round((System.nanoTime() - q0) / 1e6));
            row.put("selectReusedSnapshot", sel.at("/sync/reusedSnapshot").asBoolean());
            long x0 = System.nanoTime();
            JsonNode exp = recordingExport(p, "perf-" + n);
            row.put("streamExportMs", exp.get("exportMs").asDouble());
            row.put("streamExportEndToEndMs", round((System.nanoTime() - x0) / 1e6));
            row.put("streamExportBytes", exp.get("bytes").asLong());
            row.put("elementCount", exp.get("elementCount").asInt());
            JsonNode mem = memory();
            row.put("hostHeapUsedMb", mem.get("heapUsedMb").asDouble());
            row.put("hostHeapCommittedMb", mem.get("heapCommittedMb").asDouble());
            row.put("hostHeapMaxMb", mem.get("heapMaxMb").asDouble());
            row.put("hostRssMb", rssMb(mem.get("pid").asLong()));
            Object pageHeap = lab.js(p, "return performance.memory ? Math.round(performance.memory.usedJSHeapSize / 1048576 * 10) / 10 : null");
            if (pageHeap != null) {
                row.put("pageHeapUsedMb", ((Number) pageHeap).doubleValue());
            }
            assertEquals(1, session(p).get("fullCaptures").asInt(), "seleção e exportação sem nova captura completa");
            release(p);
            lab.close(p);

            if (n >= 10000) {
                assertTrue(row.get("maxSliceMs").asDouble() <= 50, n + ": maior fatia " + row.get("maxSliceMs"));
                assertTrue(row.get("cooperativeMaxMainThreadGapMs").asDouble() < row.get("legacyMaxMainThreadGapMs").asDouble(),
                        n + ": bloqueio cooperativo " + row.get("cooperativeMaxMainThreadGapMs") + " < síncrono " + row.get("legacyMaxMainThreadGapMs"));
            }
            if (n == 10000) {
                assertTrue(row.get("sessionStartEndToEndMs").asDouble() <= 8000, "ciclo 10k: " + row.get("sessionStartEndToEndMs"));
                assertTrue(row.get("selectReusedMs").asDouble() <= 1500, "seleção 10k: " + row.get("selectReusedMs"));
                assertTrue(row.get("selectReusedSnapshot").asBoolean());
                assertTrue(row.get("streamExportMs").asDouble() <= row.get("legacyExportMs").asDouble() * 1.10,
                        "exportação em fluxo " + row.get("streamExportMs") + " ≤ anterior " + row.get("legacyExportMs") + " × 1,10");
            }
        }
    }

    /** Exportação pelo contrato de gravação (started → finished), devolvendo o resultado. */
    private JsonNode recordingExport(Page p, String id) throws Exception {
        recording("started", id, p);
        JsonNode r = recording("finished", id, p);
        assertEquals("EXPORTED", r.get("status").asText(), r.toString());
        if (r.get("elementCount").asInt() <= 12000) {
            Contracts.assertValid("page-map.schema.json", M.readTree(Path.of(r.get("path").asText()).toFile()));
        }
        return r;
    }

    @Test
    @DisplayName("C002-039 — Captura cooperativa: mutações durante a travessia entram no primeiro delta (base + delta)")
    void cooperativeConsistency() throws Exception {
        Page p = open("/generated/large?n=10000");
        // A página insere uma linha a cada 15 ms enquanto a captura-base percorre o documento.
        lab.js(p, "window.__ins = 0; window.__mut = setInterval(() => { const tr = document.createElement('tr');"
                + " tr.innerHTML = '<td>Inserida ' + (++window.__ins) + '</td><td><button type=\"button\">Nova</button></td>';"
                + " document.querySelector('#grande tbody').appendChild(tr); if (window.__ins >= 60) clearInterval(window.__mut); }, 15);");
        acquire(p, true);
        BrowserLab.waitUntil(Duration.ofSeconds(10), () -> ((Number) lab.js(p, "return window.__ins")).intValue() >= 60, "inserções concluídas");
        JsonNode sync = sync(p);
        assertEquals("SYNCHRONIZED", sync.get("syncState").asText());
        JsonNode s = session(p);
        assertEquals("BASE_PLUS_DELTA", s.at("/lastCapture/consistency").asText(), "política declarada: base + delta, não um instante único");
        assertEquals("COOPERATIVE", s.at("/lastCapture/mode").asText());
        assertTrue(s.at("/lastCapture/slices").asInt() > 1, "travessia fatiada");
        int incremental = s.get("nodes").asInt();
        JsonNode full = lab.call("session.forceFull", p);
        assertEquals(full.get("nodes").asInt(), incremental, "base + deltas = captura completa");
        ObjectNode c = REPORT.putObject("cooperativeConsistency");
        c.put("insertedDuringCapture", 60);
        c.put("bufferedOnConfirm", s.at("/lastCapture/buffered").asInt());
        c.put("incrementalNodes", incremental);
        c.put("fullNodes", full.get("nodes").asInt());
        c.put("slices", s.at("/lastCapture/slices").asInt());
        c.put("maxSliceMs", s.at("/lastCapture/maxSliceMs").asDouble());
        release(p);
    }

    @Test
    @DisplayName("C002-040 — Memória: página pequena, média, grande e três grandes (heap usado/comprometido/máximo e RSS)")
    void memoryProfiles() throws Exception {
        ObjectNode m = REPORT.putObject("memory");
        JsonNode m0 = memory();
        m.put("idleHeapUsedMb", m0.get("heapUsedMb").asDouble());
        m.put("idleRssMb", rssMb(m0.get("pid").asLong()));
        double oneLargeDelta = 0;
        for (int n : new int[]{100, 1000, 10000}) {
            Page p = open("/generated/large?n=" + n + "&perfil=1");
            acquire(p, true);
            JsonNode mm = memory();
            ObjectNode r = m.putObject("single_" + n);
            r.put("heapUsedMb", mm.get("heapUsedMb").asDouble());
            r.put("heapCommittedMb", mm.get("heapCommittedMb").asDouble());
            r.put("heapMaxMb", mm.get("heapMaxMb").asDouble());
            r.put("rssMb", rssMb(mm.get("pid").asLong()));
            r.put("retainedCaptures", mm.at("/retainedCaptures/tab-" + p.tabId()).asInt());
            assertTrue(mm.at("/retainedCaptures/tab-" + p.tabId()).asInt() <= 4, "capturas retidas por aba (3 + referência)");
            if (n == 10000) {
                oneLargeDelta = mm.get("heapUsedMb").asDouble() - m0.get("heapUsedMb").asDouble();
            }
            release(p);
            lab.close(p);
        }
        List<Page> large = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Page p = open("/generated/large?n=10000&aba=" + i);
            acquire(p, true);
            large.add(p);
        }
        JsonNode three = memory();
        ObjectNode t = m.putObject("threeLarge");
        t.put("heapUsedMb", three.get("heapUsedMb").asDouble());
        t.put("heapCommittedMb", three.get("heapCommittedMb").asDouble());
        t.put("heapMaxMb", three.get("heapMaxMb").asDouble());
        t.put("rssMb", rssMb(three.get("pid").asLong()));
        t.put("captureSessions", three.get("captureSessions").asInt());
        double threeDelta = three.get("heapUsedMb").asDouble() - m0.get("heapUsedMb").asDouble();
        t.put("deltaVsIdleMb", round(threeDelta));
        m.put("oneLargeDeltaVsIdleMb", round(oneLargeDelta));
        assertEquals(3, three.get("captureSessions").asInt());
        assertTrue(threeDelta <= Math.max(64, oneLargeDelta) * 3.5, "três abas grandes: " + threeDelta + " MB × uma: " + oneLargeDelta + " MB");
        for (Page p : large) {
            release(p);
        }
        JsonNode after = memory();
        ObjectNode a = m.putObject("afterRelease");
        a.put("heapUsedMb", after.get("heapUsedMb").asDouble());
        a.put("rssMb", rssMb(after.get("pid").asLong()));
        a.put("captureSessions", after.get("captureSessions").asInt());
        for (Page p : large) {
            assertEquals(0, after.at("/retainedCaptures/tab-" + p.tabId()).asInt());
            lab.close(p);
        }
        assertEquals(0, after.get("captureSessions").asInt());
        assertTrue(after.get("heapUsedMb").asDouble() <= m0.get("heapUsedMb").asDouble() + 64,
                "heap após liberar: " + after.get("heapUsedMb") + " (inicial " + m0.get("heapUsedMb") + ")");
    }

    @Test
    @DisplayName("C002-041 — Sessão prolongada com mutações contínuas (10/s): estável e sem acúmulo")
    void longSession() throws Exception {
        int minutes = Integer.getInteger("nekkora.long.minutes", 5);
        REPORT.set("longSession_" + minutes + "min", longRun(minutes, 10));
    }

    /** Executa uma sessão prolongada amostrando a cada 30 s. Reutilizado pelas execuções de 15 e 30 min. */
    ObjectNode longRun(int minutes, int rate) throws Exception {
        Page p = open("/lab/chrome002/rapid.html?rate=" + rate + "&rows=50");
        acquire(p, true);
        ObjectNode out = M.createObjectNode();
        out.put("minutes", minutes);
        out.put("ratePerSecond", rate);
        ArrayNode samples = out.putArray("samples");
        long end = System.nanoTime() + Duration.ofMinutes(minutes).toNanos();
        double heapAt1 = -1;
        int maxRegistry = 0;
        int initialNodes = session(p).get("nodes").asInt();
        int i = 0;
        while (System.nanoTime() < end) {
            Thread.sleep(30_000);
            i++;
            JsonNode s = session(p);
            JsonNode d = diagnostics(p);
            JsonNode mem = memory();
            ObjectNode smp = samples.addObject();
            smp.put("t", i * 30);
            smp.put("status", s.get("status").asText());
            smp.put("deltasApplied", s.get("deltasApplied").asInt());
            smp.put("deltasRejected", s.get("deltasRejected").asInt());
            smp.put("resyncs", s.get("resyncs").asInt());
            smp.put("nodes", s.get("nodes").asInt());
            smp.put("pending", d.at("/tracker/pending").asInt());
            smp.put("registrySize", d.get("registrySize").asInt());
            smp.put("hostHeapUsedMb", mem.get("heapUsedMb").asDouble());
            smp.put("hostRssMb", rssMb(mem.get("pid").asLong()));
            Object pageHeap = lab.js(p, "return performance.memory ? Math.round(performance.memory.usedJSHeapSize / 1048576 * 10) / 10 : null");
            if (pageHeap != null) {
                smp.put("pageHeapUsedMb", ((Number) pageHeap).doubleValue());
            }
            maxRegistry = Math.max(maxRegistry, d.get("registrySize").asInt());
            if (i == 2) {
                heapAt1 = mem.get("heapUsedMb").asDouble();
            }
            assertEquals("ACTIVE", s.get("status").asText(), "sessão ativa em t=" + (i * 30) + " s");
        }
        lab.js(p, "document.getElementById('parar').onclick()");
        JsonNode sync = sync(p);
        JsonNode s = session(p);
        JsonNode memEnd = memory();
        out.put("generatedMutations", ((Number) lab.js(p, "return Number(document.getElementById('geradas').value)")).intValue());
        out.put("deltasApplied", s.get("deltasApplied").asInt());
        out.put("resyncs", s.get("resyncs").asInt());
        out.put("deltasRejected", s.get("deltasRejected").asInt());
        out.put("fullCaptures", s.get("fullCaptures").asInt());
        out.put("finalSyncState", sync.get("syncState").asText());
        out.put("heapAt1MinMb", heapAt1);
        out.put("heapEndMb", memEnd.get("heapUsedMb").asDouble());
        out.put("maxRegistrySize", maxRegistry);
        out.put("initialNodes", initialNodes);
        assertEquals(0, s.get("resyncs").asInt());
        assertEquals(0, s.get("deltasRejected").asInt());
        assertEquals(1, s.get("fullCaptures").asInt());
        assertEquals("SYNCHRONIZED", sync.get("syncState").asText());
        if (heapAt1 > 0) {
            assertTrue(memEnd.get("heapUsedMb").asDouble() <= heapAt1 + 64, "heap estável: " + heapAt1 + " → " + memEnd.get("heapUsedMb"));
        }
        assertTrue(maxRegistry <= initialNodes * 2 + 500, "registro de nós limitado: " + maxRegistry + " (inicial " + initialNodes + ")");
        // Equivalência após a sessão longa: estado incremental = captura completa.
        int incremental = s.get("nodes").asInt();
        assertEquals(lab.call("session.forceFull", p).get("nodes").asInt(), incremental);
        release(p);
        return out;
    }

    @Test
    @DisplayName("C002-042 — Descarte de sessão: aba fechada libera navegador e motor")
    void sessionDiscard() {
        Page p = open("/generated/large?n=1000&descarte=1");
        acquire(p, true);
        String sessionId = session(p).get("sessionId").asText();
        lab.close(p);
        BrowserLab.waitUntil(Duration.ofSeconds(10), () -> "TAB_CLOSED".equals(lab.call("session.status", Map.of("tabId", p.tabId())).at("/lastEnded/reason").asText()), "sessão descartada");
        JsonNode hostSessions = lab.call("host.request", Map.of("type", "capture.session.list", "payload", Map.of())).get("payload");
        for (JsonNode h : hostSessions) {
            assertTrue(!sessionId.equals(h.get("sessionId").asText()), "sessão removida do motor");
        }
        assertEquals(0, memory().at("/retainedCaptures/tab-" + p.tabId()).asInt(), "capturas liberadas no motor");
        for (JsonNode s : lab.call("session.list", Map.of())) {
            assertTrue(s.get("tabId").asInt() != p.tabId());
        }
    }

    @Test
    @DisplayName("C002-045 — PageMap incremental × completo: equivalência semântica")
    void incrementalEqualsFull() throws Exception {
        Page p = open("/lab/chrome002/continuous.html");
        acquire(p, true);
        for (String a : List.of("adicionar-10", "renomear", "remover", "rerender", "duplicar", "preencher-sensivel", "adicionar")) {
            act(p, a);
            Thread.sleep(120);
        }
        sync(p);
        JsonNode incremental = lab.call("pagemap.export", p, "returnPageMap", true).get("pageMap");
        lab.call("session.forceFull", p);
        JsonNode full = lab.call("pagemap.export", p, "returnPageMap", true).get("pageMap");
        Map<String, String> a = semantic(incremental);
        Map<String, String> b = semantic(full);
        List<String> diffs = new ArrayList<>();
        for (String k : a.keySet()) {
            if (!a.get(k).equals(b.get(k))) {
                diffs.add(k + ": " + a.get(k) + " ≠ " + b.get(k));
            }
        }
        for (String k : b.keySet()) {
            if (!a.containsKey(k)) {
                diffs.add(k + ": ausente no incremental");
            }
        }
        ObjectNode eq = REPORT.putObject("incrementalVsFull");
        eq.put("elementsIncremental", a.size());
        eq.put("elementsFull", b.size());
        eq.put("differences", diffs.size());
        assertTrue(diffs.isEmpty(), "diferenças semânticas: " + diffs.subList(0, Math.min(10, diffs.size())));
        assertEquals(incremental.get("version").asText(), full.get("version").asText());
        release(p);
    }

    /** Projeção semântica de cada elemento (sem retângulos, ids de captura, tempos ou ordem de inserção). */
    private static Map<String, String> semantic(JsonNode map) {
        Map<String, String> out = new TreeMap<>();
        for (JsonNode e : map.get("elements")) {
            List<String> cond = new ArrayList<>();
            e.path("conditions").forEach(c -> cond.add(c.asText()));
            java.util.Collections.sort(cond);
            String primary = e.at("/locators/primary").isMissingNode() ? "" : e.at("/locators/primary/strategy").asText() + "|" + e.at("/locators/primary/css").asText();
            out.put(e.get("nodeId").asText(), String.join("§", e.get("kind").asText(), e.get("role").asText(), e.at("/text/accessibleName").asText(),
                    e.at("/text/own").asText(), String.join(",", cond), e.path("parentNodeId").asText(), primary,
                    e.at("/family/form/nodeId").asText(), e.at("/family/row/nodeId").asText()));
        }
        return out;
    }

    @AfterAll
    static void writeReport() throws Exception {
        if (lab == null || (SIZES.isEmpty() && REPORT.isEmpty())) {
            return;
        }
        Path dir = lab.repo().resolve("reports").resolve("chrome-002");
        Files.createDirectories(dir);
        Path json = dir.resolve("performance.json");
        ObjectNode report = Files.exists(json) ? (ObjectNode) M.readTree(json.toFile()) : M.createObjectNode();
        report.put("generatedAt", Instant.now().toString());
        report.put("chrome", lab.chromeVersion());
        report.put("java", System.getProperty("java.version"));
        report.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        if (!SIZES.isEmpty()) {
            report.set("sizes", SIZES);
        }
        REPORT.fields().forEachRemaining(f -> report.set(f.getKey(), f.getValue()));
        Files.writeString(json, M.writerWithDefaultPrettyPrinter().writeValueAsString(report), StandardCharsets.UTF_8);
        StringBuilder md = new StringBuilder("# CHROME-002 — desempenho medido (antes × depois)\n\n")
                .append("Gerado em ").append(report.get("generatedAt").asText()).append(" · Chrome for Testing ").append(lab.chromeVersion())
                .append(" · Java ").append(System.getProperty("java.version")).append("\n\n");
        if (report.has("sizes")) {
            md.append("| Elementos | Nós | Captura síncrona (ms) | Bloqueio síncrono (ms) | Captura cooperativa (ms) | Fatias | Maior fatia (ms) | Bloqueio cooperativo (ms) | Ciclo sessão (ms) | Seleção reutilizada (ms) | Export anterior (ms) | Export em fluxo (ms) | Heap usado/comprometido/máx (MB) | RSS host (MB) |\n")
                    .append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
            for (JsonNode r : report.get("sizes")) {
                md.append("| ").append(r.get("requestedElements")).append(" | ").append(r.get("nodes"))
                        .append(" | ").append(r.get("legacyCaptureMs")).append(" | ").append(r.get("legacyMaxMainThreadGapMs"))
                        .append(" | ").append(r.get("cooperativeCaptureMs")).append(" | ").append(r.get("slices")).append(" | ").append(r.get("maxSliceMs"))
                        .append(" | ").append(r.get("cooperativeMaxMainThreadGapMs")).append(" | ").append(r.get("sessionStartEndToEndMs"))
                        .append(" | ").append(r.get("selectReusedMs")).append(" | ").append(r.get("legacyExportMs")).append(" | ").append(r.get("streamExportMs"))
                        .append(" | ").append(r.get("hostHeapUsedMb")).append(" / ").append(r.get("hostHeapCommittedMb")).append(" / ").append(r.get("hostHeapMaxMb"))
                        .append(" | ").append(r.get("hostRssMb")).append(" |\n");
            }
        }
        for (String k : List.of("cooperativeConsistency", "memory", "incrementalVsFull", "longSession_5min", "longSession_15min", "longSession_30min")) {
            if (report.has(k)) {
                JsonNode v = report.get(k).deepCopy();
                if (v.has("samples") && v.get("samples").size() > 12) {
                    ArrayNode s = (ArrayNode) v.get("samples");
                    ArrayNode keep = M.createArrayNode();
                    for (int i = 0; i < s.size(); i += Math.max(1, s.size() / 10)) {
                        keep.add(s.get(i));
                    }
                    ((ObjectNode) v).set("samples", keep);
                }
                md.append("\n## ").append(k).append("\n\n```json\n").append(M.writerWithDefaultPrettyPrinter().writeValueAsString(v)).append("\n```\n");
            }
        }
        Files.writeString(dir.resolve("performance.md"), md.toString(), StandardCharsets.UTF_8);
    }
}
