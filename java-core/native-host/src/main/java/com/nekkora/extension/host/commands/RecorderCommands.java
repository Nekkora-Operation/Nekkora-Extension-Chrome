package com.nekkora.extension.host.commands;

import com.fasterxml.jackson.databind.JsonNode;
import com.nekkora.extension.core.analysis.PageAnalysis;
import com.nekkora.extension.core.context.ElementFamily;
import com.nekkora.extension.core.context.NodeRef;
import com.nekkora.extension.core.frames.ElementAccessPath;
import com.nekkora.extension.core.identity.TargetIdentity;
import com.nekkora.extension.core.locator.LiveCheck;
import com.nekkora.extension.core.locator.LocatorDescriptor;
import com.nekkora.extension.core.locator.LocatorSet;
import com.nekkora.extension.core.privacy.Privacy;
import com.nekkora.extension.core.semantic.Classification;
import com.nekkora.extension.host.session.TabState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Referência COMPACTA de elemento para o Recorder do Studio (STUDIO-003). Reúne, numa única chamada, o que a inspeção
 * manual obtém em várias (classificação, localizadores, identidade lógica, trajetória documental e contexto), sem
 * PageMap e sem evidências volumosas: cada etapa gravada carrega só o necessário para ser apresentada e reencontrada.
 *
 * <p>Privacidade: nome e textos passam por {@link Privacy#maskText}; {@code id}/{@code name} HTML com aparência de
 * segredo, ou de elemento sensível, não saem. Localizadores vêm do snapshot já sanitizado na captura.
 */
public final class RecorderCommands implements CommandRouter.CommandGroup {

    static final int MAX_LOCATORS = 5;
    private final EngineServices s;

    public RecorderCommands(EngineServices services) {
        this.s = services;
    }

    @Override
    public void register(CommandRouter router) {
        router.register("recorder.element.describe", p -> {
            long t0 = System.nanoTime();
            TabState tab = s.session.tab(Payloads.required(p, "tabKey"));
            PageAnalysis a = s.capture(tab, Payloads.optional(p, "captureId"));
            String nodeId = Payloads.required(p, "nodeId");
            if (!a.model().contains(nodeId)) {
                throw new CommandException("UNKNOWN_NODE", "Nó " + nodeId + " não existe na captura " + a.model().snapshot().captureId());
            }
            Classification c = a.semantics().get(nodeId);
            LocatorSet set = a.locators().generate(nodeId);
            TargetIdentity identity = s.identities.register(nodeId, a.graph(), a.locators(), a.model().snapshot().captureId());
            String ownerSession = s.session.captureSessions().forTab(tab.tabKey()).map(x -> x.sessionId()).orElse(null);
            s.session.targets().put(identity, new com.nekkora.extension.host.session.TargetRegistry.Owner(tab.tabKey(), ownerSession));
            ElementFamily family = a.graph().family(nodeId);
            ElementAccessPath path = FrameCommands.pathOrNull(s, tab, a, nodeId);
            var node = a.model().node(nodeId);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("resolution", "RESOLVED");
            out.put("captureId", a.model().snapshot().captureId());
            out.put("nodeId", nodeId);
            out.put("targetId", identity.targetId());
            out.put("documentId", "doc:" + node.frameId());
            out.put("kind", c.kind().name());
            out.put("role", c.role());
            out.put("name", text(c.name().name(), 120));
            out.put("tag", node.tag());
            out.put("htmlId", c.sensitive() ? null : attr(node.attr("id")));
            out.put("htmlName", c.sensitive() ? null : attr(node.attr("name")));
            out.put("sensitive", c.sensitive());
            out.put("locators", locators(set));
            out.put("preferredLocator", preferred(set));
            out.put("accessPath", path(path));
            out.put("context", context(family));
            out.put("lookalikes", identity.lookalikeCount());
            List<String> notes = new ArrayList<>();
            if (set.all().stream().noneMatch(LocatorDescriptor::unique)) {
                notes.add("NO_UNIQUE_LOCATOR");
            }
            if (identity.indistinguishableAtRegistration()) {
                notes.add("INDISTINGUISHABLE_LOOKALIKES");
            }
            if (path != null && !path.accessible()) {
                notes.add("ACCESS_RESTRICTED");
            }
            out.put("notes", notes);
            out.put("uncertainties", c.uncertainties().stream().limit(3).map(u -> text(u, 160)).toList());
            // Para a verificação AO VIVO no documento (escopo de iframe/shadow incluído). Fica no service worker:
            // a etapa enviada ao Studio leva só a forma compacta acima.
            List<LocatorDescriptor> all = set.all();
            Map<String, Object> verification = new LinkedHashMap<>();
            verification.put("primary", all.isEmpty() ? null : all.get(0));
            verification.put("fallbacks", all.size() <= 1 ? List.of() : all.subList(1, Math.min(all.size(), MAX_LOCATORS)));
            out.put("verification", verification);
            out.put("hostMs", EngineServices.ms(t0));
            return out;
        });

        router.register("recorder.element.verify", p -> {
            TabState tab = s.session.tab(Payloads.required(p, "tabKey"));
            PageAnalysis a = s.capture(tab, Payloads.optional(p, "captureId"));
            String nodeId = Payloads.required(p, "nodeId");
            LocatorSet set = a.locators().generate(nodeId);
            Map<String, LiveCheck> checks = new LinkedHashMap<>();
            for (JsonNode c : Payloads.node(p, "checks")) {
                checks.put(c.path("semantic").asText(), new LiveCheck(c.path("cssCount").asInt(-1), c.path("xpathCount").asInt(-1),
                        c.path("cssMatchesTarget").asBoolean(false), c.path("xpathMatchesTarget").asBoolean(false),
                        c.hasNonNull("error") ? c.get("error").asText() : null));
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            List<LocatorDescriptor> all = set.all();
            for (int i = 0; i < Math.min(all.size(), MAX_LOCATORS); i++) {
                LocatorDescriptor d = all.get(i);
                LiveCheck live = checks.get(d.semantic());
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("index", i);
                row.put("checked", live != null && (d.css() != null || d.xpath() != null));
                boolean agrees = live != null && live.agreesWith(d.matchCount())
                        && (live.cssCount() < 0 || live.cssMatchesTarget()) && (live.xpathCount() < 0 || live.xpathMatchesTarget());
                row.put("liveUnique", agrees && d.unique());
                rows.add(row);
            }
            return Map.of("checks", rows);
        });
    }

    private static List<Map<String, Object>> locators(LocatorSet set) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (LocatorDescriptor d : set.all()) {
            if (out.size() >= MAX_LOCATORS) {
                break;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("strategy", d.strategy().name());
            m.put("description", text(d.semantic(), 200));
            m.put("css", d.css());
            m.put("xpath", d.xpath());
            m.put("matchCount", d.matchCount());
            m.put("matchesTarget", d.matchesTarget());
            m.put("stability", d.stability().name());
            out.add(m);
        }
        return out;
    }

    /** Índice do primeiro localizador ÚNICO no snapshot (correspondência exata do alvo), ou -1. */
    private static int preferred(LocatorSet set) {
        List<LocatorDescriptor> all = set.all();
        for (int i = 0; i < Math.min(all.size(), MAX_LOCATORS); i++) {
            if (all.get(i).unique()) {
                return i;
            }
        }
        return -1;
    }

    private static List<Map<String, Object>> path(ElementAccessPath path) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (path == null) {
            return out;
        }
        for (ElementAccessPath.Step st : path.steps()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", st.type());
            m.put("label", text(st.label(), 120));
            m.put("locator", st.locator());
            m.put("restriction", st.restriction());
            out.add(m);
        }
        return out;
    }

    /** Âncoras de contexto de que a identificação depende (diálogo, linha de tabela, formulário, item de lista). */
    private static List<Map<String, Object>> context(ElementFamily f) {
        List<Map<String, Object>> out = new ArrayList<>();
        anchor(out, "IN_DIALOG", f.dialog());
        if (f.row() != null) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("relation", "IN_ROW");
            m.put("kind", "TABLE_ROW");
            m.put("name", text(String.join(" · ", f.row().cellTexts().stream().limit(3).toList()), 120));
            out.add(m);
        }
        anchor(out, "IN_LIST_ITEM", f.listItem());
        anchor(out, "IN_FORM", f.form());
        return out;
    }

    private static void anchor(List<Map<String, Object>> out, String relation, NodeRef ref) {
        if (ref == null) {
            return;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("relation", relation);
        m.put("kind", ref.kind() == null ? "UNKNOWN" : ref.kind().name());
        m.put("name", text(ref.name(), 120));
        out.add(m);
    }

    private static String attr(String v) {
        if (v == null || v.isBlank() || v.length() > 120 || Privacy.looksSecret(v)) {
            return null;
        }
        String masked = Privacy.maskText(v);
        return masked.equals(v) ? v : null; // id/name com dado pessoal: não sai
    }

    static String text(String v, int max) {
        if (v == null) {
            return "";
        }
        String t = Privacy.maskText(v.replaceAll("\\s+", " ").strip());
        return t.length() > max ? t.substring(0, max) : t;
    }
}
