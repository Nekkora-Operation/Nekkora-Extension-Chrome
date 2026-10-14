package com.nekkora.extension.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nekkora.extension.it.support.BrowserLab;
import com.nekkora.extension.it.support.BrowserLab.Page;
import com.nekkora.extension.it.support.SessionIT;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.chrome.ChromeDriver;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CHROME-002 §14–§16, §21 — Shadow DOM dinâmico, iframes de origens locais, AccName e reencontro após mutação. */
class Chrome002DynamicIT extends SessionIT {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String SHADOW = "/lab/chrome002/shadow-dynamic.html";
    private static final String FRAMES = "/lab/chrome002/iframes.html";
    private static final String ACC = "/lab/chrome002/accessible-names.html";
    private static final ArrayNode ACC_TABLE = M.createArrayNode();
    private static final ObjectNode MEASURES = M.createObjectNode();

    private String idIn(Page p, String css, Object... chain) {
        List<Map<String, String>> hops = new java.util.ArrayList<>();
        for (int i = 0; i + 1 < chain.length; i += 2) {
            hops.add(Map.of("kind", (String) chain[i], "css", (String) chain[i + 1]));
        }
        JsonNode r = lab.call("page.idFor", p, "css", css, "scope", Map.of("chain", hops));
        assertEquals(1, r.path("count").asInt(), css + " " + r);
        return r.at("/nodeIds/0").asText();
    }

    private JsonNode selectNode(Page p, String nodeId) {
        return lab.call("page.select", p, "nodeId", nodeId);
    }

    private JsonNode exportMap(Page p) {
        return lab.call("pagemap.export", p, "returnPageMap", true).get("pageMap");
    }

    /** Elementos do PageMap cujo nome acessível é exatamente {@code name}. */
    private static int named(JsonNode map, String name) {
        int n = 0;
        for (JsonNode e : map.get("elements")) {
            if (name.equals(e.at("/text/accessibleName").asText())) {
                n++;
            }
        }
        return n;
    }

    /** Textos próprios e nomes de todos os elementos (sem alvos, evidências ou contexto). */
    private static String elementTexts(JsonNode map) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode e : map.get("elements")) {
            sb.append(e.at("/text/own").asText()).append(" | ").append(e.at("/text/accessibleName").asText()).append(" | ");
        }
        return sb.toString();
    }

    private static String frameUrls(JsonNode map) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode f : map.at("/coverage/frames")) {
            sb.append(f.path("url").asText()).append(' ');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------------------- Shadow DOM

    @Test
    @DisplayName("C002-026 — Novo shadow root (inclusive aninhado e conteúdo alterado) incorporado automaticamente")
    void newShadowRoot() {
        Page p = open(SHADOW);
        activate(p);
        act(p, "criar");
        act(p, "aninhado");
        sync(p);
        JsonNode sel = selectNode(p, idIn(p, "#confirmar", "SHADOW_HOST", "#painel"));
        assertEquals("BUTTON", sel.at("/inspection/classification/kind").asText());
        assertEquals("Confirmar painel", sel.at("/inspection/classification/name/name").asText());
        JsonNode nested = selectNode(p, idIn(p, "#confirmar", "SHADOW_HOST", "#externo", "SHADOW_HOST", "#interno"));
        assertEquals("Confirmar painel", nested.at("/inspection/classification/name/name").asText());
        act(p, "alterar");
        sync(p);
        JsonNode extra = selectNode(p, idIn(p, "#extra", "SHADOW_HOST", "#painel"));
        assertEquals("Botão interno novo", extra.at("/inspection/classification/name/name").asText());
        assertTrue(elementTexts(exportMap(p)).contains("Painel alterado"), "texto alterado dentro do shadow root");
        // Slots: conteúdo projetado inventariado.
        JsonNode slotted = select(p, "#com-slot button");
        assertEquals("Ação projetada", slotted.at("/inspection/classification/name/name").asText());
        assertEquals(1, session(p).get("fullCaptures").asInt(), "somente deltas");
        deactivate(p);
    }

    @Test
    @DisplayName("C002-027 — Shadow root removido: nós e observador descartados")
    void removedShadowRoot() {
        Page p = open(SHADOW);
        activate(p);
        act(p, "criar");
        sync(p);
        String inner = idIn(p, "#confirmar", "SHADOW_HOST", "#painel");
        selectNode(p, inner);
        int observers = diagnostics(p).get("observers").asInt();
        assertEquals(1, named(exportMap(p), "Confirmar painel"));
        String hostId = nodeIdOf(p, "#painel");
        act(p, "remover");
        sync(p);
        JsonNode after = exportMap(p);
        assertEquals(0, named(after, "Confirmar painel"), "conteúdo do shadow root removido do estado");
        for (JsonNode sr : after.at("/coverage/shadowRoots")) {
            assertNotEquals(hostId, sr.path("hostNodeId").asText(), "shadow root removido não é mais declarado: " + sr);
        }
        BrowserLab.CallException e = org.junit.jupiter.api.Assertions.assertThrows(BrowserLab.CallException.class, () -> selectNode(p, inner));
        assertTrue(e.code.contains("UNKNOWN_NODE") || e.getMessage().contains("UNKNOWN_NODE"), e.code + " " + e.getMessage());
        act(p, "criar"); // força um novo delta: observadores mortos são desligados na reconciliação
        sync(p);
        assertTrue(diagnostics(p).get("observers").asInt() <= observers, "sem acúmulo de observadores");
        deactivate(p);
    }

    @Test
    @DisplayName("C002-028 — attachShadow() em host já conectado: detectado pela reconciliação (latência medida)")
    void shadowAttachedToExistingHost() {
        Page p = open(SHADOW);
        activate(p);
        long t0 = System.nanoTime();
        act(p, "tardio");
        String[] id = new String[1];
        BrowserLab.waitUntil(Duration.ofSeconds(5), () -> {
            sync(p);
            id[0] = idIn(p, "#tardio-btn", "SHADOW_HOST", "#host-tardio");
            try {
                selectNode(p, id[0]);
                return true;
            } catch (BrowserLab.CallException ex) {
                return false;
            }
        }, "shadow root tardio incorporado");
        double latencyMs = (System.nanoTime() - t0) / 1e6;
        MEASURES.put("lateAttachShadowLatencyMs", Math.round(latencyMs));
        MEASURES.put("reconcileIntervalMs", 1000);
        assertTrue(latencyMs <= 3000, "latência de detecção: " + latencyMs);
        assertTrue(diagnostics(p).at("/tracker/shadowRootsDiscovered").asInt() >= 1);
        JsonNode sel = selectNode(p, id[0]);
        assertEquals("Botão do shadow tardio", sel.at("/inspection/classification/name/name").asText());
        // Shadow root fechado criado dinamicamente continua declarado (sem conteúdo).
        act(p, "fechado");
        sync(p);
        assertTrue(exportMap(p).at("/coverage").toString().contains("CLOSED"), "shadow root fechado declarado");
        deactivate(p);
    }

    // ---------------------------------------------------------------------------------- iframes

    private JsonNode framesList(Page p) {
        return lab.call("frames.list", p);
    }

    private int chromeFrameId(Page p, String urlPart) {
        for (JsonNode f : framesList(p).get("chrome")) {
            if (f.get("url").asText().contains(urlPart)) {
                return f.get("frameId").asInt();
            }
        }
        throw new AssertionError("frame não encontrado: " + urlPart + " em " + framesList(p));
    }

    private JsonNode sessionFrame(Page p, int frameId) {
        for (JsonNode f : session(p).get("frames")) {
            if (f.get("frameId").asInt() == frameId) {
                return f;
            }
        }
        return null;
    }

    private void waitAttached(Page p, String urlPart) {
        BrowserLab.waitUntil(Duration.ofSeconds(15), () -> {
            try {
                JsonNode f = sessionFrame(p, chromeFrameId(p, urlPart));
                return f != null && "ATTACHED".equals(f.get("status").asText());
            } catch (AssertionError e) {
                return false;
            }
        }, "frame anexado: " + urlPart);
    }

    @Test
    @DisplayName("C002-029 — Iframe de mesma origem: capturado pelo documento principal, sem duplicidade")
    void sameOriginIframe() {
        Page p = open(FRAMES);
        activate(p);
        waitAttached(p, "outra=1");
        JsonNode sel = selectIn(p, "#pagar", "FRAME", "#f-mesma");
        assertEquals("Pagar no frame", sel.at("/inspection/classification/name/name").asText());
        assertFalse(sel.at("/inspection/nodeId").asText().startsWith("x"), "nó do documento principal");
        JsonNode f = sessionFrame(p, chromeFrameId(p, "mesma=1"));
        assertEquals("CAPTURED_BY_PARENT", f.get("status").asText(), "o content script do frame não duplica o inventário");
        deactivate(p);
    }

    @Test
    @DisplayName("C002-030 — Iframe de outra origem local permitida (porta +1), aninhado e com Shadow DOM")
    void otherOriginIframe() throws Exception {
        Page p = open(FRAMES);
        activate(p);
        waitAttached(p, "outra=1");
        waitAttached(p, "neto=1");
        int fid = chromeFrameId(p, "outra=1");
        JsonNode r = lab.call("page.idFor", p, "css", "#pagar", "frameId", fid);
        assertEquals(1, r.get("count").asInt());
        String nodeId = r.at("/nodeIds/0").asText();
        assertTrue(nodeId.startsWith("x" + fid + "n"), nodeId);
        JsonNode sel = selectNode(p, nodeId);
        assertEquals("BUTTON", sel.at("/inspection/classification/kind").asText());
        assertEquals("Pagar no frame", sel.at("/inspection/classification/name/name").asText());
        BrowserLab.waitUntil(Duration.ofSeconds(5), () -> lab.call("page.diagnostics", p, "frameId", fid).at("/highlight/overlayPresent").asBoolean(), "destaque desenhado dentro do frame");
        // Shadow DOM dentro do frame de outra origem
        JsonNode shadow = lab.call("page.idFor", p, "css", "#acao-shadow", "frameId", fid, "scope", Map.of("chain", List.of(Map.of("kind", "SHADOW_HOST", "css", "#cartao-frame"))));
        assertEquals("Ação no shadow do frame", selectNode(p, shadow.at("/nodeIds/0").asText()).at("/inspection/classification/name/name").asText());
        // Mutação dentro do frame → delta da fonte do frame
        lab.focus(p);
        lab.driver().switchTo().frame(lab.driver().findElement(By.id("f-outra")));
        ((org.openqa.selenium.JavascriptExecutor) lab.driver()).executeScript("document.getElementById('mudar').onclick()");
        lab.driver().switchTo().defaultContent();
        sync(p);
        JsonNode map = exportMap(p);
        assertTrue(elementTexts(map).contains("Item do frame 1"), "delta do frame incorporado");
        // Ids duplicados entre documentos: um botão #pagar por documento inspecionado, nenhum repetido.
        assertEquals(1, named(map, "Pagar (página principal)"));
        assertEquals(3, named(map, "Pagar no frame"), "mesma origem + outra origem + neto (sandbox e sem permissão excluídos)");
        lab.writeEvidence("C002-030-frames.json", M.writerWithDefaultPrettyPrinter().writeValueAsString(framesList(p)));
        lab.screenshot(p, "C002-030-iframe-other-origin-highlight");
        deactivate(p);
    }

    @Test
    @DisplayName("C002-031 — Iframe dinâmico: inserido, removido e documento substituído")
    void dynamicIframes() {
        Page p = open(FRAMES);
        activate(p);
        waitAttached(p, "outra=1");
        act(p, "inserir");
        waitAttached(p, "dinamico=1");
        JsonNode withDynamic = exportMap(p);
        assertTrue(frameUrls(withDynamic).contains("dinamico=1"));
        int withFrame = named(withDynamic, "Pagar no frame");
        act(p, "remover");
        sync(p);
        JsonNode afterRemoval = exportMap(p);
        assertFalse(frameUrls(afterRemoval).contains("dinamico=1"), "frame removido descartado do estado: " + frameUrls(afterRemoval));
        int before = named(afterRemoval, "Pagar no frame");
        assertEquals(withFrame - 1, before, "nós do frame removido descartados");
        act(p, "substituir");
        waitAttached(p, "outra=2");
        sync(p);
        JsonNode replaced = exportMap(p);
        assertTrue(frameUrls(replaced).contains("outra=2"), frameUrls(replaced));
        assertFalse(frameUrls(replaced).contains("outra=1"), "documento anterior do frame descartado: " + frameUrls(replaced));
        assertEquals(before, named(replaced, "Pagar no frame"), "substituição sem duplicar nós");
        deactivate(p);
    }

    @Test
    @DisplayName("C002-032 — Iframe sem permissão (127.0.0.2) e sandbox: não inspecionados, limite declarado")
    void iframeWithoutPermission() {
        Page p = open(FRAMES);
        activate(p);
        waitAttached(p, "outra=1");
        JsonNode cov = exportMap(p).get("coverage");
        boolean noPermission = false;
        for (JsonNode f : cov.get("frames")) {
            if (f.path("url").asText().contains("127.0.0.2")) {
                noPermission = true;
                assertFalse(f.get("inspected").asBoolean());
            }
        }
        assertTrue(noPermission, "frame sem permissão declarado: " + cov.get("frames"));
        for (JsonNode f : session(p).get("frames")) {
            assertFalse(f.path("url").asText("").contains("127.0.0.2"), "nenhum content script na origem sem permissão");
        }
        JsonNode sandbox = sessionFrame(p, chromeFrameId(p, "sandbox=1"));
        if (sandbox != null) {
            assertNotEquals("ATTACHED", sandbox.get("status").asText(), "sandbox não anexado: " + sandbox);
        }
        assertTrue(cov.toString().contains("SANDBOXED"), "sandbox declarado na cobertura");
        deactivate(p);
    }

    // ---------------------------------------------------------------------------------- AccName

    /** Nome acessível calculado pelo próprio Chrome (CDP Accessibility) — referência, não dependência do domínio. */
    private String chromeName(Page p, String css) {
        lab.focus(p);
        ChromeDriver d = (ChromeDriver) lab.driver();
        d.executeCdpCommand("DOM.enable", Map.of());
        d.executeCdpCommand("Accessibility.enable", Map.of());
        Map<String, Object> doc = d.executeCdpCommand("DOM.getDocument", Map.of("depth", 0));
        Number rootId = (Number) ((Map<?, ?>) doc.get("root")).get("nodeId");
        Map<String, Object> q = d.executeCdpCommand("DOM.querySelector", Map.of("nodeId", rootId, "selector", css));
        Map<String, Object> ax = d.executeCdpCommand("Accessibility.getPartialAXTree", Map.of("nodeId", q.get("nodeId"), "fetchRelatives", false));
        List<?> nodes = (List<?>) ax.get("nodes");
        Map<?, ?> first = (Map<?, ?>) nodes.get(0);
        Map<?, ?> name = (Map<?, ?>) first.get("name");
        return name == null ? "" : String.valueOf(name.get("value"));
    }

    private JsonNode compareName(Page p, String css, String expected) {
        JsonNode sel = select(p, css);
        JsonNode name = sel.at("/inspection/classification/name");
        String chrome = chromeName(p, css);
        ObjectNode row = ACC_TABLE.addObject();
        row.put("element", css).put("expected", expected).put("nekkora", name.get("name").asText()).put("chrome", chrome)
                .put("source", name.get("source").asText()).put("confidence", name.path("confidence").asText())
                .put("agreesWithChrome", name.get("name").asText().equals(chrome.trim().replaceAll("\\s+", " ")));
        assertEquals(expected, name.get("name").asText(), css + " " + name);
        // O Chrome não apara espaços de fronteira em alguns casos (ex.: texto após ícone aria-hidden): compara normalizado.
        assertEquals(expected, chrome.trim().replaceAll("\\s+", " "), "referência do Chrome para " + css);
        assertTrue(name.has("confidence"), "confiança registrada");
        return name;
    }

    @Test
    @DisplayName("C002-033 — AccName: aria-labelledby recursivo (referência não é seguida em segundo nível)")
    void accnameRecursive() {
        Page p = open(ACC);
        compareName(p, "#b-recursivo", "ignorado");
    }

    @Test
    @DisplayName("C002-034 — AccName: aria-labelledby múltiplo e autorreferência")
    void accnameMultiple() {
        Page p = open(ACC);
        JsonNode n = compareName(p, "#b-multiplo", "Remessa internacional");
        assertEquals(2, n.get("sourceNodeIds").size(), "duas fontes registradas");
        compareName(p, "#b-self", "Enviar internacional");
    }

    @Test
    @DisplayName("C002-035 — AccName: referência circular termina")
    void accnameCircular() {
        Page p = open(ACC);
        compareName(p, "#b-circular", "Ciclo um Ciclo dois");
    }

    @Test
    @DisplayName("C002-036 — AccName: elemento oculto referenciado por aria-labelledby")
    void accnameHiddenReferenced() {
        Page p = open(ACC);
        compareName(p, "#b-oculto", "Rótulo oculto referenciado");
    }

    @Test
    @DisplayName("C002-037 — AccName: botões com ícone, title, controles e HTML-AAM (comparação com o Chrome)")
    void accnameIconButtonsAndHtmlAam() throws Exception {
        Page p = open(ACC);
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("#b-svg", "Excluir registro");
        cases.put("#b-img", "Imprimir");
        cases.put("#b-icone-oculto", "Favoritar");
        cases.put("#b-title", "Configurações");
        cases.put("#b-aria-label", "Fechar diálogo");
        cases.put("#c-prioridade", "Prioridade");
        cases.put("#c-placeholder", "Pesquisar clientes");
        cases.put("#c-title", "Código do cliente");
        cases.put("#c-descrito", "E-mail");
        cases.put("#c-rua", "Rua");
        cases.put("#a-texto", "Ver detalhes do pedido");
        cases.put("#r-botao", "Atualizar lista");
        for (Map.Entry<String, String> e : cases.entrySet()) {
            compareName(p, e.getKey(), e.getValue());
        }
        JsonNode described = select(p, "#c-descrito").at("/inspection/classification/name");
        assertEquals("Usaremos para notificações", described.get("description").asText());
    }

    // ------------------------------------------------------------------------------- reencontro

    @Test
    @DisplayName("C002-043 — Destaque acompanha a re-renderização (reencontro automático comprovado)")
    void highlightAfterRerender() throws Exception {
        Page p = open("/lab/chrome002/continuous.html");
        activate(p);
        JsonNode sel = select(p, "#aprovar");
        String before = sel.at("/inspection/nodeId").asText();
        lab.clearEvents();
        act(p, "rerender");
        sync(p);
        BrowserLab.waitUntil(Duration.ofSeconds(10), () -> {
            for (JsonNode e : lab.events("resolution")) {
                if (e.at("/data/automatic").asBoolean() && "RESOLVED".equals(e.at("/data/result/status").asText())) {
                    return true;
                }
            }
            return false;
        }, "reencontro automático RESOLVED");
        String now = nodeIdOf(p, "#aprovar");
        assertNotEquals(before, now, "nó substituído");
        BrowserLab.waitUntil(Duration.ofSeconds(5), () -> now.equals(diagnostics(p).at("/highlight/boxes/selection/nodeId").asText()), "destaque migrou para o novo nó");
        assertTrue(diagnostics(p).at("/highlight/boxes/selection/visible").asBoolean());
        lab.screenshot(p, "C002-043-highlight-after-rerender");
        deactivate(p);
    }

    @Test
    @DisplayName("C002-044 — Ambiguidade após mutação: sem destaque e sem escolha silenciosa")
    void ambiguityAfterMutation() {
        Page p = open("/lab/chrome002/continuous.html");
        activate(p);
        JsonNode sel = select(p, ".exportar");
        String target = sel.at("/identity/targetId").asText();
        lab.clearEvents();
        act(p, "rerender-duplicado");
        sync(p);
        BrowserLab.waitUntil(Duration.ofSeconds(10), () -> {
            for (JsonNode e : lab.events("resolution")) {
                if (e.at("/data/automatic").asBoolean()) {
                    return true;
                }
            }
            return false;
        }, "reencontro automático executado");
        JsonNode r = resolve(p, target);
        assertEquals("AMBIGUOUS", r.get("status").asText(), r.toString());
        assertTrue(r.get("resolvedNodeId").isNull());
        BrowserLab.waitUntil(Duration.ofSeconds(5), () -> !diagnostics(p).at("/highlight/boxes/selection/visible").asBoolean(false), "destaque removido");
        deactivate(p);
    }

    @AfterAll
    static void writeEvidence() throws Exception {
        if (lab == null) {
            return;
        }
        ObjectNode out = M.createObjectNode();
        out.set("accessibleNames", ACC_TABLE);
        out.set("measures", MEASURES);
        lab.writeEvidence("C002-dynamic-evidence.json", M.writerWithDefaultPrettyPrinter().writeValueAsString(out));
    }
}
