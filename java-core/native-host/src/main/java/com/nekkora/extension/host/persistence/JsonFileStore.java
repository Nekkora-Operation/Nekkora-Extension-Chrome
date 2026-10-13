package com.nekkora.extension.host.persistence;

import com.nekkora.extension.host.json.JsonCodec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Persistência em arquivos JSON locais. Suficiente para snapshots de teste, PageMaps, evidências e
 * identidades — não há necessidade comprovada de banco relacional nesta fase (decisão D-006).
 */
public final class JsonFileStore {

    private static final Pattern SAFE = Pattern.compile("[^A-Za-z0-9._-]");

    private final DataDirectory dir;
    private final JsonCodec json;

    public JsonFileStore(DataDirectory dir, JsonCodec json) {
        this.dir = dir;
        this.json = json;
    }

    public Path write(String folder, String name, Object value) throws IOException {
        Path target = dir.dir(folder).resolve(safe(name) + ".json");
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, json.writePretty(value), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    public <T> Optional<T> read(String folder, String name, Class<T> type) throws IOException {
        Path p = dir.dir(folder).resolve(safe(name) + ".json");
        if (!Files.exists(p)) {
            return Optional.empty();
        }
        return Optional.of(json.read(Files.readString(p, StandardCharsets.UTF_8), type));
    }

    static String safe(String name) {
        String s = SAFE.matcher(name).replaceAll("_");
        return s.length() > 120 ? s.substring(0, 120) : s;
    }
}
