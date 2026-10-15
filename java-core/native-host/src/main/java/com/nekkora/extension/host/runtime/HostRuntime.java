package com.nekkora.extension.host.runtime;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekkora.extension.host.protocol.ChunkCodec;
import com.nekkora.extension.host.protocol.MessageProcessor;
import com.nekkora.extension.host.protocol.NativeMessageCodec;
import com.nekkora.extension.host.protocol.ProtocolException;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Coordenador do processo do host (D-STUDIO-002-05): separa leitura, processamento, escrita e o canal do Studio.
 *
 * <pre>
 *   stdin ─► leitor (esta thread) ─┬─► MOTOR (1 thread, ordem preservada) ─► MessageProcessor ─┐
 *                                  └─► CONTROLE (1 thread) ─► StudioChannel ──────────────────┤
 *                                                                                              ▼
 *                                                       ChromeOutboundWriter (único escritor, transmissões atômicas)
 * </pre>
 *
 * <ul>
 *   <li>O leitor nunca executa trabalho: só remonta fragmentos, identifica o tipo e despacha. Uma exportação longa
 *       ocupa o MOTOR, enquanto o leitor continua recebendo e o CONTROLE continua respondendo.</li>
 *   <li>O MOTOR é uma única thread: as operações sobre o estado do motor (sessões, capturas, exportações) continuam
 *       estritamente sequenciais, como antes — prioridade de controle não significa mutação concorrente.</li>
 *   <li>Mensagens do protocolo do Studio ({@code studio.*}) nunca chegam ao roteador de comandos do motor.</li>
 * </ul>
 */
public final class HostRuntime {

    private static final Logger LOG = Logger.getLogger(HostRuntime.class.getName());

    /** Canal do Studio (implementado por {@code StudioLink}); recebe somente as mensagens {@code studio.*}. */
    public interface StudioChannel {
        /** Requisição da extensão para o canal ({@code studio.link.*}); devolve os quadros da resposta. */
        List<String> request(JsonNode message);

        /** Resposta da extensão a um comando que o host enviou ({@code studio.reply}). */
        void reply(JsonNode message);

        /** Evento da extensão para o Studio ({@code studio.event}). */
        void event(JsonNode message);

        /** O Chrome encerrou a conexão: avisar o Studio e fechar o canal. */
        void shutdown(String reason);
    }

    private static final java.util.Set<String> STUDIO_REQUESTS = java.util.Set.of("studio.link.configure", "studio.link.status");

    private final NativeMessageCodec codec;
    private final MessageProcessor processor;
    private final ChromeOutboundWriter writer;
    private final ExecutorService engine;
    private final ExecutorService control;
    private final ChunkCodec chunks;
    private final ObjectMapper mapper;
    private final JsonFactory factory;
    private final AtomicInteger engineRunning = new AtomicInteger();
    private final AtomicLong engineStartedAt = new AtomicLong();
    private StudioChannel studio;

    public HostRuntime(NativeMessageCodec codec, MessageProcessor processor, ObjectMapper mapper) {
        this.codec = codec;
        this.processor = processor;
        this.mapper = mapper;
        this.factory = mapper.getFactory();
        this.chunks = new ChunkCodec(mapper);
        this.writer = new ChromeOutboundWriter(codec);
        this.engine = Executors.newSingleThreadExecutor(r -> daemon(r, "nekkora-engine"));
        this.control = Executors.newSingleThreadExecutor(r -> daemon(r, "nekkora-control"));
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    public void attachStudio(StudioChannel channel) {
        this.studio = channel;
    }

    public ChromeOutboundWriter writer() {
        return writer;
    }

    /** Envia ao Chrome uma mensagem iniciada pelo host (comando do Studio, aviso do canal): fila de CONTROLE. */
    public void sendToExtension(String json) {
        writer.submit(List.of(json), ChromeOutboundWriter.Lane.CONTROL);
    }

    /** O motor está executando uma operação (ex.: exportação) há pelo menos {@code minMs}. */
    public boolean engineBusy(long minMs) {
        return engineRunning.get() > 0 && System.currentTimeMillis() - engineStartedAt.get() >= minMs;
    }

    /** Laço do leitor: até o Chrome fechar o stdin. Devolve o número de mensagens recebidas. */
    public int run() {
        int received = 0;
        while (true) {
            String raw;
            try {
                raw = codec.read();
            } catch (IOException e) {
                LOG.log(Level.SEVERE, "Fluxo de entrada inválido; encerrando", e);
                break;
            }
            if (raw == null) {
                LOG.info("Chrome encerrou a conexão após " + received + " mensagem(ns)");
                break;
            }
            received++;
            dispatch(raw);
        }
        shutdown();
        return received;
    }

    /** Classifica e despacha uma mensagem recebida (público para testes de concorrência). */
    public void dispatch(String raw) {
        String type = peekType(raw);
        if ("chunk".equals(type)) {
            String assembled;
            try {
                assembled = chunks.accept(mapper.readTree(raw), System.currentTimeMillis());
            } catch (ProtocolException e) {
                LOG.log(Level.WARNING, "Fragmento recusado", e);
                writer.submit(processor.failure(null, null, e.code(), e.getMessage()), ChromeOutboundWriter.Lane.ENGINE);
                return;
            } catch (IOException e) {
                writer.submit(processor.failure(null, null, "BAD_JSON", e.getMessage()), ChromeOutboundWriter.Lane.ENGINE);
                return;
            }
            if (assembled == null) {
                return;
            }
            raw = assembled;
            type = peekType(raw);
        }
        if (type != null && type.startsWith("studio.")) {
            routeStudio(type, raw);
            return;
        }
        final String message = raw;
        engine.execute(() -> {
            engineStartedAt.set(System.currentTimeMillis());
            engineRunning.incrementAndGet();
            try {
                writer.submit(processor.process(message), ChromeOutboundWriter.Lane.ENGINE);
            } finally {
                engineRunning.decrementAndGet();
            }
        });
    }

    private void routeStudio(String type, String raw) {
        JsonNode msg;
        try {
            msg = mapper.readTree(raw);
        } catch (IOException e) {
            writer.submit(processor.failure(null, type, "BAD_JSON", e.getMessage()), ChromeOutboundWriter.Lane.CONTROL);
            return;
        }
        StudioChannel channel = studio;
        if (!ChunkCodec.PROTOCOL.equals(msg.path("protocol").asText())) {
            writer.submit(processor.failure(text(msg, "id"), type, "UNSUPPORTED_PROTOCOL", "Protocolo esperado " + ChunkCodec.PROTOCOL),
                    ChromeOutboundWriter.Lane.CONTROL);
            return;
        }
        control.execute(() -> {
            try {
                if (STUDIO_REQUESTS.contains(type)) {
                    if (channel == null) {
                        writer.submit(processor.failure(text(msg, "id"), type, "UNKNOWN_COMMAND", "Canal do Studio indisponível"),
                                ChromeOutboundWriter.Lane.CONTROL);
                    } else {
                        writer.submit(channel.request(msg), ChromeOutboundWriter.Lane.CONTROL);
                    }
                } else if ("studio.reply".equals(type) && channel != null) {
                    channel.reply(msg);
                } else if ("studio.event".equals(type) && channel != null) {
                    channel.event(msg);
                } else {
                    // Tipo studio.* desconhecido: nunca é executado nem repassado.
                    LOG.warning("Mensagem do Studio desconhecida ignorada: " + type);
                }
            } catch (RuntimeException e) {
                LOG.log(Level.SEVERE, "Falha no canal do Studio (" + type + ")", e);
            }
        });
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    /** Lê somente o campo {@code type} de primeiro nível (sem montar a árvore de mensagens grandes). */
    String peekType(String raw) {
        try (JsonParser p = factory.createParser(raw)) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                return null;
            }
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String name = p.currentName();
                JsonToken value = p.nextToken();
                if ("type".equals(name)) {
                    return value == JsonToken.VALUE_STRING ? p.getText() : null;
                }
                p.skipChildren();
            }
            return null;
        } catch (IOException e) {
            return null; // JSON inválido: o MessageProcessor responde BAD_JSON na ordem normal
        }
    }

    /** Encerramento: o canal do Studio é avisado, o motor conclui a operação em andamento e as filas esvaziam. */
    public void shutdown() {
        StudioChannel channel = studio;
        if (channel != null) {
            try {
                channel.shutdown("CHROME_DISCONNECTED");
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Falha ao encerrar o canal do Studio", e);
            }
        }
        control.shutdown();
        engine.shutdown();
        try {
            engine.awaitTermination(30, TimeUnit.SECONDS);
            control.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        writer.awaitDrained(2000);
        writer.close();
    }
}
