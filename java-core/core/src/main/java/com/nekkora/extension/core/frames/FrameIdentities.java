package com.nekkora.extension.core.frames;

import com.nekkora.extension.core.context.ContextGraph;
import com.nekkora.extension.core.context.ElementFamily;
import com.nekkora.extension.core.locator.IdentifierHeuristics;
import com.nekkora.extension.core.locator.StabilityLevel;
import com.nekkora.extension.core.snapshot.FrameInfo;
import com.nekkora.extension.core.snapshot.RawNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cálculo da {@link FrameIdentity} a partir do grafo de contexto (sem depender do {@code frameId} do Chrome nem
 * do índice do iframe). Usado no registro de alvos e no reencontro entre documentos.
 */
public final class FrameIdentities {

    private static final IdentifierHeuristics IDS = new IdentifierHeuristics();
    private static final List<String> STABLE_ATTRIBUTES = List.of("data-testid", "data-test", "data-qa", "aria-label");

    private FrameIdentities() {
    }

    /** Frame lógico hospedado pelo iframe {@code host}, quando inventariado. */
    public static FrameInfo frameOf(ContextGraph g, RawNode host) {
        for (FrameInfo f : g.model().snapshot().frames()) {
            if (host.id().equals(f.hostNodeId())) {
                return f;
            }
        }
        return null;
    }

    /** Identidade do frame hospedado pelo iframe {@code host}. */
    public static FrameIdentity forHost(ContextGraph g, RawNode host) {
        List<FrameIdentity> above = chainFor(g, host);
        String parentKey = above.isEmpty() ? "main" : above.get(above.size() - 1).key();
        FrameInfo fi = frameOf(g, host);
        FrameInfo parent = parentInfo(g, host.frameId());
        String declared = UrlSanitizer.sanitize(UrlSanitizer.resolve(parent == null ? null : parent.url(), host.attr("src")));
        String effective = fi == null ? null : UrlSanitizer.sanitize(fi.url());
        String url = effective != null && !effective.startsWith("about:") ? effective : declared != null ? declared : effective;
        String sandbox = host.attr("sandbox");
        String origin = sandbox != null && !sandbox.toLowerCase().contains("allow-same-origin") ? "null" : UrlSanitizer.origin(url);
        return of(g, host, url, origin, above.size() + 1, parentKey);
    }

    /** Identidades dos frames que precisam ser atravessados até o documento do nó (do mais externo ao imediato). */
    public static List<FrameIdentity> chainFor(ContextGraph g, RawNode node) {
        List<RawNode> hosts = new ArrayList<>();
        RawNode current = node;
        int guard = 0;
        while (current != null && guard++ < 128 && !current.scopeId().equals("doc:f0")) {
            String scope = current.scopeId();
            RawNode next;
            if (scope.startsWith("shadow:")) {
                next = g.model().find(scope.substring("shadow:".length())).orElse(null);
            } else {
                String frameId = scope.substring("doc:".length());
                String hostId = null;
                for (FrameInfo f : g.model().snapshot().frames()) {
                    if (f.frameId().equals(frameId)) {
                        hostId = f.hostNodeId();
                    }
                }
                next = hostId == null ? null : g.model().find(hostId).orElse(null);
                if (next != null) {
                    hosts.add(0, next);
                }
            }
            current = next;
        }
        List<FrameIdentity> out = new ArrayList<>();
        String parentKey = "main";
        for (RawNode host : hosts) {
            FrameInfo fi = frameOf(g, host);
            FrameInfo parent = parentInfo(g, host.frameId());
            String declared = UrlSanitizer.sanitize(UrlSanitizer.resolve(parent == null ? null : parent.url(), host.attr("src")));
            String effective = fi == null ? null : UrlSanitizer.sanitize(fi.url());
            String url = effective != null && !effective.startsWith("about:") ? effective : declared != null ? declared : effective;
            String sandbox = host.attr("sandbox");
            String origin = sandbox != null && !sandbox.toLowerCase().contains("allow-same-origin") ? "null" : UrlSanitizer.origin(url);
            FrameIdentity id = of(g, host, url, origin, out.size() + 1, parentKey);
            out.add(id);
            parentKey = id.key();
        }
        return out;
    }

    static FrameIdentity of(ContextGraph g, RawNode host, String url, String origin, int depth, String parentKey) {
        String id = host.attr("id");
        if (id != null && IDS.assess(id).level() == StabilityLevel.HEURISTIC_UNSTABLE) {
            id = null;
        }
        Map<String, String> stable = new LinkedHashMap<>();
        for (String attr : STABLE_ATTRIBUTES) {
            String v = host.attr(attr);
            if (v != null && !v.isBlank() && !com.nekkora.extension.core.privacy.CapturePrivacyPolicy.HOLE.equals(v)) {
                stable.put(attr, v);
            }
        }
        String title = shown(host.attr("title")) != null ? shown(host.attr("title")) : shown(host.attr("aria-label"));
        return new FrameIdentity(id, host.attr("name"), title, stable, container(g, host), UrlSanitizer.path(url), origin, depth,
                parentKey, host.scopeId().startsWith("shadow:"));
    }

    /** Valor retido pela política ({…}) não é título. */
    private static String shown(String v) {
        return v == null || com.nekkora.extension.core.privacy.CapturePrivacyPolicy.HOLE.equals(v) ? null : v;
    }

    static String container(ContextGraph g, RawNode host) {
        try {
            ElementFamily fam = g.family(host.id());
            if (fam.form() != null) {
                return "form:" + nonBlank(fam.form().idAttr(), fam.form().name());
            }
            if (fam.dialog() != null) {
                return "dialog:" + nonBlank(fam.dialog().idAttr(), fam.dialog().name());
            }
            if (fam.region() != null) {
                return "region:" + nonBlank(fam.region().idAttr(), fam.region().name());
            }
            if (fam.containerHeading() != null && !fam.containerHeading().isBlank()) {
                return "heading:" + fam.containerHeading();
            }
        } catch (RuntimeException ignored) {
            // família indisponível
        }
        for (RawNode anc : g.model().ancestorsInScope(host)) {
            String id = anc.attr("id");
            if (id != null && !id.isBlank() && IDS.assess(id).level() != StabilityLevel.HEURISTIC_UNSTABLE) {
                return anc.tag() + "#" + id;
            }
        }
        return null;
    }

    private static FrameInfo parentInfo(ContextGraph g, String frameId) {
        for (FrameInfo f : g.model().snapshot().frames()) {
            if (f.frameId().equals(frameId)) {
                return f;
            }
        }
        return null;
    }

    private static String nonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : (b == null ? "" : b);
    }
}
