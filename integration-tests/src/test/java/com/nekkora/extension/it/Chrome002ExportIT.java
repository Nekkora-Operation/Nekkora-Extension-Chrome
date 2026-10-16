package com.nekkora.extension.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekkora.extension.it.support.BrowserLab;
import com.nekkora.extension.it.support.BrowserLab.Page;
import com.nekkora.extension.it.support.Contracts;
import com.nekkora.extension.it.support.SessionIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CHROME-002 §8, §11, §12 — exportação manual (DEV), exportação na finalização da gravação (PRD) e modos. */
class Chrome002ExportIT extends SessionIT {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String REC = "/lab/chrome002/recording.html";

    private Path pagemaps() {
        return lab.hostData().resolve("pagemaps");
    }

    private long exportedFiles() throws Exception {
        if (!Files.isDirectory(pagemaps())) {
            return 0;
        }
        try (Stream<Path> s = Files.list(pagemaps())) {
            return s.filter(f -> f.toString().endsWith(".json")).count();
        }
    }

    private static String sha256(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    /** Verifica o resultado de exportação e devolve o PageMap gravado. */
    private JsonNode assertExported(JsonNode r) throws Exception {
        assertEquals("EXPORTED", r.get("status").asText(), r.toString());
        assertEquals("0.3.0", r.get("schemaVersion").asText()); // CHROME-SECURITY-001
        assertTrue(r.get("elementCount").asInt() > 0);
        assertTrue(r.get("complete").asBoolean());
        assertEquals("SYNCHRONIZED", r.get("syncState").asText());
        assertTrue(r.at("/validation/valid").asBoolean(), r.get("validation").toString());
        assertTrue(r.get("errors").isEmpty());
        Path file = Path.of(r.get("path").asText());
        assertTrue(file.startsWith(pagemaps()), "persistido no diretório do motor: " + file);
        byte[] bytes = Files.readAllBytes(file);
        assertEquals(sha256(bytes), r.get("sha256").asText(), "checksum confere");
        JsonNode map = M.readTree(bytes);
        Contracts.assertValid("page-map.schema.json", map);
        assertEquals(r.get("elementCount").asInt(), map.get("elements").size());
        return map;
    }

    @Test
    @DisplayName("C002-021 — Exportação manual em DEV (sincronizada, contrato válido)")
    void manualExportDev() throws Exception {
        Page p = open("/lab/chrome002/continuous.html");
        activate(p);
        act(p, "adicionar");
        String panel = lab.openExtensionPage("src/ui/panel.html?tabId=" + p.tabId());
        WebDriver w = lab.driver();
        try {
            waitText(w, "mode-badge", "DEV");
            w.findElement(By.id("btn-export")).click();
            waitText(w, "message", "PageMap exportado");
            lab.screenshotWindow(panel, "C002-021-panel-manual-export");
        } finally {
            lab.closeWindow(panel);
        }
        JsonNode r = lab.call("pagemap.export", p);
        assertEquals("SYNCHRONIZED", r.get("syncState").asText());
        JsonNode map = r.get("pageMap");
        Contracts.assertValid("page-map.schema.json", map);
        assertExportedState(p, map, "li", "Tarefa 3", "mutação incorporada ao mapa exportado");
        assertEquals(1, session(p).get("fullCaptures").asInt(), "exportação sem captura completa");
        deactivate(p);
    }

    @Test
    @DisplayName("C002-022 — PRD: recording.session.finished sincroniza, consolida, valida e persiste")
    void prdExportOnFinish() throws Exception {
        lab.call("config.setMode", Map.of("mode", "PRD"));
        Page p = open(REC);
        JsonNode started = recording("started", "rec-022", p);
        assertEquals("RECORDING", started.get("status").asText());
        waitSessionActive(p);
        assertTrue(session(p).get("consumers").toString().contains("recording:rec-022"));
        act(p, "adicionar-linha");
        act(p, "adicionar-linha");
        JsonNode r = recording("finished", "rec-022", p);
        JsonNode map = assertExported(r);
        assertEquals("rec-022", r.get("recordingId").asText());
        assertFalse(r.get("duplicate").asBoolean());
        assertExportedState(p, map, "tr", "Detalhar 2", "estado final da página exportado");
        // Finalizar a gravação libera a sessão (nenhum outro consumidor).
        BrowserLab.waitUntil(Duration.ofSeconds(5), () -> session(p).isMissingNode() || session(p).isNull(), "sessão liberada");
        assertEquals("RECORDING_FINISHED", lastEnded(p).get("reason").asText());
        lab.writeEvidence("C002-022-export-result.json", M.writerWithDefaultPrettyPrinter().writeValueAsString(r));
    }

    @Test
    @DisplayName("C002-023 — Finalização duplicada e simultânea: idempotente")
    void duplicateFinalization() throws Exception {
        Page p = open(REC);
        recording("started", "rec-023", p);
        waitSessionActive(p);
        JsonNode first = recording("finished", "rec-023", p);
        assertExported(first);
        long files = exportedFiles();
        JsonNode again = recording("finished", "rec-023", p);
        assertTrue(again.get("duplicate").asBoolean());
        assertEquals(first.get("sha256").asText(), again.get("sha256").asText());
        assertEquals(first.get("path").asText(), again.get("path").asText());
        assertEquals(files, exportedFiles(), "nenhum arquivo novo");
        // Simultâneas
        recording("started", "rec-023b", p);
        waitSessionActive(p);
        Map<String, Object> event = Map.of("type", "recording.session.finished", "sessionId", "rec-023b", "tabId", p.tabId(), "reason", "USER_FINISHED");
        // As duas chamadas saem do mesmo harness sem esperar uma pela outra.
        Object both = harnessScript("const done = arguments[arguments.length - 1];"
                + "Promise.all([window.nekkora.call('recording.event', {event: arguments[0]}), window.nekkora.call('recording.event', {event: arguments[0]})]).then(done);", event);
        JsonNode pair = M.valueToTree(both);
        JsonNode a = pair.get(0).get("result");
        JsonNode b = pair.get(1).get("result");
        assertEquals(a.get("sha256").asText(), b.get("sha256").asText());
        assertTrue(a.get("duplicate").asBoolean() ^ b.get("duplicate").asBoolean(), "uma exportação original e uma duplicada");
        assertEquals(files + 1, exportedFiles(), "uma única escrita para as finalizações simultâneas");
        // Finalização de gravação inexistente
        JsonNode unknown = recording("finished", "rec-nao-existe", p);
        assertEquals("FAILED", unknown.get("status").asText());
        assertEquals("RECORDING_UNKNOWN", unknown.at("/errors/0/code").asText());
    }

    @Test
    @DisplayName("C002-024 — Finalização com delta pendente: mutação incluída")
    void finalizationWithPendingDelta() throws Exception {
        Page p = open(REC);
        recording("started", "rec-024", p);
        waitSessionActive(p);
        // Mutação imediatamente antes da finalização (o lote ainda está no debounce do content script).
        lab.js(p, "document.getElementById('adicionar-linha').onclick(); document.getElementById('nome').setAttribute('placeholder', 'Nome completo (pendente)');");
        JsonNode r = recording("finished", "rec-024", p);
        JsonNode map = assertExported(r);
        assertExportedState(p, map, "tr", "Detalhar 1", "linha inserida antes da finalização");
        assertTrue(map.toString().contains("Nome completo (pendente)"), "atributo alterado antes da finalização");
    }

    @Test
    @DisplayName("C002-025 — Falha de exportação explícita e nova tentativa")
    void exportFailure() throws Exception {
        Page p = open(REC);
        recording("started", "rec-025", p);
        waitSessionActive(p);
        Path dir = pagemaps();
        Path moved = dir.resolveSibling("pagemaps.moved");
        if (Files.isDirectory(dir)) {
            Files.move(dir, moved);
        }
        Files.writeString(dir, "arquivo no lugar do diretório: escrita impossível");
        try {
            JsonNode r = recording("finished", "rec-025", p);
            assertEquals("FAILED", r.get("status").asText(), r.toString());
            assertEquals("EXPORT_WRITE_FAILED", r.at("/errors/0/code").asText());
            assertTrue(r.get("path").isNull());
            assertEquals("ACTIVE", session(p).get("status").asText(), "falha não descarta a sessão");
        } finally {
            Files.delete(dir);
            if (Files.isDirectory(moved)) {
                Files.move(moved, dir);
            }
        }
        JsonNode retry = recording("finished", "rec-025", p);
        assertExported(retry);
        assertFalse(retry.get("duplicate").asBoolean(), "a falha não foi memorizada");
        try (Stream<Path> s = Files.list(pagemaps())) {
            assertTrue(s.noneMatch(f -> f.toString().endsWith(".tmp")), "nenhum arquivo parcial");
        }
        // Gravação cuja aba foi fechada: sessão descartada → SNAPSHOT_MISSING.
        Page q = open(REC + "?aba=fechada"); // URL distinta: o harness localiza a aba pelo endereço
        recording("started", "rec-025b", q);
        waitSessionActive(q);
        lab.close(q);
        final JsonNode[] last = new JsonNode[1];
        try {
            BrowserLab.waitUntil(Duration.ofSeconds(10), () -> {
                last[0] = lab.call("session.status", Map.of("tabId", q.tabId()));
                return "TAB_CLOSED".equals(last[0].at("/lastEnded/reason").asText());
            }, "sessão descartada ao fechar a aba");
        } catch (AssertionError e) {
            throw new AssertionError(e.getMessage() + " " + last[0] + " events=" + lab.events("session-status"), e);
        }
        JsonNode missing = lab.call("recording.event", Map.of("event", Map.of("type", "recording.session.finished", "sessionId", "rec-025b", "tabId", q.tabId(), "reason", "USER_FINISHED")));
        assertEquals("FAILED", missing.get("status").asText());
        assertEquals("SNAPSHOT_MISSING", missing.at("/errors/0/code").asText());
    }

    @Test
    @DisplayName("C002-048 — Recursos DEV não expostos em PRD")
    void prdHidesDevFeatures() throws Exception {
        JsonNode cfg = lab.call("config.setMode", Map.of("mode", "PRD"));
        assertEquals("PRD", cfg.get("mode").asText());
        Page p = open(REC);
        String panel = lab.openExtensionPage("src/ui/panel.html?tabId=" + p.tabId());
        WebDriver w = lab.driver();
        try {
            waitText(w, "mode-badge", "PRD");
            for (String id : List.of("dev-tools", "btn-capture", "btn-export", "btn-compare", "btn-resolve", "dev-output")) {
                assertTrue(w.findElements(By.id(id)).isEmpty(), id + " não existe em PRD");
            }
            assertEquals("Ativar inspeção", text(w, "btn-inspect"));
            w.findElement(By.id("btn-inspect")).click();
            waitText(w, "btn-inspect", "Desativar inspeção");
            lab.screenshotWindow(panel, "C002-048-panel-prd");
            w.findElement(By.id("btn-inspect")).click();
            waitText(w, "btn-inspect", "Ativar inspeção");
        } finally {
            lab.closeWindow(panel);
        }
        for (String cmd : List.of("page.capture", "pagemap.export", "page.compare", "target.resolve", "session.forceFull", "page.rawSnapshot", "session.debug")) {
            JsonNode r = lab.callRaw(cmd, Map.of("tabId", p.tabId()));
            assertFalse(r.get("ok").asBoolean(), cmd);
            assertEquals("MODE_RESTRICTED", r.at("/error/code").asText(), cmd);
        }
        lab.call("config.setMode", Map.of("mode", "DEV"));
        assertEquals("DEV", lab.call("config.get", Map.of()).get("mode").asText());
    }

    @Test
    @DisplayName("C002-049 — Término da inspeção sem gravação não exporta")
    void inspectionEndWithoutRecordingDoesNotExport() throws Exception {
        Page p = open(REC);
        long before = exportedFiles();
        lab.clearEvents();
        activate(p);
        act(p, "adicionar-linha");
        sync(p);
        deactivate(p);
        assertEquals(before, exportedFiles(), "desativar a inspeção não grava PageMap");
        assertTrue(lab.events("recording").isEmpty());
        assertTrue(lastEnded(p).get("lastExport").isNull());
        // Desativar a inspeção NÃO finaliza uma gravação em andamento.
        recording("started", "rec-049", p);
        activate(p);
        deactivate(p);
        JsonNode s = session(p);
        assertEquals("ACTIVE", s.get("status").asText(), "gravação mantém a captura");
        assertEquals(before, exportedFiles());
        assertEquals("CANCELLED", recording("cancelled", "rec-049", p).get("status").asText());
        BrowserLab.waitUntil(Duration.ofSeconds(5), () -> session(p).isMissingNode() || session(p).isNull(), "cancelamento libera a sessão sem exportar");
        assertEquals(before, exportedFiles());
    }

    @Test
    @DisplayName("C002-050 — Conteúdo sensível alterado por mutação não é exportado")
    void sensitiveMutationsNotExported() throws Exception {
        Page p = open("/lab/chrome002/continuous.html");
        recording("started", "rec-050", p);
        waitSessionActive(p);
        act(p, "preencher-sensivel");
        sync(p);
        JsonNode r = recording("finished", "rec-050", p);
        assertExported(r);
        String file = Files.readString(Path.of(r.get("path").asText()), StandardCharsets.UTF_8);
        for (String marker : List.of("SENHA-FICTICIA-0042", "TOKEN-FICTICIO-9911", "USUARIO-FICTICIO")) {
            assertFalse(file.contains(marker), "valor sensível no PageMap: " + marker);
        }
        // Nem o snapshot bruto do navegador transporta os valores (primeira barreira).
        String raw = lab.call("page.rawSnapshot", p).toString();
        for (String marker : List.of("SENHA-FICTICIA-0042", "TOKEN-FICTICIO-9911", "USUARIO-FICTICIO")) {
            assertFalse(raw.contains(marker), "valor sensível no snapshot bruto: " + marker);
        }
        assertNotEquals(-1, file.indexOf("senha"), "o campo continua inventariado (sem o valor)");
    }
}
