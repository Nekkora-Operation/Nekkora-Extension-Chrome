package com.nekkora.extension.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.nekkora.extension.it.support.BrowserLab;
import com.nekkora.extension.it.support.BrowserLab.Page;
import com.nekkora.extension.it.support.SessionIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CHROME-002 §5–§10, §13, §24 — botão único, captura automática contínua, sessões por aba e recuperação. */
class Chrome002LifecycleIT extends SessionIT {

    private static final String CONT = "/lab/chrome002/continuous.html";
    private static final String NEKKORA_PINK = "rgb(194, 70, 109)";

    @Test
    @DisplayName("C002-001 — Botão inicialmente inativo")
    void initiallyInactive() throws Exception {
        Page p = open(CONT);
        JsonNode st = inspection(p);
        assertEquals("INACTIVE", st.get("state").asText());
        assertFalse(st.get("active").asBoolean());
        assertTrue(session(p).isMissingNode() || session(p).isNull(), "nenhuma sessão de captura antes da ativação");
        JsonNode d = diagnostics(p);
        assertEquals(0, d.get("listeners").asInt());
        assertEquals(0, d.get("observers").asInt());
        String panel = lab.openExtensionPage("src/ui/panel.html?tabId=" + p.tabId());
        WebDriver w = lab.driver();
        try {
            waitText(w, "host-status", "Conectado");
            WebElement b = w.findElement(By.id("btn-inspect"));
            assertEquals("Ativar inspeção", b.getText());
            assertEquals("false", b.getAttribute("aria-pressed"));
            assertTrue(b.getCssValue("background-color").replace("rgba", "rgb").startsWith(NEKKORA_PINK.substring(0, NEKKORA_PINK.length() - 1)), b.getCssValue("background-color"));
            assertEquals(1, w.findElements(By.cssSelector("button.inspect-toggle")).size(), "um único botão de inspeção");
            assertEquals("Inativa", text(w, "st-capture"));
            lab.screenshotWindow(panel, "C002-001-panel-inactive");
        } finally {
            lab.closeWindow(panel);
        }
    }

    @Test
    @DisplayName("C002-002 — Ativação: captura inicial, rastreamento, ouvintes e texto 'Desativar inspeção'")
    void activation() throws Exception {
        Page p = open(CONT);
        String panel = lab.openExtensionPage("src/ui/panel.html?tabId=" + p.tabId());
        WebDriver w = lab.driver();
        try {
            waitText(w, "host-status", "Conectado");
            w.findElement(By.id("btn-inspect")).click();
            waitText(w, "btn-inspect", "Desativar inspeção");
            WebElement b = w.findElement(By.id("btn-inspect"));
            assertEquals("true", b.getAttribute("aria-pressed"));
            waitText(w, "st-capture", "Automática ativa");
            BrowserLab.waitUntil(Duration.ofSeconds(5), () -> !text(w, "st-nodes").equals("—"), "contador de elementos");
            lab.screenshotWindow(panel, "C002-002-panel-active");
        } finally {
            lab.closeWindow(panel);
        }
        JsonNode st = inspection(p);
        assertEquals("ACTIVE", st.get("state").asText());
        JsonNode s = session(p);
        assertEquals("ACTIVE", s.get("status").asText());
        assertTrue(s.get("consumers").toString().contains("inspection"));
        assertEquals(1, s.get("fullCaptures").asInt());
        assertTrue(s.get("nodes").asInt() > 20);
        assertEquals("OK", s.get("hostState").asText());
        JsonNode d = diagnostics(p);
        assertTrue(d.get("listeners").asInt() > 0, "ouvintes do inspector");
        assertTrue(d.get("observers").asInt() > 0, "observador de mutações");
        assertEquals("ACTIVE", d.at("/tracker/mode").asText());
        List<String> states = new java.util.ArrayList<>();
        for (JsonNode e : lab.events("inspection-state")) {
            if (e.at("/data/tabId").asInt() == p.tabId()) {
                states.add(e.at("/data/state").asText());
            }
        }
        assertTrue(states.indexOf("ACTIVATING") >= 0 && states.indexOf("ACTIVATING") < states.lastIndexOf("ACTIVE"), "ACTIVATING → ACTIVE: " + states);
        deactivate(p);
    }

    @Test
    @DisplayName("C002-003 — Desativação: ouvintes, destaques e sessão liberados")
    void deactivation() throws Exception {
        Page p = open(CONT);
        activate(p);
        select(p, "#aprovar");
        assertTrue(diagnostics(p).at("/highlight/overlayPresent").asBoolean());
        String sessionId = session(p).get("sessionId").asText();
        String panel = lab.openExtensionPage("src/ui/panel.html?tabId=" + p.tabId());
        WebDriver w = lab.driver();
        try {
            waitText(w, "btn-inspect", "Desativar inspeção");
            w.findElement(By.id("btn-inspect")).click();
            waitText(w, "btn-inspect", "Ativar inspeção");
            assertEquals("false", w.findElement(By.id("btn-inspect")).getAttribute("aria-pressed"));
            waitText(w, "st-capture", "Inativa");
        } finally {
            lab.closeWindow(panel);
        }
        assertEquals("INACTIVE", inspection(p).get("state").asText());
        JsonNode d = diagnostics(p);
        assertEquals(0, d.get("listeners").asInt());
        assertEquals(0, d.get("observers").asInt());
        assertEquals("OFF", d.at("/tracker/mode").asText());
        assertFalse(d.at("/highlight/overlayPresent").asBoolean());
        JsonNode ended = lastEnded(p);
        assertEquals(sessionId, ended.get("sessionId").asText());
        assertEquals("INSPECTION_DEACTIVATED", ended.get("reason").asText());
        // Sem efeito residual: a ação normal da página volta a executar (clique real).
        lab.click(p, By.id("adicionar"));
        assertEquals(3L, lab.js(p, "return document.querySelectorAll('#tarefas li').length"));
    }

    @Test
    @DisplayName("C002-004 — Clique duplo: uma única ativação")
    void doubleClick() throws Exception {
        Page p = open(CONT);
        lab.clearEvents();
        String panel = lab.openExtensionPage("src/ui/panel.html?tabId=" + p.tabId());
        WebDriver w = lab.driver();
        try {
            waitText(w, "host-status", "Conectado");
            w.switchTo().window(panel);
            new Actions(w).doubleClick(w.findElement(By.id("btn-inspect"))).perform();
            waitText(w, "btn-inspect", "Desativar inspeção");
            sleep(1500);
            assertEquals("Desativar inspeção", text(w, "btn-inspect"), "o segundo clique não desativou");
        } finally {
            lab.closeWindow(panel);
        }
        assertEquals("ACTIVE", inspection(p).get("state").asText());
        assertEquals(1, session(p).get("fullCaptures").asInt(), "uma única captura-base");
        long activations = 0;
        for (JsonNode e : lab.events("inspection-state")) {
            if (e.at("/data/tabId").asInt() == p.tabId() && "ACTIVATING".equals(e.at("/data/state").asText())) {
                activations++;
            }
        }
        assertEquals(1, activations, "uma única transição ACTIVATING");
        // Clique duplo via comando também é idempotente (sem ativação concorrente).
        Thread t = new Thread(() -> lab.callRaw("inspection.activate", Map.of("tabId", p.tabId())));
        t.start();
        lab.call("inspection.activate", p);
        t.join();
        assertEquals(1, session(p).get("fullCaptures").asInt());
        deactivate(p);
    }

    @Test
    @DisplayName("C002-005 — Falha na ativação nunca indica ativo")
    void activationFailure() throws Exception {
        // (a) origem sem permissão
        Page denied = openWithoutContentScript("http://127.0.0.2:" + BrowserLab.PORT + "/lab/chrome002/continuous.html");
        try {
            BrowserLab.CallException e = assertThrows(BrowserLab.CallException.class, () -> lab.call("inspection.toggle", denied));
            assertEquals("ORIGIN_NOT_ALLOWED", e.code);
            JsonNode st = inspection(denied);
            assertEquals("ERROR", st.get("state").asText());
            assertFalse(st.get("active").asBoolean());
            assertTrue(session(denied).isMissingNode() || session(denied).isNull());
        } finally {
            lab.close(denied);
        }
        // (b) motor Java indisponível (host desinstalado): falha explícita, sem ouvintes residuais.
        Page p = open(CONT);
        // O Chrome for Testing 155 procura o host em HKCU\Software\Chromium e, na ausência, em
        // HKCU\Software\Google\Chrome: as duas entradas são removidas e restauradas pelo script oficial
        // (install-host.ps1 -SkipBuild). O JAR em uso não é tocado.
        lab.call("host.disconnect", Map.of());
        for (String key : List.of("HKCU\\Software\\Chromium\\NativeMessagingHosts\\com.nekkora.extension_chrome",
                "HKCU\\Software\\Google\\Chrome\\NativeMessagingHosts\\com.nekkora.extension_chrome")) {
            run("reg", "delete", key, "/f");
        }
        try {
            lab.call("host.disconnect", Map.of());
            BrowserLab.CallException e = assertThrows(BrowserLab.CallException.class, () -> lab.call("inspection.toggle", p));
            assertTrue(e.code.startsWith("HOST_"), e.code);
            JsonNode st = inspection(p);
            assertEquals("ERROR", st.get("state").asText());
            assertFalse(st.get("active").asBoolean());
            assertEquals(0, diagnostics(p).get("listeners").asInt(), "nenhum ouvinte após falha");
            assertEquals(0, diagnostics(p).get("observers").asInt(), "nenhum observador após falha");
            String panel = lab.openExtensionPage("src/ui/panel.html?tabId=" + p.tabId());
            WebDriver w = lab.driver();
            try {
                waitText(w, "btn-inspect", "Ativar inspeção");
                assertEquals("false", w.findElement(By.id("btn-inspect")).getAttribute("aria-pressed"));
                waitText(w, "message", "Falha ao ativar a inspeção");
                lab.screenshotWindow(panel, "C002-005-panel-activation-failure");
            } finally {
                lab.closeWindow(panel);
            }
        } finally {
            run("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                    lab.repo().resolve("native-host/scripts/install-host.ps1").toString(), "-SkipBuild");
        }
        lab.call("host.disconnect", Map.of());
        activate(p);
        assertEquals("ACTIVE", inspection(p).get("state").asText(), "recupera após reinstalar o host");
        deactivate(p);
    }

    @Test
    @DisplayName("C002-006 — Captura inicial automática")
    void initialCaptureAutomatic() {
        Page p = open(CONT);
        lab.clearEvents();
        activate(p);
        JsonNode s = session(p);
        JsonNode init = s.get("initialCapture");
        assertEquals("SESSION_START", init.get("reason").asText());
        assertEquals("COOPERATIVE", init.get("mode").asText());
        assertTrue(init.get("nodes").asInt() > 20);
        assertTrue(init.get("captureId").asText().startsWith("cap-"));
        boolean automatic = false;
        for (JsonNode e : lab.events("captured")) {
            automatic |= e.at("/data/tabId").asInt() == p.tabId() && e.at("/data/automatic").asBoolean();
        }
        assertTrue(automatic, "captura sem comando manual");
        // Equivalência com uma captura avulsa (contagem de nós).
        deactivate(p);
        assertEquals(init.get("nodes").asInt(), capture(p).get("nodes").asInt(), "base automática = captura completa");
    }

    @Test
    @DisplayName("C002-007 — Sem recapturas desnecessárias (página parada)")
    void noUnnecessaryRecaptures() {
        Page p = open(CONT);
        activate(p);
        sleep(4000);
        JsonNode s = session(p);
        assertEquals(1, s.get("fullCaptures").asInt(), "nenhuma recaptura periódica");
        assertEquals(0, s.get("deltasApplied").asInt(), "nenhum delta sem mutação");
        assertEquals(0, diagnostics(p).at("/tracker/deltasSent").asInt());
        deactivate(p);
    }

    @Test
    @DisplayName("C002-008 — Mutação simples gera delta (sem captura completa)")
    void simpleMutation() {
        Page p = open(CONT);
        activate(p);
        int before = session(p).get("nodes").asInt();
        act(p, "adicionar");
        JsonNode sync = sync(p);
        assertEquals("SYNCHRONIZED", sync.get("syncState").asText());
        JsonNode s = session(p);
        assertTrue(s.get("deltasApplied").asInt() >= 1);
        assertTrue(s.get("nodes").asInt() > before, "nó novo incorporado: " + before + " → " + s.get("nodes").asInt());
        assertEquals(1, s.get("fullCaptures").asInt());
        JsonNode sel = select(p, "#tarefas li:last-child .concluir");
        assertEquals("BUTTON", sel.at("/inspection/classification/kind").asText());
        assertTrue(sel.at("/sync/reusedSnapshot").asBoolean());
        deactivate(p);
    }

    @Test
    @DisplayName("C002-009 — Múltiplas mutações agrupadas em poucos deltas")
    void batchedMutations() {
        Page p = open(CONT);
        activate(p);
        act(p, "adicionar-10");
        sync(p);
        JsonNode s = session(p);
        JsonNode t = diagnostics(p).get("tracker");
        assertTrue(t.get("rawMutations").asInt() >= 10, "registros brutos: " + t.get("rawMutations"));
        assertTrue(s.get("deltasApplied").asInt() <= 3, "10 inserções agrupadas: " + s.get("deltasApplied"));
        assertEquals(12L, lab.js(p, "return document.querySelectorAll('#tarefas li').length"));
        assertEquals(12, lab.call("page.idFor", p, "css", "#tarefas li").get("count").asInt());
        deactivate(p);
    }

    @Test
    @DisplayName("C002-010 — Seleção logo após mutação usa o estado atualizado (barreira)")
    void selectionAfterMutation() {
        Page p = open(CONT);
        activate(p);
        act(p, "renomear");
        // Sem espera: a seleção aguarda a barreira de sincronização.
        JsonNode sel = select(p, "#tarefas li:first-child .concluir");
        // CHROME-SECURITY-001: o nome NOVO ("Concluir revisão") não é inteiro do vocabulário → retido; o ANTIGO ("Concluir")
        // passaria — o nome vazio prova que a seleção usou o estado atualizado
        assertEquals(com.nekkora.extension.it.support.Policy.name("Concluir revisão"), sel.at("/inspection/classification/name/name").asText(),
                sel.at("/inspection/classification/name").toString());
        assertNotEquals(com.nekkora.extension.it.support.Policy.name("Concluir"), sel.at("/inspection/classification/name/name").asText(), "estado anterior não usado");
        assertEquals("SYNCHRONIZED", sel.at("/sync/syncState").asText());
        assertEquals(1, session(p).get("fullCaptures").asInt());
        deactivate(p);
    }

    @Test
    @DisplayName("C002-011 — Dez seleções sem mudanças não geram dez capturas completas")
    void tenSelections() {
        Page p = open(CONT);
        activate(p);
        List<String> css = List.of("#adicionar", "#adicionar-10", "#renomear", "#remover", "#rerender", "#aprovar", "#duplicar", "#usuario", "#senha", "#preencher-sensivel");
        for (String c : css) {
            JsonNode sel = select(p, c);
            assertTrue(sel.at("/sync/reusedSnapshot").asBoolean(), c);
        }
        JsonNode s = session(p);
        assertEquals(1, s.get("fullCaptures").asInt(), "somente a captura-base");
        JsonNode st = inspection(p);
        assertEquals(10, st.get("reusedSnapshots").asInt());
        assertEquals(0, st.get("fullCapturesForSelection").asInt());
        deactivate(p);
    }

    @Test
    @DisplayName("C002-012 / C002-013 — Fechar e reabrir o painel preserva o estado e a captura")
    void closeAndReopenPanel() throws Exception {
        Page p = open(CONT);
        String panel = lab.openExtensionPage("src/ui/panel.html?tabId=" + p.tabId());
        WebDriver w = lab.driver();
        waitText(w, "host-status", "Conectado");
        w.findElement(By.id("btn-inspect")).click();
        waitText(w, "btn-inspect", "Desativar inspeção");
        lab.closeWindow(panel);
        // Painel fechado: a captura continua.
        assertEquals("ACTIVE", inspection(p).get("state").asText());
        int deltas = session(p).get("deltasApplied").asInt();
        act(p, "adicionar");
        sync(p);
        assertTrue(session(p).get("deltasApplied").asInt() > deltas, "delta processado sem painel aberto");
        // Reabrir: estado reconstruído a partir do service worker.
        String again = lab.openExtensionPage("src/ui/panel.html?tabId=" + p.tabId());
        try {
            waitText(w, "btn-inspect", "Desativar inspeção");
            assertEquals("true", w.findElement(By.id("btn-inspect")).getAttribute("aria-pressed"));
            waitText(w, "st-capture", "Automática ativa");
            assertNotEquals("—", text(w, "st-nodes"));
            assertNotEquals("—", text(w, "st-deltas"));
            lab.screenshotWindow(again, "C002-013-panel-reopened");
        } finally {
            lab.closeWindow(again);
        }
        deactivate(p);
    }

    @Test
    @DisplayName("C002-014 — Mudança de aba: sessões independentes")
    void independentTabs() {
        Page a = open(CONT);
        Page b = open("/lab/chrome002/recording.html");
        activate(a);
        assertEquals("INACTIVE", inspection(b).get("state").asText());
        activate(b);
        String sa = session(a).get("sessionId").asText();
        String sb = session(b).get("sessionId").asText();
        assertNotEquals(sa, sb);
        int db = session(b).get("deltasApplied").asInt();
        act(a, "adicionar");
        sync(a);
        sync(b);
        assertTrue(session(a).get("deltasApplied").asInt() >= 1);
        assertEquals(db, session(b).get("deltasApplied").asInt(), "mutação na aba A não afeta a sessão da aba B");
        deactivate(a);
        assertEquals("ACTIVE", inspection(b).get("state").asText(), "desativar A não desativa B");
        assertEquals("ACTIVE", session(b).get("status").asText());
        deactivate(b);
    }

    @Test
    @DisplayName("C002-015 — Recarga da página: nova base, inspeção preservada")
    void reload() {
        Page p = open(CONT);
        activate(p);
        JsonNode s1 = session(p);
        lab.focus(p);
        lab.driver().navigate().refresh();
        BrowserLab.waitUntil(Duration.ofSeconds(20), () -> {
            JsonNode s = session(p);
            return "ACTIVE".equals(s.path("status").asText()) && !s1.get("documentId").asText().equals(s.path("documentId").asText());
        }, "nova base para o documento recarregado");
        JsonNode s2 = session(p);
        assertEquals(s1.get("sessionId").asText(), s2.get("sessionId").asText(), "mesma sessão de captura da aba");
        assertEquals(2, s2.get("fullCaptures").asInt());
        assertTrue(s2.get("epoch").asInt() > s1.get("epoch").asInt());
        assertEquals("ACTIVE", inspection(p).get("state").asText());
        BrowserLab.waitUntil(Duration.ofSeconds(10), () -> diagnostics(p).get("listeners").asInt() > 0, "ouvintes reinstalados no novo documento");
        assertEquals(1, diagnostics(p).get("observers").asInt());
        act(p, "adicionar");
        assertEquals("SYNCHRONIZED", sync(p).get("syncState").asText());
        deactivate(p);
    }

    @Test
    @DisplayName("C002-016 — SPA: rota, query e fragmento sem perder o estado válido")
    void spa() {
        Page p = open("/lab/spa/clientes");
        activate(p);
        String doc = session(p).get("documentId").asText();
        lab.js(p, "history.pushState({}, '', '/lab/spa/pedidos'); render();"); // a inspeção ativa bloqueia cliques reais
        BrowserLab.waitUntil(Duration.ofSeconds(5), () -> String.valueOf(lab.js(p, "return location.pathname")).endsWith("/pedidos"), "rota");
        JsonNode m1 = lab.call("pagemap.export", p, "returnPageMap", true).get("pageMap");
        // CHROME-SECURITY-001: a URL é o MODELO da URL real (a rota nova está refletida: segmento "pedidos" do vocabulário)
        assertEquals(com.nekkora.extension.it.support.Policy.url(String.valueOf(lab.js(p, "return location.href"))), m1.at("/document/url").asText());
        assertTrue(m1.at("/document/url").asText().endsWith("/pedidos"), m1.at("/document/url").asText());
        lab.js(p, "history.pushState({}, '', '/lab/spa/pedidos?status=aberto'); render();");
        BrowserLab.waitUntil(Duration.ofSeconds(5), () -> String.valueOf(lab.js(p, "return location.search")).contains("status=aberto"), "query");
        JsonNode m2 = lab.call("pagemap.export", p, "returnPageMap", true).get("pageMap");
        assertEquals(com.nekkora.extension.it.support.Policy.url(String.valueOf(lab.js(p, "return location.href"))), m2.at("/document/url").asText());
        assertTrue(m2.at("/document/url").asText().contains("status="), "nome do parâmetro (vocabulário) refletido");
        assertFalse(m2.toString().contains("aberto"), "valor do parâmetro retido");
        lab.js(p, "location.hash = 'detalhes';");
        BrowserLab.waitUntil(Duration.ofSeconds(5), () -> String.valueOf(lab.js(p, "return location.hash")).equals("#detalhes"), "fragmento");
        sleep(400);
        JsonNode m3 = lab.call("pagemap.export", p, "returnPageMap", true).get("pageMap");
        // o fragmento nunca vai na URL (decisão D3); a mudança de fragmento continua observada (evento FRAGMENT, abaixo)
        assertFalse(m3.at("/document/url").asText().contains("#"), m3.at("/document/url").asText());
        JsonNode s = session(p);
        assertEquals(doc, s.get("documentId").asText(), "SPA: mesmo documento");
        assertEquals(1, s.get("fullCaptures").asInt(), "SPA preserva a base válida (somente deltas)");
        Set<String> kinds = new HashSet<>();
        for (JsonNode e : lab.events("navigation")) {
            if (e.at("/data/tabId").asInt() == p.tabId()) {
                kinds.add(e.at("/data/kind").asText());
            }
        }
        assertTrue(kinds.containsAll(Set.of("SPA_ROUTE", "FRAGMENT")), kinds.toString());
        deactivate(p);
    }

    @Test
    @DisplayName("C002-017 — Perda de delta: ressincronização controlada e limitada")
    void deltaLoss() {
        Page p = open(CONT);
        activate(p);
        int epoch = session(p).get("epoch").asInt();
        lab.call("session.debug", p, "action", "skipSeq");
        act(p, "adicionar");
        BrowserLab.waitUntil(Duration.ofSeconds(15), () -> session(p).get("resyncs").asInt() >= 1 && "ACTIVE".equals(session(p).get("status").asText()), "ressincronização após DELTA_GAP");
        JsonNode s = session(p);
        assertTrue(s.get("epoch").asInt() > epoch, "nova época");
        assertEquals(2, s.get("fullCaptures").asInt());
        assertTrue(s.get("reason").asText().contains("DELTA_GAP"), s.get("reason").asText());
        act(p, "adicionar");
        assertEquals("SYNCHRONIZED", sync(p).get("syncState").asText(), "deltas voltam a ser aceitos");
        assertEquals(4L, lab.js(p, "return document.querySelectorAll('#tarefas li').length"));
        assertEquals(4, lab.call("page.idFor", p, "css", "#tarefas li").get("count").asInt());
        // Falhas repetidas: o limite impede laço infinito de recapturas.
        for (int i = 0; i < 8 && !"ERROR".equals(session(p).get("status").asText()); i++) {
            final int before = session(p).get("resyncs").asInt();
            lab.call("session.debug", p, "action", "skipSeq");
            act(p, "adicionar");
            BrowserLab.waitUntil(Duration.ofSeconds(15), () -> {
                JsonNode x = session(p);
                return "ERROR".equals(x.get("status").asText()) || (x.get("resyncs").asInt() > before && "ACTIVE".equals(x.get("status").asText()));
            }, "falha " + i + " processada");
        }
        JsonNode err = session(p);
        assertEquals("ERROR", err.get("status").asText());
        assertEquals("RESYNC_LIMIT", err.at("/error/code").asText());
        int captures = err.get("fullCaptures").asInt();
        sleep(2000);
        assertEquals(captures, session(p).get("fullCaptures").asInt(), "nenhuma recaptura após o limite");
        assertEquals("DEGRADED", inspection(p).get("state").asText());
        assertEquals(0, diagnostics(p).get("observers").asInt(), "rastreador desligado no estado de erro");
        deactivate(p);
        assertEquals("INACTIVE", inspection(p).get("state").asText());
    }

    @Test
    @DisplayName("C002-018 — Reinício do motor Java: degradado, reconexão e nova base")
    void hostRestart() {
        Page p = open(CONT);
        activate(p);
        lab.clearEvents();
        lab.call("host.disconnect", Map.of());
        BrowserLab.waitUntil(Duration.ofSeconds(20), () -> {
            JsonNode s = session(p);
            return "ACTIVE".equals(s.get("status").asText()) && "OK".equals(s.get("hostState").asText()) && s.get("fullCaptures").asInt() >= 2;
        }, "reconexão automática e nova base");
        boolean degraded = false;
        for (JsonNode e : lab.events("session-status")) {
            degraded |= e.at("/data/tabId").asInt() == p.tabId() && "DEGRADED".equals(e.at("/data/session/hostState").asText());
        }
        assertTrue(degraded, "estado degradado sinalizado");
        assertTrue(session(p).get("reason").asText().contains("HOST_RECOVERED"));
        assertEquals("ACTIVE", inspection(p).get("state").asText());
        act(p, "adicionar");
        assertEquals("SYNCHRONIZED", sync(p).get("syncState").asText());
        assertEquals(3, lab.call("page.idFor", p, "css", "#tarefas li").get("count").asInt());
        JsonNode sel = select(p, "#tarefas li:last-child .concluir");
        assertEquals("BUTTON", sel.at("/inspection/classification/kind").asText());
        deactivate(p);
    }

    @Test
    @DisplayName("C002-019 — Reinício do service worker: estado restaurado sem observadores duplicados")
    void serviceWorkerRestart() throws Exception {
        Page p = open(CONT);
        activate(p);
        String sessionId = session(p).get("sessionId").asText();
        long startedAt = lab.call("sw.diagnostics", Map.of()).get("swStartedAt").asLong();
        int observers = diagnostics(p).get("observers").asInt();
        stopServiceWorker();
        final JsonNode[] last = new JsonNode[2];
        try {
            BrowserLab.waitUntil(Duration.ofSeconds(30), () -> {
                last[0] = lab.call("sw.diagnostics", Map.of());
                last[1] = session(p);
                return last[0].get("swStartedAt").asLong() != startedAt && "ACTIVE".equals(last[1].path("status").asText())
                        && last[1].path("fullCaptures").asInt() >= 2;
            }, "novo service worker reconciliou a sessão");
        } catch (AssertionError e) {
            throw new AssertionError(e.getMessage() + " | antes=" + startedAt + " sw=" + last[0] + " sessão=" + last[1], e);
        }
        JsonNode d = lab.call("sw.diagnostics", Map.of());
        assertTrue(d.get("restoredSessions").asInt() >= 1, d.toString());
        JsonNode s = session(p);
        assertEquals(sessionId, s.get("sessionId").asText(), "mesma sessão restaurada");
        assertTrue(s.get("reason").asText().contains("SERVICE_WORKER_RESTART"), s.get("reason").asText());
        assertEquals("ACTIVE", inspection(p).get("state").asText());
        assertEquals(observers, diagnostics(p).get("observers").asInt(), "sem observadores duplicados");
        act(p, "adicionar");
        assertEquals("SYNCHRONIZED", sync(p).get("syncState").asText());
        assertEquals(3, lab.call("page.idFor", p, "css", "#tarefas li").get("count").asInt());
        deactivate(p);
    }

    @Test
    @DisplayName("C002-020 — Desativação remove todos os observadores (inclusive shadow roots)")
    void deactivationRemovesObservers() {
        Page p = open("/lab/chrome002/shadow-dynamic.html");
        activate(p);
        act(p, "criar");
        act(p, "aninhado");
        sync(p);
        BrowserLab.waitUntil(Duration.ofSeconds(5), () -> diagnostics(p).get("observers").asInt() >= 3, "observadores de documento e shadow roots");
        deactivate(p);
        JsonNode d = diagnostics(p);
        assertEquals(0, d.get("observers").asInt());
        assertEquals(0, d.get("listeners").asInt());
        assertEquals("OFF", d.at("/tracker/mode").asText());
        assertFalse(d.at("/highlight/overlayPresent").asBoolean());
        JsonNode mem = lab.call("host.memory", Map.of("gc", true));
        assertEquals(0, mem.at("/retainedCaptures/tab-" + p.tabId()).asInt(), "capturas da aba liberadas no motor");
        // Mutações após a desativação não geram tráfego.
        int sent = diagnostics(p).at("/tracker/deltasSent").asInt();
        lab.click(p, By.id("criar"));
        sleep(800);
        assertEquals(sent, diagnostics(p).at("/tracker/deltasSent").asInt());
        assertEquals(2L, lab.js(p, "return document.querySelectorAll('nk-panel').length"), "clique real voltou a executar a ação");
    }

    /** Encerra o service worker da extensão via CDP (equivalente ao encerramento por inatividade do Chrome). */
    static void stopServiceWorker() {
        org.openqa.selenium.chrome.ChromeDriver d = (org.openqa.selenium.chrome.ChromeDriver) lab.driver();
        d.executeCdpCommand("ServiceWorker.enable", Map.of());
        d.executeCdpCommand("ServiceWorker.stopAllWorkers", Map.of());
    }

    private static void run(String... cmd) throws Exception {
        Process pr = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(pr.getInputStream().readAllBytes());
        if (pr.waitFor() != 0) {
            throw new IllegalStateException(String.join(" ", cmd) + " falhou:\n" + out);
        }
    }
}
