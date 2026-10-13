package com.nekkora.extension.host.protocol;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Enquadramento do Chrome Native Messaging: cada mensagem é um inteiro de 32 bits na ordem de bytes
 * nativa (little-endian em x86/x64/ARM64) com o tamanho, seguido do JSON em UTF-8.
 *
 * <p>Limites impostos pelo Chrome: mensagens do host para o navegador ≤ 1 MiB; do navegador para o
 * host ≤ 64 MiB. Mensagens maiores precisam ser fragmentadas ({@link ChunkCodec}).
 */
public final class NativeMessageCodec {

    /** Limite do Chrome para mensagens host → navegador. */
    public static final int MAX_OUTBOUND_BYTES = 1024 * 1024;
    /** Limite do Chrome para mensagens navegador → host. */
    public static final int MAX_INBOUND_BYTES = 64 * 1024 * 1024;

    private final InputStream in;
    private final OutputStream out;

    public NativeMessageCodec(InputStream in, OutputStream out) {
        this.in = in;
        this.out = out;
    }

    /** Lê a próxima mensagem; devolve {@code null} no fim do fluxo (Chrome fechou a porta). */
    public String read() throws IOException {
        byte[] header = in.readNBytes(4);
        if (header.length == 0) {
            return null;
        }
        if (header.length < 4) {
            throw new EOFException("Cabeçalho de mensagem truncado");
        }
        int length = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (length < 0 || length > MAX_INBOUND_BYTES) {
            throw new ProtocolException("FRAME_TOO_LARGE", "Mensagem de " + Integer.toUnsignedString(length)
                    + " bytes excede o limite de " + MAX_INBOUND_BYTES);
        }
        byte[] body = in.readNBytes(length);
        if (body.length < length) {
            throw new EOFException("Corpo de mensagem truncado: " + body.length + "/" + length);
        }
        return new String(body, StandardCharsets.UTF_8);
    }

    /** Escreve uma mensagem já serializada. Falha se exceder o limite do Chrome. */
    public synchronized void write(String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        if (body.length > MAX_OUTBOUND_BYTES) {
            throw new ProtocolException("FRAME_TOO_LARGE", "Mensagem de saída com " + body.length
                    + " bytes excede o limite do Chrome (" + MAX_OUTBOUND_BYTES + "); use fragmentação");
        }
        byte[] header = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(body.length).array();
        out.write(header);
        out.write(body);
        out.flush();
    }

    /** Codifica um quadro (usado em testes e por clientes Java do protocolo). */
    public static byte[] frame(String json) {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(4 + body.length).order(ByteOrder.LITTLE_ENDIAN).putInt(body.length).put(body).array();
    }
}
