package com.nekkora.extension.host.session;

import com.nekkora.extension.core.analysis.PageAnalyzer;
import com.nekkora.extension.host.persistence.JsonFileStore;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Estado do processo host: abas, alvos e serviços do motor. */
public final class HostSession {

    private final Map<String, TabState> tabs = new ConcurrentHashMap<>();
    private final TargetRegistry targets;
    private final com.nekkora.extension.host.capture.CaptureSessionRegistry captureSessions =
            new com.nekkora.extension.host.capture.CaptureSessionRegistry();
    private final JsonFileStore store;
    private final PageAnalyzer analyzer = new PageAnalyzer();
    private final long startedAt = System.currentTimeMillis();

    public HostSession(JsonFileStore store) {
        this.store = store;
        this.targets = new TargetRegistry(store);
    }

    public TabState tab(String tabKey) {
        if (tabKey == null || tabKey.isBlank()) {
            throw new IllegalArgumentException("tabKey obrigatório");
        }
        return tabs.computeIfAbsent(tabKey, TabState::new);
    }

    public void resetTab(String tabKey) {
        tabs.remove(tabKey);
    }

    public com.nekkora.extension.host.capture.CaptureSessionRegistry captureSessions() {
        return captureSessions;
    }

    public java.util.Collection<TabState> tabs() {
        return tabs.values();
    }

    public TargetRegistry targets() {
        return targets;
    }

    public JsonFileStore store() {
        return store;
    }

    public PageAnalyzer analyzer() {
        return analyzer;
    }

    public long uptimeMs() {
        return System.currentTimeMillis() - startedAt;
    }

    public int tabCount() {
        return tabs.size();
    }
}
