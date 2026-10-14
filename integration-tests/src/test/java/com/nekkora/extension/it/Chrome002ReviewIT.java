package com.nekkora.extension.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekkora.extension.it.support.BrowserLab;
import com.nekkora.extension.it.support.BrowserLab.Page;
import com.nekkora.extension.it.support.SessionIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regressões da revisão do PR #1 (CHROME-002): sincronização com falha, exportação incompleta, gravação após
 * reinício do service worker, isolamento de alvos entre abas, finalização × cancelamento e retenção.
 */
class Chrome002ReviewIT extends SessionIT {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String REC = "/lab/chrome002/recording.html";

    private long exportedFiles() throws Exception {
        Path dir = lab.hostData().resolve("pagemaps");
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(f -> f.toString().endsWith(".json")).count();
        }
    }

    private Map<String, Object> event(String type, String id, Page p) {
        Map<String, Object> e = new java.util.LinkedHashMap<>();
        e.put("type", "recording.session." + type);
        e.put("sessionId", id);
        e.put("tabId", p.tabId());
        if ("finished".equals(type)) {
            e.put("reason", "USER_FINISHED");
        }
        return e;
    }

    @Test
    @DisplayName("C002-R01 — Sincronização com erro de flush nunca é declarada SYNCHRONIZED (documento e frame)")
    void synchronizationPropagatesFlushErrors() {
        Page p = open("/lab/chrome002/iframes.html");
        activate(p);
        BrowserLab.waitUntil(Duration.ofSeconds(15), () -> {
            for (JsonNode f : session(p).get("frames")) {
                if ("ATTACHED".equals(f.get("status").asText())) {
                    return true;
                }
            }
            return false;
        }, "frame anexado");
        assertEquals("SYNCHRONIZED", sync(p).get("syncState").asText());
        // Documento principal: as duas tentativas da barreira falham.
        lab.call("session.debug", p, "action", "failFlushes", "count", 2);
        JsonNode top = sync(p);
        assertEquals("INCOMPLETE", top.get("syncState").asText(), top.toString());
        assertEquals(0, top.at("/unconfirmed/0/frameId").asInt());
        assertEquals("top", top.at("/unconfirmed/0/source").asText());
        // Frame anexado de outra origem.
        int fid = -1;
        for (JsonNode f : session(p).get("frames")) {
            if ("ATTACHED".equals(f.get("status").asText())) {
                fid = f.get("frameId").asInt();
            }
        }
        lab.call("session.debug", p, "action", "failFlushes", "count", 2, "frameId", fid);
        JsonNode frame = sync(p);
        assertEquals("INCOMPLETE", frame.get("syncState").asText(), frame.toString());
        assertEquals("x" + fid, frame.at("/unconfirmed/0/source").asText());
        // Sem falha injetada: volta a confirmar.
        assertEquals("SYNCHRONIZED", sync(p).get("syncState").asText());
        deactivate(p);
    }

    @Test
    @DisplayName("C002-R02 — Finalização sem sincronização confirmada falha sem memorizar; nova tentativa exporta completo")
    void finishRequiresConfirmedSynchronization() throws Exception {
        Page p = open(REC);
        recording("started", "rec-r02", p);
        waitSessionActive(p);
        act(p, "adicionar-linha");
        long before = exportedFiles();
        lab.call("session.debug", p, "action", "failFlushes", "count", 2);
        JsonNode r = recording("finished", "rec-r02", p);
        assertEquals("FAILED", r.get("status").asText(), r.toString());
        assertEquals("SYNC_INCOMPLETE", r.at("/errors/0/code").asText());
        assertEquals("INCOMPLETE", r.get("syncState").asText());
        assertTrue(r.get("path").isNull());
        assertEquals(before, exportedFiles(), "nenhum arquivo para finalização não confirmada");
        assertEquals("ACTIVE", session(p).get("status").asText(), "a gravação continua (consumidor mantido)");
        JsonNode retry = recording("finished", "rec-r02", p);
        assertEquals("EXPORTED", retry.get("status").asText(), retry.toString());
        assertTrue(retry.get("complete").asBoolean());
        assertFalse(retry.get("duplicate").asBoolean(), "a falha não foi memorizada");
        assertEquals("SYNCHRONIZED", retry.get("syncState").asText());
        assertTrue(Files.readString(Path.of(retry.get("path").asText())).contains("Detalhar 1"));
        assertEquals(before + 1, exportedFiles());
        assertTrue(recording("finished", "rec-r02", p).get("duplicate").asBoolean());
    }

    @Test
    @DisplayName("C002-R03 — Gravação sobrevive ao reinício do service worker e finaliza normalmente")
    void recordingSurvivesServiceWorkerRestart() throws Exception {
        Page p = open(REC);
        recording("started", "rec-r03", p);
        waitSessionActive(p);
        String captureSession = session(p).get("sessionId").asText();
        act(p, "adicionar-linha");
        sync(p);
        long startedAt = lab.call("sw.diagnostics", Map.of()).get("swStartedAt").asLong();
        Chrome002LifecycleIT.stopServiceWorker();
        BrowserLab.waitUntil(Duration.ofSeconds(30), () -> lab.call("sw.diagnostics", Map.of()).get("swStartedAt").asLong() != startedAt
                && "ACTIVE".equals(session(p).path("status").asText()), "service worker reiniciado e sessão reconciliada");
        boolean restored = false;
        for (JsonNode r : lab.call("recording.list", Map.of())) {
            restored |= "rec-r03".equals(r.get("recordingId").asText()) && "RECORDING".equals(r.get("status").asText());
        }
        assertTrue(restored, "registro da gravação restaurado: " + lab.call("recording.list", Map.of()));
        assertEquals(captureSession, session(p).get("sessionId").asText());
        assertTrue(session(p).get("consumers").toString().contains("recording:rec-r03"));
        act(p, "adicionar-linha");
        JsonNode r = recording("finished", "rec-r03", p);
        assertEquals("EXPORTED", r.get("status").asText(), r.toString());
        String file = Files.readString(Path.of(r.get("path").asText()));
        assertTrue(file.contains("Detalhar 1") && file.contains("Detalhar 2"), "mutações de antes e depois do reinício");
        BrowserLab.waitUntil(Duration.ofSeconds(5), () -> session(p).isMissingNode() || session(p).isNull(), "sessão liberada");
    }

    @Test
    @DisplayName("C002-R04 — Alvos isolados entre abas com a mesma página (exportação da gravação e manual)")
    void targetsIsolatedBetweenTabs() throws Exception {
        Page a = open(REC + "?cliente=ana");
        Page b = open(REC + "?cliente=maria");
        recording("started", "rec-r04a", a);
        recording("started", "rec-r04b", b);
        waitSessionActive(a);
        waitSessionActive(b);
        String ta = select(a, "#nome").at("/identity/targetId").asText();
        String tb = select(b, "#cidade").at("/identity/targetId").asText();
        String tb2 = select(b, "#nome").at("/identity/targetId").asText();
        JsonNode manualA = lab.call("pagemap.export", a, "returnPageMap", true).at("/pageMap/targets");
        Set<String> manual = new HashSet<>();
        manualA.forEach(t -> manual.add(t.get("targetId").asText()));
        assertEquals(Set.of(ta), manual, "exportação manual da aba A contém somente alvos da aba A");
        JsonNode ra = recording("finished", "rec-r04a", a);
        JsonNode rb = recording("finished", "rec-r04b", b);
        Set<String> inA = new HashSet<>();
        M.readTree(Path.of(ra.get("path").asText()).toFile()).get("targets").forEach(t -> inA.add(t.get("targetId").asText()));
        Set<String> inB = new HashSet<>();
        M.readTree(Path.of(rb.get("path").asText()).toFile()).get("targets").forEach(t -> inB.add(t.get("targetId").asText()));
        assertEquals(Set.of(ta), inA, "gravação da aba A sem identidades da aba B");
        assertEquals(Set.of(tb, tb2), inB, "gravação da aba B sem identidades da aba A");
    }

    @Test
    @DisplayName("C002-R05 — Finalização × cancelamento simultâneos: um único desfecho, sessão liberada uma vez")
    void finishAndCancelConcurrently() throws Exception {
        Page p = open(REC);
        List<String> outcomes = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String id = "rec-r05-" + i;
            recording("started", id, p);
            waitSessionActive(p);
            act(p, "adicionar-linha");
            long before = exportedFiles();
            // Ordem alternada de disparo; as duas chamadas saem do harness sem esperar uma pela outra.
            Map<String, Object> first = event(i % 2 == 0 ? "finished" : "cancelled", id, p);
            Map<String, Object> second = event(i % 2 == 0 ? "cancelled" : "finished", id, p);
            JsonNode pair = M.valueToTree(harnessScript("const done = arguments[arguments.length - 1];"
                    + "Promise.all([window.nekkora.call('recording.event', {event: arguments[0]}), window.nekkora.call('recording.event', {event: arguments[1]})]).then(done);",
                    first, second));
            JsonNode fin = (i % 2 == 0 ? pair.get(0) : pair.get(1)).get("result");
            JsonNode can = (i % 2 == 0 ? pair.get(1) : pair.get(0)).get("result");
            assertTrue(pair.get(0).get("ok").asBoolean() && pair.get(1).get("ok").asBoolean(), pair.toString());
            if ("EXPORTED".equals(fin.get("status").asText())) {
                assertEquals("EXPORTED", can.get("status").asText(), "cancelamento após exportação não a desfaz: " + can);
                assertEquals(before + 1, exportedFiles());
                outcomes.add("EXPORTED");
            } else {
                assertEquals("CANCELLED", can.get("status").asText(), can.toString());
                assertEquals("RECORDING_CANCELLED", fin.at("/errors/0/code").asText(), fin.toString());
                assertEquals(before, exportedFiles(), "gravação cancelada não exporta");
                outcomes.add("CANCELLED");
            }
            BrowserLab.waitUntil(Duration.ofSeconds(5), () -> session(p).isMissingNode() || session(p).isNull(), "sessão liberada");
            String reason = lastEnded(p).get("reason").asText();
            assertTrue(reason.equals("RECORDING_FINISHED") || reason.equals("RECORDING_CANCELLED"), reason);
            assertEquals(outcomes.get(outcomes.size() - 1).equals("EXPORTED") ? "RECORDING_FINISHED" : "RECORDING_CANCELLED", reason,
                    "a sessão foi encerrada pelo desfecho efetivo");
        }
        lab.writeEvidence("C002-R05-finish-cancel.json", M.writeValueAsString(outcomes));
    }

    @Test
    @DisplayName("C002-R06 — Retenção: gravações ativas nunca são descartadas (55 simultâneas)")
    void retentionNeverDropsActiveRecordings() {
        Page p = open(REC);
        int n = 55;
        for (int i = 0; i < n; i++) {
            recording("started", "rec-r06-" + i, p);
        }
        waitSessionActive(p);
        // A primeira (mais antiga) continua conhecida e finaliza normalmente.
        JsonNode first = recording("finished", "rec-r06-0", p);
        assertEquals("EXPORTED", first.get("status").asText(), first.toString());
        for (int i = 1; i < n; i++) {
            JsonNode r = recording("finished", "rec-r06-" + i, p);
            assertEquals("EXPORTED", r.get("status").asText(), "gravação " + i + ": " + r);
        }
        int ended = 0;
        int active = 0;
        for (JsonNode r : lab.call("recording.list", Map.of())) {
            if (r.get("recordingId").asText().startsWith("rec-r06-")) {
                if ("EXPORTED".equals(r.get("status").asText())) {
                    ended++;
                } else {
                    active++;
                }
            }
        }
        assertEquals(0, active);
        assertTrue(ended <= 50, "retenção limita somente registros encerrados: " + ended);
        BrowserLab.waitUntil(Duration.ofSeconds(5), () -> session(p).isMissingNode() || session(p).isNull(), "sessão liberada após a última gravação");
    }
}
