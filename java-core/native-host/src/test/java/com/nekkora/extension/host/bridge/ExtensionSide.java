package com.nekkora.extension.host.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nekkora.extension.host.NativeHost;
import com.nekkora.extension.host.persistence.DataDirectory;
import com.nekkora.extension.host.protocol.NativeMessageCodec;
import com.nekkora.extension.host.runtime.HostRuntime;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.Pipe;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * Faz o papel do Chrome/extensão: o {@link HostRuntime} REAL (o mesmo do {@code NativeHostMain}) roda com pipes no
 * lugar de stdin/stdout. Cada quadro recebido do host é registrado com o instante de chegada.
 */
final class ExtensionSide implements AutoCloseable {

    record Frame(String raw, JsonNode json, long atNanos) {
    }

    final ObjectMapper m = new ObjectMapper();
    final HostRuntime runtime;
    final StudioLink link;
    final List<Frame> all = new CopyOnWriteArrayList<>();
    final BlockingQueue<Frame> frames = new LinkedBlockingQueue<>();
    private final OutputStream toHost;
    private final Thread hostThread;
    private final AtomicInteger ids = new AtomicInteger();

    ExtensionSide(Path data, Path bridgeDir) throws IOException {
        Pipe in = Pipe.open();
        Pipe out = Pipe.open();
        toHost = Channels.newOutputStream(in.sink());
        InputStream fromHost = Channels.newInputStream(out.source());
        NativeHost host = new NativeHost(new DataDirectory(data));
        NativeMessageCodec codec = new NativeMessageCodec(Channels.newInputStream(in.source()), Channels.newOutputStream(out.sink()));
        runtime = new HostRuntime(codec, host.processor(), m);
        link = new StudioLink(m, host.processor(), runtime::sendToExtension, () -> runtime.engineBusy(1000), new BridgeDiscovery(bridgeDir, m), 100);
        runtime.attachStudio(link);
        hostThread = new Thread(runtime::run, "host-under-test");
        hostThread.setDaemon(true);
        hostThread.start();
        Thread reader = new Thread(() -> {
            NativeMessageCodec r = new NativeMessageCodec(fromHost, OutputStream.nullOutputStream());
            try {
                String raw;
                while ((raw = r.read()) != null) {
                    Frame f = new Frame(raw, m.readTree(raw), System.nanoTime());
                    all.add(f);
                    frames.add(f);
                }
            } catch (IOException e) {
                // fim
            }
        }, "extension-reader");
        reader.setDaemon(true);
        reader.start();
    }

    synchronized void write(String json) throws IOException {
        toHost.write(NativeMessageCodec.frame(json));
        toHost.flush();
    }

    /** Requisição nekkora-nm/1 (devolve o id). */
    String request(String type, ObjectNode payload) throws IOException {
        String id = "x" + ids.incrementAndGet();
        ObjectNode r = m.createObjectNode();
        r.put("protocol", "nekkora-nm/1");
        r.put("id", id);
        r.put("type", type);
        r.set("payload", payload == null ? m.createObjectNode() : payload);
        write(r.toString());
        return id;
    }

    void enableLink() throws IOException {
        ObjectNode p = m.createObjectNode();
        p.put("enabled", true);
        p.putObject("extension").put("version", "0.4.0").put("buildMode", "DEV");
        request("studio.link.configure", p);
    }

    Frame await(Predicate<JsonNode> filter, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) {
                return null;
            }
            Frame f = frames.poll(left, TimeUnit.MILLISECONDS);
            if (f != null && filter.test(f.json())) {
                return f;
            }
        }
    }

    Frame awaitLink(String state, long timeoutMs) throws InterruptedException {
        return await(n -> "studio.link".equals(n.path("type").asText()) && state.equals(n.path("state").asText()), timeoutMs);
    }

    @Override
    public void close() throws IOException {
        toHost.close(); // EOF: o host encerra como quando o Chrome fecha a porta
        try {
            hostThread.join(10_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
