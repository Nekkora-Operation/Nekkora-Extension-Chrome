package com.nekkora.extension.host.tools;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Calcula o id da extensão a partir da chave pública declarada em {@code manifest.json}
 * (SHA-256 da chave DER, 32 primeiros dígitos hexadecimais mapeados para a–p). Usado pelo script de
 * instalação para gerar {@code allowed_origins} sem depender de valores digitados à mão.
 */
public final class ExtensionId {

    private ExtensionId() {
    }

    public static String fromPublicKey(String base64Der) throws Exception {
        byte[] der = Base64.getDecoder().decode(base64Der.replaceAll("\\s", ""));
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(der);
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < 16; i++) {
            int b = hash[i] & 0xff;
            sb.append((char) ('a' + (b >> 4))).append((char) ('a' + (b & 0x0f)));
        }
        return sb.toString();
    }

    public static String fromManifest(Path manifest) throws Exception {
        String key = new ObjectMapper().readTree(Files.readString(manifest)).path("key").asText(null);
        if (key == null) {
            throw new IllegalArgumentException("manifest.json sem campo \"key\": o id da extensão não é fixo");
        }
        return fromPublicKey(key);
    }

    /** Uso: {@code java -cp nekkora-native-host.jar com.nekkora.extension.host.tools.ExtensionId <manifest.json>} */
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("uso: ExtensionId <caminho/manifest.json>");
            System.exit(2);
        }
        System.out.println(fromManifest(Path.of(args[0])));
    }
}
