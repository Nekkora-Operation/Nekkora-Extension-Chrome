package com.nekkora.extension.host.commands;

import com.nekkora.extension.host.protocol.ChunkCodec;
import com.nekkora.extension.host.protocol.NativeMessageCodec;
import com.nekkora.extension.host.session.HostSession;

import java.util.LinkedHashMap;
import java.util.Map;

/** Diagnóstico e testes de transporte. */
public final class HostCommands implements CommandRouter.CommandGroup {

    public static final String HOST_VERSION = "0.1.0";

    private final HostSession session;
    private final String dataDir;

    public HostCommands(HostSession session, String dataDir) {
        this.session = session;
        this.dataDir = dataDir;
    }

    @Override
    public void register(CommandRouter router) {
        router.register("host.ping", p -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("protocol", ChunkCodec.PROTOCOL);
            out.put("hostVersion", HOST_VERSION);
            out.put("java", System.getProperty("java.version"));
            out.put("pid", ProcessHandle.current().pid());
            out.put("uptimeMs", session.uptimeMs());
            out.put("tabs", session.tabCount());
            out.put("targets", session.targets().all().size());
            out.put("dataDir", dataDir);
            Runtime rt = Runtime.getRuntime();
            out.put("heapUsedMb", Math.round((rt.totalMemory() - rt.freeMemory()) / 1048576.0 * 10) / 10.0);
            out.put("heapMaxMb", Math.round(rt.maxMemory() / 1048576.0));
            out.put("commands", router.types());
            out.put("limits", Map.of(
                    "maxOutboundBytes", NativeMessageCodec.MAX_OUTBOUND_BYTES,
                    "maxInboundBytes", NativeMessageCodec.MAX_INBOUND_BYTES,
                    "singleMessageLimit", ChunkCodec.SINGLE_MESSAGE_LIMIT,
                    "chunkChars", ChunkCodec.CHUNK_CHARS));
            return out;
        });
        router.register("host.echo", p -> {
            String data = p.path("data").asText("");
            return Map.of("length", data.length(), "data", data);
        });
        router.register("host.generate", p -> {
            int n = Math.min(Math.max(p.path("chars").asInt(0), 0), 32 * 1024 * 1024);
            StringBuilder sb = new StringBuilder(n);
            String pattern = "nekkora-ção-";
            while (sb.length() < n) {
                sb.append(pattern, 0, Math.min(pattern.length(), n - sb.length()));
            }
            return Map.of("length", sb.length(), "data", sb.toString());
        });
        // Operação longa CONTROLADA (teste de responsividade S002-022): ocupa o motor por até 60 s.
        router.register("host.hold", p -> {
            long ms = Math.min(Math.max(p.path("ms").asLong(0), 0), 60_000);
            long t0 = System.nanoTime();
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Map.of("heldMs", Math.round((System.nanoTime() - t0) / 1e6));
        });
        router.register("host.fail", p -> {
            throw new CommandException("TEST_FAILURE", "Falha solicitada para teste de tratamento de erro");
        });
    }
}
