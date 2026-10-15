package com.nekkora.extension.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STUDIO-005 no Chrome real: o que a página mostrou depois de cada interação (mensagens, validação, carregamento,
 * navegação, estado do elemento) chega ao "Studio" de laboratório como studio.recording.evidence, pela cadeia completa
 * content script ⇄ service worker ⇄ Native Host ⇄ Bridge. Todo evento recebido é validado contra o contrato.
 * Nenhuma evidência é resultado de negócio: o contrato não tem onde dizer "sucesso" ou "falha".
 */
class Studio005EvidenceIT extends SessionIT {

    private static final String BASE = "/lab/studio005/";
    private static final String USER = "ana.teste";
    private static final String PASSWORD = "S3nh@Secreta!42";
    /** Maior que a janela de observação (3 s): a ação seguinte não concorre pelas consequências da anterior. */
    private static final long QUIET_MS = 3300;
    private static final ObjectMapper M = new ObjectMapper();
    private static StudioBridgeLab studio;

    @BeforeAll
    static void bridge() throws Exception {
        lab = BrowserLab.get();
        studio = new StudioBridgeLab(lab.bridgeDir());
        lab.call("studio.link.set", Map.of("enabled", true));
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

    // ------------------------------------------------------------------------------------------ apoio

    private static String id(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private String refOf(Page p) {
        JsonNode r = studio.call("browser.tabs.list", null);
        String title = (String) lab.js(p, "return document.title");
        String host = java.net.URI.create(p.url()).getHost();
        for (JsonNode t : r.at("/payload/tabs")) {
            if (host.equals(t.path("host").asText()) && title.equals(t.path("title").asText())) {
                return t.path("tabRef").asText();
            }
        }
        throw new AssertionError("aba não listada: " + p.url() + " em " + r);
    }

    private JsonNode start(String rid, Page p, String mode, boolean evidence) {
        ObjectNode pl = studio.obj().put("recordingId", rid).put("operationId", id("op")).put("tabRef", refOf(p)).put("mode", mode).put("leaseMs", 60000);
        pl.putArray("capabilities").add("steps/1");
        if (evidence) {
            ((com.fasterxml.jackson.databind.node.ArrayNode) pl.get("capabilities")).add("evidence/1");
        }
        pl.put("routeRef", "route-" + rid.substring(rid.length() - 6));
        JsonNode r = studio.call("studio.recording.start", pl);
        assertTrue(r.path("ok").asBoolean(), r.toString());
        BrowserLab.waitUntil(Duration.ofSeconds(15), () -> lab.call("page.diagnostics", p).at("/recorder/active").asBoolean(false),
                "sensor de interações ligado no documento");
        return r;
    }

    private JsonNode start(String rid, Page p) {
        return start(rid, p, "CAPTURE_AND_INTERACT", true);
    }

    private JsonNode op(String type, String rid) {
        return studio.call(type, studio.obj().put("recordingId", rid).put("operationId", id("op")));
    }

    private List<JsonNode> steps(String rid) {
        TreeMap<Integer, JsonNode> bySeq = new TreeMap<>();
        for (JsonNode e : studio.allEvents) {
            if ("studio.recording.step".equals(e.path("type").asText()) && rid.equals(e.at("/payload/recordingId").asText())) {
                bySeq.putIfAbsent(e.at("/payload/sequence").asInt(), e.get("payload"));
            }
        }
        return new ArrayList<>(bySeq.values());
    }

    /** Evidências recebidas desta gravação, uma por evidenceSequence (reemissões não duplicam). */
    private List<JsonNode> evidences(String rid) {
        TreeMap<Integer, JsonNode> bySeq = new TreeMap<>();
        for (JsonNode e : studio.allEvents) {
            if ("studio.recording.evidence".equals(e.path("type").asText()) && rid.equals(e.at("/payload/recordingId").asText())) {
                bySeq.putIfAbsent(e.at("/payload/evidenceSequence").asInt(), e.get("payload"));
            }
        }
        return new ArrayList<>(bySeq.values());
    }

    private List<JsonNode> awaitSteps(String rid, int n) {
        BrowserLab.waitUntil(Duration.ofSeconds(30), () -> steps(rid).size() >= n, n + " etapa(s); até agora: " + names(steps(rid)));
        return steps(rid);
    }

    private JsonNode awaitEvidence(String rid, String what, Predicate<JsonNode> f) {
        BrowserLab.waitUntil(Duration.ofSeconds(20), () -> evidences(rid).stream().anyMatch(f), what + "; até agora: " + brief(evidences(rid)));
        return evidences(rid).stream().filter(f).findFirst().orElseThrow();
    }

    private static Predicate<JsonNode> type(String t) {
        return e -> t.equals(e.path("type").asText());
    }

    private static Predicate<JsonNode> of(JsonNode step, String t) {
        return e -> t.equals(e.path("type").asText()) && step.path("eventId").asText().equals(e.path("eventId").asText());
    }

    /** Janela de observação da etapa concluída ou declarada incompleta. */
    private JsonNode awaitWindowEnd(String rid, JsonNode step) {
        return awaitEvidence(rid, "fim da observação da etapa " + step.path("sequence"),
                e -> step.path("eventId").asText().equals(e.path("eventId").asText())
                        && (type("OBSERVATION_COMPLETE").test(e) || type("OBSERVATION_INCOMPLETE").test(e)));
    }

    private static List<String> names(List<JsonNode> steps) {
        List<String> out = new ArrayList<>();
        for (JsonNode s : steps) {
            out.add(s.path("sequence").asInt() + ":" + s.path("action").asText() + ":" + s.at("/element/name").asText());
        }
        return out;
    }

    private static List<String> brief(List<JsonNode> ev) {
        List<String> out = new ArrayList<>();
        for (JsonNode e : ev) {
            out.add(e.path("evidenceSequence").asInt() + ":" + e.path("type").asText() + "@" + e.path("stepSequence").asText("-") + ":"
                    + e.at("/details/message/availability").asText() + ":" + e.at("/details/message/text").asText("") + ":" + e.path("limitations"));
        }
        return out;
    }

    private void ackAll(String rid) {
        int lastStep = steps(rid).stream().mapToInt(s -> s.path("sequence").asInt()).max().orElse(0);
        assertTrue(studio.call("studio.recording.steps.ack", studio.obj().put("recordingId", rid).put("upToSequence", lastStep)).path("ok").asBoolean());
        int lastEv = evidences(rid).stream().mapToInt(s -> s.path("evidenceSequence").asInt()).max().orElse(0);
        if (lastEv == 0) {
            return; // nada a confirmar (e gravação sem evidence/1 recusa o comando)
        }
        JsonNode r = studio.call("studio.recording.evidence.ack", studio.obj().put("recordingId", rid).put("upToSequence", lastEv));
        assertTrue(r.path("ok").asBoolean(), r.toString());
    }

    private void click(Page p, By by) {
        lab.click(p, by);
    }

    private void type(Page p, By by, String text) {
        lab.focus(p);
        WebElement el = lab.driver().findElement(by);
        new Actions(lab.driver()).moveToElement(el).click().perform();
        el.sendKeys(text);
    }

    private String storageDump() {
        return String.valueOf(harnessScript("const done = arguments[arguments.length - 1]; chrome.storage.session.get(null).then((x) => done(JSON.stringify(x)));"));
    }

    /** Em todos os canais: eventos ao Studio, armazenamento da extensão, log local de diagnóstico e arquivos do host. */
    private void assertNoSecretAnywhere(String... secrets) throws IOException {
        assertNoSecret(true, secrets);
    }

    /**
     * Nos canais por onde a EVIDÊNCIA trafega ou fica (eventos ao Studio, armazenamento, arquivos do host). Usado depois
     * que a PRÓPRIA página escreveu o dado no seu texto visível: esse texto faz parte da captura técnica existente
     * (CHROME-002, deltas no log local de diagnóstico do service worker) — limitação documentada, fora do canal de
     * evidências e nunca enviada ao Studio.
     */
    private void assertNoSecretInEvidenceChannel(String... secrets) throws IOException {
        assertNoSecret(false, secrets);
    }

    private void assertNoSecret(boolean includeTechnicalCapture, String... secrets) throws IOException {
        String events = studio.allEvents.toString();
        String storage = storageDump();
        String panelLog = String.valueOf(lab.call("events.since", Map.of("since", 0)));
        List<String> files = new ArrayList<>();
        if (Files.isDirectory(lab.hostData())) {
            try (Stream<Path> s = Files.walk(lab.hostData())) {
                s.filter(Files::isRegularFile).filter(f -> f.toString().endsWith(".log") || f.toString().endsWith(".json") || f.toString().endsWith(".txt"))
                        // registro de alvos do motor (contextTexts = texto visível da página): captura técnica existente,
                        // fora do canal de evidências — achado registrado nos relatórios da STUDIO-005
                        .filter(f -> includeTechnicalCapture || !f.getFileName().toString().equals("registry.json"))
                        .forEach(f -> {
                            try {
                                files.add(Files.readString(f));
                            } catch (IOException e) {
                                // arquivo em uso/binário
                            }
                        });
            }
        }
        for (String secret : secrets) {
            assertFalse(events.contains(secret), "dado sensível nos eventos enviados ao Studio: " + secret);
            assertFalse(storage.contains(secret), "dado sensível no armazenamento temporário da extensão: " + secret);
            int at = includeTechnicalCapture ? panelLog.indexOf(secret) : -1;
            assertFalse(at >= 0, "dado sensível no log de eventos do service worker: '" + secret + "' em …"
                    + (at < 0 ? "" : panelLog.substring(Math.max(0, at - 400), Math.min(panelLog.length(), at + 120))));
            assertTrue(files.stream().noneMatch(f -> f.contains(secret)), "dado sensível em arquivos do host: " + secret);
        }
    }

    private JsonNode feedbackStatus(Page p) {
        return lab.call("page.diagnostics", p).at("/recorder/feedback");
    }

    /** O observador de consequências está ligado neste documento. */
    private boolean observing(Page p) {
        return feedbackStatus(p).path("active").asBoolean(false);
    }

    private void assertNoBusinessOutcome(String rid) {
        for (JsonNode e : evidences(rid)) {
            String s = e.toString();
            assertFalse(s.contains("businessOutcome") || s.contains("SUCCESS\"") || s.contains("FAILURE\""), "evidência não é resultado de negócio: " + s);
        }
    }

    // ------------------------------------------------------------------------------------------ cenários

    @Test
    @DisplayName("S005-006/009/010/011/012/019/020/047 — Cenário 1 (login com erro): alerta vinculado ao clique; mensagem antiga e classe CSS sozinha não contam")
    void s005LoginError() throws Exception {
        Page p = open(BASE + "login-erro.html");
        String rid = id("rec");
        start(rid, p);
        assertTrue(observing(p), "observador ligado com evidence/1 em CAPTURE_AND_INTERACT: " + feedbackStatus(p));
        type(p, By.id("usuario"), USER);
        type(p, By.id("senha"), PASSWORD);
        click(p, By.id("entrar"));
        List<JsonNode> s = awaitSteps(rid, 3);
        JsonNode entrar = s.get(2);
        assertEquals("CLICK", entrar.path("action").asText(), names(s).toString());
        JsonNode err = awaitEvidence(rid, "alerta de erro do clique em Entrar", of(entrar, "VISIBLE_ERROR_MESSAGE"));
        assertEquals("ERROR_INDICATOR", err.path("classification").asText());
        assertEquals("TEMPORALLY_ASSOCIATED", err.path("confidence").asText());
        assertEquals("AFTER_ACTION", err.path("relation").asText());
        assertEquals("Usuário ou senha inválidos.", err.at("/details/message/text").asText());
        assertTrue(err.at("/details/signals").toString().contains("ROLE_ALERT"), err.toString());
        assertTrue(err.at("/details/signals").toString().contains("ERROR_HINT"), err.toString());
        assertEquals("MAIN", err.at("/document/frame").asText());
        JsonNode form = awaitEvidence(rid, "envio do formulário", of(entrar, "FORM_SUBMITTED"));
        assertEquals("DIRECTLY_OBSERVED", form.path("confidence").asText());
        JsonNode end = awaitWindowEnd(rid, entrar);
        assertEquals("OBSERVATION_COMPLETE", end.path("type").asText(), end.toString());
        assertTrue(end.at("/details/signalsObserved").asInt() >= 1);
        for (JsonNode fill : s.subList(0, 2)) {
            assertEquals("OBSERVATION_COMPLETE", awaitWindowEnd(rid, fill).path("type").asText(), "preenchimento também tem a observação declarada");
        }
        assertTrue(evidences(rid).stream().noneMatch(e -> e.toString().contains("manutenção")), "mensagem que já estava na tela não é evidência");

        Thread.sleep(QUIET_MS);
        click(p, By.id("ajuda")); // div.error sem role/aria
        JsonNode ajuda = awaitSteps(rid, 4).get(3);
        JsonNode ajudaEnd = awaitWindowEnd(rid, ajuda);
        assertEquals(0, ajudaEnd.at("/details/signalsObserved").asInt(), "classe CSS \"error\" sozinha não é evidência: " + brief(evidences(rid)));

        assertNoSecretAnywhere(USER, PASSWORD); // antes de a página repetir o usuário no próprio texto
        Thread.sleep(QUIET_MS);
        click(p, By.id("entrar-eco")); // a mensagem repete o usuário digitado
        JsonNode eco = awaitSteps(rid, 5).get(4);
        JsonNode echoed = awaitEvidence(rid, "mensagem com o valor digitado", of(eco, "VISIBLE_ERROR_MESSAGE"));
        assertEquals("SUPPRESSED", echoed.at("/details/message/availability").asText());
        assertTrue(echoed.at("/details/message/text").isNull());
        assertTrue(echoed.path("limitations").toString().contains("MESSAGE_SUPPRESSED"));
        awaitWindowEnd(rid, eco);
        assertNoBusinessOutcome(rid);
        ackAll(rid);
        JsonNode fin = op("studio.recording.finish", rid);
        JsonNode sum = fin.at("/payload/summary/evidence");
        assertEquals(evidences(rid).size(), sum.path("produced").asInt(), fin.toString());
        assertEquals(0, sum.path("lost").asInt());
        assertEquals(0, sum.path("unacknowledged").asInt());
        assertNoSecretAnywhere(PASSWORD);
        assertNoSecretInEvidenceChannel(USER);
        lab.writeEvidence("S005-login-error-evidence.json", M.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("steps", steps(rid), "evidence", evidences(rid))));
    }

    @Test
    @DisplayName("S005-014/022/036 — Cenário 2 (mensagem tardia): confirmação 1 s depois é associada; fora da janela não é; observadores desligam")
    void s005LateMessage() throws Exception {
        Page p = open(BASE + "tardia.html");
        String rid = id("rec");
        start(rid, p);
        click(p, By.id("enviar"));
        JsonNode enviar = awaitSteps(rid, 1).get(0);
        JsonNode msg = awaitEvidence(rid, "confirmação tardia", of(enviar, "VISIBLE_STATUS_MESSAGE"));
        assertEquals("STATUS_INDICATOR", msg.path("classification").asText());
        assertEquals("Pedido registrado com sucesso.", msg.at("/details/message/text").asText(), "texto da página, não um resultado de negócio");
        long delay = java.time.Instant.parse(msg.path("observedAt").asText()).toEpochMilli() - java.time.Instant.parse(enviar.path("observedAt").asText()).toEpochMilli();
        assertTrue(delay >= 900 && delay < 3000, "observada ~1 s depois da ação: " + delay + " ms");
        assertEquals(1, awaitWindowEnd(rid, enviar).at("/details/signalsObserved").asInt());

        Thread.sleep(QUIET_MS);
        click(p, By.id("enviar-lento"));
        JsonNode lento = awaitSteps(rid, 2).get(1);
        JsonNode end = awaitWindowEnd(rid, lento);
        assertEquals("OBSERVATION_COMPLETE", end.path("type").asText());
        assertEquals(0, end.at("/details/signalsObserved").asInt(), "nada observado DENTRO da janela");
        waitText(lab.driver(), "status", "Pedido lento registrado.");
        Thread.sleep(500);
        assertTrue(evidences(rid).stream().noneMatch(e -> e.toString().contains("lento")), "mensagem depois da janela não é atribuída: " + brief(evidences(rid)));
        BrowserLab.waitUntil(Duration.ofSeconds(5), () -> feedbackStatus(p).path("observers").asInt(-1) == 0 && feedbackStatus(p).path("windows").asInt(-1) == 0,
                "nenhum MutationObserver ativo sem janela aberta: " + feedbackStatus(p));
        ackAll(rid);
        op("studio.recording.finish", rid);
    }

    @Test
    @DisplayName("S005-021 — Cenário 3 (ambígua): um aviso depois de dois cliques seguidos não é atribuído a nenhuma etapa")
    void s005Ambiguous() throws Exception {
        Page p = open(BASE + "ambigua.html");
        String rid = id("rec");
        start(rid, p);
        click(p, By.id("atualizar"));
        click(p, By.id("sincronizar"));
        awaitSteps(rid, 2);
        JsonNode amb = awaitEvidence(rid, "aviso ambíguo", type("AMBIGUOUS_FEEDBACK"));
        assertTrue(amb.path("eventId").isNull(), amb.toString());
        assertTrue(amb.path("stepSequence").isNull());
        assertEquals("AMBIGUOUS", amb.path("confidence").asText());
        assertEquals(2, amb.at("/details/candidates").asInt());
        assertEquals("Atualização concluída.", amb.at("/details/message/text").asText());
        assertTrue(evidences(rid).stream().noneMatch(e -> type("VISIBLE_STATUS_MESSAGE").test(e)), "nenhuma etapa escolhida arbitrariamente");
        for (JsonNode st : steps(rid)) {
            awaitWindowEnd(rid, st);
        }
        ackAll(rid);
        JsonNode fin = op("studio.recording.finish", rid);
        assertEquals(1, fin.at("/payload/summary/evidence/unattributed").asInt(), fin.toString());
    }

    @Test
    @DisplayName("S005-015 — Cenário 4 (validação): aria-invalid ao sair do campo e validação nativa no envio, sem reportValidity")
    void s005Validation() throws Exception {
        Page p = open(BASE + "validacao.html");
        String rid = id("rec");
        start(rid, p);
        type(p, By.id("cep"), "123");
        click(p, By.id("enviar")); // e-mail obrigatório vazio: o próprio navegador recusa o envio
        List<JsonNode> s = awaitSteps(rid, 2);
        JsonNode fill = s.get(0);
        JsonNode send = s.get(1);
        assertEquals("FILL", fill.path("action").asText(), names(s).toString());
        JsonNode cep = awaitEvidence(rid, "validação do CEP", of(fill, "VALIDATION_FEEDBACK"));
        assertEquals("DIRECTLY_OBSERVED", cep.path("confidence").asText(), "o próprio campo preenchido ficou inválido");
        assertEquals("CEP", cep.at("/details/field").asText());
        assertEquals("CEP inválido.", cep.at("/details/message/text").asText());
        assertTrue(cep.at("/details/signals").toString().contains("ARIA_INVALID"));
        JsonNode nat = awaitEvidence(rid, "validação nativa do e-mail", of(send, "VALIDATION_FEEDBACK"));
        assertEquals("E-mail", nat.at("/details/field").asText());
        assertTrue(nat.at("/details/signals").toString().contains("NATIVE_INVALID"), nat.toString());
        assertEquals("FORM_OBSERVER", nat.path("source").asText());
        assertTrue(evidences(rid).stream().noneMatch(of(send, "FORM_SUBMITTED")), "formulário não foi enviado");
        assertEquals("", lab.js(p, "return document.getElementById('resultado').textContent"), "a observação não enviou nada");
        awaitWindowEnd(rid, send);
        ackAll(rid);
        op("studio.recording.finish", rid);
        lab.writeEvidence("S005-validation-evidence.json", M.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("steps", steps(rid), "evidence", evidences(rid))));
    }

    @Test
    @DisplayName("S005-013/016/017/034 — Cenário 5 (pesquisa): carregamento, fim do carregamento, conteúdo atualizado e contagem — sem concluir sucesso")
    void s005SearchLoading() throws Exception {
        Page p = open(BASE + "pesquisa.html");
        String rid = id("rec");
        start(rid, p);
        type(p, By.id("termo"), "contrato");
        click(p, By.id("pesquisar"));
        JsonNode search = awaitSteps(rid, 2).get(1);
        awaitEvidence(rid, "indicador de carregamento", of(search, "LOADING_INDICATOR"));
        awaitEvidence(rid, "fim do carregamento", of(search, "LOADING_FINISHED"));
        awaitEvidence(rid, "conteúdo atualizado", of(search, "CONTENT_CHANGED"));
        JsonNode count = awaitEvidence(rid, "contagem de resultados", of(search, "VISIBLE_STATUS_MESSAGE"));
        assertEquals("3 resultados encontrados.", count.at("/details/message/text").asText());
        JsonNode end = awaitWindowEnd(rid, search);
        assertEquals("OBSERVATION_COMPLETE", end.path("type").asText(), brief(evidences(rid)).toString());
        List<String> order = evidences(rid).stream().filter(e -> search.path("eventId").asText().equals(e.path("eventId").asText()))
                .map(e -> e.path("type").asText()).toList();
        assertTrue(order.indexOf("LOADING_INDICATOR") < order.indexOf("LOADING_FINISHED"), "ordem técnica preservada: " + order);
        assertTrue(evidences(rid).stream().noneMatch(e -> e.toString().contains("Contrato Alfa")), "conteúdo da lista não sai");
        assertNoBusinessOutcome(rid);
        ackAll(rid);
        op("studio.recording.finish", rid);
        lab.writeEvidence("S005-search-evidence.json", M.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("steps", steps(rid), "evidence", evidences(rid))));
    }

    @Test
    @DisplayName("S005-007/008/018/045 — Cenário 6 (SPA): rota trocada, painel aberto (aria-expanded) e novo documento (janela cortada pela navegação)")
    void s005Spa() throws Exception {
        Page p = open(BASE + "spa.html");
        String rid = id("rec");
        start(rid, p);
        click(p, By.id("rota-clientes"));
        JsonNode rota = awaitSteps(rid, 1).get(0);
        JsonNode route = awaitEvidence(rid, "troca de rota", of(rota, "ROUTE_CHANGED"));
        assertEquals("SAME_DOCUMENT", route.at("/details/navigation/kind").asText());
        assertTrue(route.at("/details/navigation/url").asText().contains("view=clientes"), route.toString());
        Thread.sleep(QUIET_MS);
        click(p, By.id("filtros"));
        JsonNode filtros = awaitSteps(rid, 2).get(1);
        JsonNode state = awaitEvidence(rid, "estado do botão", of(filtros, "ELEMENT_STATE_CHANGED"));
        assertEquals("DIRECTLY_OBSERVED", state.path("confidence").asText());
        assertEquals("aria-expanded", state.at("/details/state/attribute").asText());
        assertEquals("false", state.at("/details/state/from").asText());
        assertEquals("true", state.at("/details/state/to").asText());
        Thread.sleep(QUIET_MS);
        click(p, By.id("outro-documento"));
        JsonNode link = awaitSteps(rid, 3).get(2);
        JsonNode doc = awaitEvidence(rid, "novo documento", of(link, "DOCUMENT_CHANGED"));
        assertEquals("NEW_DOCUMENT", doc.at("/details/navigation/kind").asText());
        JsonNode cut = awaitWindowEnd(rid, link);
        assertEquals("OBSERVATION_INCOMPLETE", cut.path("type").asText(), cut.toString());
        assertTrue(cut.path("limitations").toString().contains("WINDOW_CUT_BY_NAVIGATION"), cut.toString());
        ackAll(rid);
        op("studio.recording.finish", rid);
    }

    @Test
    @DisplayName("S005-042/043/044 — Cenário 7 (iframe e Shadow DOM): mensagem do iframe é da ação no iframe; escrita pelo principal no iframe não é atribuída; sem permissão não é observado; região no shadow root observada")
    void s005Frames() throws Exception {
        Page p = open(BASE + "iframe.html?externo=" + java.net.URLEncoder.encode(lab.externalUrl(BrowserLab.SITE_X, "/lab/studio005/quadro.html"),
                java.nio.charset.StandardCharsets.UTF_8));
        String rid = id("rec");
        start(rid, p);
        WebDriver d = lab.driver();
        lab.focus(p);
        d.switchTo().frame("quadro");
        new Actions(d).moveToElement(d.findElement(By.id("salvar"))).click().perform();
        d.switchTo().defaultContent();
        JsonNode salvar = awaitSteps(rid, 1).get(0);
        JsonNode inFrame = awaitEvidence(rid, "erro dentro do iframe", of(salvar, "VISIBLE_ERROR_MESSAGE"));
        assertEquals("FRAME", inFrame.at("/document/frame").asText());
        assertEquals("Erro ao salvar o rascunho.", inFrame.at("/details/message/text").asText());
        d.switchTo().frame("externo"); // origem SEM permissão: nada é observado
        new Actions(d).moveToElement(d.findElement(By.id("salvar"))).click().perform();
        d.switchTo().defaultContent();
        Thread.sleep(QUIET_MS);
        click(p, By.id("principal"));
        JsonNode principal = awaitSteps(rid, 2).get(1);
        JsonNode main = awaitEvidence(rid, "status do principal", of(principal, "VISIBLE_STATUS_MESSAGE"));
        assertEquals("MAIN", main.at("/document/frame").asText());
        Thread.sleep(QUIET_MS);
        click(p, By.id("principal-no-quadro"));
        JsonNode noQuadro = awaitSteps(rid, 3).get(2);
        JsonNode end = awaitWindowEnd(rid, noQuadro);
        assertEquals(0, end.at("/details/signalsObserved").asInt(), "aviso em OUTRO documento não é consequência atribuída: " + brief(evidences(rid)));
        assertTrue(evidences(rid).stream().noneMatch(e -> e.toString().contains("Aviso escrito pelo painel")), brief(evidences(rid)).toString());
        assertEquals(3, steps(rid).size(), "nenhuma etapa do iframe sem permissão: " + names(steps(rid)));
        // Shadow DOM aberto: a região de status dentro do shadow root é observada e associada ao clique no componente
        Thread.sleep(QUIET_MS);
        WebElement confirmar = d.findElement(By.id("caixa")).getShadowRoot().findElement(By.cssSelector("#confirmar"));
        new Actions(d).moveToElement(confirmar).click().perform();
        JsonNode comp = awaitSteps(rid, 4).get(3);
        JsonNode inShadow = awaitEvidence(rid, "status no shadow root", of(comp, "VISIBLE_STATUS_MESSAGE"));
        assertTrue(inShadow.at("/document/inShadow").asBoolean(), inShadow.toString());
        assertEquals("MAIN", inShadow.at("/document/frame").asText());
        assertEquals("Envio confirmado no componente.", inShadow.at("/details/message/text").asText());
        ackAll(rid);
        JsonNode fin = op("studio.recording.finish", rid);
        assertTrue(fin.at("/payload/summary/limitations").toString().contains("PARTIAL_COVERAGE"), "cobertura parcial declarada: " + fin);
    }

    @Test
    @DisplayName("S005-047/048/049/051 — Cenário 8 (dados sensíveis): CPF, e-mail, token, valor digitado, senha, texto longo e HTML nunca saem da página")
    void s005Sensitive() throws Exception {
        Page p = open(BASE + "sensivel.html");
        String rid = id("rec");
        start(rid, p);
        type(p, By.id("nome"), "Joana Prado");
        type(p, By.id("codigo"), "Kq7-Segredo");
        click(p, By.id("cpf")); // consolida os preenchimentos antes de qualquer mensagem repetir os valores
        awaitSteps(rid, 3);
        assertNoSecretAnywhere("Joana Prado", "Kq7-Segredo");
        Thread.sleep(QUIET_MS);
        List<String> buttons = List.of("email", "token", "eco", "senha", "longo", "marcacao");
        for (String b : buttons) {
            click(p, By.id(b));
            Thread.sleep(QUIET_MS); // cada mensagem com a sua etapa (sem ambiguidade)
        }
        List<JsonNode> s = awaitSteps(rid, 3 + buttons.size());
        for (JsonNode st : s) {
            awaitWindowEnd(rid, st);
        }
        List<JsonNode> msgs = evidences(rid).stream().filter(e -> type("VISIBLE_ERROR_MESSAGE").test(e)).toList();
        assertEquals(buttons.size() + 1, msgs.size(), brief(evidences(rid)).toString());
        List<String> available = msgs.stream().filter(e -> "AVAILABLE".equals(e.at("/details/message/availability").asText()))
                .map(e -> e.at("/details/message/text").asText()).toList();
        assertEquals(List.of("Senha incorreta."), available, "só o texto sem dado sensível sai (sem a marcação HTML): " + brief(msgs));
        assertEquals(buttons.size(), msgs.stream().filter(e -> e.path("limitations").toString().contains("MESSAGE_SUPPRESSED")).count());
        assertNoSecretInEvidenceChannel("123.456.789-09", "ana.souza@exemplo.com", "eyJhbGciOiJIUzI1NiJ9", "Joana Prado", "Kq7-Segredo", "<b>", "termos de uso");
        ackAll(rid);
        op("studio.recording.finish", rid);
        lab.writeEvidence("S005-sensitive-evidence.json", M.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("evidence", evidences(rid))));
    }

    @Test
    @DisplayName("S005-037/038 — pausa com observação aberta: INCOMPLETA (WINDOW_CUT_BY_PAUSE); a mensagem depois da pausa não é evidência")
    void s005PauseCutsWindow() throws Exception {
        Page p = open(BASE + "tardia.html");
        String rid = id("rec");
        start(rid, p);
        click(p, By.id("enviar"));
        JsonNode enviar = awaitSteps(rid, 1).get(0);
        op("studio.recording.pause", rid);
        JsonNode cut = awaitWindowEnd(rid, enviar);
        assertEquals("OBSERVATION_INCOMPLETE", cut.path("type").asText(), cut.toString());
        assertTrue(cut.path("limitations").toString().contains("WINDOW_CUT_BY_PAUSE"), cut.toString());
        waitText(lab.driver(), "status", "Pedido registrado com sucesso.");
        Thread.sleep(800);
        assertTrue(evidences(rid).stream().noneMatch(type("VISIBLE_STATUS_MESSAGE")), "nada observado durante a pausa: " + brief(evidences(rid)));
        assertFalse(observing(p), "observador desligado na pausa");
        op("studio.recording.resume", rid);
        ackAll(rid);
        JsonNode fin = op("studio.recording.finish", rid);
        assertEquals(1, fin.at("/payload/summary/evidence/incomplete").asInt(), fin.toString());
    }

    @Test
    @DisplayName("S005-035 — CAPTURE_ONLY: a ação não é executada, nenhum observador e nenhuma evidência de consequência")
    void s005CaptureOnly() throws Exception {
        Page p = open(BASE + "login-erro.html");
        String rid = id("rec");
        start(rid, p, "CAPTURE_ONLY", true);
        assertFalse(observing(p), "observador desligado em CAPTURE_ONLY: " + feedbackStatus(p));
        click(p, By.id("entrar"));
        awaitSteps(rid, 1);
        Thread.sleep(QUIET_MS);
        assertTrue((Boolean) lab.js(p, "return document.getElementById('erro').hidden"), "a ação não foi executada");
        assertEquals(0, evidences(rid).size(), brief(evidences(rid)).toString());
        ackAll(rid);
        op("studio.recording.finish", rid);
    }

    @Test
    @DisplayName("S005-004 — Studio sem evidence/1 (STUDIO-003/004): observador desligado e nenhum evento novo")
    void s005Compat() throws Exception {
        Page p = open(BASE + "login-erro.html");
        String rid = id("rec");
        JsonNode r = start(rid, p, "CAPTURE_AND_INTERACT", false);
        assertTrue(r.at("/payload/recording/evidence").isMissingNode(), r.toString());
        assertFalse(observing(p), "sem a capacidade, nada observado");
        click(p, By.id("entrar"));
        awaitSteps(rid, 1);
        Thread.sleep(QUIET_MS);
        assertEquals(0, evidences(rid).size());
        ackAll(rid);
        JsonNode fin = op("studio.recording.finish", rid);
        assertTrue(fin.at("/payload/summary/evidence").isMissingNode(), "resumo igual ao da STUDIO-003: " + fin);
    }

    @Test
    @DisplayName("S005-024/025 — reconexão: evidências não confirmadas são reemitidas com a mesma identidade e não duplicam")
    void s005Reconnect() throws Exception {
        Page p = open(BASE + "login-erro.html");
        String rid = id("rec");
        start(rid, p);
        click(p, By.id("entrar"));
        JsonNode entrar = awaitSteps(rid, 1).get(0);
        awaitWindowEnd(rid, entrar);
        int n = evidences(rid).size();
        studio.dropConnection();
        lab.call("studio.link.set", Map.of("enabled", true));
        studio.awaitConnected(Duration.ofSeconds(30));
        JsonNode st = studio.call("studio.recording.status", studio.obj().put("recordingId", rid));
        assertEquals(n, st.at("/payload/recordings/0/evidence/pending").asInt(), st.toString());
        long before = studio.allEvents.stream().filter(e -> "studio.recording.evidence".equals(e.path("type").asText())).count();
        JsonNode sync = studio.call("studio.recording.evidence.sync", studio.obj().put("recordingId", rid).put("afterSequence", 0));
        assertEquals(n, sync.at("/payload/resent").asInt(), sync.toString());
        BrowserLab.waitUntil(Duration.ofSeconds(10), () -> studio.allEvents.stream().filter(e -> "studio.recording.evidence".equals(e.path("type").asText())).count() >= before + n,
                "reemissões recebidas");
        assertEquals(n, evidences(rid).size(), "deduplicação por evidenceSequence/evidenceId");
        ackAll(rid);
        JsonNode st2 = studio.call("studio.recording.status", studio.obj().put("recordingId", rid));
        assertEquals(0, st2.at("/payload/recordings/0/evidence/pending").asInt());
        op("studio.recording.finish", rid);
    }

    @Test
    @DisplayName("Desempenho (S005-063) — cliques consecutivos com e sem evidence/1: latência até a etapa, custo das evidências, observadores ociosos")
    void s005Performance() throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        for (boolean ev : new boolean[] {false, true}) {
            Page p = open(BASE + "tardia.html");
            String rid = id("rec");
            start(rid, p, "CAPTURE_AND_INTERACT", ev);
            List<Double> lat = new ArrayList<>();
            for (int i = 0; i < 15; i++) {
                int expected = steps(rid).size() + 1;
                long t0 = System.nanoTime();
                click(p, By.id("enviar"));
                BrowserLab.waitUntil(Duration.ofSeconds(10), () -> steps(rid).size() >= expected, "etapa " + expected);
                lat.add((System.nanoTime() - t0) / 1e6);
            }
            Thread.sleep(4500);
            lat.sort(Double::compare);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("clickToStepMs", Map.of("p50", Math.round(lat.get(7)), "p95", Math.round(lat.get(13)), "max", Math.round(lat.get(14))));
            if (ev) {
                List<JsonNode> e = evidences(rid);
                m.put("evidenceEvents", e.size());
                m.put("evidenceBytesMax", e.stream().mapToInt(x -> x.toString().length()).max().orElse(0));
                m.put("pipeline", lab.call("studio.evidence.metrics", Map.of("recordingId", rid)));
                JsonNode fs = feedbackStatus(p);
                m.put("idleObservers", fs.path("observers").asInt());
                assertEquals(0, fs.path("observers").asInt(), "observadores desligados depois das janelas: " + fs);
                assertEquals(0, fs.path("windows").asInt());
            }
            out.put(ev ? "withEvidence" : "withoutEvidence", m);
            ackAll(rid);
            op("studio.recording.finish", rid);
            lab.close(p); // a próxima rodada abre a mesma página: uma aba por título
            assertTrue(lat.get(13) < 3000, "p95 do clique até a etapa: " + lat);
        }
        lab.writeEvidence("S005-performance.json", M.writerWithDefaultPrettyPrinter().writeValueAsString(out));
    }
}
