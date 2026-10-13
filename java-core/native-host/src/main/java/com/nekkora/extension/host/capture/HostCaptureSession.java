package com.nekkora.extension.host.capture;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Estado de uma sessão de captura no motor Java. A sessão é a autoridade sobre a consistência: decide se um
 * delta pode ser aplicado (época, documento, sequência por fonte) e guarda o resultado das exportações.
 *
 * <p>Ciclo de vida: {@code ACTIVE} → ({@code FINALIZING} durante uma exportação) → {@code ACTIVE} | {@code STOPPED}.
 * O acesso é serializado pelo monitor da própria instância.
 */
public final class HostCaptureSession {

    public enum Status { ACTIVE, FINALIZING, STOPPED }

    private final String sessionId;
    private final String tabKey;
    private final Instant startedAt = Instant.now();
    private Status status = Status.ACTIVE;
    private String pageSessionId;
    private int epoch;
    private final Map<String, Long> lastSeq = new HashMap<>();
    private String baseCaptureId;
    private String latestCaptureId;
    private long deltasApplied;
    private long deltasRejected;
    private long fullCaptures;
    private long frameAttachments;
    private Instant lastUpdateAt = Instant.now();
    private String lastRejection;
    private final Map<String, ExportResult> exports = new LinkedHashMap<>();
    private String endReason;

    HostCaptureSession(String sessionId, String tabKey) {
        this.sessionId = sessionId;
        this.tabKey = tabKey;
    }

    public String sessionId() {
        return sessionId;
    }

    public String tabKey() {
        return tabKey;
    }

    public synchronized Status status() {
        return status;
    }

    public synchronized void status(Status s) {
        this.status = s;
    }

    public synchronized int epoch() {
        return epoch;
    }

    /** Nova base (captura completa): incrementa a época e zera as sequências de todas as fontes. */
    public synchronized int rebase(String captureId, String pageSessionId) {
        epoch++;
        lastSeq.clear();
        baseCaptureId = captureId;
        latestCaptureId = captureId;
        this.pageSessionId = pageSessionId;
        fullCaptures++;
        status = Status.ACTIVE;
        lastUpdateAt = Instant.now();
        return epoch;
    }

    /**
     * Valida um delta sequenciado.
     *
     * @return {@code null} se aceito; senão o código de rejeição
     */
    public synchronized String check(int deltaEpoch, String source, long seq, String deltaPageSessionId) {
        if (status == Status.STOPPED) {
            return "SESSION_STOPPED";
        }
        if (deltaEpoch != epoch) {
            return "DELTA_STALE";
        }
        if (pageSessionId != null && deltaPageSessionId != null && !pageSessionId.equals(deltaPageSessionId)) {
            return "DOCUMENT_MISMATCH";
        }
        long expected = lastSeq.getOrDefault(source, 0L) + 1;
        if (seq < expected) {
            return "DELTA_STALE";
        }
        if (seq > expected) {
            return "DELTA_GAP";
        }
        return null;
    }

    public synchronized void accepted(String source, long seq, String captureId) {
        lastSeq.put(source, seq);
        latestCaptureId = captureId;
        deltasApplied++;
        lastUpdateAt = Instant.now();
    }

    public synchronized void rejected(String code) {
        deltasRejected++;
        lastRejection = code;
    }

    public synchronized void frameAttached(String source, String captureId) {
        lastSeq.put(source, 0L);
        latestCaptureId = captureId;
        frameAttachments++;
        lastUpdateAt = Instant.now();
    }

    public synchronized String latestCaptureId() {
        return latestCaptureId;
    }

    synchronized ExportResult export(String operationId) {
        return exports.get(operationId);
    }

    synchronized void exported(String operationId, ExportResult r) {
        exports.put(operationId, r);
    }

    public synchronized void stop(String reason) {
        status = Status.STOPPED;
        endReason = reason;
        lastUpdateAt = Instant.now();
    }

    public synchronized Map<String, Object> describe() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", sessionId);
        out.put("tabKey", tabKey);
        out.put("status", status.name());
        out.put("pageSessionId", pageSessionId);
        out.put("epoch", epoch);
        out.put("baseCaptureId", baseCaptureId);
        out.put("latestCaptureId", latestCaptureId);
        out.put("lastSeq", new LinkedHashMap<>(lastSeq));
        out.put("deltasApplied", deltasApplied);
        out.put("deltasRejected", deltasRejected);
        out.put("lastRejection", lastRejection);
        out.put("fullCaptures", fullCaptures);
        out.put("frameAttachments", frameAttachments);
        out.put("startedAt", startedAt.toString());
        out.put("lastUpdateAt", lastUpdateAt.toString());
        out.put("exports", exports.size());
        out.put("endReason", endReason);
        return out;
    }
}
