package com.nekkora.extension.host.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void framingIsLittleEndianLengthPrefixed() throws Exception {
        byte[] frame = NativeMessageCodec.frame("{\"a\":\"ção\"}");
        int len = "{\"a\":\"ção\"}".getBytes(StandardCharsets.UTF_8).length;
        assertEquals(len, frame[0] & 0xff);
        assertEquals(0, frame[3]);
        NativeMessageCodec codec = new NativeMessageCodec(new ByteArrayInputStream(frame), new ByteArrayOutputStream());
        assertEquals("{\"a\":\"ção\"}", codec.read());
        assertNull(codec.read(), "fim do fluxo = Chrome fechou a porta");
    }

    @Test
    void outboundLimitIsEnforced() {
        NativeMessageCodec codec = new NativeMessageCodec(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream());
        String big = "x".repeat(NativeMessageCodec.MAX_OUTBOUND_BYTES + 1);
        ProtocolException e = assertThrows(ProtocolException.class, () -> codec.write(big));
        assertEquals("FRAME_TOO_LARGE", e.code());
    }

    @Test
    void inboundLengthAboveLimitIsRejected() {
        byte[] header = {(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0x7f};
        NativeMessageCodec codec = new NativeMessageCodec(new ByteArrayInputStream(header), new ByteArrayOutputStream());
        assertThrows(ProtocolException.class, codec::read);
    }

    @Test
    void largeMessagesAreFragmentedAndReassembled() throws Exception {
        ChunkCodec codec = new ChunkCodec(mapper);
        StringBuilder sb = new StringBuilder("{\"data\":\"");
        while (sb.length() < 3_000_000) {
            sb.append("Ação \\\"aspas\\\" 😀 ");
        }
        sb.append("\"}");
        String json = sb.toString();
        List<String> frames = codec.split(json);
        assertTrue(frames.size() > 10);
        for (String f : frames) {
            assertTrue(f.getBytes(StandardCharsets.UTF_8).length < NativeMessageCodec.MAX_OUTBOUND_BYTES, "cada fragmento cabe no limite do Chrome");
        }
        ChunkCodec receiver = new ChunkCodec(mapper);
        String result = null;
        // Entrega fora de ordem: o receptor deve remontar pela posição.
        for (int i = frames.size() - 1; i >= 0; i--) {
            String r = receiver.accept(mapper.readTree(frames.get(i)), 0);
            if (r != null) {
                result = r;
            }
        }
        assertEquals(json, result);
        assertEquals(0, receiver.pendingTransfers());
    }

    @Test
    void smallMessagesAreNotFragmented() {
        assertEquals(List.of("{}"), new ChunkCodec(mapper).split("{}"));
    }

    @Test
    void duplicateAndInconsistentChunksAreRejected() throws Exception {
        ChunkCodec c = new ChunkCodec(mapper);
        JsonNode a = mapper.readTree("{\"type\":\"chunk\",\"transferId\":\"t\",\"index\":0,\"count\":2,\"data\":\"ab\"}");
        c.accept(a, 0);
        assertEquals("DUPLICATE_CHUNK", assertThrows(ProtocolException.class, () -> c.accept(a, 0)).code());
        JsonNode bad = mapper.readTree("{\"type\":\"chunk\",\"transferId\":\"t\",\"index\":1,\"count\":3,\"data\":\"cd\"}");
        assertEquals("BAD_CHUNK", assertThrows(ProtocolException.class, () -> c.accept(bad, 0)).code());
        JsonNode malformed = mapper.readTree("{\"type\":\"chunk\",\"index\":0}");
        assertEquals("BAD_CHUNK", assertThrows(ProtocolException.class, () -> c.accept(malformed, 0)).code());
    }

    @Test
    void expiredTransfersAreEvicted() throws Exception {
        ChunkCodec c = new ChunkCodec(mapper);
        c.accept(mapper.readTree("{\"transferId\":\"old\",\"index\":0,\"count\":2,\"data\":\"a\"}"), 0);
        assertEquals(1, c.pendingTransfers());
        c.accept(mapper.readTree("{\"transferId\":\"new\",\"index\":0,\"count\":2,\"data\":\"a\"}"), ChunkCodec.TRANSFER_TIMEOUT_MS + 1);
        assertEquals(1, c.pendingTransfers(), "transferência antiga descartada");
    }
}
