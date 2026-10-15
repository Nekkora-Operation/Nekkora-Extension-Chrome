package com.nekkora.extension.host.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Canal Studio ↔ host (nekkora-bridge/1) com o HostRuntime real: S002-002..005, 019 e segurança dos comandos. */
class StudioLinkTest {

    private final ObjectMapper m = new ObjectMapper();
    @TempDir
    Path tmp;

    private JsonNode fixture(String name) throws Exception {
        return m.readTree(getClass().getResourceAsStream("/contracts/studio-bridge/fixtures/valid/" + name + ".json"));
    }

    private static boolean isType(JsonNode n, String type) {
        return type.equals(n.path("type").asText());
    }

    @Test
    void s002003ConexaoAutenticadaEComandoRepassado() throws Exception {
        try (TestStudioServer studio = new TestStudioServer(tmp.resolve("bridge"), TestStudioServer.Mode.NORMAL, 5000);
             ExtensionSide ext = new ExtensionSide(tmp.resolve("data"), tmp.resolve("bridge"))) {
            ext.enableLink();
            assertNotNull(ext.awaitLink("CONNECTED", 10_000), "canal CONNECTED");
            assertEquals("WELCOMED", studio.handshakeResult);
            JsonNode hello = studio.received.stream().filter(n -> isType(n, "bridge.hello")).findFirst().orElseThrow();
            assertFalse(hello.toString().contains(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(studio.secret)),
                    "o segredo nunca trafega");
            JsonNode connected = studio.await(n -> isType(n, "bridge.connected"), 5000);
            assertNotNull(connected);
            assertEquals("0.4.0", connected.at("/payload/extension/version").asText());

            String sid = studio.command("bridge.status", null);
            JsonNode status = studio.await(n -> sid.equals(n.path("id").asText()), 5000);
            assertTrue(status.path("ok").asBoolean(), status.toString());
            assertTrue(BridgeContract.valid(status), BridgeContract.problems(status).toString());

            ObjectNode pause = m.createObjectNode().put("recordingId", "rec-1").put("operationId", "op-1");
            String pid = studio.command("studio.recording.pause", pause);
            ExtensionSide.Frame cmd = ext.await(n -> isType(n, "studio.command"), 5000);
            assertNotNull(cmd, "comando repassado à extensão");
            assertEquals("studio.recording.pause", cmd.json().path("command").asText());
            assertEquals(studio.instanceId, cmd.json().at("/controller/instanceId").asText(), "controlador identificado pelo host");
            assertEquals("rec-1", cmd.json().at("/payload/recordingId").asText());

            ObjectNode reply = m.createObjectNode().put("protocol", "nekkora-nm/1").put("type", "studio.reply").put("id", cmd.json().path("id").asText()).put("ok", true);
            reply.set("payload", fixture("response-pause").path("payload"));
            reply.putNull("error");
            ext.write(reply.toString());
            JsonNode resp = studio.await(n -> pid.equals(n.path("id").asText()), 5000);
            assertNotNull(resp, "resposta da extensão correlacionada ao comando do Studio");
            assertTrue(resp.path("ok").asBoolean());
            assertEquals("PAUSED", resp.at("/payload/recording/state").asText());

            ObjectNode ev = m.createObjectNode().put("protocol", "nekkora-nm/1").put("type", "studio.event").put("event", "studio.recording.paused");
            ev.set("payload", fixture("event-paused").path("payload"));
            ev.putObject("controller").put("instanceId", studio.instanceId);
            ext.write(ev.toString());
            JsonNode got = studio.await(n -> isType(n, "studio.recording.paused"), 5000);
            assertNotNull(got);
            assertEquals("op-pause-1", got.at("/payload/operationId").asText());
            assertTrue(BridgeContract.valid(got), BridgeContract.problems(got).toString());
        }
    }

    @Test
    void s002004CredencialInvalidaRecusada() throws Exception {
        byte[] other = new byte[32];
        new java.security.SecureRandom().nextBytes(other);
        try (TestStudioServer studio = new TestStudioServer(tmp.resolve("bridge"), TestStudioServer.Mode.NORMAL, 5000, other);
             ExtensionSide ext = new ExtensionSide(tmp.resolve("data"), tmp.resolve("bridge"))) {
            ext.enableLink();
            assertNotNull(ext.awaitLink("AUTH_FAILED", 10_000), "host informa a recusa");
            assertEquals("REJECTED_AUTH", studio.handshakeResult);
            Thread.sleep(500);
            assertTrue(ext.all.stream().noneMatch(f -> "CONNECTED".equals(f.json().path("state").asText())));
        }
    }

    @Test
    void autenticacaoMutuaStudioFalsoRecusado() throws Exception {
        try (TestStudioServer studio = new TestStudioServer(tmp.resolve("bridge"), TestStudioServer.Mode.WRONG_SERVER_PROOF, 5000);
             ExtensionSide ext = new ExtensionSide(tmp.resolve("data"), tmp.resolve("bridge"))) {
            ext.enableLink();
            assertNotNull(ext.awaitLink("AUTH_FAILED", 10_000), "o host não aceita um Studio que não prova conhecer o segredo");
            assertTrue(ext.all.stream().noneMatch(f -> "CONNECTED".equals(f.json().path("state").asText())));
            assertNull(studio.await(n -> isType(n, "bridge.connected"), 500));
        }
    }

    @Test
    void s002005ProtocoloIncompativelRecusadoComErroExplicito() throws Exception {
        try (TestStudioServer studio = new TestStudioServer(tmp.resolve("bridge"), TestStudioServer.Mode.WRONG_PROTOCOL, 5000);
             ExtensionSide ext = new ExtensionSide(tmp.resolve("data"), tmp.resolve("bridge"))) {
            ext.enableLink();
            ExtensionSide.Frame f = ext.awaitLink("INCOMPATIBLE", 10_000);
            assertNotNull(f);
            assertEquals("PROTOCOL_UNSUPPORTED", f.json().path("detail").asText());
            JsonNode rejected = studio.await(n -> isType(n, "bridge.rejected"), 5000);
            assertNotNull(rejected, "o Studio recebe a recusa explícita");
            assertEquals("PROTOCOL_UNSUPPORTED", rejected.at("/payload/error/code").asText());
        }
    }

    @Test
    void arquivoDeDescobertaInseguroNaoEhUsado() throws Exception {
        try (TestStudioServer studio = new TestStudioServer(tmp.resolve("bridge"), TestStudioServer.Mode.NORMAL, 5000);
             ExtensionSide ext = new ExtensionSide(tmp.resolve("data"), tmp.resolve("bridge"))) {
            Path f = tmp.resolve("bridge").resolve(BridgeDiscovery.FILE_NAME);
            AclFileAttributeView view = Files.getFileAttributeView(f, AclFileAttributeView.class);
            if (view != null) {
                List<AclEntry> acl = new ArrayList<>(view.getAcl());
                acl.add(AclEntry.newBuilder().setType(AclEntryType.ALLOW)
                        .setPrincipal(f.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByGroupName(everyone()))
                        .setPermissions(EnumSet.of(AclEntryPermission.READ_DATA)).build());
                view.setAcl(acl);
            } else {
                Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-r--r--"));
            }
            ext.enableLink();
            ExtensionSide.Frame st = ext.awaitLink("INSECURE", 10_000);
            assertNotNull(st, "arquivo acessível a outros: recusado sem conectar");
            Thread.sleep(400);
            assertEquals("PENDING", studio.handshakeResult, "nenhuma conexão foi feita");
        }
    }

    /** Nome do grupo "Todos" (S-1-1-0) no idioma do Windows. */
    private static String everyone() throws Exception {
        Process p = new ProcessBuilder("powershell", "-NoProfile", "-Command",
                "(New-Object System.Security.Principal.SecurityIdentifier('S-1-1-0')).Translate([System.Security.Principal.NTAccount]).Value").start();
        return new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
    }

    @Test
    void listaExplicitaEValidacaoDosComandos() throws Exception {
        try (TestStudioServer studio = new TestStudioServer(tmp.resolve("bridge"), TestStudioServer.Mode.NORMAL, 5000);
             ExtensionSide ext = new ExtensionSide(tmp.resolve("data"), tmp.resolve("bridge"))) {
            ext.enableLink();
            assertNotNull(ext.awaitLink("CONNECTED", 10_000));
            String a = studio.command("page.capture", null);
            JsonNode ra = studio.await(n -> a.equals(n.path("id").asText()), 5000);
            assertEquals("UNKNOWN_COMMAND", ra.at("/error/code").asText());
            assertTrue(BridgeContract.valid(ra), BridgeContract.problems(ra).toString());
            ObjectNode bad = m.createObjectNode().put("recordingId", "rec-1").put("operationId", "op-1").put("tabRef", "t-AAAAAAAAAA")
                    .put("mode", "CAPTURE_ONLY").put("leaseMs", 30000).put("script", "document.cookie");
            String b = studio.command("studio.recording.start", bad);
            JsonNode rb = studio.await(n -> b.equals(n.path("id").asText()), 5000);
            assertEquals("BAD_REQUEST", rb.at("/error/code").asText());
            String c = studio.command("host.ping", null);
            assertEquals("UNKNOWN_COMMAND", studio.await(n -> c.equals(n.path("id").asText()), 5000).at("/error/code").asText());
            Thread.sleep(300);
            assertTrue(ext.all.stream().noneMatch(f -> isType(f.json(), "studio.command")), "nada fora da lista chega à extensão");
        }
    }

    @Test
    void respostaForaDoContratoNaoVaza() throws Exception {
        try (TestStudioServer studio = new TestStudioServer(tmp.resolve("bridge"), TestStudioServer.Mode.NORMAL, 5000);
             ExtensionSide ext = new ExtensionSide(tmp.resolve("data"), tmp.resolve("bridge"))) {
            ext.enableLink();
            assertNotNull(ext.awaitLink("CONNECTED", 10_000));
            String id = studio.command("browser.tabs.list", null);
            ExtensionSide.Frame cmd = ext.await(n -> isType(n, "studio.command"), 5000);
            ObjectNode reply = m.createObjectNode().put("protocol", "nekkora-nm/1").put("type", "studio.reply").put("id", cmd.json().path("id").asText()).put("ok", true);
            reply.set("payload", m.readTree("{\"tabs\":[{\"tabRef\":\"t-AAAAAAAAAA\",\"title\":\"x\",\"host\":\"a\",\"url\":\"https://a/?token=SEGREDO\","
                    + "\"access\":\"LAB\",\"active\":true,\"recording\":false}],\"truncated\":false}"));
            reply.putNull("error");
            ext.write(reply.toString());
            JsonNode r = studio.await(n -> id.equals(n.path("id").asText()), 5000);
            assertEquals("INTERNAL_ERROR", r.at("/error/code").asText());
            assertFalse(r.toString().contains("SEGREDO"), "URL completa não sai pelo canal de controle");
        }
    }

    @Test
    void heartbeatPerdidoEncerraOCanal() throws Exception {
        try (TestStudioServer studio = new TestStudioServer(tmp.resolve("bridge"), TestStudioServer.Mode.NORMAL, 1000);
             ExtensionSide ext = new ExtensionSide(tmp.resolve("data"), tmp.resolve("bridge"))) {
            ext.enableLink();
            assertNotNull(ext.awaitLink("CONNECTED", 10_000));
            long t0 = System.currentTimeMillis();
            ExtensionSide.Frame lost = ext.await(n -> "studio.link".equals(n.path("type").asText()) && "HEARTBEAT_LOST".equals(n.path("detail").asText()), 10_000);
            assertNotNull(lost, "Studio silencioso (travado) é detectado");
            long ms = System.currentTimeMillis() - t0;
            assertTrue(ms >= 2500 && ms < 7000, "detecção em ~3 batimentos: " + ms + " ms");
            assertNotNull(studio.await(n -> "closed".equals(n.path("kind").asText()), 5000));
        }
    }

    @Test
    void handshakeSemRespostaExpira() throws Exception {
        try (TestStudioServer studio = new TestStudioServer(tmp.resolve("bridge"), TestStudioServer.Mode.SILENT, 5000);
             ExtensionSide ext = new ExtensionSide(tmp.resolve("data"), tmp.resolve("bridge"))) {
            ext.enableLink();
            ExtensionSide.Frame f = ext.await(n -> "HANDSHAKE_TIMEOUT".equals(n.path("detail").asText()), 15_000);
            assertNotNull(f, "handshake parado não prende o host");
        }
    }

    @Test
    void s002002SemCanalHabilitadoNadaMuda() throws Exception {
        try (TestStudioServer studio = new TestStudioServer(tmp.resolve("bridge"), TestStudioServer.Mode.NORMAL, 5000);
             ExtensionSide ext = new ExtensionSide(tmp.resolve("data"), tmp.resolve("bridge"))) {
            String id = ext.request("host.ping", null);
            ExtensionSide.Frame ping = ext.await(n -> id.equals(n.path("id").asText()), 10_000);
            assertTrue(ping.json().path("ok").asBoolean(), "motor responde normalmente");
            Thread.sleep(800);
            assertEquals("PENDING", studio.handshakeResult, "sem opt-in o host não procura o Studio");
            assertTrue(ext.all.stream().noneMatch(f -> isType(f.json(), "studio.link")));
        }
    }

    @Test
    void s002019ChromeEncerradoAvisaOStudio() throws Exception {
        try (TestStudioServer studio = new TestStudioServer(tmp.resolve("bridge"), TestStudioServer.Mode.NORMAL, 5000)) {
            ExtensionSide ext = new ExtensionSide(tmp.resolve("data"), tmp.resolve("bridge"));
            ext.enableLink();
            assertNotNull(ext.awaitLink("CONNECTED", 10_000));
            ext.close(); // Chrome fechou a porta nativa
            JsonNode d = studio.await(n -> isType(n, "bridge.disconnected"), 10_000);
            assertNotNull(d, "o Studio é avisado antes do canal fechar");
            assertEquals("CHROME_DISCONNECTED", d.at("/payload/reason").asText());
            assertNotNull(studio.await(n -> "closed".equals(n.path("kind").asText()), 5000));
        }
    }
}
