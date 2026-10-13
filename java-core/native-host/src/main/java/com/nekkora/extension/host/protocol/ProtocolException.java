package com.nekkora.extension.host.protocol;

import java.io.IOException;

/** Violação do protocolo, com código estável para o cliente. */
public class ProtocolException extends IOException {

    private final String code;

    public ProtocolException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
