package com.nekkora.extension.host.commands;

import com.nekkora.extension.core.analysis.PageAnalysis;
import com.nekkora.extension.core.identity.IdentityFactory;
import com.nekkora.extension.core.interaction.InteractionAnalyzer;
import com.nekkora.extension.core.mutation.DeltaApplier;
import com.nekkora.extension.core.mutation.SnapshotComparator;
import com.nekkora.extension.core.options.OptionSetAnalyzer;
import com.nekkora.extension.core.pagemap.InspectionEvidence;
import com.nekkora.extension.core.pagemap.PageMapAssembler;
import com.nekkora.extension.core.resolve.TargetResolver;
import com.nekkora.extension.host.json.JsonCodec;
import com.nekkora.extension.host.session.HostSession;
import com.nekkora.extension.host.session.TabState;

import java.time.Instant;
import java.util.List;

/** Serviços do motor compartilhados pelos grupos de comandos. */
public final class EngineServices {

    final HostSession session;
    final JsonCodec json;
    final IdentityFactory identities = new IdentityFactory();
    final TargetResolver resolver = new TargetResolver();
    final SnapshotComparator comparator = new SnapshotComparator();
    final DeltaApplier deltas = new DeltaApplier();
    final PageMapAssembler assembler = new PageMapAssembler();
    final InteractionAnalyzer interactions = new InteractionAnalyzer();
    final OptionSetAnalyzer options = new OptionSetAnalyzer();
    final com.nekkora.extension.core.frames.FrameGraphBuilder frameGraphs = new com.nekkora.extension.core.frames.FrameGraphBuilder();
    final com.nekkora.extension.core.frames.AccessPathBuilder accessPaths = new com.nekkora.extension.core.frames.AccessPathBuilder();
    final com.nekkora.extension.core.protection.ProtectionDetector protections = new com.nekkora.extension.core.protection.ProtectionDetector();
    final com.nekkora.extension.core.diagnostics.AutomationDiagnostics diagnostics = new com.nekkora.extension.core.diagnostics.AutomationDiagnostics();
    final com.nekkora.extension.core.protection.SignatureCatalog catalog = com.nekkora.extension.host.json.CatalogLoader.load();

    public EngineServices(HostSession session, JsonCodec json) {
        this.session = session;
        this.json = json;
    }

    /**
     * CHROME-SECURITY-001 — 3ª barreira: todo snapshot recebido passa por {@code nekkora-privacy/2} ANTES do motor, seja
     * qual for o produtor (extensão antiga, página hostil, defeito).
     */
    com.nekkora.extension.core.snapshot.RawSnapshot snapshot(com.fasterxml.jackson.databind.JsonNode node) throws java.io.IOException {
        return com.nekkora.extension.core.privacy.SnapshotPrivacy.apply(json.read(node, com.nekkora.extension.core.snapshot.RawSnapshot.class));
    }

    /** Idem para deltas: o contexto (portador de nome, ancestrais) vem do delta e da captura-base. */
    com.nekkora.extension.core.mutation.SnapshotDelta privacy(com.nekkora.extension.core.mutation.SnapshotDelta raw, PageAnalysis base) {
        return com.nekkora.extension.core.privacy.SnapshotPrivacy.apply(raw, id -> base == null ? null : base.model().find(id).orElse(null));
    }

    PageAnalysis capture(TabState tab, String captureId) {
        return tab.capture(captureId).orElseThrow(() -> new CommandException("UNKNOWN_CAPTURE",
                captureId == null ? "Nenhuma captura registrada para a aba " + tab.tabKey()
                        : "Captura " + captureId + " não está disponível (capturas mantidas: " + tab.captureIds() + ")"));
    }

    /** Grafo documental da captura com os metadados de frames do navegador conhecidos para a aba. */
    com.nekkora.extension.core.frames.FrameGraph frameGraph(TabState tab, PageAnalysis a) {
        return frameGraphs.build(a, tab.browserFrames());
    }

    /** Entradas do diagnóstico para exportação (metadados de frames + catálogo). */
    public com.nekkora.extension.core.pagemap.PageMapAssembler.DiagnosticsInput diagnosticsInput(TabState tab) {
        return new com.nekkora.extension.core.pagemap.PageMapAssembler.DiagnosticsInput(tab.browserFrames(), catalog);
    }

    com.nekkora.extension.core.diagnostics.AutomationReadinessAssessment readiness(TabState tab, PageAnalysis a,
                                                                                    com.nekkora.extension.core.frames.FrameGraph g,
                                                                                    com.nekkora.extension.core.protection.ProtectionReport pr) {
        return diagnostics.assess(a, g, pr, options.analyzeAll(a.graph(), tab.optionHistory()), tab.mutations());
    }

    static double msBetween(long a, long b) {
        return Math.round((b - a) / 10_000.0) / 100.0;
    }

    static InspectionEvidence evidence(String subject, String kind, String source, List<String> statements) {
        return new InspectionEvidence(subject, kind, source, statements, Instant.now().toString());
    }

    static double ms(long startNanos) {
        return Math.round((System.nanoTime() - startNanos) / 10_000.0) / 100.0;
    }
}
