package com.nekkora.extension.host.bridge;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Autenticação mútua do canal nekkora-bridge/1 (desafio-resposta com HMAC-SHA256).
 *
 * <p>O segredo efêmero (32 bytes) é criado pelo Studio a cada inicialização e fica somente no arquivo de descoberta
 * (diretório restrito ao usuário). Ele NUNCA trafega: cada lado prova que o conhece assinando os dois nonces da
 * conexão. Como o nonce do servidor muda a cada conexão, uma prova capturada não pode ser reutilizada (repetição).
 */
public final class BridgeAuth {

    public static final String PROTOCOL = "nekkora-bridge/1";
    private static final SecureRandom RANDOM = new SecureRandom();

    private BridgeAuth() {
    }

    /** 32 bytes aleatórios em base64url sem preenchimento (43 caracteres). */
    public static String nonce() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public static String clientProof(byte[] secret, String instanceId, String serverNonce, String clientNonce) {
        return hmac(secret, PROTOCOL + "|client|" + instanceId + "|" + serverNonce + "|" + clientNonce);
    }

    public static String serverProof(byte[] secret, String instanceId, String serverNonce, String clientNonce) {
        return hmac(secret, PROTOCOL + "|server|" + instanceId + "|" + serverNonce + "|" + clientNonce);
    }

    /** Comparação em tempo constante (não revela em que posição a prova diverge). */
    public static boolean matches(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), actual.getBytes(StandardCharsets.US_ASCII));
    }

    public static byte[] decodeSecret(String text) {
        byte[] b = Base64.getUrlDecoder().decode(text);
        if (b.length < 32) {
            throw new IllegalArgumentException("segredo do Bridge curto demais");
        }
        return b;
    }

    private static String hmac(byte[] secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 indisponível", e);
        }
    }
}
