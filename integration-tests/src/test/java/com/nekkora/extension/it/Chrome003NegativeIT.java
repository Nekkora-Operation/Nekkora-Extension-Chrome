package com.nekkora.extension.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.nekkora.extension.it.support.BrowserLab;
import com.nekkora.extension.it.support.BrowserLab.Page;
import com.nekkora.extension.it.support.FrameIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Casos negativos da CHROME-003 (§32): o diagnóstico nunca inventa, nunca amplia permissões e nunca age sobre a página. */
class Chrome003NegativeIT extends FrameIT {

    @Test
    @DisplayName("N003-A — Origem sem permissão (127.0.0.2): comandos de diagnóstico recusados com ORIGIN_NOT_ALLOWED")
    void originNotAllowed() {
        Page p = openWithoutContentScript("http://127.0.0.2:" + BrowserLab.PORT + "/lab/chrome003/frames.html");
        try {
            for (String cmd : new String[]{"page.diagnose", "frames.graph", "protection.assess", "diagnostics.readiness"}) {
                JsonNode r = lab.callRaw(cmd, Map.of("tabId", p.tabId()));
                assertFalse(r.path("ok").asBoolean(), cmd + ": " + r);
                assertEquals("ORIGIN_NOT_ALLOWED", r.at("/error/code").asText(), cmd);
            }
        } finally {
            lab.close(p);
        }
    }

    @Test
    @DisplayName("N003-B — Trajetória de nó inexistente: UNKNOWN_NODE (nada é reconstruído por adivinhação)")
    void unknownNode() {
        Page p = openActive(FRAMES, 9);
        BrowserLab.CallException e = assertThrows(BrowserLab.CallException.class, () -> accessPath(p, "n999999"));
        assertEquals("UNKNOWN_NODE", e.code);
    }

    @Test
    @DisplayName("N003-C — Anúncios de handshake forjados pela página não alteram a associação de frames")
    void forgedHandshake() {
        Page p = openActive(FRAMES, 9);
        String before = mappings(graph(p));
        lab.js(p, "for (const f of document.querySelectorAll('iframe')) { f.contentWindow.postMessage({__nekkoraFrame: 'nkf-forjado'}, '*');"
                + " window.postMessage({__nekkoraFrame: 'nkf-forjado-' + f.id}, '*'); }");
        lab.call("frames.syncMetadata", p);
        sleep(500);
        assertEquals(before, mappings(graph(p)), "tokens forjados não são aceitos como handshake");
    }

    private static String mappings(JsonNode g) {
        StringBuilder sb = new StringBuilder();
        g.get("frames").forEach(f -> sb.append(f.get("frameId").asText()).append('=').append(f.at("/browser/browserFrameId").asText())
                .append('/').append(f.get("hostNodeId").asText()).append(';'));
        return sb.toString();
    }

    @Test
    @DisplayName("N003-D — Elemento no documento principal: 'Destacar iframe' não destaca nada (ELEMENT_IN_MAIN_DOCUMENT)")
    void highlightWithoutFrame() {
        Page p = openActive(FRAMES, 9);
        JsonNode r = lab.call("page.highlightFrame", p, "nodeId", nodeIdOf(p, "#salvar-principal"));
        assertFalse(r.get("highlighted").asBoolean());
        assertEquals("ELEMENT_IN_MAIN_DOCUMENT", r.get("reason").asText());
    }

    @Test
    @DisplayName("N003-E — Iframe sem permissão: nenhuma trajetória para o interior; o próprio iframe tem trajetória no documento pai")
    void noPathIntoInaccessibleFrame() {
        Page p = openActive(ORIGINS, 5);
        JsonNode r = lab.callRaw("page.idFor", Map.of("tabId", p.tabId(), "css", "button", "scope",
                Map.of("chain", java.util.List.of(Map.of("kind", "FRAME", "css", "#f-externo")))));
        assertTrue(r.toString().contains("SCOPE_UNAVAILABLE"), r.toString());
        JsonNode host = accessPath(p, nodeIdOf(p, "#f-externo"));
        assertEquals("MAIN_DOCUMENT", host.at("/steps/0/type").asText());
        assertEquals("ELEMENT", host.at("/steps/1/type").asText());
        assertTrue(diagnose(p).at("/readiness/obstacles").toString().contains("INACCESSIBLE_FRAME"));
    }

    @Test
    @DisplayName("N003-F — Documento do frame trocado (src alterado): alvo não aceito em outro documento ⇒ FRAME_DOCUMENT_CHANGED")
    void frameDocumentChanged() {
        Page p = openActive(DYNAMIC, 5);
        String t = selectNode(p, idIn(p, "button[type=submit]", "FRAME", "#f-pedidos")).at("/identity/targetId").asText();
        act(p, "mudar-src");
        waitGraph(p, x -> frameTitled(p, x, "Pedidos") != null && frameTitled(p, x, "Pedidos").get("effectiveUrl").asText().endsWith("/widget.html"),
                "documento do frame substituído");
        JsonNode r = resolve(p, t);
        evidence("N003-F-frame-document-changed.json", r);
        assertNotEquals("RESOLVED", r.get("status").asText(), r.toString());
        assertTrue(r.get("diagnostics").toString().contains("FRAME_DOCUMENT_CHANGED"), r.get("diagnostics").toString());
    }

    @Test
    @DisplayName("N003-G — Shadow root fechado: contado e declarado, conteúdo nunca inventado")
    void closedShadowRoot() {
        Page p = openActive(FRAMES, 9);
        JsonNode d = diagnose(p);
        assertEquals(1, d.at("/counts/shadowRootsClosed").asInt());
        assertTrue(d.at("/readiness/obstacles").toString().contains("CLOSED_SHADOW_ROOT"));
        assertFalse(exportMap(p).get("elements").toString().contains("Abrir cofre"));
    }

    @Test
    @DisplayName("N003-H — Permissão da página não se estende ao frame: nenhum content script no frame de 127.0.0.2")
    void noContentScriptInUnpermittedFrame() {
        Page p = openActive(ORIGINS, 5);
        JsonNode g = waitGraph(p, x -> frameTitled(p, x, "Parceiro externo") != null && frameTitled(p, x, "Parceiro externo").path("browser").isObject(),
                "metadados do frame externo");
        int frameId = requireFrame(p, g, "Parceiro externo").at("/browser/browserFrameId").asInt();
        JsonNode r = lab.callRaw("page.diagnostics", Map.of("tabId", p.tabId(), "frameId", frameId));
        assertFalse(r.path("ok").asBoolean(), "content script presente no frame sem permissão: " + r);
    }

    @Test
    @DisplayName("N003-I — Diagnóstico não altera a página: DOM idêntico antes/depois de diagnosticar, avaliar e exportar")
    void diagnosisDoesNotModifyThePage() {
        Page p = openActive(PROTECTION + "p02-hcaptcha.html", 1);
        String before = String.valueOf(lab.js(p, "return document.body.innerHTML"));
        diagnose(p);
        lab.call("protection.assess", p);
        lab.call("diagnostics.readiness", p);
        exportMap(p);
        assertEquals(before, String.valueOf(lab.js(p, "return document.body.innerHTML")));
        assertEquals("", String.valueOf(lab.js(p, "return document.getElementById('desafio').innerHTML")), "nenhum desafio aberto pela extensão");
    }

    @Test
    @DisplayName("N003-J — Sem captura: diagnóstico recusado (NO_CAPTURE), nunca fabricado")
    void noCaptureNoDiagnosis() {
        Page p = open(FRAMES);
        BrowserLab.waitUntil(Duration.ofSeconds(5), () -> true, "");
        JsonNode r = lab.callRaw("page.diagnose", Map.of("tabId", p.tabId()));
        assertFalse(r.path("ok").asBoolean(), r.toString());
        assertTrue(r.at("/error/code").asText().contains("CAPTURE"), r.toString());
    }
}
