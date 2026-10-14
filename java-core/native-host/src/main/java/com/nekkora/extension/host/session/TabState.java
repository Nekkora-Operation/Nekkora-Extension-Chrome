package com.nekkora.extension.host.session;

import com.nekkora.extension.core.analysis.PageAnalysis;
import com.nekkora.extension.core.mutation.MutationRecord;
import com.nekkora.extension.core.options.OptionHistory;
import com.nekkora.extension.core.pagemap.InspectionEvidence;
import com.nekkora.extension.core.resolve.ResolutionResult;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Estado de uma aba: capturas recentes (com análise), histórico de opções, mutações e evidências.
 * Mantém um número limitado de capturas para controlar memória.
 */
public final class TabState {

    /** Capturas retidas por aba (a mais recente, a anterior para comparação e uma margem). CHROME-001 retinha 6. */
    public static final int MAX_CAPTURES = 3;
    private static final int MAX_MUTATIONS = 100;
    private static final int MAX_EVIDENCE = 500;

    private final String tabKey;
    private final LinkedHashMap<String, PageAnalysis> captures = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, PageAnalysis> eldest) {
            return size() > MAX_CAPTURES;
        }
    };
    private OptionHistory optionHistory = new OptionHistory();
    private final Deque<MutationRecord> mutations = new ArrayDeque<>();
    private final Deque<InspectionEvidence> evidence = new ArrayDeque<>();
    private final Deque<ResolutionResult> resolutions = new ArrayDeque<>();
    private String latestCaptureId;
    private String latestPageSessionId;
    /** Captura de REFERÊNCIA fixada (fora do limite LRU): base de comparação com sessões incrementais. */
    private PageAnalysis reference;

    public TabState(String tabKey) {
        this.tabKey = tabKey;
    }

    public String tabKey() {
        return tabKey;
    }

    public synchronized void putCapture(PageAnalysis analysis) {
        String id = analysis.model().snapshot().captureId();
        String session = analysis.model().snapshot().document() == null ? null : analysis.model().snapshot().document().pageSessionId();
        if (latestPageSessionId != null && session != null && !latestPageSessionId.equals(session)) {
            resetForNewPageSession();
        }
        captures.put(id, analysis);
        latestCaptureId = id;
        latestPageSessionId = session;
    }

    /** Ids temporários e opções observadas valem por sessão de página: nova carga recomeça a observação. */
    private void resetForNewPageSession() {
        reference = null;
        optionHistory = new OptionHistory();
        mutations.clear();
    }

    public synchronized Optional<PageAnalysis> capture(String captureId) {
        if (captureId == null) {
            return latest();
        }
        PageAnalysis a = captures.get(captureId);
        if (a == null && reference != null && reference.model().snapshot().captureId().equals(captureId)) {
            a = reference;
        }
        return Optional.ofNullable(a);
    }

    public synchronized Optional<PageAnalysis> latest() {
        return latestCaptureId == null ? Optional.empty() : Optional.ofNullable(captures.get(latestCaptureId));
    }

    /**
     * Fixa uma captura retida como referência (substitui a anterior). Com deltas contínuos cada delta gera uma
     * captura nova; sem a referência, a base de uma comparação sairia do limite de {@link #MAX_CAPTURES}.
     */
    public synchronized boolean pin(String captureId) {
        PageAnalysis a = captures.get(captureId);
        if (a == null) {
            return false;
        }
        reference = a;
        return true;
    }

    public synchronized String referenceCaptureId() {
        return reference == null ? null : reference.model().snapshot().captureId();
    }

    public synchronized List<String> captureIds() {
        return new ArrayList<>(captures.keySet());
    }

    public synchronized OptionHistory optionHistory() {
        return optionHistory;
    }

    public synchronized void addMutation(MutationRecord r) {
        mutations.addLast(r);
        while (mutations.size() > MAX_MUTATIONS) {
            mutations.removeFirst();
        }
    }

    public synchronized List<MutationRecord> mutations() {
        return new ArrayList<>(mutations);
    }

    public synchronized void addEvidence(InspectionEvidence e) {
        evidence.addLast(e);
        while (evidence.size() > MAX_EVIDENCE) {
            evidence.removeFirst();
        }
    }

    public synchronized List<InspectionEvidence> evidence() {
        return new ArrayList<>(evidence);
    }

    public synchronized void addResolution(ResolutionResult r) {
        resolutions.addLast(r);
        while (resolutions.size() > MAX_MUTATIONS) {
            resolutions.removeFirst();
        }
    }

    public synchronized List<ResolutionResult> resolutions() {
        return new ArrayList<>(resolutions);
    }

    /** Libera capturas, histórico de opções, mutações, evidências e resoluções (encerramento da sessão). */
    public synchronized void release() {
        captures.clear();
        reference = null;
        latestCaptureId = null;
        latestPageSessionId = null;
        optionHistory = new OptionHistory();
        mutations.clear();
        evidence.clear();
        resolutions.clear();
    }

    public synchronized int retainedCaptures() {
        return captures.size() + (reference != null && !captures.containsValue(reference) ? 1 : 0);
    }
}
