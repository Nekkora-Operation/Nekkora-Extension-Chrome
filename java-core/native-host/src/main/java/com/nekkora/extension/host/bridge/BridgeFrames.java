package com.nekkora.extension.host.bridge;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Enquadramento do nekkora-bridge/1: 4 bytes big-endian sem sinal com o tamanho + JSON UTF-8. Canal de CONTROLE:
 * quadros acima de {@link #MAX_FRAME_BYTES} são recusados (nenhum PageMap ou snapshot passa por aqui).
 */
public final class BridgeFrames {

    public static final int MAX_FRAME_BYTES = 262_144;

    private BridgeFrames() {
    }

    /** Lê um quadro; {@code null} no fim do fluxo (conexão encerrada entre quadros). */
    public static String read(InputStream in) throws IOException {
        byte[] header = in.readNBytes(4);
        if (header.length == 0) {
            return null;
        }
        if (header.length < 4) {
            throw new EOFException("cabeçalho de quadro truncado");
        }
        long length = Integer.toUnsignedLong(ByteBuffer.wrap(header).getInt());
        if (length == 0 || length > MAX_FRAME_BYTES) {
            throw new BridgeException("FRAME_TOO_LARGE", "quadro de " + length + " bytes (limite " + MAX_FRAME_BYTES + ")");
        }
        byte[] body = in.readNBytes((int) length);
        if (body.length < length) {
            throw new EOFException("quadro truncado: " + body.length + "/" + length);
        }
        return new String(body, StandardCharsets.UTF_8);
    }

    /** Escreve um quadro completo (cabeçalho + corpo) e esvazia o fluxo. O chamador sincroniza. */
    public static void write(OutputStream out, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        if (body.length > MAX_FRAME_BYTES) {
            throw new BridgeException("FRAME_TOO_LARGE", "quadro de saída com " + body.length + " bytes");
        }
        byte[] frame = ByteBuffer.allocate(4 + body.length).putInt(body.length).put(body).array();
        out.write(frame);
        out.flush();
    }
}
