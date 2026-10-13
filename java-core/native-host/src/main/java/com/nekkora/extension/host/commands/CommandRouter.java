package com.nekkora.extension.host.commands;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** Registro de tratadores por tipo de mensagem. */
public final class CommandRouter {

    /** Grupo de comandos relacionados (cada grupo com responsabilidade própria). */
    public interface CommandGroup {
        void register(CommandRouter router);
    }

    private final Map<String, CommandHandler> handlers = new TreeMap<>();

    public CommandRouter register(String type, CommandHandler handler) {
        if (handlers.putIfAbsent(type, handler) != null) {
            throw new IllegalStateException("Comando duplicado: " + type);
        }
        return this;
    }

    public CommandRouter install(CommandGroup group) {
        group.register(this);
        return this;
    }

    public Optional<CommandHandler> find(String type) {
        return Optional.ofNullable(handlers.get(type));
    }

    public Set<String> types() {
        return handlers.keySet();
    }
}
