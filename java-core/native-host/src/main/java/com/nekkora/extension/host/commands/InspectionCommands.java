package com.nekkora.extension.host.commands;

import com.fasterxml.jackson.databind.JsonNode;
import com.nekkora.extension.core.analysis.PageAnalysis;
import com.nekkora.extension.core.identity.IdentitySignature;
import com.nekkora.extension.core.identity.SignatureExtractor;
import com.nekkora.extension.core.locator.LiveCheck;
import com.nekkora.extension.core.locator.LocatorDescriptor;
import com.nekkora.extension.core.locator.LocatorSet;
import com.nekkora.extension.core.options.OptionSetAnalyzer;
import com.nekkora.extension.core.semantic.Classification;
import com.nekkora.extension.host.session.TabState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Inspeção de elemento (classificação, família, localizadores) e verificação ao vivo dos localizadores. */
public final class InspectionCommands implements CommandRouter.CommandGroup {

    private final EngineServices s;
    private final SignatureExtractor signatures = new SignatureExtractor();

    public InspectionCommands(EngineServices services) {
        this.s = services;
    }

    @Override
    public void register(CommandRouter router) {
        router.register("element.inspect", p -> {
            long t0 = System.nanoTime();
            TabState tab = s.session.tab(Payloads.required(p, "tabKey"));
            PageAnalysis a = s.capture(tab, Payloads.optional(p, "captureId"));
            String nodeId = Payloads.required(p, "nodeId");
            if (!a.model().contains(nodeId)) {
                throw new CommandException("UNKNOWN_NODE", "Nó " + nodeId + " não existe na captura " + a.model().snapshot().captureId());
            }
            Classification c = a.semantics().get(nodeId);
            long t1 = System.nanoTime();
            var family = a.graph().family(nodeId);
            long t2 = System.nanoTime();
            LocatorSet locators = a.locators().generate(nodeId);
            long t3 = System.nanoTime();
            IdentitySignature sig = signatures.extract(nodeId, a.graph());
            List<String> lookalikes = s.identities.lookalikes(nodeId, sig, a.graph());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("captureId", a.model().snapshot().captureId());
            out.put("nodeId", nodeId);
            out.put("tag", a.model().node(nodeId).tag());
            out.put("classification", c);
            out.put("family", family);
            out.put("locators", locators);
            out.put("signature", sig);
            out.put("lookalikes", lookalikes);
            if (OptionSetAnalyzer.isChoiceControl(c.kind())) {
                out.put("optionSet", s.options.analyze(nodeId, a.graph(), tab.optionHistory()));
            }
            // CHROME-003: documento, frame e trajetória completa até o elemento.
            var path = FrameCommands.pathOrNull(s, tab, a, nodeId);
            out.put("accessPath", path);
            out.put("documentId", "doc:" + a.model().node(nodeId).frameId());
            out.put("timings", Map.of("familyMs", round((t2 - t1) / 1e6), "locatorMs", round((t3 - t2) / 1e6),
                    "hostTotalMs", EngineServices.ms(t0)));
            List<String> statements = new ArrayList<>();
            statements.add("Classificado como " + c.kind() + " (papel " + c.role() + ", confiança " + c.confidence() + ")");
            c.evidence().forEach(e -> statements.add("evidência: " + e));
            c.uncertainties().forEach(u -> statements.add("incerteza: " + u));
            statements.add(lookalikes.size() + " elemento(s) parecido(s) na página");
            tab.addEvidence(EngineServices.evidence(nodeId, "CLASSIFICATION", "ENGINE", statements));
            return out;
        });

        router.register("locators.verify", p -> {
            TabState tab = s.session.tab(Payloads.required(p, "tabKey"));
            PageAnalysis a = s.capture(tab, Payloads.optional(p, "captureId"));
            String nodeId = Payloads.required(p, "nodeId");
            LocatorSet set = a.locators().generate(nodeId);
            Map<String, LiveCheck> checks = new HashMap<>();
            for (JsonNode c : Payloads.node(p, "checks")) {
                checks.put(c.path("semantic").asText(), new LiveCheck(c.path("cssCount").asInt(-1), c.path("xpathCount").asInt(-1),
                        c.path("cssMatchesTarget").asBoolean(false), c.path("xpathMatchesTarget").asBoolean(false),
                        c.hasNonNull("error") ? c.get("error").asText() : null));
            }
            List<Map<String, Object>> agreement = new ArrayList<>();
            List<LocatorDescriptor> verified = new ArrayList<>();
            boolean all = true;
            int compared = 0;
            for (LocatorDescriptor d : set.all()) {
                LiveCheck live = checks.get(d.semantic());
                verified.add(live == null ? d : d.withLive(live));
                if (live == null || (d.css() == null && d.xpath() == null)) {
                    continue;
                }
                compared++;
                boolean agrees = live.agreesWith(d.matchCount())
                        && (live.cssCount() < 0 || live.cssMatchesTarget())
                        && (live.xpathCount() < 0 || live.xpathMatchesTarget());
                all &= agrees;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("strategy", d.strategy());
                row.put("semantic", d.semantic());
                row.put("css", d.css());
                row.put("xpath", d.xpath());
                row.put("snapshotCount", d.matchCount());
                row.put("cssCount", live.cssCount());
                row.put("xpathCount", live.xpathCount());
                row.put("agrees", agrees);
                row.put("error", live.error());
                agreement.add(row);
            }
            LocatorSet withLive = verified.isEmpty() ? set
                    : new LocatorSet(set.nodeId(), verified.get(0), verified.subList(1, verified.size()), set.warnings());
            List<String> statements = new ArrayList<>();
            statements.add(compared + " localizador(es) executados na DOM real; concordância com o snapshot: " + (all ? "total" : "PARCIAL"));
            for (Map<String, Object> row : agreement) {
                statements.add(row.get("strategy") + " " + (row.get("css") != null ? row.get("css") : row.get("xpath"))
                        + " → snapshot " + row.get("snapshotCount") + ", CSS " + row.get("cssCount") + ", XPath " + row.get("xpathCount"));
            }
            tab.addEvidence(EngineServices.evidence(nodeId, "LIVE_VERIFICATION", "BROWSER_LIVE", statements));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("locators", withLive);
            out.put("agreement", agreement);
            out.put("compared", compared);
            out.put("allAgree", all && compared > 0);
            return out;
        });
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
