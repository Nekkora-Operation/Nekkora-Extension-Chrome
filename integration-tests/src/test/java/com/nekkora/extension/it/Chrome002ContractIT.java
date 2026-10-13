package com.nekkora.extension.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekkora.extension.it.support.BrowserLab;
import com.nekkora.extension.it.support.BrowserLab.Page;
import com.nekkora.extension.it.support.Contracts;
import com.nekkora.extension.it.support.SessionIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CHROME-002 §32 — contratos preservados (PageMap 0.1.0, raw-snapshot) e contratos novos separados por responsabilidade. */
class Chrome002ContractIT extends SessionIT {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String FILE = "capture-session.schema.json";

    @Test
    @DisplayName("C002-046 — Contrato v0.1.0 preservado e contratos de sessão/inspeção/finalização/exportação")
    void contracts() throws Exception {
        // PageMap 0.1.0 com fontes de frame, shadow roots e segmentos de texto.
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
        JsonNode map = lab.call("pagemap.export", p, "returnPageMap", true).get("pageMap");
        Contracts.assertValid("page-map.schema.json", map);
        assertEquals("nekkora.pagemap", map.get("schema").asText());
        assertEquals("0.1.0", map.get("version").asText(), "versão do contrato inalterada");
        boolean frameNodes = false;
        for (JsonNode e : map.get("elements")) {
            frameNodes |= e.get("nodeId").asText().startsWith("x");
        }
        assertTrue(frameNodes, "elementos de frames de outra origem com ids prefixados");

        // Snapshots brutos (principal e de um frame de outra origem) seguem o raw-snapshot.
        Contracts.assertValid("raw-snapshot.schema.json", lab.call("page.rawSnapshot", p));
        int fid = -1;
        for (JsonNode f : session(p).get("frames")) {
            if ("ATTACHED".equals(f.get("status").asText())) {
                fid = f.get("frameId").asInt();
            }
        }
        JsonNode frameRaw = lab.call("page.rawSnapshot", p, "frameId", fid);
        Contracts.assertValid("raw-snapshot.schema.json", frameRaw);
        assertTrue(frameRaw.at("/nodes/0/id").asText().startsWith("x" + fid + "n"));

        // Sessão (navegador e motor) e inspeção.
        Contracts.assertValidDef(FILE, "CaptureSessionStatus", session(p));
        Contracts.assertValidDef(FILE, "InspectionState", inspection(p));
        JsonNode hostStatus = lab.call("host.request", Map.of("type", "capture.session.status", "payload", Map.of("sessionId", session(p).get("sessionId").asText()))).get("payload");
        Contracts.assertValidDef(FILE, "HostSessionStatus", hostStatus);

        // Delta sequenciado real (instrumento DEV guarda o último delta enviado).
        lab.call("session.debug", p, "action", "keepLastDelta");
        act(p, "inserir");
        sync(p);
        JsonNode delta = lab.call("session.debug", p, "action", "lastDelta").get("delta");
        Contracts.assertValidDef(FILE, "SequencedDelta", delta);
        assertEquals("top", delta.get("source").asText());
        deactivate(p);
        Contracts.assertValidDef(FILE, "InspectionState", inspection(p));

        // Gravação: eventos, resultado exportado, resultado com falha e exportação do motor.
        Page r = open("/lab/chrome002/recording.html");
        Map<String, Object> started = Map.of("type", "recording.session.started", "sessionId", "rec-046", "tabId", r.tabId());
        Map<String, Object> finished = Map.of("type", "recording.session.finished", "sessionId", "rec-046", "tabId", r.tabId(), "reason", "USER_FINISHED");
        Contracts.assertValidDef(FILE, "RecordingEvent", M.valueToTree(started));
        Contracts.assertValidDef(FILE, "RecordingEvent", M.valueToTree(finished));
        lab.call("recording.event", Map.of("event", started));
        waitSessionActive(r);
        String captureSession = session(r).get("sessionId").asText();
        JsonNode exported = lab.call("recording.event", Map.of("event", finished));
        Contracts.assertValidDef(FILE, "RecordingExportResult", exported);
        Contracts.assertValid("page-map.schema.json", M.readTree(Path.of(exported.get("path").asText()).toFile()));
        JsonNode duplicate = lab.call("recording.event", Map.of("event", finished));
        Contracts.assertValidDef(FILE, "RecordingExportResult", duplicate);
        JsonNode failed = lab.call("recording.event", Map.of("event", Map.of("type", "recording.session.finished", "sessionId", "rec-046-x", "tabId", r.tabId(), "reason", "USER_FINISHED")));
        Contracts.assertValidDef(FILE, "RecordingExportResult", failed);
        // export.session do motor (nova sessão manual para obter o resultado bruto)
        lab.call("session.acquire", r, "consumer", "manual");
        String sid = session(r).get("sessionId").asText();
        JsonNode hostExport = lab.call("host.request", Map.of("type", "export.session", "payload", Map.of("sessionId", sid, "operationId", "op-046", "syncState", "SYNCHRONIZED"))).at("/payload/export");
        Contracts.assertValidDef(FILE, "ExportResult", hostExport);
        assertTrue(!captureSession.equals(sid), "nova sessão após a gravação finalizada");
        lab.call("session.release", r, "consumer", "manual");
    }
}
