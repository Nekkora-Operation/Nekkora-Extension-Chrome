package com.nekkora.extension.host.commands;

import com.fasterxml.jackson.databind.JsonNode;
import com.nekkora.extension.core.analysis.PageAnalysis;
import com.nekkora.extension.core.diagnostics.AutomationReadinessAssessment;
import com.nekkora.extension.core.frames.BrowserFrameMetadata;
import com.nekkora.extension.core.frames.ElementAccessPath;
import com.nekkora.extension.core.frames.FrameGraph;
import com.nekkora.extension.core.protection.ProtectionReport;
import com.nekkora.extension.host.session.TabState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Comandos da inteligência documental e do diagnóstico (CHROME-003):
 * <ul>
 *   <li>{@code capture.frames.metadata} — metadados de frames do navegador (frameId/documentId do Chrome);</li>
 *   <li>{@code frames.graph} — grafo documental;</li>
 *   <li>{@code element.accessPath} — trajetória de acesso a um nó;</li>
 *   <li>{@code protection.assess} — diagnóstico de proteções;</li>
 *   <li>{@code diagnostics.readiness} — avaliação de prontidão (obstáculos, recomendações);</li>
 *   <li>{@code page.diagnose} — resumo dos três anteriores para o painel (sem o PageMap).</li>
 * </ul>
 */
public final class FrameCommands implements CommandRouter.CommandGroup {

    private final EngineServices s;

    public FrameCommands(EngineServices services) {
        this.s = services;
    }

    @Override
    public void register(CommandRouter router) {
        router.register("capture.frames.metadata", p -> {
            TabState tab = s.session.tab(Payloads.required(p, "tabKey"));
            List<BrowserFrameMetadata> frames = new ArrayList<>();
            for (JsonNode f : p.path("frames")) {
                frames.add(s.json.read(f, BrowserFrameMetadata.class));
            }
            tab.browserFrames(frames);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("frames", frames.size());
            tab.latest().ifPresent(a -> out.put("counts", s.frameGraph(tab, a).counts()));
            return out;
        });

        router.register("frames.graph", p -> {
            TabState tab = s.session.tab(Payloads.required(p, "tabKey"));
            PageAnalysis a = s.capture(tab, Payloads.optional(p, "captureId"));
            return s.frameGraph(tab, a);
        });

        router.register("element.accessPath", p -> {
            TabState tab = s.session.tab(Payloads.required(p, "tabKey"));
            PageAnalysis a = s.capture(tab, Payloads.optional(p, "captureId"));
            String nodeId = Payloads.required(p, "nodeId");
            if (!a.model().contains(nodeId)) {
                throw new CommandException("UNKNOWN_NODE", "Nó " + nodeId + " não existe na captura " + a.model().snapshot().captureId());
            }
            return s.accessPaths.build(a, s.frameGraph(tab, a), nodeId);
        });

        router.register("protection.assess", p -> {
            TabState tab = s.session.tab(Payloads.required(p, "tabKey"));
            PageAnalysis a = s.capture(tab, Payloads.optional(p, "captureId"));
            return s.protections.assess(a, s.frameGraph(tab, a), s.catalog);
        });

        router.register("diagnostics.readiness", p -> {
            TabState tab = s.session.tab(Payloads.required(p, "tabKey"));
            PageAnalysis a = s.capture(tab, Payloads.optional(p, "captureId"));
            FrameGraph g = s.frameGraph(tab, a);
            ProtectionReport pr = s.protections.assess(a, g, s.catalog);
            return s.readiness(tab, a, g, pr);
        });

        router.register("page.diagnose", p -> {
            long t0 = System.nanoTime();
            TabState tab = s.session.tab(Payloads.required(p, "tabKey"));
            PageAnalysis a = s.capture(tab, Payloads.optional(p, "captureId"));
            FrameGraph g = s.frameGraph(tab, a);
            long t1 = System.nanoTime();
            ProtectionReport pr = s.protections.assess(a, g, s.catalog);
            long t2 = System.nanoTime();
            AutomationReadinessAssessment r = s.readiness(tab, a, g, pr);
            long t3 = System.nanoTime();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("captureId", a.model().snapshot().captureId());
            out.put("counts", g.counts());
            out.put("maxDepth", g.maxDepth());
            out.put("frames", g.frames().stream().map(f -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("frameId", f.frameId());
                m.put("depth", f.depth());
                m.put("label", f.title() != null ? f.title() : f.name() != null ? f.name() : f.effectiveUrl());
                m.put("status", f.status());
                m.put("parentFrameId", f.parentFrameId());
                return m;
            }).toList());
            out.put("protection", pr);
            out.put("readiness", r);
            out.put("timings", Map.of("graphMs", EngineServices.msBetween(t0, t1), "protectionMs", EngineServices.msBetween(t1, t2),
                    "readinessMs", EngineServices.msBetween(t2, t3), "totalMs", EngineServices.ms(t0)));
            return out;
        });
    }

    /** Trajetória pronta para anexar a respostas de inspeção. */
    static ElementAccessPath pathOrNull(EngineServices s, TabState tab, PageAnalysis a, String nodeId) {
        try {
            return s.accessPaths.build(a, s.frameGraph(tab, a), nodeId);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
