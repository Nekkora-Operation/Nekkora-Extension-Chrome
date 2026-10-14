package com.nekkora.extension.host.capture;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Sessões de captura conhecidas pelo host (uma por aba em uso; sessões encerradas são descartadas). */
public final class CaptureSessionRegistry {

    private final Map<String, HostCaptureSession> sessions = new ConcurrentHashMap<>();

    public HostCaptureSession open(String sessionId, String tabKey) {
        HostCaptureSession existing = sessions.get(sessionId);
        if (existing != null) {
            if (!existing.tabKey().equals(tabKey)) {
                throw new IllegalArgumentException("Sessão " + sessionId + " pertence a outra aba");
            }
            return existing;
        }
        // Uma aba tem no máximo uma sessão de captura: a anterior (de outro documento/ciclo) é encerrada.
        for (HostCaptureSession s : sessions.values()) {
            if (s.tabKey().equals(tabKey)) {
                s.stop("REPLACED");
                sessions.remove(s.sessionId());
            }
        }
        HostCaptureSession s = new HostCaptureSession(sessionId, tabKey);
        sessions.put(sessionId, s);
        return s;
    }

    public Optional<HostCaptureSession> find(String sessionId) {
        return Optional.ofNullable(sessionId == null ? null : sessions.get(sessionId));
    }

    /** Sessão de captura ativa da aba, se houver. */
    public Optional<HostCaptureSession> forTab(String tabKey) {
        for (HostCaptureSession s : sessions.values()) {
            if (s.tabKey().equals(tabKey) && s.status() != HostCaptureSession.Status.STOPPED) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }

    public Optional<HostCaptureSession> remove(String sessionId) {
        return Optional.ofNullable(sessions.remove(sessionId));
    }

    public List<HostCaptureSession> all() {
        return new ArrayList<>(sessions.values());
    }

    public int size() {
        return sessions.size();
    }
}
