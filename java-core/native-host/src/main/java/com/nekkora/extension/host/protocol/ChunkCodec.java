package com.nekkora.extension.host.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fragmentação de mensagens grandes. Uma mensagem serializada que exceda o limite é enviada como
 * uma sequência de envelopes {@code {"type":"chunk","transferId","index","count","data"}}, onde
 * {@code data} são fatias do JSON original. O receptor remonta e só então interpreta.
 */
public final class ChunkCodec {

    public static final String PROTOCOL = "nekkora-nm/1";
    /** Envia em mensagem única até este tamanho (UTF-8). Margem abaixo de 1 MiB. */
    public static final int SINGLE_MESSAGE_LIMIT = 900 * 1024;
    /** Caracteres por fragmento: mesmo com escapes JSON o fragmento permanece abaixo de 1 MiB. */
    public static final int CHUNK_CHARS = 120_000;
    /** Tamanho máximo de uma mensagem remontada. */
    public static final long MAX_REASSEMBLED_CHARS = 64L * 1024 * 1024;
    public static final int MAX_PENDING_TRANSFERS = 16;
    public static final long TRANSFER_TIMEOUT_MS = 120_000;

    private final ObjectMapper mapper;
    private final AtomicLong transfers = new AtomicLong();
    private final Map<String, Pending> pending = new HashMap<>();

    private static final class Pending {
        final String[] parts;
        final long startedAt;
        int received;
        long chars;

        Pending(int count, long startedAt) {
            this.parts = new String[count];
            this.startedAt = startedAt;
        }
    }

    public ChunkCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Divide uma mensagem serializada em quadros que respeitam o limite do Chrome. */
    public List<String> split(String json) {
        if (json.getBytes(StandardCharsets.UTF_8).length <= SINGLE_MESSAGE_LIMIT) {
            return List.of(json);
        }
        List<String> pieces = new ArrayList<>();
        int pos = 0;
        while (pos < json.length()) {
            int end = Math.min(json.length(), pos + CHUNK_CHARS);
            // Não separa pares substitutos (caracteres fora do BMP) entre fragmentos.
            if (end < json.length() && Character.isHighSurrogate(json.charAt(end - 1))) {
                end = end - 1 > pos ? end - 1 : end + 1; // mantém o par junto e sempre avança
            }
            pieces.add(json.substring(pos, end));
            pos = end;
        }
        String transferId = "h-" + transfers.incrementAndGet();
        List<String> out = new ArrayList<>(pieces.size());
        for (int i = 0; i < pieces.size(); i++) {
            ObjectNode chunk = mapper.createObjectNode();
            chunk.put("protocol", PROTOCOL);
            chunk.put("type", "chunk");
            chunk.put("transferId", transferId);
            chunk.put("index", i);
            chunk.put("count", pieces.size());
            chunk.put("data", pieces.get(i));
            out.add(chunk.toString());
        }
        return out;
    }

    /**
     * Recebe um fragmento. Devolve o JSON completo quando o último fragmento chega; senão {@code null}.
     */
    public synchronized String accept(JsonNode chunk, long now) throws ProtocolException {
        evictExpired(now);
        String transferId = text(chunk, "transferId");
        int index = chunk.path("index").asInt(-1);
        int count = chunk.path("count").asInt(-1);
        String data = chunk.path("data").asText(null);
        if (transferId == null || index < 0 || count <= 0 || index >= count || data == null) {
            throw new ProtocolException("BAD_CHUNK", "Fragmento inválido: " + abbreviate(chunk.toString()));
        }
        Pending p = pending.get(transferId);
        if (p == null) {
            if (pending.size() >= MAX_PENDING_TRANSFERS) {
                throw new ProtocolException("TOO_MANY_TRANSFERS", "Transferências simultâneas excedem " + MAX_PENDING_TRANSFERS);
            }
            p = new Pending(count, now);
            pending.put(transferId, p);
        } else if (p.parts.length != count) {
            pending.remove(transferId);
            throw new ProtocolException("BAD_CHUNK", "Contagem de fragmentos inconsistente na transferência " + transferId);
        }
        if (p.parts[index] != null) {
            throw new ProtocolException("DUPLICATE_CHUNK", "Fragmento " + index + " repetido em " + transferId);
        }
        p.parts[index] = data;
        p.received++;
        p.chars += data.length();
        if (p.chars > MAX_REASSEMBLED_CHARS) {
            pending.remove(transferId);
            throw new ProtocolException("MESSAGE_TOO_LARGE", "Mensagem remontada excede " + MAX_REASSEMBLED_CHARS + " caracteres");
        }
        if (p.received < count) {
            return null;
        }
        pending.remove(transferId);
        StringBuilder sb = new StringBuilder((int) p.chars);
        for (String part : p.parts) {
            sb.append(part);
        }
        return sb.toString();
    }

    public synchronized int pendingTransfers() {
        return pending.size();
    }

    private void evictExpired(long now) {
        Iterator<Map.Entry<String, Pending>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue().startedAt > TRANSFER_TIMEOUT_MS) {
                it.remove();
            }
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String abbreviate(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
