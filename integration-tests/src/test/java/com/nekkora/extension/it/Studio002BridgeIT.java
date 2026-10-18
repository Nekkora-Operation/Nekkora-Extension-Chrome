package com.nekkora.extension.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nekkora.extension.it.support.BrowserLab;
import com.nekkora.extension.it.support.BrowserLab.Page;
import com.nekkora.extension.it.support.SessionIT;
import com.nekkora.extension.it.support.StudioBridgeLab;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STUDIO-002 no Chrome real: o "Studio" de laboratório (servidor nekkora-bridge/1 real) controla a gravação técnica
 * pela cadeia completa Studio ⇄ Native Host ⇄ Native Messaging ⇄ service worker ⇄ CaptureSession.
 */
class Studio002BridgeIT extends SessionIT {

    private static final String CONT = "/lab/chrome002/continuous.html";
    private static final String SITE = "/lab/real001/site.html";
    private static StudioBridgeLab studio;

    @BeforeAll
    static void bridge() throws Exception {
        lab = BrowserLab.get();
        studio = new StudioBridgeLab(lab.bridgeDir());
        lab.call("studio.link.set", Map.of("enabled", true)); // gesto equivalente ao interruptor do painel
        studio.awaitConnected(Duration.ofSeconds(30));
    }

    @AfterAll
    static void unbridge() throws IOException {
        try {
            lab.call("studio.link.set", Map.of("enabled", false));
        } finally {
            studio.close();
        }
        assertTrue(studio.contractViolations.isEmpty(), "mensagens fora do contrato: " + studio.contractViolations);
    }

    @AfterEach
    void reconnectIfNeeded() {
        if (!studio.connected()) {
            lab.call("studio.link.set", Map.of("enabled", true));
            studio.awaitConnected(Duration.ofSeconds(30));
        }
    }

    private static String id(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private JsonNode tabs() {
        JsonNode r = studio.call("browser.tabs.list", null);
        assertTrue(r.path("ok").asBoolean(), r.toString());
        return r.at("/payload/tabs");
    }

    private String refOf(Page p) {
        // CHROME-SECURITY-001 (D4): o título só segue se for do vocabulário — a aba é identificada pela referência exata
        // (comando DEV) e o teste confere que ela está na listagem que o Studio recebe, com o host certo.
        String ref = lab.call("studio.tabRef", p).path("tabRef").asText();
        for (JsonNode t : tabs()) {
            if (ref.equals(t.path("tabRef").asText()) && hostOf(p).equals(t.path("host").asText())) {
                return ref;
            }
        }
        throw new AssertionError("aba não listada: " + p.url());
    }

    private static String hostOf(Page p) {
        return java.net.URI.create(p.url()).getHost();
    }

    private JsonNode start(String recordingId, String op, Page p, String mode, int leaseMs) {
        ObjectNode pl = studio.obj().put("recordingId", recordingId).put("operationId", op).put("tabRef", refOf(p)).put("mode", mode).put("leaseMs", leaseMs);
        return studio.call("studio.recording.start", pl);
    }

    private JsonNode op(String type, String recordingId) {
        return studio.call(type, studio.obj().put("recordingId", recordingId).put("operationId", id("op")));
    }

    private JsonNode consumers(Page p) {
        return session(p).path("consumers");
    }

    private boolean hasConsumer(Page p, String consumer) {
        for (JsonNode c : consumers(p)) {
            if (consumer.equals(c.asText())) {
                return true;
            }
        }
        return false;
    }

    private long exportedFiles() throws IOException {
        Path dir = lab.hostData().resolve("pagemaps");
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).count();
        }
    }

    @Test
    @DisplayName("S002-003/006 — conexão autenticada e lista de abas sanitizada (sem URL nem id do Chrome)")
    void s002006ListaDeAbas() throws IOException {
        Page labPage = open(CONT);
        Page denied = openWithoutContent(lab.externalUrl(BrowserLab.SITE_X, SITE));
        JsonNode list = tabs();
        String json = list.toString();
        assertFalse(json.contains("http://") || json.contains("https://"), "nenhuma URL completa: " + json);
        assertFalse(json.contains("\"tabId\""), "nenhum id interno do Chrome");
        boolean labSeen = false;
        boolean deniedSeen = false;
        for (JsonNode t : list) {
            assertTrue(t.path("tabRef").asText().matches("t-[A-Za-z0-9]{8,40}"), t.toString());
            if ("127.0.0.1".equals(t.path("host").asText()) && "LAB".equals(t.path("access").asText())) {
                labSeen = true;
            }
            if (BrowserLab.SITE_X.equals(t.path("host").asText())) {
                deniedSeen = true;
                assertEquals("PERMISSION_REQUIRED", t.path("access").asText());
                assertTrue(t.path("title").isNull(), "título de página sem acesso não é lido");
            }
        }
        assertTrue(labSeen && deniedSeen, json);
        JsonNode status = studio.call("bridge.status", null);
        String version = new com.fasterxml.jackson.databind.ObjectMapper().readTree(Path.of(System.getProperty("nekkora.repo", ".."), "chrome-extension", "manifest.json").toFile()).path("version").asText();
        assertEquals(version, status.at("/payload/extension/version").asText(), "a versão do manifesto: " + status);
        lab.close(denied);
        lab.close(labPage);
    }

    @Test
    @DisplayName("S002-007 — página não autorizada: início recusado com orientação; nenhuma captura")
    void s002007NaoAutorizada() {
        Page denied = openWithoutContent(lab.externalUrl(BrowserLab.SITE_X, SITE));
        JsonNode r = start(id("rec"), id("op"), denied, "CAPTURE_ONLY", 30000);
        assertFalse(r.path("ok").asBoolean(), r.toString());
        assertEquals("ORIGIN_NOT_ALLOWED", r.at("/error/code").asText());
        assertEquals("PERMISSION_REQUIRED", r.at("/error/reason").asText());
        assertTrue(r.at("/error/message").asText().contains("Abra a Nekkora Extension no Chrome e conceda acesso ao site"));
        assertTrue(session(denied).isMissingNode() || session(denied).isNull(), "nenhuma sessão de captura");
    }

    @Test
    @DisplayName("S002-008/011/012/013/014 — início real, pausa e retomada verificáveis, modo confirmado, finalização sem exportar")
    void s002008CicloCompleto() throws Exception {
        Page p = open(CONT);
        long exportsBefore = exportedFiles();
        String rid = id("rec");
        JsonNode r = start(rid, id("op"), p, "CAPTURE_AND_INTERACT", 30000);
        assertTrue(r.path("ok").asBoolean(), r.toString());
        assertEquals("RECORDING", r.at("/payload/recording/state").asText());
        assertTrue(r.at("/payload/recording/capture/recorderAttached").asBoolean());
        waitSessionActive(p);
        assertTrue(hasConsumer(p, "studio-recorder:" + rid), "consumidor próprio na CaptureSession: " + consumers(p));
        assertNotNull(studio.awaitEvent(e -> "studio.recording.started".equals(e.path("type").asText()) && rid.equals(e.at("/payload/recordingId").asText()),
                Duration.ofSeconds(10)));
        act(p, "adicionar");
        assertEquals("SYNCHRONIZED", sync(p).get("syncState").asText(), "observação real da página");

        JsonNode paused = op("studio.recording.pause", rid);
        assertEquals("PAUSED", paused.at("/payload/recording/state").asText(), paused.toString());
        assertFalse(paused.at("/payload/recording/capture/recorderAttached").asBoolean());
        assertEquals(0, paused.at("/payload/recording/capture/otherConsumers").asInt());
        BrowserLab.waitUntil(Duration.ofSeconds(10), () -> session(p).isMissingNode() || session(p).isNull(), "captura encerrada (único consumidor era o Recorder)");
        assertEquals("STUDIO_RECORDER_PAUSED", lastEnded(p).path("reason").asText(), "pausa = liberação real do consumidor");

        JsonNode resumed = op("studio.recording.resume", rid);
        assertEquals("RECORDING", resumed.at("/payload/recording/state").asText(), resumed.toString());
        waitSessionActive(p);
        assertTrue(hasConsumer(p, "studio-recorder:" + rid), "consumidor readquirido com nova base");
        assertEquals(2, resumed.at("/payload/recording/counters/segments").asInt());

        JsonNode mode = studio.call("studio.recording.mode", studio.obj().put("recordingId", rid).put("operationId", id("op")).put("mode", "CAPTURE_ONLY"));
        assertEquals("CAPTURE_ONLY", mode.at("/payload/recording/mode").asText());
        assertNotNull(studio.awaitEvent(e -> "studio.recording.modeChanged".equals(e.path("type").asText()), Duration.ofSeconds(10)));

        JsonNode fin = op("studio.recording.finish", rid);
        assertEquals("COMPLETED", fin.at("/payload/recording/state").asText(), fin.toString());
        assertFalse(fin.at("/payload/summary/pageMapExported").asBoolean());
        assertEquals("NOT_AVAILABLE", fin.at("/payload/summary/actionCapture").asText());
        assertTrue(fin.at("/payload/summary/counters/technicalCaptures").asInt() >= 2, fin.toString());
        Thread.sleep(500);
        assertEquals(exportsBefore, exportedFiles(), "finalizar NÃO exporta PageMap");
        BrowserLab.waitUntil(Duration.ofSeconds(10), () -> session(p).isMissingNode() || session(p).isNull(), "consumidor liberado");
        lab.writeEvidence("S002-008-finish-summary.json", fin.at("/payload").toPrettyString());
    }

    @Test
    @DisplayName("S002-009/010 — Recorder e inspeção manual coexistem; pausar/finalizar o Recorder preserva a inspeção")
    void s002009Coexistencia() throws Exception {
        Page p = open(CONT);
        activate(p);
        String rid = id("rec");
        assertTrue(start(rid, id("op"), p, "CAPTURE_ONLY", 30000).path("ok").asBoolean());
        assertTrue(hasConsumer(p, "inspection") && hasConsumer(p, "studio-recorder:" + rid), consumers(p).toString());
        String sessionId = session(p).path("sessionId").asText();
        JsonNode paused = op("studio.recording.pause", rid);
        assertEquals(1, paused.at("/payload/recording/capture/otherConsumers").asInt(), "a página continua observada pela inspeção");
        assertEquals(sessionId, session(p).path("sessionId").asText(), "mesma CaptureSession compartilhada");
        assertFalse(hasConsumer(p, "studio-recorder:" + rid));
        act(p, "adicionar");
        assertEquals("SYNCHRONIZED", sync(p).get("syncState").asText());
        JsonNode resumed = op("studio.recording.resume", rid);
        assertEquals("RECORDING", resumed.at("/payload/recording/state").asText());
        assertEquals(sessionId, session(p).path("sessionId").asText(), "retomada sem nova base: a sessão compartilhada já está consistente");
        op("studio.recording.finish", rid);
        assertEquals("ACTIVE", inspection(p).path("state").asText(), "inspeção manual continua ativa");
        assertTrue(hasConsumer(p, "inspection"));
        JsonNode sel = select(p, "#tarefas li:last-child .concluir");
        assertEquals("BUTTON", sel.at("/inspection/classification/kind").asText(), "inspeção funcionando depois do Recorder");
        deactivate(p);
    }

    @Test
    @DisplayName("S002-016/017 — comandos repetidos e finalizar × cancelar simultâneos: um único desfecho")
    void s002016Duplicidade() throws Exception {
        Page p = open(CONT);
        String rid = id("rec");
        String op = id("op");
        JsonNode a = start(rid, op, p, "CAPTURE_ONLY", 30000);
        JsonNode b = start(rid, op, p, "CAPTURE_ONLY", 30000);
        assertTrue(b.at("/payload/duplicate").asBoolean(), "mesma operação não inicia duas gravações");
        assertEquals(a.at("/payload/recording/recordingId").asText(), b.at("/payload/recording/recordingId").asText());
        assertEquals(1, java.util.stream.StreamSupport.stream(consumers(p).spliterator(), false).filter(c -> c.asText().startsWith("studio-recorder:")).count());
        JsonNode other = start(id("rec"), id("op"), p, "CAPTURE_ONLY", 30000);
        assertEquals("RECORDING_ALREADY_ACTIVE", other.at("/error/code").asText());
        CompletableFuture<JsonNode> fin = studio.command("studio.recording.finish", studio.obj().put("recordingId", rid).put("operationId", id("op")));
        CompletableFuture<JsonNode> can = studio.command("studio.recording.cancel", studio.obj().put("recordingId", rid).put("operationId", id("op")));
        JsonNode f = fin.get(30, java.util.concurrent.TimeUnit.SECONDS);
        JsonNode c = can.get(30, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(1, (f.path("ok").asBoolean() ? 1 : 0) + (c.path("ok").asBoolean() ? 1 : 0), "um único vencedor: " + f + " / " + c);
        JsonNode loser = f.path("ok").asBoolean() ? c : f;
        assertEquals("RECORDING_ENDED", loser.at("/error/code").asText());
    }

    @Test
    @DisplayName("S002-028 — aba fechada: gravação DEGRADED sem operação do Studio; finalização registra a interrupção")
    void s002028AbaFechada() throws Exception {
        Page p = open(CONT);
        String rid = id("rec");
        assertTrue(start(rid, id("op"), p, "CAPTURE_ONLY", 30000).path("ok").asBoolean());
        studio.events.clear();
        lab.close(p);
        JsonNode e = studio.awaitEvent(x -> "studio.recording.degraded".equals(x.path("type").asText()), Duration.ofSeconds(15));
        assertNotNull(e, "evento de degradação");
        assertEquals("TAB_CLOSED", e.at("/payload/recording/reason").asText());
        assertTrue(e.at("/payload/operationId").isNull(), "originado no navegador");
        JsonNode res = op("studio.recording.resume", rid);
        assertEquals("TAB_NOT_FOUND", res.at("/error/code").asText());
        JsonNode fin = op("studio.recording.finish", rid);
        assertTrue(fin.at("/payload/summary/limitations").toString().contains("INTERRUPTED"), fin.toString());
    }

    @Test
    @DisplayName("S002-027 — permissão revogada: captura encerrada, gravação DEGRADED, retomada recusada")
    void s002027PermissaoRevogada() throws Exception {
        Page p = openUrl(lab.externalUrl(BrowserLab.SITE_A, SITE));
        String rid = id("rec");
        JsonNode r = start(rid, id("op"), p, "CAPTURE_ONLY", 30000);
        assertTrue(r.path("ok").asBoolean(), r.toString());
        studio.events.clear();
        String pattern = "http://" + BrowserLab.SITE_A + "/*";
        try {
            lab.siteAccess(pattern, false);
            JsonNode e = studio.awaitEvent(x -> "studio.recording.degraded".equals(x.path("type").asText()), Duration.ofSeconds(20));
            assertNotNull(e, "degradação após revogação");
            assertEquals("PERMISSION_REVOKED", e.at("/payload/recording/reason").asText());
            assertTrue(session(p).isMissingNode() || session(p).isNull(), "não continua observando a origem revogada");
            JsonNode res = op("studio.recording.resume", rid);
            assertEquals("ORIGIN_NOT_ALLOWED", res.at("/error/code").asText());
        } finally {
            lab.siteAccess(pattern, true);
        }
        JsonNode res2 = op("studio.recording.resume", rid);
        assertEquals("RECORDING", res2.at("/payload/recording/state").asText(), "retomada depois da nova autorização: " + res2);
        assertNotNull(studio.awaitEvent(x -> "studio.recording.recovered".equals(x.path("type").asText()), Duration.ofSeconds(10)));
        op("studio.recording.cancel", rid);
    }

    @Test
    @DisplayName("S002-018 — Studio perdido: a concessão expira e SÓ o consumidor do Recorder é liberado")
    void s002018ConcessaoExpira() throws Exception {
        Page p = open(CONT);
        activate(p); // outro consumidor que precisa ser preservado
        String rid = id("rec");
        assertTrue(start(rid, id("op"), p, "CAPTURE_ONLY", 5000).path("ok").asBoolean());
        assertTrue(hasConsumer(p, "studio-recorder:" + rid));
        long t0 = System.currentTimeMillis();
        studio.dropConnection(); // Studio travado/encerrado: sem aviso e sem renovação
        BrowserLab.waitUntil(Duration.ofSeconds(20), () -> {
            for (JsonNode r : lab.call("studio.recordings", Map.of())) {
                if (rid.equals(r.path("recordingId").asText())) {
                    return "EXPIRED".equals(r.path("state").asText());
                }
            }
            return false;
        }, "concessão expirada");
        long ms = System.currentTimeMillis() - t0;
        assertFalse(hasConsumer(p, "studio-recorder:" + rid), "consumidor do Recorder liberado (sem gravação órfã)");
        assertTrue(hasConsumer(p, "inspection"), "demais consumidores preservados");
        assertEquals("ACTIVE", session(p).path("status").asText());
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("leaseMs", 5000);
        ev.put("expiredAfterMs", ms);
        ev.put("remainingConsumers", consumers(p));
        lab.writeEvidence("S002-018-lease-expiry.json", new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(ev));
        deactivate(p);
    }

    @Test
    @DisplayName("S002-020 — reinício do service worker: novo host reconecta e a gravação é reconciliada")
    void s002020ReinicioDoServiceWorker() throws Exception {
        Page p = open(CONT);
        String rid = id("rec");
        assertTrue(start(rid, id("op"), p, "CAPTURE_ONLY", 60000).path("ok").asBoolean());
        int before = studio.connections.get();
        Object bytes = harnessScript("const done = arguments[arguments.length - 1]; chrome.storage.session.getBytesInUse(null).then(done);");
        lab.writeEvidence("S002-020-storage-session.json", "{\"bytesInUseBeforeRestart\": " + bytes + ", \"quotaBytes\": 10485760}");
        Chrome002LifecycleIT.stopServiceWorker();
        lab.call("studio.link.get", Map.of()); // o próximo evento acorda o service worker
        BrowserLab.waitUntil(Duration.ofSeconds(60), () -> studio.connections.get() > before && studio.connected(), "novo processo do host reconectado ao Studio");
        JsonNode[] last = new JsonNode[2];
        try {
            BrowserLab.waitUntil(Duration.ofSeconds(60), () -> {
                JsonNode s = studio.call("studio.recording.status", studio.obj().put("recordingId", rid));
                last[0] = s;
                last[1] = session(p);
                return s.path("ok").asBoolean() && "RECORDING".equals(s.at("/payload/recordings/0/state").asText())
                        && s.at("/payload/recordings/0/capture/recorderAttached").asBoolean();
            }, "gravação reconciliada (estado mínimo restaurado)");
        } catch (AssertionError e) {
            throw new AssertionError(e.getMessage() + " | gravação=" + last[0] + " | captura=" + last[1], e);
        }
        waitSessionActive(p);
        assertTrue(hasConsumer(p, "studio-recorder:" + rid));
        op("studio.recording.finish", rid);
    }

    @Test
    @DisplayName("S002-022 — exportação/operação longa no motor não atrasa os comandos do Recorder")
    void s002022OperacaoLonga() throws Exception {
        Page p = open(CONT);
        String rid = id("rec");
        assertTrue(start(rid, id("op"), p, "CAPTURE_ONLY", 60000).path("ok").asBoolean());
        // Operação longa CONTROLADA no motor (8 s), disparada sem esperar.
        lab.call("host.status", Map.of()); // posiciona no harness da extensão
        ((org.openqa.selenium.JavascriptExecutor) lab.driver()).executeScript(
                "window.nekkora.call('host.request', {type: 'host.hold', payload: {ms: 8000}, timeoutMs: 30000});");
        Thread.sleep(1500);
        long s0 = System.nanoTime();
        JsonNode status = studio.call("bridge.status", null);
        long statusMs = (System.nanoTime() - s0) / 1_000_000;
        long p0 = System.nanoTime();
        JsonNode paused = op("studio.recording.pause", rid);
        long pauseMs = (System.nanoTime() - p0) / 1_000_000;
        long r0 = System.nanoTime();
        JsonNode renew = studio.call("studio.recording.renew", studio.obj().put("recordingId", rid));
        long renewMs = (System.nanoTime() - r0) / 1_000_000;
        assertTrue(status.at("/payload/host/engineBusy").asBoolean(), "o motor estava ocupado: " + status);
        assertEquals("PAUSED", paused.at("/payload/recording/state").asText(), paused.toString());
        assertTrue(renew.path("ok").asBoolean());
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("engineOperation", "host.hold 8000 ms");
        ev.put("bridgeStatusMs", statusMs);
        ev.put("pauseConfirmedMs", pauseMs);
        ev.put("renewMs", renewMs);
        ev.put("engineBusyReported", status.at("/payload/host/engineBusy").asBoolean());
        lab.writeEvidence("S002-022-browser-long-operation.json", new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(ev));
        assertTrue(statusMs < 1000, "bridge.status em " + statusMs + " ms");
        assertTrue(pauseMs < 2000, "pausa confirmada em " + pauseMs + " ms com o motor ocupado");
        assertTrue(renewMs < 1000, "renovação em " + renewMs + " ms");
        Thread.sleep(7000); // deixa a operação longa terminar antes do próximo teste
        op("studio.recording.cancel", rid);
    }

    @Test
    @DisplayName("S002-002 — com o canal ligado e sem Studio, a extensão segue inspecionando normalmente")
    void s002002SemStudio() throws Exception {
        studio.dropConnection();
        Path discovery = lab.bridgeDir().resolve("studio-bridge.json");
        Path hidden = lab.bridgeDir().resolve("studio-bridge.json.off");
        Files.move(discovery, hidden);
        try {
            BrowserLab.waitUntil(Duration.ofSeconds(20), () -> "SEARCHING".equals(lab.call("studio.link.get", Map.of()).path("state").asText()),
                    "aguardando o Studio");
            Page p = open(CONT);
            activate(p);
            JsonNode sel = select(p, "#tarefas li:first-child .concluir");
            assertEquals("BUTTON", sel.at("/inspection/classification/kind").asText());
            deactivate(p);
        } finally {
            Files.move(hidden, discovery);
        }
        studio.awaitConnected(Duration.ofSeconds(30));
    }
}
