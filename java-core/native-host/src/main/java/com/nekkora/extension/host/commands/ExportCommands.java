package com.nekkora.extension.host.commands;

import com.fasterxml.jackson.databind.JsonNode;
import com.nekkora.extension.core.analysis.PageAnalysis;
import com.nekkora.extension.core.identity.TargetIdentity;
import com.nekkora.extension.core.interaction.InteractionObservation;
import com.nekkora.extension.core.pagemap.PageMap;
import com.nekkora.extension.core.pagemap.PageMapOptions;
import com.nekkora.extension.core.semantic.ElementCategory;
import com.nekkora.extension.host.session.TabState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Exportação do PageMap e observação de interações. */
public final class ExportCommands implements CommandRouter.CommandGroup {

    private final EngineServices s;

    public ExportCommands(EngineServices services) {
        this.s = services;
    }

    @Override
    public void register(CommandRouter router) {
        router.register("pagemap.export", p -> {
            long t0 = System.nanoTime();
            TabState tab = s.session.tab(Payloads.required(p, "tabKey"));
            PageAnalysis a = s.capture(tab, Payloads.optional(p, "captureId"));
            PageMapOptions opts = new PageMapOptions(
                    Payloads.flag(p, "includeFamilies", true) ? EnumSet.of(ElementCategory.INTERACTIVE, ElementCategory.TEXT) : EnumSet.noneOf(ElementCategory.class),
                    Payloads.flag(p, "includeLocators", true) ? EnumSet.of(ElementCategory.INTERACTIVE) : EnumSet.noneOf(ElementCategory.class),
                    Payloads.flag(p, "includeContainers", false), p.path("maxElements").asInt(20_000));
            String url = a.model().snapshot().document() == null ? null : a.model().snapshot().document().url();
            List<TargetIdentity> targets = new ArrayList<>();
            for (TargetIdentity t : s.session.targets().all()) {
                if (url != null && t.origin() != null && url.startsWith(t.origin())) {
                    targets.add(t);
                }
            }
            PageMap map = s.assembler.assemble(a, opts, tab.optionHistory(), targets, tab.resolutions(), tab.mutations(),
                    tab.evidence(), Instant.now().toString(), "FULL");
            Map<String, Object> out = new LinkedHashMap<>();
            Path file = null;
            if (Payloads.flag(p, "persist", true) && s.session.store() != null) {
                file = s.session.store().write("pagemaps", a.model().snapshot().captureId(), map);
            }
            out.put("path", file == null ? null : file.toString());
            out.put("bytes", file == null ? -1 : Files.size(file));
            out.put("exportMs", EngineServices.ms(t0));
            out.put("pageMap", Payloads.flag(p, "returnPageMap", true) ? map : null);
            return out;
        });

        router.register("interaction.observe", p -> {
            TabState tab = s.session.tab(Payloads.required(p, "tabKey"));
            PageAnalysis before = s.capture(tab, Payloads.required(p, "beforeCaptureId"));
            PageAnalysis after = s.capture(tab, Payloads.optional(p, "afterCaptureId"));
            Map<String, Object> instrumentation = null;
            JsonNode inst = p.get("instrumentation");
            if (inst != null && !inst.isNull()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = s.json.mapper().convertValue(inst, Map.class);
                instrumentation = m;
            }
            InteractionObservation o = s.interactions.analyze(before.graph(), after.graph(), instrumentation);
            List<String> statements = new ArrayList<>();
            statements.add("[URL] " + o.urlChange().kind() + ": " + o.urlChange().queryChanges());
            o.uiQueries().forEach(q -> statements.add("[DOM] campo " + q.label() + ": \"" + q.before() + "\" → \"" + q.after() + "\""));
            o.apiRequests().forEach(r -> statements.add("[RESOURCE_TIMING] " + r.initiatorType() + " " + r.url()));
            o.resultRegions().forEach(r -> statements.add("[DOM] " + r.kind() + " " + r.name() + ": " + r.itemsBefore() + " → " + r.itemsAfter() + " itens"));
            if (o.instrumentation() != null && !o.instrumentation().isEmpty()) {
                statements.add("[TEST_INSTRUMENTATION] " + o.instrumentation());
            }
            tab.addEvidence(EngineServices.evidence(Objects.toString(after.model().snapshot().captureId()), "INTERACTION", "MIXED", statements));
            return o;
        });
    }
}
