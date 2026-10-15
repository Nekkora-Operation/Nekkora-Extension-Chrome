package com.nekkora.extension.host.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lado "Studio" do nekkora-bridge/1 para os testes do host: publica o arquivo de descoberta (restrito ao usuário),
 * aceita UMA conexão, faz o desafio e verifica a prova do host. Comportamentos de ataque/erro configuráveis.
 */
final class TestStudioServer implements AutoCloseable {

    final ObjectMapper m = new ObjectMapper();
    final Path dir;
    final byte[] secret = new byte[32];
    final String instanceId = "studio-test-" + System.nanoTime();
    final BlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();
    /** Tudo o que chegou, na ordem (o {@link #await} consome a fila; este registro não). */
    final java.util.List<JsonNode> all = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final ServerSocketChannel server;
    private final Path socket;
    private final AtomicLong seq = new AtomicLong();
    private volatile SocketChannel client;
    private OutputStream out;
    volatile String handshakeResult = "PENDING";

    /** Comportamento do lado Studio. */
    enum Mode { NORMAL, WRONG_PROTOCOL, WRONG_SERVER_PROOF, SILENT }

    TestStudioServer(Path dir, Mode mode, int heartbeatMs) throws IOException {
        this(dir, mode, heartbeatMs, null);
    }

    /** @param publishedSecret segredo escrito no arquivo (null = o mesmo do servidor; diferente = credencial inválida) */
    TestStudioServer(Path dir, Mode mode, int heartbeatMs, byte[] publishedSecret) throws IOException {
        this.dir = dir;
        new SecureRandom().nextBytes(secret);
        Files.createDirectories(dir);
        restrict(dir, true);
        socket = dir.resolve("s.sock");
        Files.deleteIfExists(socket);
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(socket));
        ObjectNode d = m.createObjectNode();
        d.put("protocol", "nekkora-bridge/1");
        d.put("transport", "unix");
        d.put("address", socket.toString());
        d.put("instanceId", instanceId);
        d.put("pid", ProcessHandle.current().pid());
        d.put("secret", Base64.getUrlEncoder().withoutPadding().encodeToString(publishedSecret != null ? publishedSecret : secret));
        Path f = dir.resolve(BridgeDiscovery.FILE_NAME);
        Files.writeString(f, d.toString());
        restrict(f, false);
        Thread t = new Thread(() -> serve(mode, heartbeatMs), "test-studio");
        t.setDaemon(true);
        t.start();
    }

    /** ACL somente do dono (Windows) ou 700/600 (POSIX). */
    static void restrict(Path p, boolean directory) throws IOException {
        AclFileAttributeView acl = Files.getFileAttributeView(p, AclFileAttributeView.class);
        if (acl != null) {
            UserPrincipal owner = Files.getOwner(p);
            AclEntry.Builder b = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner).setPermissions(EnumSet.allOf(AclEntryPermission.class));
            if (directory) {
                b.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
            }
            acl.setAcl(List.of(b.build()));
        } else {
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
        }
    }

    private void serve(Mode mode, int heartbeatMs) {
        try {
            SocketChannel c = server.accept();
            client = c;
            InputStream in = Channels.newInputStream(c);
            out = Channels.newOutputStream(c);
            if (mode == Mode.SILENT) {
                handshakeResult = "SILENT";
                in.read(); // nunca desafia: o host deve desistir por tempo
                return;
            }
            String serverNonce = BridgeAuth.nonce();
            ObjectNode ch = envelope("handshake", "bridge.challenge");
            ObjectNode p = ch.putObject("payload");
            p.put("instanceId", instanceId);
            p.put("serverNonce", serverNonce);
            p.putArray("accepts").add(mode == Mode.WRONG_PROTOCOL ? "nekkora-bridge/2" : "nekkora-bridge/1");
            p.putObject("studio").put("version", "test");
            send(ch);
            JsonNode hello = m.readTree(BridgeFrames.read(in));
            received.add(hello);
            if (!"bridge.hello".equals(hello.path("type").asText())) {
                handshakeResult = "CLIENT_" + hello.path("type").asText() + ":" + hello.at("/payload/error/code").asText();
                return;
            }
            String clientNonce = hello.at("/payload/clientNonce").asText();
            if (!BridgeAuth.matches(BridgeAuth.clientProof(secret, instanceId, serverNonce, clientNonce), hello.at("/payload/proof").asText())) {
                ObjectNode r = envelope("handshake", "bridge.rejected");
                r.putObject("payload").putObject("error").put("code", "AUTH_FAILED").put("message", "credencial inválida");
                send(r);
                handshakeResult = "REJECTED_AUTH";
                c.close();
                return;
            }
            ObjectNode w = envelope("handshake", "bridge.welcome");
            byte[] proofKey = mode == Mode.WRONG_SERVER_PROOF ? new byte[32] : secret;
            w.putObject("payload").put("proof", BridgeAuth.serverProof(proofKey, instanceId, serverNonce, clientNonce))
                    .put("heartbeatMs", heartbeatMs).put("maxFrameBytes", BridgeFrames.MAX_FRAME_BYTES);
            send(w);
            handshakeResult = "WELCOMED";
            while (true) {
                String raw = BridgeFrames.read(in);
                if (raw == null) {
                    break;
                }
                JsonNode msg = m.readTree(raw);
                all.add(msg);
                received.add(msg);
            }
        } catch (IOException e) {
            // conexão encerrada
        } finally {
            received.add(m.createObjectNode().put("kind", "closed"));
        }
    }

    ObjectNode envelope(String kind, String type) {
        ObjectNode n = m.createObjectNode();
        n.put("protocol", "nekkora-bridge/1");
        n.put("kind", kind);
        n.put("type", type);
        return n;
    }

    synchronized void send(ObjectNode msg) throws IOException {
        msg.put("seq", seq.incrementAndGet());
        BridgeFrames.write(out, msg.toString());
    }

    /** Envia um comando e devolve o id. */
    String command(String type, ObjectNode payload) throws IOException {
        String id = "c-" + seq.get() + "-" + System.nanoTime() % 100000;
        ObjectNode c = envelope("command", type);
        c.put("id", id);
        c.set("payload", payload == null ? m.createObjectNode() : payload);
        send(c);
        return id;
    }

    /** Próxima mensagem que satisfaz o filtro (descarta as demais). */
    JsonNode await(java.util.function.Predicate<JsonNode> filter, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) {
                return null;
            }
            JsonNode n = received.poll(left, TimeUnit.MILLISECONDS);
            if (n != null && filter.test(n)) {
                return n;
            }
        }
    }

    void dropConnection() throws IOException {
        SocketChannel c = client;
        if (c != null) {
            c.close();
        }
    }

    @Override
    public void close() throws IOException {
        dropConnection();
        server.close();
        Files.deleteIfExists(socket);
    }
}
