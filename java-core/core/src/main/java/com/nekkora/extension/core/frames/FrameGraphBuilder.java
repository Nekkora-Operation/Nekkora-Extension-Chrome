package com.nekkora.extension.core.frames;

import com.nekkora.extension.core.analysis.PageAnalysis;
import com.nekkora.extension.core.locator.LocatorDescriptor;
import com.nekkora.extension.core.locator.LocatorSet;
import com.nekkora.extension.core.locator.StabilityLevel;
import com.nekkora.extension.core.mutation.Sources;
import com.nekkora.extension.core.semantic.Classification;
import com.nekkora.extension.core.snapshot.FrameInfo;
import com.nekkora.extension.core.snapshot.RawNode;
import com.nekkora.extension.core.snapshot.RawSnapshot;
import com.nekkora.extension.core.snapshot.ShadowRootInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Frame Intelligence Engine — constrói o {@link FrameGraph} a partir do snapshot consolidado (todas as fontes) e,
 * quando disponíveis, dos metadados do navegador ({@link BrowserFrameMetadata}).
 *
 * <p>Regras:
 * <ul>
 *   <li>URL declarada ({@code src}) ≠ URL efetiva (documento carregado): um iframe pode começar em
 *       {@code about:blank}, usar {@code srcdoc} ou navegar depois;</li>
 *   <li>frame com {@code about:blank} inicial e {@code src} de rede pendente ⇒ {@code LOADING} (não é ausência);</li>
 *   <li>frame sem acesso ⇒ somente metadados observáveis; o conteúdo nunca é inventariado;</li>
 *   <li>{@code documentId}/{@code frameId} do Chrome só aparecem quando informados pela extensão.</li>
 * </ul>
 */
public final class FrameGraphBuilder {


    public FrameGraph build(PageAnalysis a, List<BrowserFrameMetadata> browser) {
        RawSnapshot snap = a.model().snapshot();
        List<BrowserFrameMetadata> meta = browser == null ? List.of() : browser;
        Map<String, FrameInfo> infos = new LinkedHashMap<>();
        for (FrameInfo f : snap.frames()) {
            infos.put(f.frameId(), f);
        }
        if (!infos.containsKey("f0")) {
            infos.put("f0", new FrameInfo("f0", null, null, snap.document() == null ? null : snap.document().url(), true, null));
        }
        // Contagens por frame lógico.
        Map<String, int[]> counts = new HashMap<>(); // [elementos, interativos]
        for (RawNode n : a.model().nodes()) {
            int[] c = counts.computeIfAbsent(n.frameId(), k -> new int[2]);
            c[0]++;
            Classification cls = a.semantics().get(n.id());
            if (cls != null && cls.interactive()) {
                c[1]++;
            }
        }
        Map<String, Integer> shadowsByFrame = new HashMap<>();
        int open = 0;
        int closed = 0;
        for (ShadowRootInfo s : snap.shadowRoots()) {
            String frame = a.model().find(s.hostNodeId()).map(RawNode::frameId).orElse("f0");
            shadowsByFrame.merge(frame, 1, Integer::sum);
            if ("open".equals(s.mode())) {
                open++;
            } else {
                closed++;
            }
        }

        Map<String, FrameIdentity> identities = new HashMap<>();
        Map<String, Integer> depths = new HashMap<>();
        List<FrameDescriptor> frames = new ArrayList<>();
        List<DocumentDescriptor> documents = new ArrayList<>();
        List<FrameGraph.Relation> relations = new ArrayList<>();
        List<String> limitations = new ArrayList<>();
        Map<String, List<String>> children = new HashMap<>();
        for (FrameInfo f : infos.values()) {
            if (f.parentFrameId() != null) {
                children.computeIfAbsent(f.parentFrameId(), k -> new ArrayList<>()).add(f.frameId());
            }
        }
        // Ordem: pais antes de filhos.
        List<FrameInfo> ordered = new ArrayList<>();
        addInOrder("f0", infos, children, ordered, 0);
        for (FrameInfo f : infos.values()) {
            if (!ordered.contains(f)) {
                ordered.add(f); // órfãos (pai desconhecido): ainda declarados
            }
        }
        Map<String, BrowserFrameMetadata> byHost = new HashMap<>();
        BrowserFrameMetadata topMeta = null;
        List<BrowserFrameMetadata> unmapped = new ArrayList<>();
        for (BrowserFrameMetadata m : meta) {
            if ("TOP".equals(m.mapping()) || (m.parentBrowserFrameId() != null && m.parentBrowserFrameId() < 0)) {
                topMeta = m;
            } else if (m.hostNodeId() != null) {
                byHost.put(m.hostNodeId(), m);
            } else {
                unmapped.add(m);
            }
        }

        // URL candidata de cada frame lógico sem handshake (base do URL_MATCH) — usada para exigir unicidade também do
        // lado lógico: um único registro do Chrome não identifica qual de dois irmãos equivalentes ele descreve.
        Map<String, String> urlCandidates = new HashMap<>();
        for (FrameInfo f : ordered) {
            if (f.parentFrameId() == null || f.hostNodeId() == null) {
                continue;
            }
            RawNode host = a.model().find(f.hostNodeId()).orElse(null);
            if (host == null || byHost.containsKey(host.id())) {
                continue;
            }
            FrameInfo parent = infos.get(f.parentFrameId());
            String declared = UrlSanitizer.sanitize(UrlSanitizer.resolve(parent == null ? null : parent.url(), host.attr("src")));
            String candidate = declared != null ? declared : UrlSanitizer.sanitize(f.url());
            if (candidate != null) {
                urlCandidates.put(f.frameId(), candidate);
            }
        }

        Map<String, BrowserFrameMetadata> associated = new HashMap<>(); // frame lógico -> metadados do Chrome
        for (FrameInfo f : ordered) {
            boolean main = f.parentFrameId() == null && "f0".equals(f.frameId());
            RawNode host = f.hostNodeId() == null ? null : a.model().find(f.hostNodeId()).orElse(null);
            int depth = main ? 0 : depths.getOrDefault(f.parentFrameId(), 0) + 1;
            depths.put(f.frameId(), depth);
            FrameInfo parent = f.parentFrameId() == null ? null : infos.get(f.parentFrameId());
            String parentUrl = parent == null ? null : parent.url();
            String declared = host == null ? null : UrlSanitizer.sanitize(UrlSanitizer.resolve(parentUrl, host.attr("src")));
            boolean srcdoc = host != null && host.hasAttr("srcdoc");
            String sandbox = host == null ? null : host.attr("sandbox");
            String effective = UrlSanitizer.sanitize(f.url());
            BrowserFrameMetadata m = main ? topMeta : (host == null ? null : byHost.get(host.id()));
            if (m == null && !main && host != null) {
                // Sem handshake (ex.: about:blank/srcdoc, que não recebem content script): associa somente quando o pai
                // tem identidade confirmada no Chrome e a URL é ÚNICA tanto entre os filhos desse pai no Chrome quanto
                // entre os iframes lógicos elegíveis do mesmo pai (D-052). Nunca por posição ou ordem.
                BrowserFrameMetadata parentMeta = associated.get(f.parentFrameId());
                String candidate = urlCandidates.get(f.frameId());
                m = uniqueLogicalCandidate(urlCandidates, infos, f, candidate) ? matchByUrl(unmapped, candidate, parentMeta) : null;
                if (m != null) {
                    unmapped.remove(m);
                    m = new BrowserFrameMetadata(m.browserFrameId(), m.browserDocumentId(), m.parentBrowserFrameId(), m.parentDocumentId(),
                            m.url(), m.frameType(), m.documentLifecycle(), m.hostNodeId(), "URL_MATCH", m.inspection(), m.source(), m.reason());
                }
            }
            if (m != null) {
                associated.put(f.frameId(), m);
            }
            if ((effective == null || effective.isEmpty()) && m != null && m.url() != null) {
                effective = m.url();
            }
            String kind = documentKind(main, effective, declared, srcdoc);
            String origin = originOf(effective, parent == null ? null : originFromInfo(parent, infos), sandbox, kind);
            String inspectedBy = f.accessible() ? Sources.frameOwner(f.frameId()) : null;
            String[] st = status(f, kind, sandbox, m);
            String documentId = "doc:" + f.frameId();
            String parentDocumentId = f.parentFrameId() == null ? null : "doc:" + f.parentFrameId();

            FrameIdentity identity = null;
            String locator = null;
            String locatorStability = null;
            if (host != null) {
                identity = FrameIdentities.forHost(a.graph(), host);
                identities.put(f.frameId(), identity);
                try {
                    LocatorSet ls = a.locators().generate(host.id());
                    LocatorDescriptor primary = ls.all().stream().filter(d -> d.css() != null).findFirst().orElse(ls.primary());
                    if (primary != null) {
                        locator = primary.css() != null ? primary.css() : primary.semantic();
                        locatorStability = primary.stability() == null ? StabilityLevel.UNKNOWN.name() : primary.stability().name();
                    }
                } catch (RuntimeException e) {
                    locatorStability = StabilityLevel.UNKNOWN.name();
                }
            }
            int[] c = counts.getOrDefault(f.frameId(), new int[2]);
            frames.add(new FrameDescriptor(f.frameId(), documentId, f.parentFrameId(), parentDocumentId, f.hostNodeId(), depth,
                    declared, srcdoc, effective, origin, host == null ? null : host.attr("name"),
                    host == null || com.nekkora.extension.core.privacy.CapturePrivacyPolicy.HOLE.equals(host.attr("title")) ? null : host.attr("title"), main ? (snap.document() == null ? null : snap.document().title()) : f.title(),
                    sandbox, kind, st[0], st[1], inspectedBy, host != null && host.scopeId().startsWith("shadow:"), identity, false,
                    locator, locatorStability, children.getOrDefault(f.frameId(), List.of()), c[0], c[1],
                    shadowsByFrame.getOrDefault(f.frameId(), 0), m));
            String readyState = main ? (snap.document() == null ? null : snap.document().readyState()) : f.readyState();
            String lifecycle = !f.accessible() ? ("LOADING".equals(st[0]) ? "LOADING" : "INACCESSIBLE")
                    : "LOADING".equals(st[0]) ? "LOADING" : ("ABOUT_BLANK".equals(kind) || "INITIAL_ABOUT_BLANK".equals(kind)) ? "TEMPORARY" : "ACTIVE";
            documents.add(new DocumentDescriptor(documentId, f.frameId(), m == null ? null : m.browserDocumentId(), effective,
                    main ? UrlSanitizer.queryKeys(snap.document() == null ? null : snap.document().url()) : UrlSanitizer.queryKeys(f.url()),
                    origin, main ? (snap.document() == null ? null : snap.document().title()) : f.title(), readyState, kind, lifecycle,
                    f.accessible(), main && snap.document() != null ? snap.document().pageSessionId() : null, c[0]));
            if (!main) {
                relations.add(new FrameGraph.Relation("DOCUMENT_CONTAINS_FRAME", parentDocumentId, f.frameId()));
                relations.add(new FrameGraph.Relation("FRAME_CHILD_OF", f.frameId(), f.parentFrameId()));
                relations.add(new FrameGraph.Relation("FRAME_PARENT_OF", f.parentFrameId(), f.frameId()));
                if (f.hostNodeId() != null) {
                    relations.add(new FrameGraph.Relation("FRAME_HOSTED_BY_ELEMENT", f.frameId(), f.hostNodeId()));
                    // Pertença dos elementos ESTRUTURAIS (hosts) ao documento/frame; a de cada elemento comum é dada por
                    // elements[].documentId no PageMap (evita uma relação por nó).
                    relations.add(new FrameGraph.Relation("ELEMENT_BELONGS_TO_DOCUMENT", f.hostNodeId(), parentDocumentId));
                    relations.add(new FrameGraph.Relation("ELEMENT_BELONGS_TO_FRAME", f.hostNodeId(), f.parentFrameId()));
                }
            }
            relations.add(new FrameGraph.Relation("FRAME_HAS_DOCUMENT", f.frameId(), documentId));
            if (!f.accessible()) {
                limitations.add(f.frameId() + ": " + st[0] + (st[1] == null ? "" : " — " + st[1]) + " (conteúdo não inventariado)");
            }
        }
        for (ShadowRootInfo s : snap.shadowRoots()) {
            String frame = a.model().find(s.hostNodeId()).map(RawNode::frameId).orElse("f0");
            relations.add(new FrameGraph.Relation("SHADOW_ROOT_BELONGS_TO_DOCUMENT", "shadow:" + s.hostNodeId(), "doc:" + frame));
            if (a.model().contains(s.hostNodeId())) {
                relations.add(new FrameGraph.Relation("ELEMENT_BELONGS_TO_DOCUMENT", s.hostNodeId(), "doc:" + frame));
                relations.add(new FrameGraph.Relation("ELEMENT_BELONGS_TO_FRAME", s.hostNodeId(), frame));
            }
        }
        frames = markAmbiguousSiblings(frames);
        if (meta.isEmpty()) {
            limitations.add("metadados do navegador (frameId/documentId do Chrome) indisponíveis nesta análise");
        }
        Map<String, Integer> summary = new LinkedHashMap<>();
        int inspected = 0;
        int inaccessible = 0;
        int loading = 0;
        int maxDepth = 0;
        for (FrameDescriptor f : frames) {
            maxDepth = Math.max(maxDepth, f.depth());
            switch (f.status()) {
                case "INSPECTED" -> inspected++;
                case "LOADING", "ATTACHING" -> loading++;
                default -> inaccessible++;
            }
        }
        summary.put("documents", documents.size());
        summary.put("frames", frames.size() - 1);
        summary.put("inspected", inspected);
        summary.put("inaccessible", inaccessible);
        summary.put("loading", loading);
        summary.put("shadowRootsOpen", open);
        summary.put("shadowRootsClosed", closed);
        return new FrameGraph("doc:f0", documents, frames, relations, maxDepth, summary, limitations);
    }

    private static void addInOrder(String id, Map<String, FrameInfo> infos, Map<String, List<String>> children, List<FrameInfo> out, int guard) {
        FrameInfo f = infos.get(id);
        if (f == null || out.contains(f) || guard > 64) {
            return;
        }
        out.add(f);
        for (String c : children.getOrDefault(id, List.of())) {
            addInOrder(c, infos, children, out, guard + 1);
        }
    }

    /** A URL candidata pertence a um único iframe lógico elegível (sem handshake) sob o mesmo pai. */
    private static boolean uniqueLogicalCandidate(Map<String, String> urlCandidates, Map<String, FrameInfo> infos, FrameInfo f, String candidate) {
        if (candidate == null) {
            return false;
        }
        int same = 0;
        for (Map.Entry<String, String> e : urlCandidates.entrySet()) {
            FrameInfo other = infos.get(e.getKey());
            if (other != null && f.parentFrameId().equals(other.parentFrameId()) && candidate.equals(e.getValue())) {
                same++;
            }
        }
        return same == 1;
    }

    private static final Set<String> CONFIRMED_MAPPINGS = Set.of("TOP", "HANDSHAKE", "URL_MATCH");

    /**
     * Candidato do Chrome com a URL indicada, filho do pai já confirmado. Sem pai confirmado (sem {@code frameId} do
     * Chrome ou associação não confirmada), sem pai declarado no candidato, com {@code documentId} do pai divergente ou
     * com mais de um candidato: nenhuma associação.
     */
    private static BrowserFrameMetadata matchByUrl(List<BrowserFrameMetadata> unmapped, String declared, BrowserFrameMetadata parentMeta) {
        if (declared == null || parentMeta == null || parentMeta.browserFrameId() == null || !CONFIRMED_MAPPINGS.contains(parentMeta.mapping())) {
            return null;
        }
        Integer parentBrowserFrameId = parentMeta.browserFrameId();
        BrowserFrameMetadata found = null;
        for (BrowserFrameMetadata m : unmapped) {
            if (m.parentBrowserFrameId() == null || !parentBrowserFrameId.equals(m.parentBrowserFrameId())) {
                continue; // filho de outro frame (ou pai não informado pelo Chrome)
            }
            if (m.parentDocumentId() != null && parentMeta.browserDocumentId() != null && !parentMeta.browserDocumentId().equals(m.parentDocumentId())) {
                continue; // mesmo frameId do pai, mas outro documento (navegação do pai)
            }
            if (declared.equals(m.url())) {
                if (found != null) {
                    return null; // mais de um frame com a mesma URL: não associa por adivinhação
                }
                found = m;
            }
        }
        return found;
    }

    public static String documentKind(boolean main, String effective, String declared, boolean srcdoc) {
        if (main) {
            return "MAIN";
        }
        if (srcdoc || "about:srcdoc".equals(effective)) {
            return "SRCDOC";
        }
        if (effective == null) {
            return "UNKNOWN";
        }
        if (effective.startsWith("about:blank")) {
            boolean networkPending = declared != null && !declared.startsWith("about:") && !declared.startsWith("javascript:");
            return networkPending ? "INITIAL_ABOUT_BLANK" : "ABOUT_BLANK";
        }
        return "NETWORK";
    }

    private static String originFromInfo(FrameInfo f, Map<String, FrameInfo> infos) {
        String o = UrlSanitizer.origin(f.url());
        if (o != null || f.parentFrameId() == null) {
            return o;
        }
        FrameInfo p = infos.get(f.parentFrameId());
        return p == null ? null : originFromInfo(p, infos);
    }

    private static String originOf(String effective, String parentOrigin, String sandbox, String kind) {
        boolean opaque = sandbox != null && !sandbox.toLowerCase().contains("allow-same-origin");
        if (opaque) {
            return "null"; // sandbox sem allow-same-origin: origem opaca
        }
        String o = UrlSanitizer.origin(effective);
        if (o != null) {
            return o;
        }
        // about:blank / srcdoc herdam a origem do documento que os criou.
        return ("ABOUT_BLANK".equals(kind) || "SRCDOC".equals(kind) || "INITIAL_ABOUT_BLANK".equals(kind)) ? parentOrigin : null;
    }

    /** [status, motivo]. */
    private static String[] status(FrameInfo f, String kind, String sandbox, BrowserFrameMetadata m) {
        if (f.accessible()) {
            if ("INITIAL_ABOUT_BLANK".equals(kind)) {
                return new String[]{"LOADING", "documento inicial about:blank; navegação para o src declarado ainda não concluída"};
            }
            if ("loading".equals(f.readyState())) {
                return new String[]{"LOADING", "document.readyState = loading"};
            }
            return new String[]{"INSPECTED", null};
        }
        String reason = f.reason() == null ? "" : f.reason();
        if (reason.contains("SANDBOX") || (sandbox != null && m != null && "NOT_ATTACHED".equals(m.inspection()))) {
            return new String[]{"SANDBOX_RESTRICTED", "iframe com sandbox: conteúdo não inspecionado por política"};
        }
        if (reason.contains("NOT_LOADED")) {
            return new String[]{"LOADING", "documento do frame ainda não carregado"};
        }
        if (m != null) {
            if ("PENDING".equals(m.inspection())) {
                return new String[]{"ATTACHING", "frame de origem permitida em anexação pela extensão"};
            }
            if (m.url() != null && m.url().startsWith("about:blank")) {
                return new String[]{"LOADING", "navegador informa about:blank: navegação pendente"};
            }
            if ("NO_PERMISSION".equals(m.inspection())) {
                return new String[]{"PERMISSION_DENIED", "origem sem permissão da extensão"};
            }
            if ("FAILED".equals(m.inspection())) {
                return new String[]{"INACCESSIBLE", "anexação do frame falhou" + (m.reason() == null ? "" : ": " + m.reason())};
            }
        }
        if (reason.contains("CROSS_ORIGIN")) {
            return new String[]{"PERMISSION_DENIED", "outra origem; conteúdo não acessível pelo documento pai"};
        }
        return new String[]{"INACCESSIBLE", reason.isEmpty() ? null : reason};
    }

    private static String keyOf(FrameIdentity i) {
        return i == null ? "?" : i.key();
    }

    private static List<FrameDescriptor> markAmbiguousSiblings(List<FrameDescriptor> frames) {
        Map<String, Integer> keys = new HashMap<>();
        for (FrameDescriptor f : frames) {
            if (f.identity() != null) {
                keys.merge(f.parentFrameId() + "§" + f.identity().key(), 1, Integer::sum);
            }
        }
        List<FrameDescriptor> out = new ArrayList<>(frames.size());
        for (FrameDescriptor f : frames) {
            boolean amb = f.identity() != null && keys.getOrDefault(f.parentFrameId() + "§" + f.identity().key(), 0) > 1;
            out.add(amb == f.identityAmbiguous() ? f : new FrameDescriptor(f.frameId(), f.documentId(), f.parentFrameId(),
                    f.parentDocumentId(), f.hostNodeId(), f.depth(), f.declaredSrc(), f.srcdoc(), f.effectiveUrl(), f.origin(),
                    f.name(), f.title(), f.documentTitle(), f.sandbox(), f.documentKind(), f.status(), f.reason(), f.inspectedBy(),
                    f.insideShadowRoot(), f.identity(), true, f.locator(), f.locatorStability(), f.childFrameIds(),
                    f.elementCount(), f.interactiveCount(), f.shadowRoots(), f.browser()));
        }
        return out;
    }

    /** Conjunto de frames lógicos cujo conteúdo foi inventariado. */
    public static Set<String> inspectedFrames(FrameGraph g) {
        Set<String> out = new java.util.HashSet<>();
        for (FrameDescriptor f : g.frames()) {
            if (f.inspected()) {
                out.add(f.frameId());
            }
        }
        return out;
    }
}
