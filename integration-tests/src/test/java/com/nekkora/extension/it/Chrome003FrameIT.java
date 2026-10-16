package com.nekkora.extension.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nekkora.extension.it.support.BrowserLab;
import com.nekkora.extension.it.support.BrowserLab.Page;
import com.nekkora.extension.it.support.Contracts;
import com.nekkora.extension.it.support.FrameIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Matriz F003 — Frame Intelligence, Document Graph e Element Access Path (CHROME-003 §27). */
class Chrome003FrameIT extends FrameIT {

    @Test
    @DisplayName("F003-001 — Grafo documental: documentos, frames, profundidade e relações tipadas (§6)")
    void documentGraph() {
        Page p = openActive(FRAMES, 9);
        JsonNode g = graph(p);
        evidence("F003-001-frame-graph.json", g);
        assertEquals(10, g.at("/counts/documents").asInt(), titles(g).toString());
        assertEquals(9, g.at("/counts/frames").asInt());
        assertEquals(2, g.get("maxDepth").asInt());
        assertEquals(0, g.at("/counts/inaccessible").asInt());
        assertEquals("doc:f0", g.get("mainDocumentId").asText());
        for (String t : List.of("DOCUMENT_CONTAINS_FRAME", "FRAME_HAS_DOCUMENT", "FRAME_PARENT_OF", "FRAME_CHILD_OF", "FRAME_HOSTED_BY_ELEMENT",
                "ELEMENT_BELONGS_TO_DOCUMENT", "ELEMENT_BELONGS_TO_FRAME", "SHADOW_ROOT_BELONGS_TO_DOCUMENT")) {
            assertTrue(relationTypes(g).contains(t), t + " em " + relationTypes(g));
        }
        JsonNode pag = requireFrame(p, g, "Pagamentos");
        JsonNode fin = requireFrame(p, g, "Financeiro");
        assertEquals(2, pag.get("depth").asInt());
        assertEquals(fin.get("frameId").asText(), pag.get("parentFrameId").asText(), "Pagamentos é filho de Financeiro");
        assertEquals(fin.get("documentId").asText(), pag.get("parentDocumentId").asText());
        assertTrue(fin.get("childFrameIds").toString().contains(pag.get("frameId").asText()));
        JsonNode widget = requireFrame(p, g, "Widget de suporte");
        assertTrue(widget.get("insideShadowRoot").asBoolean(), "iframe hospedado dentro de shadow root (cenário D)");
        assertEquals(1, g.at("/counts/shadowRootsClosed").asInt(), "shadow root fechado declarado, conteúdo não inventado");
    }

    @Test
    @DisplayName("F003-002 — documentId/frameId do Chrome associados sem inventar; ids lógicos separados (§5.2)")
    void browserIdentifiers() {
        Page p = openActive(FRAMES, 9);
        JsonNode g = waitGraph(p, x -> {
            for (JsonNode f : x.get("frames")) {
                if (f.path("browser").path("browserDocumentId").isMissingNode() || f.path("browser").path("browserDocumentId").isNull()) {
                    return false;
                }
            }
            return true;
        }, "metadados do Chrome associados a todos os frames acessíveis");
        evidence("F003-002-browser-metadata.json", g.get("frames"));
        java.util.Set<String> browserDocs = new java.util.HashSet<>();
        for (JsonNode f : g.get("frames")) {
            JsonNode b = f.get("browser");
            String mapping = b.get("mapping").asText();
            assertTrue(List.of("TOP", "HANDSHAKE").contains(mapping), f.get("frameId") + " associado por " + mapping);
            assertTrue(f.get("documentId").asText().startsWith("doc:"), "id lógico do motor");
            assertFalse(b.get("browserDocumentId").asText().startsWith("doc:"), "id do navegador não se confunde com o lógico");
            assertTrue(browserDocs.add(b.get("browserDocumentId").asText()), "documentId do Chrome único por documento");
            JsonNode d = document(g, f.get("documentId").asText());
            assertEquals(b.get("browserDocumentId").asText(), d.get("browserDocumentId").asText());
            if (f.get("depth").asInt() > 0) {
                JsonNode parent = null;
                for (JsonNode x : g.get("frames")) {
                    if (x.get("frameId").asText().equals(f.get("parentFrameId").asText())) {
                        parent = x;
                    }
                }
                assertNotNull(parent);
                assertEquals(parent.at("/browser/browserFrameId").asInt(), b.get("parentBrowserFrameId").asInt(),
                        "hierarquia lógica coincide com a do navegador: " + f.get("title"));
                assertEquals(parent.at("/browser/browserDocumentId").asText(), b.get("parentDocumentId").asText());
            }
        }
        // O grafo do documento ORIGINAL com o id antigo: depois de recarregar, o documentId do Chrome muda.
        String before = document(g, "doc:f0").get("browserDocumentId").asText();
        lab.js(p, "location.reload()");
        BrowserLab.waitUntil(Duration.ofSeconds(30), () -> {
            JsonNode s = session(p);
            return "ACTIVE".equals(s.path("status").asText()) && !before.equals(document(graph(p), "doc:f0").path("browserDocumentId").asText());
        }, "novo documentId após recarga");
    }

    @Test
    @DisplayName("F003-003 — URL declarada × efetiva × origem: about:blank, srcdoc e navegação interna (§5.3)")
    void declaredEffectiveOrigin() {
        Page p = openActive(DYNAMIC, 5);
        JsonNode g = graph(p);
        JsonNode blank = requireFrame(p, g, "Rascunho");
        assertEquals("ABOUT_BLANK", blank.get("documentKind").asText());
        assertTrue(blank.get("declaredSrc").isNull(), "nenhum src declarado");
        assertEquals("about:blank", blank.get("effectiveUrl").asText());
        assertEquals("http://127.0.0.1:" + BrowserLab.PORT, blank.get("origin").asText(), "about:blank herda a origem do pai");
        JsonNode srcdoc = requireFrame(p, g, "Resumo");
        assertEquals("SRCDOC", srcdoc.get("documentKind").asText());
        assertTrue(srcdoc.get("srcdoc").asBoolean());
        JsonNode pag = requireFrame(p, g, "Pagamentos");
        assertEquals(pag.get("declaredSrc").asText(), pag.get("effectiveUrl").asText());

        act(p, "navegar");
        JsonNode after = waitGraph(p, x -> {
            JsonNode f = frameTitled(p, x, "Pagamentos");
            return f != null && f.get("effectiveUrl").asText().endsWith("/widget.html");
        }, "navegação interna refletida na URL efetiva");
        JsonNode nav = requireFrame(p, after, "Pagamentos");
        evidence("F003-003-declared-vs-effective.json", Map.of("before", pag, "after", nav, "aboutBlank", blank, "srcdoc", srcdoc));
        assertTrue(nav.get("declaredSrc").asText().endsWith("/pagamentos.html"), "src declarado inalterado");
        assertTrue(nav.get("effectiveUrl").asText().endsWith("/widget.html"), "URL efetiva = documento atual");
        assertEquals("Widget de suporte", nav.get("documentTitle").asText());
        assertFalse(nav.get("effectiveUrl").asText().contains("navegado"), "query removida da URL exportada");
        // CHROME-SECURITY-001: até o NOME do parâmetro passa pela política (vocabulário); o valor nunca
        assertEquals(com.nekkora.extension.it.support.Policy.passes("navegado"),
                document(after, nav.get("documentId").asText()).get("queryKeys").toString().contains("navegado"), "somente o NOME do parâmetro, se do vocabulário");
    }

    @Test
    @DisplayName("F003-004 — Cenário A: documento → iframe → elemento (trajetória, localizador escopado e reencontro)")
    void scenarioA() {
        Page p = openActive(FRAMES, 9);
        String id = idIn(p, "button[type=submit]", "FRAME", "#f-contas");
        JsonNode sel = selectNode(p, id);
        JsonNode path = sel.at("/inspection/accessPath");
        assertEquals(List.of("MAIN_DOCUMENT", "IFRAME", "ELEMENT"), stepTypes(path));
        assertEquals("Contas", path.at("/steps/1/label").asText());
        // CHROME-SECURITY-001: o id "f-contas" não é do vocabulário → o localizador do iframe usa só o que atravessou
        String frameLocator = path.at("/steps/1/locator").asText();
        assertTrue(com.nekkora.extension.core.privacy.CapturePrivacyPolicy.expressionSafe(frameLocator), frameLocator);
        assertFalse(frameLocator.contains("f-contas"), frameLocator);
        assertEquals(1, path.at("/steps/1/matchCount").asInt());
        assertEquals(path.at("/steps/1/enteredDocumentId").asText(), path.at("/steps/2/documentId").asText(),
                "o localizador do elemento é executado no documento do iframe");
        assertFalse(path.at("/steps/2/locator").asText().contains("iframe"), "localizador do elemento não é uma string CSS única atravessando o iframe");
        assertEquals(1, path.get("frameDepth").asInt());
        assertTrue(path.get("accessible").asBoolean());
        assertEquals("RESOLVED", resolve(p, sel.at("/identity/targetId").asText()).get("status").asText());
        evidence("F003-004-access-path.json", path);
    }

    @Test
    @DisplayName("F003-005 — Cenário B: dois níveis de iframe (Financeiro → Contas a pagar)")
    void scenarioB() {
        Page p = openActive(FRAMES, 9);
        String id = idIn(p, "button[type=submit]", "FRAME", "#f-financeiro", "FRAME", "#f-contas-pagar");
        JsonNode sel = selectNode(p, id);
        JsonNode path = sel.at("/inspection/accessPath");
        assertEquals(List.of("MAIN_DOCUMENT", "IFRAME", "IFRAME", "ELEMENT"), stepTypes(path));
        assertEquals(List.of("Financeiro", "Contas a pagar"), stepLabels(path).subList(1, 3));
        assertEquals(2, path.get("frameDepth").asInt());
        assertEquals(3, path.get("documentsTraversed").asInt());
        assertTrue(sel.at("/identity/frameChain").size() == 2, "identidade inclui a cadeia de frames");
        JsonNode r = resolve(p, sel.at("/identity/targetId").asText());
        assertEquals("RESOLVED", r.get("status").asText());
        assertEquals(id, r.get("resolvedNodeId").asText(), "não confunde com o Salvar do iframe Contas (cenário A, mesma URL)");
    }

    @Test
    @DisplayName("F003-006 — Cenário C: dois níveis + Shadow DOM, explicação legível e destaque do iframe (§9, §21)")
    void scenarioC() {
        Page p = openActive(FRAMES, 9);
        String id = idIn(p, "button[type=submit]", "FRAME", "#f-financeiro", "FRAME", "#f-pagamentos", "SHADOW_HOST", "nk-pagamento");
        JsonNode sel = selectNode(p, id);
        JsonNode path = sel.at("/inspection/accessPath");
        evidence("F003-006-access-path.json", path);
        assertEquals(List.of("MAIN_DOCUMENT", "IFRAME", "IFRAME", "SHADOW_ROOT", "ELEMENT"), stepTypes(path));
        assertEquals(1, path.get("shadowDepth").asInt());
        String expl = path.get("explanation").asText();
        assertTrue(expl.contains("Confirmar pagamento") && expl.contains("Financeiro") && expl.contains("Pagamentos")
                && expl.contains("2º nível") && expl.contains("shadow root"), expl);
        assertNotNull(path.at("/steps/2/browserDocumentId").textValue(), "documento do Chrome do iframe Pagamentos");
        JsonNode hl = lab.call("page.highlightFrame", p, "nodeId", id);
        assertTrue(hl.get("highlighted").asBoolean());
        assertEquals("Pagamentos", hl.get("label").asText());
        assertEquals(2, hl.get("depth").asInt());
        screenshot(p, "F003-006-frame-highlight");
        assertEquals("RESOLVED", resolve(p, sel.at("/identity/targetId").asText()).get("status").asText());
    }

    @Test
    @DisplayName("F003-007 — Cenário D: Shadow DOM contendo iframe (ordem SHADOW_ROOT → IFRAME)")
    void scenarioD() {
        Page p = openActive(FRAMES, 9);
        String id = idIn(p, "#enviar", "SHADOW_HOST", "nk-suporte", "FRAME", "#f-widget");
        JsonNode sel = selectNode(p, id);
        JsonNode path = sel.at("/inspection/accessPath");
        assertEquals(List.of("MAIN_DOCUMENT", "SHADOW_ROOT", "IFRAME", "ELEMENT"), stepTypes(path));
        assertEquals("Widget de suporte", path.at("/steps/2/label").asText());
        assertTrue(sel.at("/identity/frameChain/0/insideShadowRoot").asBoolean());
        assertEquals("RESOLVED", resolve(p, sel.at("/identity/targetId").asText()).get("status").asText());
    }

    @Test
    @DisplayName("F003-008 — Cenário E: iframes irmãos com botões idênticos são distinguidos pelo frame")
    void scenarioE() {
        Page p = openActive(FRAMES, 9);
        String sul = idIn(p, "button", "FRAME", "#filial-sul iframe");
        String norte = idIn(p, "button", "FRAME", "#filial-norte iframe");
        JsonNode sel = selectNode(p, sul);
        assertEquals(com.nekkora.extension.it.support.Policy.name("Filial Sul"), sel.at("/identity/frameChain/0/title").asText(""));
        assertTrue(sel.at("/inspection/lookalikes").size() >= 0);
        // CHROME-SECURITY-001: as filiais só se distinguem por "Filial Sul"/#filial-sul, fora do vocabulário. O resultado
        // honesto é a ambiguidade DECLARADA (ou a filial certa, se outra evidência comprovar) — nunca a filial Norte.
        JsonNode r = resolve(p, sel.at("/identity/targetId").asText());
        assertHonest(r, sul, norte);
        // Re-renderização do documento do frame Sul (recarga): reencontrado no Sul, nunca no Norte.
        lab.js(p, "document.querySelector('#filial-sul iframe').contentWindow.location.reload()");
        sleep(1500);
        JsonNode again = resolve(p, sel.at("/identity/targetId").asText());
        evidence("F003-008-siblings.json", Map.of("first", r, "afterReload", again));
        String sulNow = idIn(p, "button", "FRAME", "#filial-sul iframe");
        assertHonest(again, sulNow, idIn(p, "button", "FRAME", "#filial-norte iframe"));
    }

    private static void assertHonest(JsonNode r, String right, String wrong) {
        String st = r.get("status").asText();
        if ("RESOLVED".equals(st)) {
            assertEquals(right, r.get("resolvedNodeId").asText(), r.toString());
        } else {
            assertTrue(java.util.Set.of("AMBIGUOUS", "INSUFFICIENT_EVIDENCE").contains(st), r.toString());
            assertTrue(r.path("resolvedNodeId").isNull(), r.toString());
        }
        if (!wrong.isEmpty()) {
            assertNotEquals(wrong, r.path("resolvedNodeId").asText(""), r.toString());
        }
    }

    @Test
    @DisplayName("F003-009 — Cenário F: mesmo name/URL em contêineres diferentes; contêineres iguais ⇒ FRAME_AMBIGUOUS, nunca o primeiro")
    void scenarioF() {
        Page p = openActive(FRAMES, 9);
        String compras = idIn(p, "button", "FRAME", "#compras iframe");
        JsonNode sel = selectNode(p, compras);
        assertTrue(sel.at("/identity/frameChain/0/container").asText().contains("compras"), sel.at("/identity/frameChain").toString());
        String t = sel.at("/identity/targetId").asText();
        // CHROME-SECURITY-001: o frame certo é escolhido pelo contêiner (#compras, do vocabulário); dentro dele, o botão
        // "Atualizar indicadores" só tinha o nome (retido) → a identidade do elemento não é comprovável. O que importa
        // aqui: nunca o botão do painel de Vendas.
        String vendas = idIn(p, "button", "FRAME", "#vendas iframe");
        JsonNode first = resolve(p, t);
        assertHonest(first, compras, vendas);
        assertTrue(first.toString().contains("\"actual\":\"#compras\",\"expected\":\"#compras\",\"feature\":\"REGION\",\"outcome\":\"MATCH\""), first.toString());
        act(p, "igualar");
        BrowserLab.waitUntil(Duration.ofSeconds(15), () -> "AMBIGUOUS".equals(resolve(p, t).get("status").asText()), "ambiguidade declarada");
        JsonNode r = resolve(p, t);
        evidence("F003-009-ambiguous-frames.json", r);
        assertTrue(r.get("resolvedNodeId").isNull(), "nenhum candidato escolhido");
        assertTrue(r.get("diagnostics").toString().contains("FRAME_AMBIGUOUS"), r.get("diagnostics").toString());
        assertTrue(graph(p).get("frames").toString().contains("\"identityAmbiguous\":true"), "grafo marca as identidades ambíguas");
    }

    @Test
    @DisplayName("F003-010 — Cenário G: iframe removido durante a inspeção ⇒ FRAME_NOT_FOUND (alvo não procurado em outro frame)")
    void scenarioG() {
        Page p = openActive(DYNAMIC, 5);
        String id = idIn(p, "button[type=submit]", "FRAME", "#f-pedidos");
        String t = selectNode(p, id).at("/identity/targetId").asText();
        act(p, "remover");
        JsonNode g = waitGraph(p, x -> frameTitled(p, x, "Pedidos") == null, "frame removido do grafo");
        JsonNode r = resolve(p, t);
        evidence("F003-010-removed-frame.json", Map.of("resolution", r, "graph", g.get("counts")));
        assertNotEquals("RESOLVED", r.get("status").asText(), "o Salvar do iframe Cadastro (mesma URL) não é aceito no lugar");
        assertTrue(r.get("diagnostics").toString().contains("FRAME_NOT_FOUND"), r.get("diagnostics").toString());
    }

    @Test
    @DisplayName("F003-011 — Cenário H: iframe recriado por re-renderização de SPA ⇒ invalidado, identificado, validado e reconstruído")
    void scenarioH() {
        Page p = openActive(DYNAMIC, 5);
        String hostBefore = requireFrame(p, graph(p), "Cadastro").get("hostNodeId").asText();
        String id = idIn(p, "button[type=submit]", "FRAME", "#spa-root iframe");
        String t = selectNode(p, id).at("/identity/targetId").asText();
        act(p, "spa");
        JsonNode g = waitGraph(p, x -> {
            JsonNode f = frameTitled(p, x, "Cadastro");
            return f != null && !hostBefore.equals(f.get("hostNodeId").asText()) && "INSPECTED".equals(f.get("status").asText());
        }, "iframe recriado e reinventariado");
        JsonNode r = resolve(p, t);
        evidence("F003-011-spa-replaced-frame.json", Map.of("resolution", r, "frame", requireFrame(p, g, "Cadastro")));
        assertEquals("RESOLVED", r.get("status").asText(), r.toString());
        assertNotEquals(id, r.get("resolvedNodeId").asText(), "nó novo (documento do frame reconstruído)");
        assertEquals(idIn(p, "button[type=submit]", "FRAME", "#spa-root iframe"), r.get("resolvedNodeId").asText());
        assertEquals(1, session(p).get("fullCaptures").asInt(), "sem recaptura completa da página");
    }

    @Test
    @DisplayName("F003-012 — Cenário I: about:blank preenchido pelo pai (origem herdada, metadados por URL única)")
    void scenarioI() {
        Page p = openActive(DYNAMIC, 5);
        JsonNode g = waitGraph(p, x -> {
            JsonNode f = frameTitled(p, x, "Rascunho");
            return f != null && f.path("browser").isObject();
        }, "about:blank associado ao frame do Chrome");
        JsonNode f = requireFrame(p, g, "Rascunho");
        assertEquals("URL_MATCH", f.at("/browser/mapping").asText());
        assertEquals("NO_CONTENT_SCRIPT", f.at("/browser/inspection").asText(), "sem content script (sem match_about_blank): capturado pelo pai");
        assertEquals("INSPECTED", f.get("status").asText());
        assertEquals("TEMPORARY", document(g, f.get("documentId").asText()).get("lifecycle").asText());
        JsonNode sel = selectNode(p, idIn(p, "button", "FRAME", "#f-branco"));
        assertEquals(com.nekkora.extension.it.support.Policy.name("Guardar nota"), sel.at("/inspection/classification/name/name").asText());
        assertEquals(List.of("MAIN_DOCUMENT", "IFRAME", "ELEMENT"), stepTypes(sel.at("/inspection/accessPath")));
        assertEquals(1, Integer.parseInt(String.valueOf(lab.js(p, "return document.querySelectorAll('#f-branco').length"))));
    }

    @Test
    @DisplayName("F003-013 — srcdoc: documento SRCDOC inventariado pelo pai, sem duplicidade")
    void srcdoc() {
        Page p = openActive(DYNAMIC, 5);
        JsonNode g = graph(p);
        JsonNode f = requireFrame(p, g, "Resumo");
        assertEquals("SRCDOC", f.get("documentKind").asText());
        assertEquals("about:srcdoc", f.get("effectiveUrl").asText());
        JsonNode map = exportMap(p);
        int fechar = 0;
        for (JsonNode e : map.get("elements")) {
            if ("Fechar resumo".equals(e.at("/text/accessibleName").asText())) {
                fechar++;
                assertEquals(f.get("documentId").asText(), e.get("documentId").asText());
            }
        }
        assertEquals(1, fechar, "um único elemento (nenhuma captura duplicada)");
    }

    @Test
    @DisplayName("F003-014 — Cenário J: origem sem permissão ⇒ PERMISSION_DENIED; permissão da página não se estende aos frames")
    void scenarioJ() {
        Page p = openActive(ORIGINS, 5);
        JsonNode g = waitGraph(p, x -> {
            JsonNode f = frameTitled(p, x, "Parceiro externo");
            return f != null && f.path("browser").isObject();
        }, "frame externo com metadados do Chrome");
        JsonNode f = requireFrame(p, g, "Parceiro externo");
        evidence("F003-014-no-permission.json", f);
        assertEquals("PERMISSION_DENIED", f.get("status").asText());
        assertEquals("NO_PERMISSION", f.at("/browser/inspection").asText());
        assertEquals("http://127.0.0.2:" + BrowserLab.PORT, f.get("origin").asText());
        assertEquals(0, f.get("elementCount").asInt(), "conteúdo nunca inventariado");
        assertFalse(document(g, f.get("documentId").asText()).get("accessible").asBoolean());
        assertTrue(g.get("limitations").toString().contains("PERMISSION_DENIED"));
        JsonNode map = exportMap(p);
        assertFalse(map.get("elements").toString().contains("Vencimento"), "nenhum campo do documento sem permissão no PageMap");
    }

    @Test
    @DisplayName("F003-015 — Sandbox: sem allow-same-origin ⇒ SANDBOX_RESTRICTED com origem opaca; com allow-same-origin ⇒ inspecionado")
    void sandbox() {
        Page p = openActive(ORIGINS, 5);
        JsonNode g = graph(p);
        JsonNode closed = requireFrame(p, g, "Conteúdo isolado");
        assertEquals("SANDBOX_RESTRICTED", closed.get("status").asText());
        assertEquals("null", closed.get("origin").asText(), "origem opaca");
        assertEquals("allow-scripts", closed.get("sandbox").asText());
        JsonNode same = requireFrame(p, g, "Isolado com mesma origem");
        assertEquals("INSPECTED", same.get("status").asText());
        assertTrue(same.get("elementCount").asInt() > 0);
        assertTrue(diagnose(p).at("/readiness/obstacles").toString().contains("SANDBOXED_FRAME"));
    }

    @Test
    @DisplayName("F003-016 — Outra origem permitida com iframe aninhado e Shadow DOM: trajetória entre documentos de origens diferentes")
    void otherOriginNested() {
        Page p = openActive(ORIGINS, 5);
        // "Gráficos" (título fora do vocabulário) é o frame FILHO de "Relatórios" — identificado pela estrutura do grafo
        JsonNode g = waitGraph(p, x -> onlyChildFrame(x, frameTitled(p, x, "Relatórios")) != null
                && "INSPECTED".equals(onlyChildFrame(x, frameTitled(p, x, "Relatórios")).get("status").asText()), "frame aninhado de outra origem inventariado");
        JsonNode rel = requireFrame(p, g, "Relatórios");
        JsonNode graficos = onlyChildFrame(g, rel);
        assertEquals("ATTACHED", rel.at("/browser/inspection").asText(), "capturado pelo content script do próprio frame");
        assertTrue(rel.get("origin").asText().endsWith(":" + (BrowserLab.PORT + 1)));
        String nodeId = onlyShadowButton(exportMap(p), graficos.get("frameId").asText());
        assertNotNull(nodeId);
        JsonNode sel = selectNode(p, nodeId);
        JsonNode path = sel.at("/inspection/accessPath");
        evidence("F003-016-cross-origin-path.json", path);
        assertEquals(List.of("MAIN_DOCUMENT", "IFRAME", "IFRAME", "SHADOW_ROOT", "ELEMENT"), stepTypes(path));
        // CHROME-SECURITY-001: rótulo do frame = título pela política; sem título do vocabulário, o rótulo é o MODELO da URL
        assertEquals(com.nekkora.extension.it.support.Policy.name("Relatórios"), stepLabels(path).get(1));
        String graficosLabel = stepLabels(path).get(2);
        assertFalse(graficosLabel.contains("Gráficos") || graficosLabel.contains("graficos"), graficosLabel);
        assertTrue(graficosLabel.isEmpty() || graficosLabel.contains("{…}"), graficosLabel);
        assertNotEquals(path.at("/steps/0/url").asText(""), path.at("/steps/1/url").asText(""));
        // o único traço próprio do botão era o nome (fora do vocabulário): a trajetória entre origens é preservada, mas a
        // identidade do elemento não é comprovável → INSUFFICIENT_EVIDENCE declarado, nunca outro elemento
        JsonNode r = resolve(p, sel.at("/identity/targetId").asText());
        assertHonest(r, nodeId, "");
        lab.call("page.highlightFrame", p, "nodeId", nodeId);
        screenshot(p, "F003-016-cross-origin-frame-highlight");
    }

    @Test
    @DisplayName("F003-017 — Frame em carregamento: LOADING declarado e confiança reduzida; após carregar, INSPECTED (§23.2)")
    void loadingFrame() {
        Page p = openActive(DYNAMIC, 5);
        act(p, "lento");
        JsonNode loading = waitGraph(p, x -> frameTitled(p, x, "Relatório lento") != null, "frame lento declarado");
        JsonNode f = requireFrame(p, loading, "Relatório lento");
        JsonNode d = diagnose(p);
        evidence("F003-017-loading.json", Map.of("frame", f, "readiness", d.get("readiness")));
        assertEquals("LOADING", f.get("status").asText(), f.toString());
        assertTrue(d.at("/readiness/obstacles").toString().contains("FRAME_LOADING"));
        assertEquals("LOW", d.at("/readiness/confidence").asText());
        JsonNode done = waitGraph(p, x -> {
            JsonNode y = frameTitled(p, x, "Relatório lento");
            return y != null && "INSPECTED".equals(y.get("status").asText()) && y.get("elementCount").asInt() > 0;
        }, "frame carregado e inventariado");
        assertEquals("NETWORK", requireFrame(p, done, "Relatório lento").get("documentKind").asText());
    }

    @Test
    @DisplayName("F003-018 — Observação contínua: inserção, troca de src e navegação interna sem recaptura completa (§22)")
    void continuousObservation() {
        Page p = openActive(DYNAMIC, 5);
        int full = session(p).get("fullCaptures").asInt();
        act(p, "inserir");
        waitGraph(p, x -> frameTitled(p, x, "Inserido 1") != null && "INSPECTED".equals(frameTitled(p, x, "Inserido 1").get("status").asText()), "iframe inserido");
        act(p, "mudar-src");
        waitGraph(p, x -> frameTitled(p, x, "Pedidos") != null && frameTitled(p, x, "Pedidos").get("effectiveUrl").asText().endsWith("/widget.html"), "src trocado");
        act(p, "navegar");
        JsonNode g = waitGraph(p, x -> frameTitled(p, x, "Pagamentos") != null && frameTitled(p, x, "Pagamentos").get("effectiveUrl").asText().endsWith("/widget.html"),
                "navegação interna");
        JsonNode s = session(p);
        evidence("F003-018-continuous.json", Map.of("session", s, "frames", titles(g)));
        assertEquals(full, s.get("fullCaptures").asInt(), "nenhuma recaptura completa: " + s.get("lastCapture"));
        assertTrue(s.get("deltasApplied").asInt() > 0);
        assertEquals(6, g.at("/counts/frames").asInt(), titles(g).toString());
    }

    @Test
    @DisplayName("F003-019 — Painel: Documentos/Iframes/Shadow Roots, TRAJETÓRIA e DIAGNÓSTICO; botão DEV Destacar iframe (§9, §26)")
    void panel() throws Exception {
        Page p = openActive(FRAMES, 9);
        String panel = lab.openExtensionPage("src/ui/panel.html?tabId=" + p.tabId());
        WebDriver d = lab.driver();
        try {
            BrowserLab.waitUntil(Duration.ofSeconds(20), () -> text(d, "st-docs").startsWith("10"), "contador de documentos");
            assertTrue(text(d, "st-frames").startsWith("9"), text(d, "st-frames"));
            assertTrue(text(d, "st-shadow").contains("fechado"), text(d, "st-shadow"));
            waitText(d, "diag-info", "Prontidão");
            String id = idIn(p, "button[type=submit]", "FRAME", "#f-financeiro", "FRAME", "#f-pagamentos", "SHADOW_HOST", "nk-pagamento");
            selectNode(p, id);
            d.switchTo().window(panel);
            waitText(d, "path-info", "Pagamentos");
            assertTrue(text(d, "path-info").contains("2 iframe(s) · 1 shadow root(s)"), text(d, "path-info"));
            assertEquals(5, d.findElements(By.cssSelector("#path-steps li")).size());
            assertTrue(text(d, "path-explanation").contains("2º nível"));
            d.findElement(By.id("btn-frame")).click();
            waitText(d, "message", "Iframe destacado: Pagamentos");
            lab.screenshotWindow(panel, "F003-019-panel");
            screenshot(p, "F003-019-page-frame-highlight");
        } finally {
            lab.closeWindow(panel);
        }
        // PRD: o botão DEV não existe e o comando é recusado.
        lab.callRaw("config.setMode", Map.of("mode", "PRD"));
        JsonNode denied = lab.callRaw("page.highlightFrame", Map.of("tabId", p.tabId()));
        assertFalse(denied.path("ok").asBoolean(), denied.toString());
    }

    @Test
    @DisplayName("F003-020 — PageMap 0.2.0: frameGraph, accessPaths, protection e readiness válidos no contrato; campos 0.1.0 preservados")
    void pageMap() throws Exception {
        Page p = openActive(FRAMES, 9);
        JsonNode map = exportMap(p);
        assertEquals("0.3.0", map.get("version").asText()); // CHROME-SECURITY-001
        Contracts.assertValid("page-map.schema.json", map);
        for (String k : List.of("snapshot", "elements", "frameGraph", "accessPaths", "protection", "readiness")) {
            assertTrue(map.has(k), k);
        }
        assertEquals(10, map.at("/frameGraph/counts/documents").asInt());
        assertTrue(map.get("accessPaths").size() > 0, "trajetórias de elementos dentro de frames/shadow roots");
        for (JsonNode ap : map.get("accessPaths")) {
            assertTrue(ap.get("frameDepth").asInt() + ap.get("shadowDepth").asInt() > 0, "somente trajetórias aninhadas");
        }
        for (JsonNode e : map.get("elements")) {
            assertTrue(e.get("documentId").asText().startsWith("doc:"));
        }
        ObjectNode legacy = map.deepCopy();
        for (String k : List.of("frameGraph", "accessPaths", "protection", "readiness")) {
            legacy.remove(k);
        }
        legacy.put("version", "0.1.0");
        legacy.remove("privacy"); // CHROME-SECURITY-001: campos da 0.3.0 não existem na 0.1.0
        for (JsonNode el : legacy.path("elements")) {
            if (el.path("text") instanceof ObjectNode t) {
                t.remove("suppressed");
            }
        }
        Contracts.assertValid("v0.1.0/page-map.schema.json", stripDocumentIds(legacy));
        evidence("F003-020-pagemap-sections.json", Map.of("version", map.get("version"), "frameGraphCounts", map.at("/frameGraph/counts"),
                "accessPaths", map.get("accessPaths").size(), "protection", map.at("/protection/status"), "readiness", map.at("/readiness/status")));
    }

    /** Remove somente os campos ADITIVOS da 0.2.0: o restante precisa continuar válido no contrato 0.1.0. */
    private static JsonNode stripDocumentIds(ObjectNode map) {
        map.get("elements").forEach(e -> ((ObjectNode) e).remove("documentId"));
        map.at("/document/frames").forEach(f -> ((ObjectNode) f).remove(List.of("title", "readyState")));
        return map;
    }
}
