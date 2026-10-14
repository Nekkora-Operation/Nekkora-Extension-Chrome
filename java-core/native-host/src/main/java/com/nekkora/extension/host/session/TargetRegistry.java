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

/**
 * Identidades lógicas registradas, persistidas em {@code targets/registry.json}.
 *
 * <p>Cada alvo tem um DONO (aba e, quando havia, sessão de captura em que foi registrado). Exportações e
 * reencontros automáticos consideram somente os alvos do próprio contexto: duas abas na mesma URL (ex.:
 * cadastros de clientes diferentes) nunca misturam identidades. A resolução explícita por {@code targetId}
 * continua possível (reuso deliberado de uma identidade lógica).
 */
public final class TargetRegistry {

    private static final Logger LOG = Logger.getLogger(TargetRegistry.class.getName());

    /** Formato persistido. */
    public record Persisted(String schemaVersion, List<TargetIdentity> targets, Map<String, Owner> owners) {
    }

    /** Contexto proprietário de um alvo. {@code sessionId} é nulo para registros sem sessão de captura. */
    public record Owner(String tabKey, String sessionId) {
    }

    private final Map<String, TargetIdentity> targets = new LinkedHashMap<>();
    private final Map<String, Owner> owners = new LinkedHashMap<>();
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
                if (p.owners() != null) {
                    owners.putAll(p.owners()); // registros da CHROME-001 não têm dono: nunca entram em exportações de sessão
                }
            });
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Registro de alvos ilegível; iniciando vazio", e);
        }
    }

    /** Atualiza uma identidade existente (ex.: após reencontro) preservando o dono. */
    public synchronized void put(TargetIdentity identity) {
        targets.put(identity.targetId(), identity);
        persist();
    }

    /** Registra uma identidade com o contexto proprietário. */
    public synchronized void put(TargetIdentity identity, Owner owner) {
        targets.put(identity.targetId(), identity);
        if (owner != null) {
            owners.put(identity.targetId(), owner);
        }
        persist();
    }

    public synchronized Optional<Owner> owner(String targetId) {
        return Optional.ofNullable(owners.get(targetId));
    }

    /** Alvos registrados na sessão de captura indicada. */
    public synchronized List<TargetIdentity> ownedBySession(String sessionId) {
        List<TargetIdentity> out = new ArrayList<>();
        for (TargetIdentity t : targets.values()) {
            Owner o = owners.get(t.targetId());
            if (o != null && sessionId != null && sessionId.equals(o.sessionId())) {
                out.add(t);
            }
        }
        return out;
    }

    /** Alvos registrados na aba indicada SEM sessão de captura (fluxo de captura avulsa). */
    public synchronized List<TargetIdentity> ownedByTabWithoutSession(String tabKey) {
        List<TargetIdentity> out = new ArrayList<>();
        for (TargetIdentity t : targets.values()) {
            Owner o = owners.get(t.targetId());
            if (o != null && tabKey != null && tabKey.equals(o.tabKey()) && o.sessionId() == null) {
                out.add(t);
            }
        }
        return out;
    }

    /** Alvos registrados na aba indicada (com ou sem sessão de captura). */
    public synchronized List<TargetIdentity> ownedByTab(String tabKey) {
        List<TargetIdentity> out = new ArrayList<>();
        for (TargetIdentity t : targets.values()) {
            Owner o = owners.get(t.targetId());
            if (o != null && tabKey != null && tabKey.equals(o.tabKey())) {
                out.add(t);
            }
        }
        return out;
    }

    public synchronized Optional<TargetIdentity> get(String targetId) {
        return Optional.ofNullable(targets.get(targetId));
    }

    public synchronized Collection<TargetIdentity> all() {
        return new ArrayList<>(targets.values());
    }

    public synchronized void clear() {
        targets.clear();
        owners.clear();
        persist();
    }

    private void persist() {
        if (store == null) {
            return;
        }
        try {
            store.write("targets", "registry", new Persisted(TargetIdentity.SCHEMA_VERSION, new ArrayList<>(targets.values()), new LinkedHashMap<>(owners)));
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Falha ao persistir registro de alvos", e);
        }
    }
}
