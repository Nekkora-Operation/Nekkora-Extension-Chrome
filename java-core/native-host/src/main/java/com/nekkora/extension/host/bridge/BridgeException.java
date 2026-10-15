package com.nekkora.extension.host.bridge;

import java.io.IOException;

/** Falha do canal nekkora-bridge/1 com código estável (os mesmos de {@code contracts/studio-bridge.schema.json}). */
public final class BridgeException extends IOException {

    private final String code;

    public BridgeException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
