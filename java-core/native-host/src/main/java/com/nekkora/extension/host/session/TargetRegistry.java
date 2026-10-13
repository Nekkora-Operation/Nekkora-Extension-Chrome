package com.nekkora.extension.host.session;

import com.nekkora.extension.core.identity.TargetIdentity;
import com.nekkora.extension.host.persistence.JsonFileStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Identidades lógicas registradas, persistidas em {@code targets/registry.json}. */
public final class TargetRegistry {

    private static final Logger LOG = Logger.getLogger(TargetRegistry.class.getName());

    /** Formato persistido. */
    public record Persisted(String schemaVersion, List<TargetIdentity> targets) {
    }

    private final Map<String, TargetIdentity> targets = new LinkedHashMap<>();
    private final JsonFileStore store;

    public TargetRegistry(JsonFileStore store) {
        this.store = store;
        load();
    }

    private void load() {
        if (store == null) {
            return;
        }
        try {
            store.read("targets", "registry", Persisted.class).ifPresent(p -> {
                if (p.targets() != null) {
                    p.targets().forEach(t -> targets.put(t.targetId(), t));
                }
            });
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Registro de alvos ilegível; iniciando vazio", e);
        }
    }

    public synchronized void put(TargetIdentity identity) {
        targets.put(identity.targetId(), identity);
        persist();
    }

    public synchronized Optional<TargetIdentity> get(String targetId) {
        return Optional.ofNullable(targets.get(targetId));
    }

    public synchronized Collection<TargetIdentity> all() {
        return new ArrayList<>(targets.values());
    }

    public synchronized void clear() {
        targets.clear();
        persist();
    }

    private void persist() {
        if (store == null) {
            return;
        }
        try {
            store.write("targets", "registry", new Persisted(TargetIdentity.SCHEMA_VERSION, new ArrayList<>(targets.values())));
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Falha ao persistir registro de alvos", e);
        }
    }
}
