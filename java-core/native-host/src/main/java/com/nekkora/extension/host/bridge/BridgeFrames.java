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
 *
 * <p>NEKKORA-STUDIO-013 — exceção ÚNICA, só na ESCRITA para o Studio: o evento {@code studio.pdd.captured} da política
 * {@code nekkora-visual/4} (print real com o texto de dados mascarado) ou {@code nekkora-visual/5} (print integral) leva um PNG
 * maior e pode ter até
 * {@link #MAX_CAPTURE_FRAME_BYTES}. O host nunca lê do Studio quadros acima do teto de controle.
 */
public final class BridgeFrames {

    public static final int MAX_FRAME_BYTES = 262_144;
    /** Teto do quadro de uma captura nekkora-visual/4 (PNG de até 8 000 000 bytes em base64 + metadados). */
    public static final int MAX_CAPTURE_FRAME_BYTES = 12 * 1024 * 1024;

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
        write(out, json, MAX_FRAME_BYTES);
    }

    /** Escreve com o teto dado ({@link #MAX_CAPTURE_FRAME_BYTES} só para a captura nekkora-visual/4). */
    public static void write(OutputStream out, String json, int max) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        if (body.length > max) {
            throw new BridgeException("FRAME_TOO_LARGE", "quadro de saída com " + body.length + " bytes");
        }
        byte[] frame = ByteBuffer.allocate(4 + body.length).putInt(body.length).put(body).array();
        out.write(frame);
        out.flush();
    }

    /** Mensagem que pode passar do teto de controle: só o evento da captura nekkora-visual/4 ou /5 (print integral). */
    public static boolean largeAllowed(String kind, String type, String policy) {
        return "event".equals(kind) && "studio.pdd.captured".equals(type) && ("nekkora-visual/4".equals(policy) || "nekkora-visual/5".equals(policy));
    }
}
