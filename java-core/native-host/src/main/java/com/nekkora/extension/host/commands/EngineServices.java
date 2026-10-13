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

    public EngineServices(HostSession session, JsonCodec json) {
        this.session = session;
        this.json = json;
    }

    PageAnalysis capture(TabState tab, String captureId) {
        return tab.capture(captureId).orElseThrow(() -> new CommandException("UNKNOWN_CAPTURE",
                captureId == null ? "Nenhuma captura registrada para a aba " + tab.tabKey()
                        : "Captura " + captureId + " não está disponível (capturas mantidas: " + tab.captureIds() + ")"));
    }

    static InspectionEvidence evidence(String subject, String kind, String source, List<String> statements) {
        return new InspectionEvidence(subject, kind, source, statements, Instant.now().toString());
    }

    static double ms(long startNanos) {
        return Math.round((System.nanoTime() - startNanos) / 10_000.0) / 100.0;
    }
}
