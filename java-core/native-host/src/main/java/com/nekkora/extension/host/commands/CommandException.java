package com.nekkora.extension.host.commands;

/** Erro de negócio com código estável devolvido ao cliente. */
public class CommandException extends RuntimeException {

    private final String code;

    public CommandException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
