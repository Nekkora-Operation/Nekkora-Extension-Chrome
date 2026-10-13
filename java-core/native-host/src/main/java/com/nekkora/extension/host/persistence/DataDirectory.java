package com.nekkora.extension.host.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Diretório local de dados do host (somente máquina local; nada é enviado a servidores).
 * Ordem: {@code NEKKORA_DATA_DIR} → {@code %LOCALAPPDATA%\Nekkora\ExtensionChrome} → {@code ~/.nekkora/extension-chrome}.
 */
public final class DataDirectory {

    private final Path root;

    public DataDirectory(Path root) {
        this.root = root;
    }

    public static DataDirectory fromEnvironment() {
        String explicit = System.getenv("NEKKORA_DATA_DIR");
        if (explicit != null && !explicit.isBlank()) {
            return new DataDirectory(Paths.get(explicit));
        }
        String local = System.getenv("LOCALAPPDATA");
        if (local != null && !local.isBlank()) {
            return new DataDirectory(Paths.get(local, "Nekkora", "ExtensionChrome"));
        }
        return new DataDirectory(Paths.get(System.getProperty("user.home"), ".nekkora", "extension-chrome"));
    }

    public Path root() {
        return root;
    }

    public Path dir(String name) throws IOException {
        Path p = root.resolve(name);
        Files.createDirectories(p);
        return p;
    }
}
