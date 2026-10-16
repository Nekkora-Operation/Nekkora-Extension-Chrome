package com.nekkora.extension.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.nekkora.extension.it.support.BrowserLab;
import com.nekkora.extension.it.support.BrowserLab.Page;
import com.nekkora.extension.it.support.LabIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.Keys;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.Select;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fase 10 — contexto de URL, navegação SPA e interações por clique real. */
class NavigationInteractionIT extends LabIT {

    @Test
    @DisplayName("T022 — Navegação SPA: contexto atualizado")
    void spaNavigation() {
        Page p = open("/lab/spa/clientes");
        JsonNode first = capture(p);
        assertEquals("clientes", first.at("/navigation/inferred/screen").asText());
        String pageSession = first.get("pageSessionId").asText();
        lab.call("tracking.start", p);
        lab.clearEvents();
        lab.click(p, By.linkText("Pedidos"));
        BrowserLab.waitUntil(Duration.ofSeconds(5), () -> {
            for (JsonNode e : lab.events("navigation")) {
                // CHROME-SECURITY-001: a URL do evento é o MODELO (segmentos fora do vocabulário viram {…})
                if (e.at("/data/url").asText().equals(com.nekkora.extension.it.support.Policy.url("http://127.0.0.1:" + BrowserLab.PORT + "/lab/spa/pedidos")) && "SPA_ROUTE".equals(e.at("/data/kind").asText())) {
                    return true;
                }
            }
            return false;
        }, "evento de rota SPA (history.pushState)");
        JsonNode after = capture(p);
        assertEquals("pedidos", after.at("/navigation/inferred/screen").asText());
        assertEquals(com.nekkora.extension.it.support.Policy.url("http://127.0.0.1:" + BrowserLab.PORT + "/lab/spa/pedidos")
                .substring(("http://127.0.0.1:" + BrowserLab.PORT).length()), after.at("/navigation/observed/pathname").asText());
        assertEquals(pageSession, after.get("pageSessionId").asText(), "SPA: mesmo documento, mesma sessão de página");
        lab.call("tracking.stop", p);
    }

    @Test
    @DisplayName("T023 — Alteração de query: parâmetros atualizados")
    void queryChange() {
        Page p = open("/lab/spa/pedidos");
        String before = capture(p).get("captureId").asText();
        lab.click(p, By.linkText("Pedidos abertos"));
        BrowserLab.waitUntil(Duration.ofSeconds(5), () -> String.valueOf(lab.js(p, "return location.search")).contains("status=aberto"), "query aplicada");
        JsonNode o = lab.call("interaction.observe", p, "beforeCaptureId", before);
        assertEquals("QUERY_CHANGE", o.at("/urlChange/kind").asText());
        // CHROME-SECURITY-001: a MUDANÇA do parâmetro "status" é observada; o valor ("aberto") é dado e não atravessa
        assertTrue(o.at("/urlChange/queryChanges/status/0").isNull());
        assertEquals("{…}", o.at("/urlChange/queryChanges/status/1").asText());
        JsonNode after = capture(p);
        assertEquals("{…}", after.at("/navigation/observed/query/status/0").asText());
        assertFalse(o.toString().contains("aberto") || after.at("/navigation").toString().contains("aberto"), "valor do parâmetro retido");
    }

    @Test
    @DisplayName("T024 — Pesquisa por clique: mudanças observadas")
    void searchByClick() {
        Page p = open("/clientes?nome=ana&status=ativo");
        BrowserLab.waitUntil(Duration.ofSeconds(10), () -> ((Number) lab.js(p, "return document.querySelectorAll('#resultados tbody tr').length")).intValue() == 3, "resultados iniciais");
        JsonNode initial = capture(p);
        assertEquals("{…}", initial.at("/navigation/observed/query/nome/0").asText(), "o nome do parâmetro aparece, o valor não");
        assertEquals("clientes", initial.at("/navigation/inferred/screen").asText());
        assertTrue(initial.at("/navigation/inferred/caveat").asText().contains("não comprovam"));
        JsonNode field = select(p, "#pesquisa");
        JsonNode button = select(p, "#btn-pesquisar");
        assertEquals("form-pesquisa", field.at("/inspection/family/form/idAttr").asText());
        assertEquals("form-pesquisa", button.at("/inspection/family/form/idAttr").asText(), "campo e botão na mesma família");
        String before = capture(p).get("captureId").asText();

        // Interação real: teclado + seleção + clique de ponteiro (nenhuma função da página é chamada diretamente).
        lab.focus(p);
        WebElement input = lab.driver().findElement(By.id("pesquisa"));
        input.sendKeys(Keys.chord(Keys.CONTROL, "a"), Keys.DELETE);
        input.sendKeys("maria");
        new Select(lab.driver().findElement(By.id("filtro-status"))).selectByVisibleText("Todos");
        lab.click(p, By.id("btn-pesquisar"));
        // Atenção: os resultados iniciais ("ana") já contêm "Mariana"; esperar pelo TEXTO "Maria" seria um falso sinal.
        BrowserLab.waitUntil(Duration.ofSeconds(10), () -> Boolean.TRUE.equals(lab.js(p,
                "return !!document.querySelector('#resultados tbody tr[data-client=\"202\"]')"
                        + " && !document.querySelector('#resultados tbody tr[data-client=\"101\"]')")), "resultados de 'maria' renderizados");

        JsonNode o = lab.call("interaction.observe", p, "beforeCaptureId", before, "readHooks", true);
        // URL
        assertEquals("QUERY_CHANGE", o.at("/urlChange/kind").asText());
        // CHROME-SECURITY-001: os valores ("ana" → "maria") não atravessam; a mudança é declarada sem eles
        // (a instrumentação de teste, só em DEV e rotulada, é a única parte que lê valores do laboratório)
        for (String part : new String[]{"urlChange", "uiQueries", "apiRequests", "domChanges", "resultRegions"}) {
            String j = o.path(part).toString();
            assertFalse(j.contains("\"ana\"") || j.contains("maria"), part + ": " + j);
        }
        // Consulta realizada pela interface (DOM)
        JsonNode ui = null;
        for (JsonNode q : o.get("uiQueries")) {
            if ("Nome".equals(q.get("label").asText())) {
                ui = q;
            }
        }
        // o valor digitado nunca é coletado (nem antes nem depois): a consulta pela interface é observada pela mudança
        // do controle de seleção (posição da opção), sem texto
        if (ui != null) {
            assertTrue(ui.get("before").isNull() && ui.get("after").isNull(), ui.toString());
        }
        // Consulta à API (somente o que a página realmente requisitou)
        boolean api = false;
        for (JsonNode r : o.get("apiRequests")) {
            if (r.get("path").asText().equals("/api/clientes") && r.at("/query/nome/0").asText().equals("{…}")) {
                api = true;
                assertEquals("RESOURCE_TIMING", r.get("source").asText());
            }
        }
        assertTrue(api, o.get("apiRequests").toString());
        // Região de resultados
        int domRows = ((Number) lab.js(p, "return document.querySelectorAll('#resultados tbody tr').length")).intValue();
        JsonNode region = null;
        for (JsonNode r : o.get("resultRegions")) {
            if ("TABLE".equals(r.get("kind").asText())) {
                region = r;
            }
        }
        assertNotNull(region);
        assertEquals(3, region.get("itemsBefore").asInt());
        assertEquals(domRows, region.get("itemsAfter").asInt());
        // Instrumentação de teste (separada e rotulada)
        assertEquals("TEST_INSTRUMENTATION", o.at("/instrumentation/source").asText());
        assertEquals("maria", o.at("/instrumentation/values/getCurrentFilters/nome").asText(), "hooks de teste: somente em DEV (laboratório)");
        assertEquals(domRows, o.at("/instrumentation/values/getVisibleResultCount").asInt(), "hook confere com a DOM");
        assertTrue(String.valueOf(lab.js(p, "return window.NekkoraLab.actions.join(',')")).contains("pesquisar:maria|"),
                "o manipulador real do botão foi executado pelo clique");
    }

    @Test
    @DisplayName("T038 — Funções locais de teste: resultado verificado")
    void testHooks() {
        Page p = open("/clientes?nome=ana&status=ativo");
        BrowserLab.waitUntil(Duration.ofSeconds(10), () -> ((Number) lab.js(p, "return document.querySelectorAll('#resultados tbody tr').length")).intValue() == 3, "resultados");
        JsonNode h = lab.call("testHooks.read", p);
        assertTrue(h.get("available").asBoolean());
        assertEquals("TEST_INSTRUMENTATION", h.get("source").asText());
        assertEquals("ana", h.at("/values/getCurrentFilters/nome").asText());
        assertEquals("ativo", h.at("/values/getCurrentFilters/status").asText());
        assertEquals(3, h.at("/values/getVisibleResultCount").asInt());
        assertTrue(h.at("/values/getLastApiQuery").asText().startsWith("/api/clientes?"));
        assertFalse(h.get("values").has("resetarDados"), "somente hooks da lista permitida são lidos");
        assertEquals(Boolean.FALSE, lab.js(p, "return window.__nekkoraForbiddenHookCalled === true"), "função com efeito colateral nunca é executada");
        assertEquals(3L, lab.js(p, "return document.querySelectorAll('#resultados tbody tr').length"));

        // Página sem hooks declarados: nada é executado.
        Page frame = open("/lab/complex/frame-inner.html");
        assertFalse(lab.call("testHooks.read", frame).get("available").asBoolean());
        // Origem não permitida não é tocada (página da extensão não é laboratório).
        JsonNode denied = lab.callRaw("testHooks.read", Map.of("tabId", -1));
        assertFalse(denied.get("ok").asBoolean());
    }
}
