package com.nekkora.extension.it.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nekkora.extension.host.bridge.BridgeAuth;
import com.nekkora.extension.host.bridge.BridgeContract;
import com.nekkora.extension.host.bridge.BridgeFrames;

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
import java.nio.file.attribute.UserPrincipal;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * "Studio" de laboratório para a homologação no Chrome (STUDIO-002): o lado servidor REAL do nekkora-bridge/1 —
 * descoberta restrita ao usuário, desafio-resposta HMAC, comandos e eventos validados contra o contrato. Aceita
 * reconexões (novo processo do host após reinício do service worker).
 */
public final class StudioBridgeLab implements AutoCloseable {

    private final ObjectMapper m = new ObjectMapper();
    private final Path dir;
    private final byte[] secret = new byte[32];
    private final String instanceId;
    private final ServerSocketChannel server;
    private final Path socket;
    private final AtomicLong seq = new AtomicLong();
    private final AtomicInteger ids = new AtomicInteger();
    private final Map<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    /** Eventos recebidos (studio.recording.* e bridge.*), na ordem. */
    public final BlockingQueue<JsonNode> events = new LinkedBlockingQueue<>();
    public final List<JsonNode> allEvents = new CopyOnWriteArrayList<>();
    /** Mensagens fora do contrato recebidas do host (devem ficar vazias). */
    public final List<String> contractViolations = new CopyOnWriteArrayList<>();
    public final AtomicInteger connections = new AtomicInteger();
    private volatile SocketChannel client;
    private volatile OutputStream out;
    private volatile boolean closed;

    public StudioBridgeLab(Path dir) throws IOException {
        this.dir = dir;
        new SecureRandom().nextBytes(secret);
        this.instanceId = "studio-it-" + Long.toString(System.nanoTime(), 36);
        Files.createDirectories(dir);
        restrict(dir, true);
        socket = dir.resolve("studio.sock");
        Files.deleteIfExists(socket);
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(socket));
        ObjectNode d = m.createObjectNode();
        d.put("protocol", "nekkora-bridge/1");
        d.put("transport", "unix");
        d.put("address", socket.toString());
        d.put("instanceId", instanceId);
        d.put("pid", ProcessHandle.current().pid());
        d.put("secret", Base64.getUrlEncoder().withoutPadding().encodeToString(secret));
        Path f = dir.resolve("studio-bridge.json");
        Files.writeString(f, d.toString());
        restrict(f, false);
        Thread t = new Thread(this::acceptLoop, "it-studio-accept");
        t.setDaemon(true);
        t.start();
    }

    private static void restrict(Path p, boolean directory) throws IOException {
        AclFileAttributeView acl = Files.getFileAttributeView(p, AclFileAttributeView.class);
        if (acl == null) {
            return;
        }
        UserPrincipal owner = Files.getOwner(p);
        AclEntry.Builder b = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner).setPermissions(EnumSet.allOf(AclEntryPermission.class));
        if (directory) {
            b.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
        }
        acl.setAcl(List.of(b.build()));
    }

    public String instanceId() {
        return instanceId;
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                SocketChannel c = server.accept();
                Thread t = new Thread(() -> serve(c), "it-studio-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private void serve(SocketChannel c) {
        try {
            InputStream in = Channels.newInputStream(c);
            OutputStream o = Channels.newOutputStream(c);
            String serverNonce = BridgeAuth.nonce();
            ObjectNode ch = env("handshake", "bridge.challenge");
            ObjectNode p = ch.putObject("payload");
            p.put("instanceId", instanceId);
            p.put("serverNonce", serverNonce);
            p.putArray("accepts").add("nekkora-bridge/1");
            p.putObject("studio").put("version", "it");
            write(o, ch);
            JsonNode hello = m.readTree(BridgeFrames.read(in));
            String clientNonce = hello.at("/payload/clientNonce").asText();
            if (!BridgeAuth.matches(BridgeAuth.clientProof(secret, instanceId, serverNonce, clientNonce), hello.at("/payload/proof").asText())) {
                c.close();
                return;
            }
            ObjectNode w = env("handshake", "bridge.welcome");
            w.putObject("payload").put("proof", BridgeAuth.serverProof(secret, instanceId, serverNonce, clientNonce)).put("heartbeatMs", 5000)
                    .put("maxFrameBytes", BridgeFrames.MAX_FRAME_BYTES);
            write(o, w);
            SocketChannel previous = client;
            if (previous != null) {
                previous.close();
            }
            client = c;
            out = o;
            connections.incrementAndGet();
            Thread hb = new Thread(() -> heartbeat(c), "it-studio-heartbeat");
            hb.setDaemon(true);
            hb.start();
            while (true) {
                String raw = BridgeFrames.read(in);
                if (raw == null) {
                    break;
                }
                JsonNode msg = m.readTree(raw);
                if (!BridgeContract.valid(msg)) {
                    contractViolations.add(raw + " -> " + BridgeContract.problems(msg));
                }
                if ("response".equals(msg.path("kind").asText())) {
                    CompletableFuture<JsonNode> f = pending.remove(msg.path("id").asText());
                    if (f != null) {
                        f.complete(msg);
                    }
                } else if ("event".equals(msg.path("kind").asText())) {
                    events.add(msg);
                    allEvents.add(msg);
                }
            }
        } catch (IOException e) {
            // conexão encerrada
        } finally {
            if (client == c) {
                client = null;
                out = null;
            }
        }
    }

    private void heartbeat(SocketChannel c) {
        while (client == c && !closed) {
            try {
                Thread.sleep(2000);
                if (client == c) {
                    command("bridge.heartbeat", null);
                }
            } catch (InterruptedException | IOException e) {
                return;
            }
        }
    }

    private ObjectNode env(String kind, String type) {
        ObjectNode n = m.createObjectNode();
        n.put("protocol", "nekkora-bridge/1");
        n.put("kind", kind);
        n.put("type", type);
        return n;
    }

    private synchronized void write(OutputStream o, ObjectNode msg) throws IOException {
        msg.put("seq", seq.incrementAndGet());
        BridgeFrames.write(o, msg.toString());
    }

    public boolean connected() {
        return client != null;
    }

    public void awaitConnected(Duration timeout) {
        BrowserLab.waitUntil(timeout, this::connected, "extensão conectada ao Studio de laboratório");
    }

    /** Envia um comando; o futuro completa com a resposta (envelope completo). */
    public CompletableFuture<JsonNode> command(String type, ObjectNode payload) throws IOException {
        OutputStream o = out;
        if (o == null) {
            throw new IOException("sem conexão");
        }
        String id = "it-" + ids.incrementAndGet();
        CompletableFuture<JsonNode> f = new CompletableFuture<>();
        pending.put(id, f);
        ObjectNode c = env("command", type);
        c.put("id", id);
        c.set("payload", payload == null ? m.createObjectNode() : payload);
        write(o, c);
        return f;
    }

    /** Comando com espera da resposta. */
    public JsonNode call(String type, ObjectNode payload, Duration timeout) {
        try {
            return command(type, payload).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new AssertionError(type + " sem resposta: " + e, e);
        }
    }

    public JsonNode call(String type, ObjectNode payload) {
        return call(type, payload, Duration.ofSeconds(60));
    }

    public ObjectNode obj() {
        return m.createObjectNode();
    }

    public JsonNode awaitEvent(Predicate<JsonNode> f, Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (true) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) {
                return null;
            }
            JsonNode e = events.poll(left, TimeUnit.MILLISECONDS);
            if (e != null && f.test(e)) {
                return e;
            }
        }
    }

    /** Derruba a conexão (Studio travado/encerrado de forma abrupta): nada é enviado antes. */
    public void dropConnection() throws IOException {
        SocketChannel c = client;
        client = null;
        out = null;
        if (c != null) {
            c.close();
        }
    }

    @Override
    public void close() throws IOException {
        closed = true;
        dropConnection();
        server.close();
        Files.deleteIfExists(socket);
        Files.deleteIfExists(dir.resolve("studio-bridge.json"));
    }
}
