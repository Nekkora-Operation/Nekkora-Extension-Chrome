package com.nekkora.extension.core.privacy;

import com.nekkora.extension.core.frames.UrlSanitizer;
import com.nekkora.extension.core.mutation.SnapshotDelta;
import com.nekkora.extension.core.snapshot.DocumentInfo;
import com.nekkora.extension.core.snapshot.FrameInfo;
import com.nekkora.extension.core.snapshot.ObservedRequest;
import com.nekkora.extension.core.snapshot.RawNode;
import com.nekkora.extension.core.snapshot.RawSnapshot;
import com.nekkora.extension.core.snapshot.ResourceRef;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * CHROME-SECURITY-001 — 3ª barreira: aplica {@code nekkora-privacy/2} a TODO snapshot e delta que o Native Host recebe,
 * antes do motor, mesmo que o produtor (extensão antiga, página hostil, defeito) não o tenha feito:
 *
 * <ul>
 *   <li>atributos pela categoria ({@link CapturePrivacyPolicy#attributes});</li>
 *   <li>texto só atravessa dentro de um PORTADOR de nome cujo texto inteiro (sem subárvores de valor) passe no
 *       vocabulário; o resto vira presença ({@code textPresent}/{@code textSuppressed}), nunca conteúdo;</li>
 *   <li>URLs de documento, frames, requisições e recursos como modelo; títulos só se passarem no vocabulário;</li>
 *   <li>{@code exposedValue} nunca.</li>
 * </ul>
 *
 * É idempotente: aplicada a um snapshot já filtrado pela página, não muda nada.
 */
public final class SnapshotPrivacy {

    private static final Set<String> VALUE_SUBTREES = Set.of("select", "option", "optgroup", "datalist", "textarea", "script", "style",
            "template", "noscript");
    private static final int MAX_DEPTH = 8;

    private SnapshotPrivacy() {
    }

    /** Snapshot completo (ou documento de frame anexado). */
    public static RawSnapshot apply(RawSnapshot s) {
        if (s == null) {
            return null;
        }
        Map<String, RawNode> idx = index(s.nodes());
        List<RawNode> nodes = nodes(s.nodes(), idx::get);
        return new RawSnapshot(s.captureId(), s.capturedAt(), document(s.document()), frames(s.frames()), s.shadowRoots(), nodes,
                requests(s.requests()), s.stats(), resources(s.resources()));
    }

    /** Delta: o contexto (ancestrais, portador) vem do próprio delta e, na falta, da captura-base. */
    public static SnapshotDelta apply(SnapshotDelta d, Function<String, RawNode> base) {
        if (d == null) {
            return null;
        }
        Map<String, RawNode> up = index(d.upserts());
        Function<String, RawNode> look = id -> {
            RawNode n = up.get(id);
            return n != null ? n : base == null ? null : base.apply(id);
        };
        return d.withContent(document(d.document()), nodes(d.upserts(), look), d.frames() == null ? null : frames(d.frames()),
                requests(d.requests()), resources(d.resources()));
    }

    private static Map<String, RawNode> index(List<RawNode> nodes) {
        Map<String, RawNode> idx = new HashMap<>();
        if (nodes != null) {
            for (RawNode n : nodes) {
                idx.put(n.id(), n);
            }
        }
        return idx;
    }

    // ------------------------------------------------------------------------------------------ nós

    private static List<RawNode> nodes(List<RawNode> nodes, Function<String, RawNode> look) {
        if (nodes == null) {
            return List.of();
        }
        Map<String, Map<String, List<String>>> refs = refsOf(nodes);
        Pass pass = new Pass(look, labelTargets(refs));
        List<RawNode> out = new ArrayList<>(nodes.size());
        for (RawNode n : nodes) {
            out.add(node(n, pass, refs.get(n.id())));
        }
        return out;
    }

    /**
     * Relações por id de cada nó. Produtor novo: as {@code refs} que a página resolveu. Produtor ANTIGO (valor do
     * atributo com ids): resolvidas AQUI, antes de o valor ser retido — a relação continua, o id da página não.
     */
    private static Map<String, Map<String, List<String>>> refsOf(List<RawNode> nodes) {
        Map<String, String> byIdAttr = new HashMap<>();
        for (RawNode n : nodes) {
            String id = n.attr("id");
            if (id != null && !id.isEmpty()) {
                byIdAttr.putIfAbsent(n.scopeId() + "|" + id, n.id()); // getElementById: o primeiro
            }
        }
        Map<String, Map<String, List<String>>> out = new HashMap<>();
        for (RawNode n : nodes) {
            Map<String, List<String>> r = new LinkedHashMap<>(n.refs());
            for (String attr : CapturePrivacyPolicy.IDREFS) {
                String v = n.attr(attr);
                if (r.containsKey(attr) || v == null || v.isBlank()) {
                    continue;
                }
                List<String> ids = new ArrayList<>();
                for (String token : v.trim().split("\\s+")) {
                    String t = byIdAttr.get(n.scopeId() + "|" + token);
                    if (t != null) {
                        ids.add(t);
                    }
                }
                if (!ids.isEmpty()) {
                    r.put(attr, List.copyOf(ids));
                }
            }
            out.put(n.id(), r);
        }
        return out;
    }

    private static RawNode node(RawNode n, Pass pass, Map<String, List<String>> refs) {
        Map<String, String> attrs = CapturePrivacyPolicy.attributes(n.attributes(), n.tag());
        String own = n.ownText();
        boolean present = !own.isBlank() || Boolean.TRUE.equals(n.textPresent());
        boolean eligible = !own.isBlank() && textEligible(n, pass);
        String keptText = eligible ? CapturePrivacyPolicy.clean(own) : "";
        List<String> segs = eligible ? n.textSegments() : null;
        boolean suppressed = Boolean.TRUE.equals(n.textSuppressed()) || !own.isBlank() && !eligible;
        // 2ª revisão dos PRs #7: nome de elemento fora do catálogo HTML/SVG/MathML e do vocabulário => marcador neutro
        String tag = CapturePrivacyPolicy.tagName(n.tag());
        return new RawNode(n.id(), n.parentId(), n.childIds(), tag == null ? CapturePrivacyPolicy.NEUTRAL_TAG : tag, n.frameId(), n.scopeId(),
                attrs, keptText, n.state(), n.rect(),
                n.labelIds(), n.scroll(), null, segs, present ? Boolean.TRUE : n.textPresent(), suppressed ? Boolean.TRUE : null,
                refs == null ? n.refs() : refs, n.textRevision(), n.childPositions());
    }

    /** Estado de uma passada: portadores aprovados e alvos de aria-labelledby. */
    private record Pass(Function<String, RawNode> look, Set<String> labelTargets, Map<String, Boolean> carrierOk) {
        Pass(Function<String, RawNode> look, Set<String> labelTargets) {
            this(look, labelTargets, new HashMap<>());
        }
    }

    /** Nós referenciados por aria-labelledby (nome de interface por referência). */
    private static Set<String> labelTargets(Map<String, Map<String, List<String>>> refs) {
        Set<String> out = new HashSet<>();
        for (Map<String, List<String>> r : refs.values()) {
            List<String> l = r.get("aria-labelledby");
            if (l != null) {
                out.addAll(l);
            }
        }
        return out;
    }

    private static boolean editable(RawNode n) {
        String ce = n.attr("contenteditable");
        return ce != null && (ce.isEmpty() || ce.equals("true") || ce.equals("plaintext-only"));
    }

    private static RawNode carrierOf(RawNode n, Pass pass) {
        RawNode e = n;
        for (int i = 0; e != null && i < MAX_DEPTH; i++) {
            if (VALUE_SUBTREES.contains(e.tag()) || editable(e)) {
                return null;
            }
            if (CapturePrivacyPolicy.isCarrier(e.tag(), e.attr("role"), e.hasAttr("href")) || pass.labelTargets().contains(e.id())
                    || CapturePrivacyPolicy.isRowHeader(e.tag(), e.attr("role"), e.attr("scope"), rowHasDataCell(e, pass))) {
                return e;
            }
            e = e.parentId() == null ? null : pass.look().apply(e.parentId());
        }
        return null;
    }

    /** th numa linha (tr) que também tem td: cabeçalho de LINHA, não de coluna. */
    private static boolean rowHasDataCell(RawNode e, Pass pass) {
        if (!"th".equals(e.tag()) || e.parentId() == null) {
            return false;
        }
        RawNode row = pass.look().apply(e.parentId());
        if (row == null || !"tr".equals(row.tag())) {
            return false;
        }
        for (String cid : row.childIds()) {
            RawNode c = pass.look().apply(cid);
            if (c != null && "td".equals(c.tag())) {
                return true;
            }
        }
        return false;
    }

    private static boolean textEligible(RawNode n, Pass pass) {
        RawNode c = carrierOf(n, pass);
        if (c == null) {
            return false;
        }
        boolean ok = pass.carrierOk().computeIfAbsent(c.id(), k -> {
            StringBuilder sb = new StringBuilder();
            gather(c, pass, sb, 0);
            String t = CapturePrivacyPolicy.clean(sb.toString());
            return t.isEmpty() || CapturePrivacyPolicy.vocabularyOnly(t);
        });
        // texto de subárvore decorativa (aria-hidden) abaixo do portador ficou FORA da aprovação: nunca sai
        for (RawNode e = n; ok && e != null && !e.id().equals(c.id()); e = e.parentId() == null ? null : pass.look().apply(e.parentId())) {
            if (decorative(e)) {
                return false;
            }
        }
        return ok;
    }

    /** aria-hidden="true": fora do nome acessível (ex.: a seta de "Next <span aria-hidden>→</span>"). */
    private static boolean decorative(RawNode n) {
        return "true".equals(n.attr("aria-hidden"));
    }

    private static void gather(RawNode n, Pass pass, StringBuilder sb, int depth) {
        if (depth > 12 || sb.length() > 400) {
            return;
        }
        sb.append(' ').append(n.ownText());
        for (String cid : n.childIds()) {
            RawNode c = pass.look().apply(cid);
            if (c != null && !VALUE_SUBTREES.contains(c.tag()) && !editable(c) && !decorative(c)) {
                gather(c, pass, sb, depth + 1);
            }
        }
    }

    // ------------------------------------------------------------------------------------------ documento, frames, URLs

    private static DocumentInfo document(DocumentInfo d) {
        if (d == null) {
            return null;
        }
        return new DocumentInfo(CapturePrivacyPolicy.urlTemplate(d.url()), CapturePrivacyPolicy.title(d.title()), d.readyState(), d.viewport(),
                d.navigationKind(), d.pageSessionId());
    }

    private static List<FrameInfo> frames(List<FrameInfo> frames) {
        if (frames == null) {
            return List.of();
        }
        List<FrameInfo> out = new ArrayList<>(frames.size());
        for (FrameInfo f : frames) {
            out.add(new FrameInfo(f.frameId(), f.parentFrameId(), f.hostNodeId(), CapturePrivacyPolicy.urlTemplate(f.url()), f.accessible(),
                    f.reason(), CapturePrivacyPolicy.title(f.title()), f.readyState(), f.registryEpoch()));
        }
        return out;
    }

    private static List<ObservedRequest> requests(List<ObservedRequest> rs) {
        if (rs == null) {
            return List.of();
        }
        List<ObservedRequest> out = new ArrayList<>(rs.size());
        for (ObservedRequest r : rs) {
            out.add(new ObservedRequest(CapturePrivacyPolicy.urlTemplate(r.url()), r.initiatorType(), r.startTime(), r.duration()));
        }
        return out;
    }

    private static List<ResourceRef> resources(List<ResourceRef> rs) {
        if (rs == null) {
            return null;
        }
        List<ResourceRef> out = new ArrayList<>(rs.size());
        for (ResourceRef r : rs) {
            List<String> keys = r.queryKeys() == null ? List.of()
                    : r.queryKeys().stream().filter(k -> CapturePrivacyPolicy.identifier(k) != null).toList();
            out.add(new ResourceRef(r.kind(), UrlSanitizer.sanitize(r.url()), r.observedBy(), r.frameId(), r.type(), r.nodeId(), keys));
        }
        return out;
    }
}
