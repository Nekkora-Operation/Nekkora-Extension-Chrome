package com.nekkora.extension.host.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nekkora.extension.host.protocol.ChunkCodec;
import com.nekkora.extension.host.protocol.MessageProcessor;
import com.nekkora.extension.host.runtime.HostRuntime;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * StudioBridgeClient do host: conecta-se ao Studio local (descoberta → handshake mútuo → comandos) e faz a ponte
 * com a extensão. O host é iniciado pelo Chrome; o canal só é procurado depois que a extensão o habilita
 * ({@code studio.link.configure}), então a extensão continua funcionando exatamente como antes sem o Studio.
 *
 * <p>Fronteiras (D-STUDIO-002-06/09): nekkora-bridge/1 com o Studio, nekkora-nm/1 com a extensão. Cada comando do
 * Studio é validado pelo contrato, conferido contra a lista EXPLÍCITA e só então vira um {@code studio.command} para a
 * extensão — que é quem decide e confirma. O host não guarda estado de gravação.
 */
public final class StudioLink implements HostRuntime.StudioChannel {

    private static final Logger LOG = Logger.getLogger(StudioLink.class.getName());

    /** Estado do canal com o Studio (também exibido no painel da extensão). */
    public enum State { DISABLED, SEARCHING, CONNECTING, CONNECTED, INSECURE, INCOMPATIBLE, AUTH_FAILED }

    public static final String HOST_VERSION = "0.2.0";
    static final long HANDSHAKE_TIMEOUT_MS = 5000;
    static final long START_TIMEOUT_MS = 120_000;
    static final long COMMAND_TIMEOUT_MS = 30_000;

    private final ObjectMapper mapper;
    private final MessageProcessor responses;
    private final Consumer<String> toExtension;
    private final BooleanSupplier engineBusy;
    private final BridgeDiscovery discovery;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "nekkora-studio-link");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final AtomicLong commandSeq = new AtomicLong();
    private final Object writeLock = new Object();
    private final long pollMs;

    private volatile State state = State.DISABLED;
    private volatile String detail;
    private volatile Connection connection;
    private volatile ObjectNode extensionInfo;
    private ScheduledFuture<?> poller;
    private long lastRejectedModified = -1;

    private record Pending(String bridgeId, String type, ScheduledFuture<?> timeout) {
    }

    /** Conexão autenticada com uma instância do Studio. */
    private final class Connection {
        final SocketChannel channel;
        final InputStream in;
        final OutputStream out;
        final String instanceId;
        final AtomicLong seq = new AtomicLong();
        volatile long lastInbound = System.currentTimeMillis();
        volatile int heartbeatMs = 5000;
        ScheduledFuture<?> watchdog;

        Connection(SocketChannel channel, String instanceId) {
            this.channel = channel;
            this.in = Channels.newInputStream(channel);
            this.out = Channels.newOutputStream(channel);
            this.instanceId = instanceId;
        }

        void send(ObjectNode message) throws IOException {
            synchronized (writeLock) {
                message.put("seq", seq.incrementAndGet());
                BridgeFrames.write(out, message.toString());
            }
        }

        void close() {
            try {
                channel.close();
            } catch (IOException ignored) {
                // encerramento
            }
        }
    }

    public StudioLink(ObjectMapper mapper, MessageProcessor responses, Consumer<String> toExtension, BooleanSupplier engineBusy,
            BridgeDiscovery discovery, long pollMs) {
        this.mapper = mapper;
        this.responses = responses;
        this.toExtension = toExtension;
        this.engineBusy = engineBusy;
        this.discovery = discovery;
        this.pollMs = pollMs;
    }

    public State state() {
        return state;
    }

    // ------------------------------------------------------------------------------------ extensão → canal

    @Override
    public List<String> request(JsonNode msg) {
        String id = msg.path("id").asText(null);
        String type = msg.path("type").asText();
        if (id == null) {
            return responses.failure(null, type, "BAD_REQUEST", "Envelope sem id");
        }
        try {
            return switch (type) {
                case "studio.link.configure" -> {
                    JsonNode p = msg.path("payload");
                    ObjectNode ext = mapper.createObjectNode();
                    ext.put("version", limited(p.path("extension").path("version").asText("0.0.0"), 40));
                    ext.put("buildMode", "PRD".equals(p.path("extension").path("buildMode").asText()) ? "PRD" : "DEV");
                    extensionInfo = ext;
                    boolean enabled = p.path("enabled").asBoolean(false);
                    call(() -> configure(enabled));
                    yield responses.success(id, type, describe());
                }
                case "studio.link.status" -> responses.success(id, type, describe());
                default -> responses.failure(id, type, "UNKNOWN_COMMAND", "Comando desconhecido: " + type);
            };
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "studio.link falhou", e);
            return responses.failure(id, type, "INTERNAL_ERROR", e.getClass().getSimpleName());
        }
    }

    @Override
    public void reply(JsonNode msg) {
        Pending p = pending.remove(msg.path("id").asText(""));
        if (p == null) {
            return; // resposta tardia (comando expirado) ou de outra conexão
        }
        p.timeout().cancel(false);
        Connection c = connection;
        if (c == null) {
            return;
        }
        boolean ok = msg.path("ok").asBoolean(false);
        ObjectNode resp = envelope("response", p.type());
        resp.put("id", p.bridgeId());
        resp.put("ok", ok);
        if (ok) {
            resp.set("payload", msg.path("payload").isObject() ? msg.get("payload") : mapper.createObjectNode());
            resp.putNull("error");
        } else {
            resp.putNull("payload");
            resp.set("error", errorNode(msg.path("error")));
        }
        sendChecked(c, resp, p.bridgeId(), p.type());
    }

    @Override
    public void event(JsonNode msg) {
        String type = msg.path("event").asText();
        Connection c = connection;
        if (c == null || !BridgeContract.FORWARDED_EVENTS.contains(type)) {
            if (!BridgeContract.FORWARDED_EVENTS.contains(type)) {
                LOG.warning("Evento da extensão fora da lista do Bridge ignorado: " + type);
            }
            return;
        }
        if (!c.instanceId.equals(msg.path("controller").path("instanceId").asText(c.instanceId))) {
            return; // evento de gravação de outro controlador
        }
        ObjectNode ev = envelope("event", type);
        ev.set("payload", msg.path("payload"));
        ev.put("seq", 1);
        if (!BridgeContract.valid(ev)) {
            LOG.warning("Evento fora do contrato não repassado (" + type + "): " + BridgeContract.problems(ev));
            return;
        }
        ev.remove("seq");
        send(c, ev);
    }

    @Override
    public void shutdown(String reason) {
        call(() -> {
            Connection c = connection;
            if (c != null) {
                ObjectNode ev = envelope("event", "bridge.disconnected");
                ev.putObject("payload").put("reason", reason);
                send(c, ev);
                disconnect(c, State.DISABLED, reason);
            }
            stopPolling();
            state = State.DISABLED;
        });
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------------------------ ciclo do canal

    private void configure(boolean enabled) {
        if (!enabled) {
            Connection c = connection;
            if (c != null) {
                ObjectNode ev = envelope("event", "bridge.disconnected");
                ev.putObject("payload").put("reason", "LINK_DISABLED");
                send(c, ev);
                disconnect(c, State.DISABLED, "LINK_DISABLED");
            }
            stopPolling();
            setState(State.DISABLED, null);
            return;
        }
        if (state == State.DISABLED) {
            setState(State.SEARCHING, null);
        }
        if (poller == null) {
            poller = scheduler.scheduleWithFixedDelay(this::poll, 0, pollMs, TimeUnit.MILLISECONDS);
        }
    }

    private void stopPolling() {
        if (poller != null) {
            poller.cancel(false);
            poller = null;
        }
    }

    /** Executado no agendador: procura o Studio quando não há conexão. */
    private void poll() {
        if (connection != null || state == State.DISABLED) {
            return;
        }
        BridgeDiscovery.Endpoint ep;
        try {
            ep = discovery.read();
        } catch (BridgeException e) {
            setState("AUTH_FAILED".equals(e.code()) ? State.INSECURE : State.INCOMPATIBLE, e.code());
            return;
        } catch (IOException | RuntimeException e) {
            setState(State.SEARCHING, "DISCOVERY_UNREADABLE");
            return;
        }
        if (ep == null) {
            setState(State.SEARCHING, null);
            return;
        }
        if (ep.modifiedAt() == lastRejectedModified) {
            return; // a mesma publicação já foi recusada: espera o Studio publicar de novo
        }
        if (ep.pid() > 0 && ProcessHandle.of(ep.pid()).map(h -> !h.isAlive()).orElse(true)) {
            setState(State.SEARCHING, "STUDIO_NOT_RUNNING"); // arquivo de uma instância que já terminou
            return;
        }
        setState(State.CONNECTING, null);
        try {
            connect(ep);
        } catch (BridgeException e) {
            lastRejectedModified = ep.modifiedAt();
            setState(switch (e.code()) {
                case "PROTOCOL_UNSUPPORTED" -> State.INCOMPATIBLE;
                case "AUTH_FAILED" -> State.AUTH_FAILED;
                default -> State.SEARCHING;
            }, e.code());
            LOG.warning("Conexão com o Studio recusada: " + e.code());
        } catch (IOException | RuntimeException e) {
            setState(State.SEARCHING, "CONNECT_FAILED");
        }
    }

    private void connect(BridgeDiscovery.Endpoint ep) throws IOException {
        SocketChannel ch;
        if (ep.unix()) {
            ch = SocketChannel.open(StandardProtocolFamily.UNIX);
            ch.connect(UnixDomainSocketAddress.of(ep.address()));
        } else {
            int colon = ep.address().lastIndexOf(':');
            ch = SocketChannel.open(new InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), Integer.parseInt(ep.address().substring(colon + 1))));
        }
        Connection c = new Connection(ch, ep.instanceId());
        // O handshake bloqueia esta thread: o prazo é vigiado por OUTRA thread, que fecha o canal se ele estourar.
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.CompletableFuture.delayedExecutor(HANDSHAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS).execute(() -> {
            if (!done.get()) {
                c.close();
            }
        });
        try {
            handshake(c, ep);
        } catch (java.nio.channels.AsynchronousCloseException e) {
            throw new BridgeException("HANDSHAKE_TIMEOUT", "handshake não concluído em " + HANDSHAKE_TIMEOUT_MS + " ms");
        } catch (IOException | RuntimeException e) {
            c.close();
            if (e instanceof IOException io) {
                throw io;
            }
            throw new BridgeException("BAD_REQUEST", String.valueOf(e.getMessage()));
        } finally {
            done.set(true);
        }
        connection = c;
        setState(State.CONNECTED, null);
        Thread reader = new Thread(() -> readLoop(c), "nekkora-studio-reader");
        reader.setDaemon(true);
        reader.start();
        c.watchdog = scheduler.scheduleWithFixedDelay(() -> {
            if (System.currentTimeMillis() - c.lastInbound > 3L * c.heartbeatMs) {
                LOG.warning("Studio sem sinal há " + (System.currentTimeMillis() - c.lastInbound) + " ms: canal encerrado");
                disconnect(c, State.SEARCHING, "HEARTBEAT_LOST");
            }
        }, 1000, 1000, TimeUnit.MILLISECONDS);
        ObjectNode ev = envelope("event", "bridge.connected");
        ev.set("payload", statusPayload());
        send(c, ev);
    }

    private void handshake(Connection c, BridgeDiscovery.Endpoint ep) throws IOException {
        JsonNode challenge = readFrame(c);
        if (challenge == null || !"bridge.challenge".equals(challenge.path("type").asText())) {
            throw new BridgeException("BAD_REQUEST", "desafio ausente");
        }
        if (!BridgeAuth.PROTOCOL.equals(challenge.path("protocol").asText()) || !accepts(challenge)) {
            reject(c, "PROTOCOL_UNSUPPORTED", "O host fala " + BridgeAuth.PROTOCOL + ".");
            throw new BridgeException("PROTOCOL_UNSUPPORTED", "Studio com protocolo incompatível");
        }
        if (!BridgeContract.valid(challenge)) {
            throw new BridgeException("BAD_REQUEST", "desafio fora do contrato");
        }
        String instanceId = challenge.at("/payload/instanceId").asText();
        if (!instanceId.equals(ep.instanceId())) {
            throw new BridgeException("AUTH_FAILED", "instância diferente da publicada no arquivo de descoberta");
        }
        String serverNonce = challenge.at("/payload/serverNonce").asText();
        String clientNonce = BridgeAuth.nonce();
        ObjectNode hello = envelope("handshake", "bridge.hello");
        ObjectNode p = hello.putObject("payload");
        p.put("instanceId", instanceId);
        p.put("clientNonce", clientNonce);
        p.put("proof", BridgeAuth.clientProof(ep.secret(), instanceId, serverNonce, clientNonce));
        ObjectNode client = p.putObject("client");
        client.put("hostVersion", HOST_VERSION);
        client.put("nativeProtocol", ChunkCodec.PROTOCOL);
        client.set("extension", extensionInfo != null ? extensionInfo.deepCopy() : mapper.createObjectNode().put("version", "0.0.0").put("buildMode", "DEV"));
        c.send(hello);
        JsonNode answer = readFrame(c);
        if (answer == null) {
            throw new BridgeException("AUTH_FAILED", "Studio encerrou o handshake");
        }
        if ("bridge.rejected".equals(answer.path("type").asText())) {
            throw new BridgeException(answer.at("/payload/error/code").asText("AUTH_FAILED"), "Studio recusou: " + answer.at("/payload/error/code").asText());
        }
        if (!"bridge.welcome".equals(answer.path("type").asText()) || !BridgeContract.valid(answer)) {
            throw new BridgeException("BAD_REQUEST", "resposta de handshake inválida");
        }
        String expected = BridgeAuth.serverProof(ep.secret(), instanceId, serverNonce, clientNonce);
        if (!BridgeAuth.matches(expected, answer.at("/payload/proof").asText())) {
            throw new BridgeException("AUTH_FAILED", "o Studio não comprovou conhecer o segredo da instância");
        }
        c.heartbeatMs = answer.at("/payload/heartbeatMs").asInt(5000);
    }

    private static boolean accepts(JsonNode challenge) {
        for (JsonNode v : challenge.at("/payload/accepts")) {
            if (BridgeAuth.PROTOCOL.equals(v.asText())) {
                return true;
            }
        }
        return false;
    }

    private void reject(Connection c, String code, String message) {
        ObjectNode r = envelope("handshake", "bridge.rejected");
        r.putObject("payload").putObject("error").put("code", code).put("message", message);
        try {
            c.send(r);
        } catch (IOException ignored) {
            // o Studio já pode ter fechado
        }
    }

    private JsonNode readFrame(Connection c) throws IOException {
        String raw = BridgeFrames.read(c.in);
        if (raw == null) {
            return null;
        }
        c.lastInbound = System.currentTimeMillis();
        return mapper.readTree(raw);
    }

    private void readLoop(Connection c) {
        try {
            while (true) {
                JsonNode msg = readFrame(c);
                if (msg == null) {
                    break;
                }
                call(() -> onStudioMessage(c, msg));
            }
        } catch (IOException e) {
            LOG.fine("Canal com o Studio encerrado: " + e.getMessage());
        }
        call(() -> disconnect(c, State.SEARCHING, "STUDIO_DISCONNECTED"));
    }

    /** Comando do Studio (executado no agendador do canal: serializado). */
    private void onStudioMessage(Connection c, JsonNode msg) {
        if (connection != c) {
            return;
        }
        String kind = msg.path("kind").asText();
        String type = msg.path("type").asText();
        String id = msg.path("id").asText(null);
        if (!"command".equals(kind) || id == null || !id.matches("[A-Za-z0-9._:-]{1,80}")) {
            LOG.warning("Mensagem do Studio ignorada (kind=" + kind + ")");
            return;
        }
        if (!BridgeContract.LOCAL_COMMANDS.contains(type) && !BridgeContract.FORWARDED_COMMANDS.contains(type)) {
            respondError(c, id, type, "UNKNOWN_COMMAND", "Comando não permitido pelo Bridge.");
            return;
        }
        if (!BridgeContract.valid(msg)) {
            respondError(c, id, type, "BAD_REQUEST", "Comando fora do contrato nekkora-bridge/1.");
            return;
        }
        switch (type) {
            case "bridge.status" -> respond(c, id, type, statusPayload());
            case "bridge.heartbeat" -> respond(c, id, type, mapper.createObjectNode().put("at", Instant.now().toString()));
            default -> forward(c, id, type, msg.path("payload"));
        }
    }

    private void forward(Connection c, String bridgeId, String type, JsonNode payload) {
        String nmId = "sc-" + commandSeq.incrementAndGet();
        long timeout = "studio.recording.start".equals(type) ? START_TIMEOUT_MS : COMMAND_TIMEOUT_MS;
        ScheduledFuture<?> t = scheduler.schedule(() -> {
            Pending p = pending.remove(nmId);
            if (p != null && connection == c) {
                respondError(c, bridgeId, type, "TIMEOUT", "A extensão não respondeu a tempo.");
            }
        }, timeout, TimeUnit.MILLISECONDS);
        pending.put(nmId, new Pending(bridgeId, type, t));
        ObjectNode cmd = mapper.createObjectNode();
        cmd.put("protocol", ChunkCodec.PROTOCOL);
        cmd.put("type", "studio.command");
        cmd.put("id", nmId);
        cmd.put("command", type);
        cmd.set("payload", payload);
        cmd.putObject("controller").put("instanceId", c.instanceId);
        toExtension.accept(cmd.toString());
    }

    private void respond(Connection c, String id, String type, JsonNode payload) {
        ObjectNode resp = envelope("response", type);
        resp.put("id", id);
        resp.put("ok", true);
        resp.set("payload", payload);
        resp.putNull("error");
        sendChecked(c, resp, id, type);
    }

    private void respondError(Connection c, String id, String type, String code, String message) {
        String echoed = type != null && type.matches("[a-z][A-Za-z0-9._-]{0,79}") ? type : "invalid";
        ObjectNode resp = envelope("response", echoed);
        resp.put("id", id);
        resp.put("ok", false);
        resp.putNull("payload");
        resp.putObject("error").put("code", code).put("message", message);
        send(c, resp);
    }

    /** Envia uma resposta somente se estiver dentro do contrato; senão devolve INTERNAL_ERROR (nada vaza). */
    private void sendChecked(Connection c, ObjectNode resp, String id, String type) {
        resp.put("seq", 1);
        boolean ok = BridgeContract.valid(resp);
        resp.remove("seq");
        if (!ok) {
            LOG.warning("Resposta fora do contrato não repassada (" + type + "): " + BridgeContract.problems(resp));
            respondError(c, id, type, "INTERNAL_ERROR", "Resposta da extensão fora do contrato.");
            return;
        }
        send(c, resp);
    }

    private void send(Connection c, ObjectNode message) {
        try {
            c.send(message);
        } catch (IOException e) {
            call(() -> disconnect(c, State.SEARCHING, "WRITE_FAILED"));
        }
    }

    private void disconnect(Connection c, State next, String reason) {
        if (connection != c) {
            c.close();
            return;
        }
        connection = null;
        if (c.watchdog != null) {
            c.watchdog.cancel(false);
        }
        c.close();
        for (Pending p : pending.values()) {
            p.timeout().cancel(false);
        }
        pending.clear();
        setState(state == State.DISABLED ? State.DISABLED : next, reason);
        LOG.info("Canal com o Studio encerrado: " + reason);
    }

    // ------------------------------------------------------------------------------------ apoio

    private ObjectNode statusPayload() {
        ObjectNode s = mapper.createObjectNode();
        s.put("link", "CONNECTED");
        s.set("extension", extensionInfo != null ? extensionInfo.deepCopy() : mapper.createObjectNode().put("version", "0.0.0").put("buildMode", "DEV"));
        s.putObject("host").put("version", HOST_VERSION).put("engineBusy", engineBusy.getAsBoolean());
        return s;
    }

    private ObjectNode envelope(String kind, String type) {
        ObjectNode n = mapper.createObjectNode();
        n.put("protocol", BridgeAuth.PROTOCOL);
        n.put("kind", kind);
        n.put("type", type);
        return n;
    }

    private ObjectNode errorNode(JsonNode e) {
        ObjectNode out = mapper.createObjectNode();
        out.put("code", e.path("code").asText("INTERNAL_ERROR"));
        out.put("message", limited(e.path("message").asText("Falha na extensão."), 500));
        if (e.hasNonNull("reason")) {
            out.put("reason", limited(e.path("reason").asText(), 60));
        }
        if (e.has("retryable")) {
            out.put("retryable", e.path("retryable").asBoolean());
        }
        return out;
    }

    private static String limited(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    private Map<String, Object> describe() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("state", state.name());
        out.put("detail", detail);
        Connection c = connection;
        out.put("controller", c == null ? null : Map.of("instanceId", c.instanceId));
        return out;
    }

    private void setState(State next, String why) {
        boolean changed = next != state || !java.util.Objects.equals(why, detail);
        state = next;
        detail = why;
        if (changed) {
            ObjectNode n = mapper.createObjectNode();
            n.put("protocol", ChunkCodec.PROTOCOL);
            n.put("type", "studio.link");
            n.put("state", next.name());
            if (why == null) {
                n.putNull("detail");
            } else {
                n.put("detail", why);
            }
            Connection c = connection;
            if (c == null) {
                n.putNull("controller");
            } else {
                n.putObject("controller").put("instanceId", c.instanceId);
            }
            toExtension.accept(n.toString());
        }
    }

    /** Executa no agendador do canal (todas as mudanças de estado são serializadas ali). */
    private void call(Runnable r) {
        if (scheduler.isShutdown()) {
            return;
        }
        try {
            scheduler.execute(() -> {
                try {
                    r.run();
                } catch (RuntimeException e) {
                    LOG.log(Level.SEVERE, "Falha no canal do Studio", e);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // encerrando
        }
    }
}
