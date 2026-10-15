package com.nekkora.extension.host.commands;

import com.nekkora.extension.core.analysis.PageAnalysis;
import com.nekkora.extension.core.coverage.CoverageAnalyzer;
import com.nekkora.extension.core.mutation.FrameMerger;
import com.nekkora.extension.core.mutation.SnapshotDelta;
import com.nekkora.extension.core.navigation.UrlContextParser;
import com.nekkora.extension.core.semantic.Classification;
import com.nekkora.extension.core.semantic.ElementCategory;
import com.nekkora.extension.core.snapshot.RawSnapshot;
import com.nekkora.extension.host.capture.ExportResult;
import com.nekkora.extension.host.capture.ExportService;
import com.nekkora.extension.host.capture.HostCaptureSession;
import com.nekkora.extension.host.session.TabState;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ciclo de vida das sessões de captura (CHROME-002): base completa, deltas sequenciados, anexação de frames,
 * estado, encerramento com liberação de recursos e exportação consolidada idempotente.
 *
 * <p>Contratos separados por responsabilidade: captura ({@code capture.session.*}), exportação
 * ({@code export.session}) e diagnóstico de memória ({@code host.memory}). Inspeção (destaque, seleção) é do
 * navegador; o host não conhece o conceito de "inspeção ativa".
 */
public final class SessionCommands implements CommandRouter.CommandGroup {

    private final EngineServices s;
    private final ExportService exports;
    private final DeltaProcessor deltas;
    private final FrameMerger frames = new FrameMerger();
    private final CoverageAnalyzer coverage = new CoverageAnalyzer();
    private final UrlContextParser urls = new UrlContextParser();

    public SessionCommands(EngineServices services, ExportService exports) {
        this.s = services;
        this.exports = exports;
        this.deltas = new DeltaProcessor(services);
    }

    @Override
    public void register(CommandRouter router) {
        router.register("capture.session.start", p -> {
            long t0 = System.nanoTime();
            String sessionId = Payloads.required(p, "sessionId");
            String tabKey = Payloads.required(p, "tabKey");
            RawSnapshot snap = s.snapshot(Payloads.node(p, "snapshot"));
            HostCaptureSession session = s.session.captureSessions().open(sessionId, tabKey);
            TabState tab = s.session.tab(tabKey);
            PageAnalysis analysis;
            synchronized (session) {
                analysis = s.session.analyzer().analyze(snap);
                tab.putCapture(analysis);
                tab.pin(snap.captureId()); // a base da sessão é a referência inicial de comparação
                session.rebase(snap.captureId(), snap.document() == null ? null : snap.document().pageSessionId());
            }
            Map<String, Object> out = summary(analysis);
            out.put("session", session.describe());
            out.put("timings", Map.of("indexMs", analysis.indexMs(), "classificationMs", analysis.classificationMs(),
                    "hostTotalMs", EngineServices.ms(t0)));
            return out;
        });

        router.register("capture.session.delta", p -> {
            HostCaptureSession session = session(Payloads.required(p, "sessionId"));
            SnapshotDelta raw = s.json.read(Payloads.node(p, "delta"), SnapshotDelta.class);
            synchronized (session) {
                String pageSession = raw.document() == null ? null : raw.document().pageSessionId();
                String rejection = session.check(raw.epoch(), raw.sourceOrTop(), raw.seq(), pageSession);
                if (rejection != null) {
                    session.rejected(rejection);
                    throw new CommandException(rejection, "Delta " + raw.sourceOrTop() + "#" + raw.seq() + " (época " + raw.epoch()
                            + ") recusado: " + rejection + "; sessão " + session.describe());
                }
                TabState tab = s.session.tab(session.tabKey());
                PageAnalysis base = s.capture(tab, session.latestCaptureId());
                SnapshotDelta delta = s.privacy(raw, base);
                Map<String, Object> out = deltas.apply(tab, base, delta, Payloads.flag(p, "autoResolve", true), session.sessionId());
                session.accepted(delta.sourceOrTop(), delta.seq(), (String) out.get("captureId"));
                out.put("session", session.describe());
                return out;
            }
        });

        router.register("capture.session.attachFrame", p -> {
            HostCaptureSession session = session(Payloads.required(p, "sessionId"));
            String hostNodeId = Payloads.required(p, "hostNodeId");
            String source = Payloads.required(p, "source");
            RawSnapshot child = s.snapshot(Payloads.node(p, "snapshot"));
            synchronized (session) {
                TabState tab = s.session.tab(session.tabKey());
                PageAnalysis base = s.capture(tab, session.latestCaptureId());
                String captureId = base.model().snapshot().captureId() + "+" + source;
                RawSnapshot merged = frames.attach(base.model().snapshot(), hostNodeId, child, source, captureId);
                PageAnalysis analysis = s.session.analyzer().analyze(merged);
                tab.putCapture(analysis);
                session.frameAttached(source, captureId);
                Map<String, Object> out = summary(analysis);
                out.put("session", session.describe());
                return out;
            }
        });

        router.register("capture.pin", p -> {
            TabState tab = s.session.tab(Payloads.required(p, "tabKey"));
            String captureId = Payloads.optional(p, "captureId");
            String id = captureId == null ? tab.latest().map(a -> a.model().snapshot().captureId()).orElse(null) : captureId;
            if (id == null || !tab.pin(id)) {
                throw new CommandException("UNKNOWN_CAPTURE", "Captura não retida para fixar: " + id);
            }
            return Map.of("referenceCaptureId", id);
        });

        router.register("capture.session.status", p -> {
            HostCaptureSession session = session(Payloads.required(p, "sessionId"));
            Map<String, Object> out = session.describe();
            s.session.tab(session.tabKey()).capture(session.latestCaptureId())
                    .ifPresent(a -> out.put("nodes", a.model().size()));
            return out;
        });

        router.register("capture.session.list", p -> s.session.captureSessions().all().stream().map(HostCaptureSession::describe).toList());

        router.register("capture.session.stop", p -> {
            String sessionId = Payloads.required(p, "sessionId");
            HostCaptureSession session = s.session.captureSessions().remove(sessionId)
                    .orElseThrow(() -> new CommandException("SESSION_UNKNOWN", "Sessão inexistente: " + sessionId));
            synchronized (session) {
                session.stop(Payloads.optional(p, "reason") == null ? "STOPPED" : Payloads.optional(p, "reason"));
                if (Payloads.flag(p, "release", true)) {
                    s.session.tab(session.tabKey()).release();
                }
                return session.describe();
            }
        });

        router.register("export.session", p -> {
            HostCaptureSession session = session(Payloads.required(p, "sessionId"));
            String operationId = Payloads.required(p, "operationId");
            String reason = Payloads.optional(p, "reason") == null ? "MANUAL" : Payloads.optional(p, "reason");
            String sync = Payloads.optional(p, "syncState") == null ? "UNKNOWN" : Payloads.optional(p, "syncState");
            int parallelism = p.path("parallelism").asInt(Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
            boolean diagnostic = "DIAGNOSTIC".equals(Payloads.optional(p, "mode"));
            ExportResult r = exports.export(session, operationId, reason, sync, parallelism, diagnostic);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("export", r);
            if (Payloads.flag(p, "returnPageMap", false) && r.path() != null) {
                out.put("pageMap", s.json.mapper().readTree(java.nio.file.Path.of(r.path()).toFile()));
            }
            return out;
        });

        router.register("host.memory", p -> {
            if (Payloads.flag(p, "gc", false)) {
                System.gc();
                Thread.sleep(200);
            }
            MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("heapUsedMb", mb(heap.getUsed()));
            out.put("heapCommittedMb", mb(heap.getCommitted()));
            out.put("heapMaxMb", mb(heap.getMax()));
            out.put("afterGc", Payloads.flag(p, "gc", false));
            out.put("pid", ProcessHandle.current().pid());
            out.put("captureSessions", s.session.captureSessions().size());
            Map<String, Integer> retained = new LinkedHashMap<>();
            for (TabState t : s.session.tabs()) {
                retained.put(t.tabKey(), t.retainedCaptures());
            }
            out.put("retainedCaptures", retained);
            out.put("targets", s.session.targets().all().size());
            out.put("gc", ManagementFactory.getGarbageCollectorMXBeans().stream().map(b -> b.getName()).toList());
            return out;
        });
    }

    private HostCaptureSession session(String sessionId) {
        return s.session.captureSessions().find(sessionId)
                .orElseThrow(() -> new CommandException("SESSION_UNKNOWN", "Sessão de captura inexistente no host: " + sessionId));
    }

    private Map<String, Object> summary(PageAnalysis a) {
        RawSnapshot snap = a.model().snapshot();
        int interactive = 0;
        int text = 0;
        for (Classification c : a.semantics().all()) {
            if (c.category() == ElementCategory.INTERACTIVE) {
                interactive++;
            } else if (c.category() == ElementCategory.TEXT) {
                text++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("captureId", snap.captureId());
        out.put("pageSessionId", snap.document() == null ? null : snap.document().pageSessionId());
        out.put("nodes", a.model().size());
        out.put("interactive", interactive);
        out.put("text", text);
        out.put("coverage", coverage.analyze(a.model(), a.semantics()));
        out.put("navigation", snap.document() == null ? null : urls.parse(snap.document().url(), snap.document().title()));
        return out;
    }

    private static double mb(long bytes) {
        return Math.round(bytes / 1048576.0 * 10) / 10.0;
    }
}
