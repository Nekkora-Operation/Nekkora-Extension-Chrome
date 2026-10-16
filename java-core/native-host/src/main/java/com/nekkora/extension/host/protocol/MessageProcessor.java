package com.nekkora.extension.host.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nekkora.extension.host.commands.CommandException;
import com.nekkora.extension.host.commands.CommandHandler;
import com.nekkora.extension.host.commands.CommandRouter;
import com.nekkora.extension.host.json.JsonCodec;

import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Processa uma mensagem de entrada e produz os quadros de saída. Independente de transporte
 * (stdio do Native Messaging nos casos reais; chamadas diretas nos testes).
 *
 * <p>Envelope de requisição: {@code {"protocol","id","type","payload"}}.
 * Envelope de resposta: {@code {"protocol","id","type":"response","requestType","ok","payload","error","timing"}}.
 */
public final class MessageProcessor {

    private static final Logger LOG = Logger.getLogger(MessageProcessor.class.getName());

    private final CommandRouter router;
    private final JsonCodec json;
    private final ChunkCodec chunks;

    public MessageProcessor(CommandRouter router, JsonCodec json) {
        this.router = router;
        this.json = json;
        this.chunks = new ChunkCodec(json.mapper());
    }

    /** @return quadros a enviar (vazio quando a mensagem era um fragmento intermediário) */
    public List<String> process(String raw) {
        long t0 = System.nanoTime();
        String id = null;
        String type = null;
        try {
            JsonNode msg = json.parse(raw);
            if ("chunk".equals(msg.path("type").asText())) {
                String assembled = chunks.accept(msg, System.currentTimeMillis());
                if (assembled == null) {
                    return List.of();
                }
                msg = json.parse(assembled);
            }
            id = msg.hasNonNull("id") ? msg.get("id").asText() : null;
            type = msg.path("type").asText(null);
            String protocol = msg.path("protocol").asText(null);
            if (!ChunkCodec.PROTOCOL.equals(protocol)) {
                return error(id, type, "UNSUPPORTED_PROTOCOL", "Protocolo esperado " + ChunkCodec.PROTOCOL + ", recebido " + protocol, t0);
            }
            if (id == null || type == null) {
                return error(id, type, "BAD_REQUEST", "Envelope sem id ou type", t0);
            }
            Optional<CommandHandler> handler = router.find(type);
            if (handler.isEmpty()) {
                return error(id, type, "UNKNOWN_COMMAND", "Comando desconhecido: " + type, t0);
            }
            JsonNode payload = msg.hasNonNull("payload") ? msg.get("payload") : json.mapper().createObjectNode();
            Object result = handler.get().handle(payload);
            ObjectNode resp = envelope(id, type, true, t0);
            resp.set("payload", json.tree(result));
            resp.putNull("error");
            return chunks.split(resp.toString());
        } catch (ProtocolException e) {
            LOG.log(Level.WARNING, "Violação de protocolo", com.nekkora.extension.host.runtime.LogSafety.redacted(e));
            return error(id, type, e.code(), e.getMessage(), t0);
        } catch (CommandException e) {
            return error(id, type, e.code(), e.getMessage(), t0);
        } catch (IllegalArgumentException e) {
            LOG.log(Level.INFO, "Requisição inválida: " + type, com.nekkora.extension.host.runtime.LogSafety.redacted(e));
            return error(id, type, "BAD_REQUEST", e.getMessage(), t0);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            LOG.log(Level.WARNING, "JSON inválido", com.nekkora.extension.host.runtime.LogSafety.redacted(e));
            return error(id, type, "BAD_JSON", com.nekkora.extension.host.runtime.LogSafety.jsonProblem(e), t0);
        } catch (Exception | StackOverflowError e) {
            LOG.log(Level.SEVERE, "Falha ao processar " + type, com.nekkora.extension.host.runtime.LogSafety.redacted(e));
            return error(id, type, "INTERNAL_ERROR", e.getClass().getSimpleName() + " (mensagem omitida)", t0); // CHROME-SECURITY-001
        }
    }

    /** Resposta de sucesso no envelope padrão (usada pelo canal do Studio, fora do roteador do motor). */
    public List<String> success(String id, String type, Object payload) {
        ObjectNode resp = envelope(id, type, true, System.nanoTime());
        resp.set("payload", json.tree(payload));
        resp.putNull("error");
        return chunks.split(resp.toString());
    }

    /** Resposta de erro no envelope padrão (fragmento recusado pelo leitor, canal do Studio). */
    public List<String> failure(String id, String type, String code, String message) {
        return error(id, type, code, message, System.nanoTime());
    }

    private ObjectNode envelope(String id, String type, boolean ok, long t0) {
        ObjectNode resp = json.mapper().createObjectNode();
        resp.put("protocol", ChunkCodec.PROTOCOL);
        if (id == null) {
            resp.putNull("id");
        } else {
            resp.put("id", id);
        }
        resp.put("type", "response");
        resp.put("requestType", type);
        resp.put("ok", ok);
        resp.putObject("timing").put("hostMs", Math.round((System.nanoTime() - t0) / 10_000.0) / 100.0);
        return resp;
    }

    private List<String> error(String id, String type, String code, String message, long t0) {
        ObjectNode resp = envelope(id, type, false, t0);
        resp.putNull("payload");
        resp.putObject("error").put("code", code).put("message", message == null ? code : message);
        return chunks.split(resp.toString());
    }
}
