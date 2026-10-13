package com.nekkora.extension.host.commands;

import com.nekkora.extension.core.analysis.PageAnalysis;
import com.nekkora.extension.core.identity.TargetIdentity;
import com.nekkora.extension.core.resolve.ResolutionResult;
import com.nekkora.extension.core.resolve.ResolutionStatus;
import com.nekkora.extension.host.session.TabState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Registro de identidades lógicas e reencontro. */
public final class TargetCommands implements CommandRouter.CommandGroup {

    private final EngineServices s;

    public TargetCommands(EngineServices services) {
        this.s = services;
    }

    @Override
    public void register(CommandRouter router) {
        router.register("target.register", p -> {
            TabState tab = s.session.tab(Payloads.required(p, "tabKey"));
            PageAnalysis a = s.capture(tab, Payloads.optional(p, "captureId"));
            String nodeId = Payloads.required(p, "nodeId");
            if (!a.model().contains(nodeId)) {
                throw new CommandException("UNKNOWN_NODE", "Nó " + nodeId + " não existe na captura " + a.model().snapshot().captureId());
            }
            TargetIdentity identity = s.identities.register(nodeId, a.graph(), a.locators(), a.model().snapshot().captureId());
            s.session.targets().put(identity);
            List<String> statements = new ArrayList<>();
            statements.add("Alvo lógico " + identity.targetId() + " registrado a partir do nó temporário " + nodeId);
            statements.add(identity.lookalikeCount() + " elemento(s) parecido(s); discriminadores: " + identity.discriminators());
            identity.warnings().forEach(w -> statements.add("alerta: " + w));
            tab.addEvidence(EngineServices.evidence(identity.targetId(), "IDENTITY", "ENGINE", statements));
            return identity;
        });

        router.register("target.resolve", p -> {
            TabState tab = s.session.tab(Payloads.required(p, "tabKey"));
            String targetId = Payloads.required(p, "targetId");
            PageAnalysis a = s.capture(tab, Payloads.optional(p, "captureId"));
            TargetIdentity identity = s.session.targets().get(targetId)
                    .orElseThrow(() -> new CommandException("UNKNOWN_TARGET", "Alvo não registrado: " + targetId));
            long t0 = System.nanoTime();
            ResolutionResult r = s.resolver.resolve(identity, a.graph(), a.locators());
            double ms = EngineServices.ms(t0);
            if (r.status() == ResolutionStatus.RESOLVED && r.updatedIdentity() != null) {
                s.session.targets().put(r.updatedIdentity());
            }
            List<String> statements = new ArrayList<>();
            statements.add("Resultado: " + r.status() + (r.resolvedNodeId() == null ? "" : " → " + r.resolvedNodeId()));
            statements.addAll(r.evidence());
            tab.addEvidence(EngineServices.evidence(targetId, "RESOLUTION", "ENGINE", statements));
            tab.addResolution(r);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("result", r);
            out.put("timings", Map.of("resolveMs", ms));
            return out;
        });

        router.register("target.list", p -> s.session.targets().all());

        router.register("target.get", p -> s.session.targets().get(Payloads.required(p, "targetId"))
                .orElseThrow(() -> new CommandException("UNKNOWN_TARGET", "Alvo não registrado")));
    }
}
