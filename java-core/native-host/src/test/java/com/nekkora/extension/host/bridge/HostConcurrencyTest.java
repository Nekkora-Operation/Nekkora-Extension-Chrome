package com.nekkora.extension.host.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** D-STUDIO-002-05: escrita atômica de respostas fragmentadas e controle responsivo durante operação longa. */
class HostConcurrencyTest {

    private final ObjectMapper m = new ObjectMapper();
    @TempDir
    Path tmp;

    /** Evidência medida (target/studio-002/*.json). */
    private void evidence(String name, Map<String, Object> data) throws Exception {
        Path dir = Paths.get("target", "studio-002");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name + ".json"), m.writerWithDefaultPrettyPrinter().writeValueAsString(data));
    }

    @Test
    void s002021RespostaFragmentadaNuncaEhIntercalada() throws Exception {
        try (ExtensionSide ext = new ExtensionSide(tmp.resolve("data"), tmp.resolve("bridge"))) {
            java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
            java.util.concurrent.atomic.AtomicInteger sent = new java.util.concurrent.atomic.AtomicInteger();
            Thread control = new Thread(() -> {
                // Mensagens de controle CONTÍNUAS enquanto as respostas grandes são produzidas e escritas.
                while (!stop.get()) {
                    int i = sent.getAndIncrement();
                    ext.runtime.sendToExtension("{\"protocol\":\"nekkora-nm/1\",\"type\":\"studio.link\",\"state\":\"SEARCHING\",\"detail\":\"flood-" + i
                            + "\",\"controller\":null}");
                    try {
                        Thread.sleep(0, 200_000);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            });
            control.start();
            // Três respostas grandes (≈ 6 milhões de caracteres cada → dezenas de fragmentos) disputando o stdout.
            String a = ext.request("host.generate", m.createObjectNode().put("chars", 6_000_000));
            String b = ext.request("host.generate", m.createObjectNode().put("chars", 6_000_000));
            String c = ext.request("host.generate", m.createObjectNode().put("chars", 6_000_000));
            long deadline = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < deadline) {
                long done = ext.all.stream().filter(f -> "chunk".equals(f.json().path("type").asText())
                        && f.json().path("index").asInt() == f.json().path("count").asInt() - 1).count();
                if (done == 3) {
                    break;
                }
                Thread.sleep(20);
            }
            stop.set(true);
            control.join();
            int flood = sent.get();
            ext.runtime.writer().awaitDrained(10_000);
            Thread.sleep(300);
            List<ExtensionSide.Frame> seq = ext.all;
            Map<String, int[]> span = new HashMap<>(); // transferId -> [primeira posição, última posição, quantidade]
            int interleavedControl = 0;
            for (int i = 0; i < seq.size(); i++) {
                JsonNode j = seq.get(i).json();
                if ("chunk".equals(j.path("type").asText())) {
                    String t = j.path("transferId").asText();
                    int[] s = span.computeIfAbsent(t, k -> new int[] {Integer.MAX_VALUE, -1, 0});
                    s[0] = Math.min(s[0], i);
                    s[1] = Math.max(s[1], i);
                    s[2]++;
                    assertEquals(s[2] - 1, j.path("index").asInt(), "fragmentos em ordem em " + t);
                }
            }
            assertEquals(3, span.size(), "três transferências fragmentadas");
            int chunks = 0;
            for (Map.Entry<String, int[]> e : span.entrySet()) {
                int[] s = e.getValue();
                assertEquals(s[2], s[1] - s[0] + 1, "a transferência " + e.getKey() + " saiu contígua (nenhum envelope entre os fragmentos)");
                chunks += s[2];
            }
            long floods = seq.stream().filter(f -> f.json().path("detail").asText().startsWith("flood-")).count();
            assertEquals(flood, floods, "todas as mensagens de controle entregues");
            for (String id : List.of(a, b, c)) {
                assertTrue(seq.stream().noneMatch(f -> id.equals(f.json().path("id").asText()) && !f.json().path("ok").asBoolean(true)));
            }
            // Disputa real: mensagens de controle saíram ENTRE transferências (logo após o último fragmento de uma).
            for (int i = 1; i < seq.size(); i++) {
                if (seq.get(i).json().path("detail").asText().startsWith("flood-") && "chunk".equals(seq.get(i - 1).json().path("type").asText())) {
                    interleavedControl++;
                }
            }
            assertTrue(interleavedControl > 0, "o teste precisa exercitar a disputa pelo stdout");
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("transfers", span.size());
            ev.put("chunks", chunks);
            ev.put("controlMessages", flood);
            ev.put("framesObserved", seq.size());
            ev.put("controlRightAfterATransfer", interleavedControl);
            ev.put("interleavedWithinTransfer", 0);
            evidence("S002-021-fragmentation", ev);
        }
    }

    @Test
    void s002022OperacaoLongaNaoBloqueiaOControle() throws Exception {
        try (TestStudioServer studio = new TestStudioServer(tmp.resolve("bridge"), TestStudioServer.Mode.NORMAL, 5000);
             ExtensionSide ext = new ExtensionSide(tmp.resolve("data"), tmp.resolve("bridge"))) {
            ext.enableLink();
            assertNotNull(ext.awaitLink("CONNECTED", 10_000));
            long holdMs = 6000;
            long t0 = System.nanoTime();
            String hold = ext.request("host.hold", m.createObjectNode().put("ms", holdMs)); // motor ocupado (operação controlada)
            String ping = ext.request("host.ping", null); // fila do motor: só depois da operação longa
            Thread.sleep(1500);

            long s0 = System.nanoTime();
            String st = studio.command("bridge.status", null);
            JsonNode status = studio.await(n -> st.equals(n.path("id").asText()), 5000);
            long statusMs = (System.nanoTime() - s0) / 1_000_000;
            assertTrue(status.at("/payload/host/engineBusy").asBoolean(), "o host informa que o motor está ocupado");

            long p0 = System.nanoTime();
            String pid = studio.command("studio.recording.pause", m.createObjectNode().put("recordingId", "rec-1").put("operationId", "op-1"));
            ExtensionSide.Frame cmd = ext.await(n -> "studio.command".equals(n.path("type").asText()), 5000);
            long forwardMs = (cmd.atNanos() - p0) / 1_000_000;
            ObjectNode reply = m.createObjectNode().put("protocol", "nekkora-nm/1").put("type", "studio.reply").put("id", cmd.json().path("id").asText()).put("ok", true);
            reply.set("payload", m.readTree(getClass().getResourceAsStream("/contracts/studio-bridge/fixtures/valid/response-pause.json")).path("payload"));
            reply.putNull("error");
            long r0 = System.nanoTime();
            ext.write(reply.toString());
            JsonNode resp = studio.await(n -> pid.equals(n.path("id").asText()), 5000);
            long replyMs = (System.nanoTime() - r0) / 1_000_000;
            long roundTripMs = (System.nanoTime() - p0) / 1_000_000;
            assertTrue(resp.path("ok").asBoolean());

            long l0 = System.nanoTime();
            String ls = ext.request("studio.link.status", null);
            ExtensionSide.Frame linkStatus = ext.await(n -> ls.equals(n.path("id").asText()), 5000);
            long linkStatusMs = (linkStatus.atNanos() - l0) / 1_000_000;

            ExtensionSide.Frame held = ext.await(n -> hold.equals(n.path("id").asText()), 15_000);
            ExtensionSide.Frame pinged = ext.await(n -> ping.equals(n.path("id").asText()), 15_000);
            assertNotNull(held);
            assertNotNull(pinged);
            long holdDoneMs = (held.atNanos() - t0) / 1_000_000;
            long pingDoneMs = (pinged.atNanos() - t0) / 1_000_000;
            boolean controlBeforeHold = cmd.atNanos() < held.atNanos() && linkStatus.atNanos() < held.atNanos();

            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("engineOperation", "host.hold " + holdMs + " ms (operação longa controlada)");
            ev.put("bridgeStatusResponseMs", statusMs);
            ev.put("studioCommandForwardedToExtensionMs", forwardMs);
            ev.put("extensionReplyToStudioMs", replyMs);
            ev.put("studioPauseRoundTripMs", roundTripMs);
            ev.put("extensionLinkStatusResponseMs", linkStatusMs);
            ev.put("engineOperationCompletedAtMs", holdDoneMs);
            ev.put("engineQueuedPingCompletedAtMs", pingDoneMs);
            ev.put("controlHandledWhileEngineBusy", controlBeforeHold);
            evidence("S002-022-long-operation", ev);

            assertTrue(controlBeforeHold, "comandos de controle atendidos enquanto o motor ainda estava ocupado");
            assertTrue(statusMs < 500, "bridge.status em " + statusMs + " ms");
            assertTrue(forwardMs < 500, "repasse do comando em " + forwardMs + " ms");
            assertTrue(replyMs < 500, "resposta ao Studio em " + replyMs + " ms");
            assertTrue(linkStatusMs < 500, "studio.link.status em " + linkStatusMs + " ms");
            assertTrue(holdDoneMs >= holdMs - 50, "a operação longa foi de fato longa (" + holdDoneMs + " ms)");
            assertTrue(pingDoneMs >= holdDoneMs, "a fila do motor continua sequencial (ping depois do hold)");
        }
    }
}
