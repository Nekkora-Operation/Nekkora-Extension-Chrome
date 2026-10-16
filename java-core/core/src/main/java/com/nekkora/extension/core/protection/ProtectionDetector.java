package com.nekkora.extension.core.protection;

import com.nekkora.extension.core.analysis.PageAnalysis;
import com.nekkora.extension.core.frames.AccessPathBuilder;
import com.nekkora.extension.core.frames.BrowserFrameMetadata;
import com.nekkora.extension.core.frames.ElementAccessPath;
import com.nekkora.extension.core.frames.FrameDescriptor;
import com.nekkora.extension.core.frames.FrameGraph;
import com.nekkora.extension.core.frames.UrlSanitizer;
import com.nekkora.extension.core.snapshot.FrameInfo;
import com.nekkora.extension.core.snapshot.ObservedRequest;
import com.nekkora.extension.core.snapshot.RawNode;
import com.nekkora.extension.core.snapshot.RawSnapshot;
import com.nekkora.extension.core.snapshot.ResourceRef;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Protection Detection Engine — classificação DETERMINÍSTICA e explicável de evidências observáveis de CAPTCHA,
 * desafios e ferramentas antibot, a partir do catálogo versionado. Totalmente passiva: usa somente o que já foi
 * capturado (DOM, recursos referenciados/carregados, metadados de frames). Não executa código da página, não
 * resolve desafios e não gera tráfego.
 *
 * <p>Modelo de confiança:
 * <ul>
 *   <li>{@code HIGH}: ≥ 2 evidências FORTES de canais independentes (recurso/URL × DOM);</li>
 *   <li>{@code MEDIUM}: ≥ 1 evidência forte, ou ≥ 2 médias de canais independentes;</li>
 *   <li>{@code LOW}: somente evidências médias/fracas ou parciais;</li>
 *   <li>limitado pelo teto da assinatura ({@code maxConfidence}) e reduzido um nível quando o mesmo elemento
 *       corresponde a assinaturas FORTES de fornecedores diferentes (conflito).</li>
 * </ul>
 * Presença de tecnologia ≠ desafio ativo ≠ acesso bloqueado; desafio concluído só com evidência explícita (campo
 * de resposta preenchido), nunca pelo desaparecimento visual.
 */
public final class ProtectionDetector {

    /** Tipo de script usado pelo laboratório para referências INERTES (o navegador não busca nem executa). */
    public static final String INERT_SCRIPT_TYPE = "application/x-nekkora-inert";
    public static final String SIMULATED_SRC_ATTRIBUTE = "data-nekkora-simulated-src";
    private static final String SERVER_SIDE_LIMIT = "Proteções aplicadas no servidor ou na borda (WAF, gerenciamento de bots) podem não "
            + "deixar evidência no documento: a ausência de assinaturas não comprova ausência de proteção.";
    private static final List<String> ORDER = List.of("LOW", "MEDIUM", "HIGH");

    private final AccessPathBuilder paths = new AccessPathBuilder();

    /** Observação de URL (script, frame ou recurso de rede). */
    private record UrlObs(String kind, String url, String host, String path, List<String> queryKeys, String rawFragment,
                          String source, String frameId, String nodeId, boolean simulated, Boolean visible) {
    }

    private record Group(SignatureCatalog.Signature signature, String documentId, String frameId, List<ProtectionEvidence> evidence) {
    }

    public ProtectionReport assess(PageAnalysis a, FrameGraph graph, SignatureCatalog catalog) {
        RawSnapshot snap = a.model().snapshot();
        List<UrlObs> urls = urlObservations(a, graph);
        Map<String, Group> groups = new LinkedHashMap<>();
        Map<String, Set<String>> strongDomProviders = new HashMap<>();
        Set<String> matchedDomNodes = new HashSet<>();
        Map<String, List<String[]>> urlMatches = new HashMap<>(); // observação -> [assinatura, fornecedor, específico]
        Map<String, Set<String>> urlObsByGroup = new HashMap<>();   // grupo -> observações de URL usadas

        for (SignatureCatalog.Signature sig : catalog.signatures()) {
            for (SignatureCatalog.Indicator ind : sig.indicators()) {
                switch (ind.kind()) {
                    case "SCRIPT_URL", "FRAME_URL", "RESOURCE_URL" -> {
                        String want = ind.kind().equals("SCRIPT_URL") ? "SCRIPT" : ind.kind().equals("FRAME_URL") ? "FRAME" : null;
                        for (UrlObs u : urls) {
                            if (want != null && !want.equals(u.kind())) {
                                continue;
                            }
                            if (want == null && !"RESOURCE_TIMING".equals(u.source())) {
                                continue; // RESOURCE_URL: somente recursos efetivamente carregados
                            }
                            if (!urlMatches(ind, u)) {
                                continue;
                            }
                            String obsKey = u.kind() + "|" + u.url() + "|" + u.frameId() + "|" + u.nodeId() + "|" + u.source();
                            urlMatches.computeIfAbsent(obsKey, k -> new ArrayList<>())
                                    .add(new String[]{sig.id(), sig.provider(), Boolean.toString(ind.requiresQueryKey() != null || ind.requiresFragment() != null)});
                            urlObsByGroup.computeIfAbsent(sig.id() + "@" + u.frameId(), k -> new HashSet<>()).add(obsKey);
                            String type = u.kind().equals("SCRIPT") ? "SCRIPT_RESOURCE" : u.kind().equals("FRAME") ? "FRAME_RESOURCE" : "NETWORK_RESOURCE";
                            add(groups, sig, u.frameId(), new ProtectionEvidence(type, ind.id(),
                                    ind.description() + ": " + u.url() + (u.simulated() ? " [simulado no laboratório]" : ""), u.source(),
                                    ind.strength(), ind.signal(), u.nodeId(), "doc:" + u.frameId(), u.frameId(), u.visible(), u.simulated()));
                        }
                    }
                    case "DOM_CLASS", "DOM_ID", "DOM_ATTRIBUTE", "FORM_FIELD" -> {
                        Pattern re = "DOM_ID".equals(ind.kind()) || "FORM_FIELD".equals(ind.kind()) ? Pattern.compile(ind.token(), Pattern.CASE_INSENSITIVE) : null;
                        for (RawNode n : a.model().nodes()) {
                            boolean hit = switch (ind.kind()) {
                                case "DOM_CLASS" -> hasClass(n, ind.token());
                                case "DOM_ID" -> n.attr("id") != null && re.matcher(n.attr("id")).find();
                                case "DOM_ATTRIBUTE" -> n.hasAttr(ind.token());
                                default -> n.attr("name") != null && re.matcher(n.attr("name")).find()
                                        && (n.tag().equals("textarea") || n.tag().equals("input"));
                            };
                            if (!hit) {
                                continue;
                            }
                            boolean visible = n.state() != null && Boolean.TRUE.equals(n.state().visible());
                            String type = "FORM_FIELD".equals(ind.kind()) ? "RESPONSE_FIELD" : "WIDGET".equals(ind.signal()) ? "WIDGET" : "DOM_ELEMENT";
                            String detail = ind.description() + " (" + n.tag() + describeNode(n) + ")";
                            String signal = ind.signal();
                            if ("COMPLETED_FIELD".equals(signal)) {
                                boolean filled = n.state() != null && Boolean.TRUE.equals(n.state().hasValue());
                                detail += filled ? " — campo PREENCHIDO (valor não coletado)" : " — campo vazio";
                                if (!filled) {
                                    signal = "TECHNOLOGY";
                                }
                            }
                            add(groups, sig, n.frameId(), new ProtectionEvidence(type, ind.id(), detail, "DOM", ind.strength(), signal,
                                    n.id(), "doc:" + n.frameId(), n.frameId(), visible, false));
                            matchedDomNodes.add(n.id());
                            if ("STRONG".equals(ind.strength())) {
                                strongDomProviders.computeIfAbsent(n.id(), k -> new HashSet<>()).add(sig.provider() + "/" + sig.product());
                            }
                        }
                    }
                    case "TITLE" -> {
                        Pattern re = Pattern.compile(ind.token(), Pattern.CASE_INSENSITIVE);
                        for (FrameDescriptor f : graph.frames()) {
                            String title = f.documentTitle();
                            if (title != null && re.matcher(title.trim()).find()) {
                                add(groups, sig, f.frameId(), new ProtectionEvidence("DOCUMENT_TITLE", ind.id(),
                                        ind.description() + ": «" + title.trim() + "»", "DOM", ind.strength(), ind.signal(), null,
                                        f.documentId(), f.frameId(), true, false));
                            }
                        }
                    }
                    default -> {
                        // TEXT é tratado somente como indicador genérico
                    }
                }
            }
        }

        // Indicadores genéricos (nunca identificam fornecedor).
        List<ProtectionEvidence> generic = new ArrayList<>();
        for (SignatureCatalog.Indicator ind : catalog.genericIndicators()) {
            if ("TEXT".equals(ind.kind())) {
                Pattern re = Pattern.compile(ind.token(), Pattern.CASE_INSENSITIVE);
                for (RawNode n : a.model().nodes()) {
                    String t = n.ownText();
                    if (t != null && !t.isBlank() && t.length() <= 400 && re.matcher(t).find()
                            && n.state() != null && Boolean.TRUE.equals(n.state().visible())) {
                        generic.add(new ProtectionEvidence("TEXT", ind.id(), ind.description() + ": «" + abbreviate(t) + "»", "DOM",
                                ind.strength(), ind.signal(), n.id(), "doc:" + n.frameId(), n.frameId(), true, false));
                    }
                }
            } else if ("DOM_ATTRIBUTE".equals(ind.kind())) {
                for (RawNode n : a.model().nodes()) {
                    if (n.hasAttr(ind.token()) && !matchedDomNodes.contains(n.id())) {
                        generic.add(new ProtectionEvidence("ATTRIBUTE", ind.id(), ind.description() + " (" + n.tag() + describeNode(n) + ")",
                                "DOM", ind.strength(), ind.signal(), n.id(), "doc:" + n.frameId(), n.frameId(),
                                n.state() != null && Boolean.TRUE.equals(n.state().visible()), false));
                    }
                }
            }
        }

        List<ProtectionAssessment> assessments = new ArrayList<>();
        for (Map.Entry<String, Group> e : groups.entrySet()) {
            assessments.add(classify(a, graph, e.getValue(), strongDomProviders,
                    shadowedBy(e.getValue(), urlObsByGroup.getOrDefault(e.getKey(), Set.of()), urlMatches)));
        }
        assessments.removeIf(x -> x == null);
        List<String> limitations = new ArrayList<>();
        limitations.add(SERVER_SIDE_LIMIT);
        long uninspected = graph.frames().stream().filter(f -> !f.inspected() && f.depth() > 0).count();
        if (uninspected > 0) {
            limitations.add(uninspected + " frame(s) sem inspeção de conteúdo: somente URL/atributos do iframe foram avaliados");
        }
        if (snap.resources().stream().noneMatch(r -> "RESOURCE_TIMING".equals(r.observedBy()))) {
            limitations.add("Nenhum recurso de Resource Timing disponível: avaliação baseada em referências do DOM");
        }
        String status;
        boolean genericWidget = generic.stream().anyMatch(e -> !"MENTION".equals(e.signal()));
        if (!assessments.isEmpty()) {
            status = assessments.stream().anyMatch(x -> "DETECTED".equals(x.status())) ? "OBSERVED" : "INSUFFICIENT_EVIDENCE";
        } else if (genericWidget) {
            status = "INSUFFICIENT_EVIDENCE";
            assessments.add(unknownAssessment(a, graph, generic));
        } else {
            status = "NOT_OBSERVED";
            if (!generic.isEmpty()) {
                limitations.add("Menção textual a verificação humana sem mecanismo associado: não confirma tecnologia nem desafio");
            }
        }
        return new ProtectionReport(catalog.catalogVersion(), status, assessments, generic, limitations);
    }

    /**
     * Assinatura "sombreada": TODA a sua evidência vem de URLs que também casaram, por um indicador MAIS específico
     * (parâmetro/fragmento exigido), com outra assinatura do MESMO fornecedor (ex.: {@code api.js?render=} é
     * compatível com reCAPTCHA v2 em renderização explícita e com v3; o valor do parâmetro não é coletado).
     * Devolve a assinatura mais específica, ou {@code null}.
     */
    private static String shadowedBy(Group g, Set<String> obsKeys, Map<String, List<String[]>> urlMatches) {
        if (obsKeys.isEmpty() || g.evidence().stream().anyMatch(e -> !"RESOURCE".equals(channel(e.type())))) {
            return null;
        }
        String by = null;
        for (String k : obsKeys) {
            String own = null;
            String other = null;
            for (String[] m : urlMatches.getOrDefault(k, List.of())) {
                if (m[0].equals(g.signature().id())) {
                    own = m[2];
                } else if (m[1].equals(g.signature().provider()) && "true".equals(m[2])) {
                    other = m[0];
                }
            }
            if (other == null || "true".equals(own)) {
                return null;
            }
            by = other;
        }
        return by;
    }

    private ProtectionAssessment classify(PageAnalysis a, FrameGraph graph, Group g, Map<String, Set<String>> strongDomProviders,
                                          String shadowedBy) {
        SignatureCatalog.Signature sig = g.signature();
        List<ProtectionEvidence> ev = dedupe(g.evidence());
        int strong = 0;
        int medium = 0;
        Set<String> strongChannels = new HashSet<>();
        Set<String> mediumChannels = new HashSet<>();
        for (ProtectionEvidence e : ev) {
            String channel = channel(e.type());
            if ("STRONG".equals(e.strength())) {
                strong++;
                strongChannels.add(channel);
            } else if ("MEDIUM".equals(e.strength())) {
                medium++;
                mediumChannels.add(channel);
            }
        }
        List<String> reasons = new ArrayList<>();
        String confidence;
        if (strong >= 2 && strongChannels.size() >= 2) {
            confidence = "HIGH";
            reasons.add(strong + " evidências fortes de canais independentes (" + String.join(", ", strongChannels) + ")");
        } else if (strong >= 1) {
            confidence = "MEDIUM";
            reasons.add(strong + " evidência(s) forte(s) de um único canal (" + String.join(", ", strongChannels) + ")");
        } else if (medium >= 2 && mediumChannels.size() >= 2) {
            confidence = "MEDIUM";
            reasons.add(medium + " evidências médias de canais independentes");
        } else {
            confidence = "LOW";
            reasons.add("somente evidências médias/fracas ou parciais");
        }
        if (ORDER.indexOf(confidence) > ORDER.indexOf(sig.maxConfidence())) {
            confidence = sig.maxConfidence();
            reasons.add("limitada a " + sig.maxConfidence() + " pela base da assinatura (" + sig.basis() + ")");
        }
        List<String> limitations = new ArrayList<>();
        boolean conflict = false;
        for (ProtectionEvidence e : ev) {
            if (e.nodeId() != null && strongDomProviders.getOrDefault(e.nodeId(), Set.of()).size() > 1) {
                conflict = true;
            }
        }
        if (conflict) {
            int i = ORDER.indexOf(confidence);
            confidence = ORDER.get(Math.max(0, i - 1));
            reasons.add("reduzida: o mesmo elemento corresponde a assinaturas de fornecedores diferentes");
            limitations.add("Assinaturas conflitantes no mesmo elemento: evidências preservadas, fornecedor incerto");
        }
        if (shadowedBy != null) {
            int i = ORDER.indexOf(confidence);
            confidence = ORDER.get(Math.max(0, i - 1));
            reasons.add("reduzida: a mesma referência corresponde de forma mais específica a " + shadowedBy);
            limitations.add("Referência compatível com mais de um produto do fornecedor (valores de parâmetros não são coletados): "
                    + "produto incerto");
        }
        if (ev.stream().anyMatch(ProtectionEvidence::simulated)) {
            limitations.add("Evidência(s) produzida(s) por metadados de simulação do laboratório (referência inerte, sem tráfego real)");
        }

        // Estado do desafio.
        boolean blocked = ev.stream().anyMatch(e -> "BLOCKED".equals(e.signal()) && !Boolean.FALSE.equals(e.visible()));
        boolean challengePage = ev.stream().anyMatch(e -> "CHALLENGE_PAGE".equals(e.signal()) && !Boolean.FALSE.equals(e.visible()));
        boolean challengeActive = ev.stream().anyMatch(e -> "CHALLENGE_ACTIVE".equals(e.signal()) && Boolean.TRUE.equals(e.visible()));
        boolean widgetVisible = ev.stream().anyMatch(e -> "WIDGET".equals(e.signal()) && Boolean.TRUE.equals(e.visible()));
        boolean completed = ev.stream().anyMatch(e -> "COMPLETED_FIELD".equals(e.signal()));
        String state;
        if (blocked) {
            state = "ACCESS_BLOCKED";
        } else if (challengePage || challengeActive) {
            state = "CHALLENGE_ACTIVE";
        } else if (completed && widgetVisible) {
            state = "CHALLENGE_COMPLETED";
        } else if (widgetVisible) {
            state = "CHALLENGE_PRESENT";
        } else {
            state = "TECHNOLOGY_DETECTED";
        }
        boolean weakOnly = ev.stream().allMatch(e -> "WEAK".equals(e.strength()));
        if (weakOnly && !"ACCESS_BLOCKED".equals(state)) {
            state = "UNKNOWN"; // evidência fraca não sustenta afirmação sobre desafio
            limitations.add("Somente evidência fraca: o estado de desafio não pode ser afirmado");
        }
        if (completed && !widgetVisible) {
            limitations.add("Campo de resposta preenchido sem widget visível: conclusão não confirmada");
        }

        // Localização.
        FrameDescriptor frame = graph.frame(g.frameId()).orElse(null);
        String widgetNode = ev.stream().filter(e -> e.nodeId() != null && ("WIDGET".equals(e.signal()) || "CHALLENGE_ACTIVE".equals(e.signal())
                || "CHALLENGE_PAGE".equals(e.signal()) || "BLOCKED".equals(e.signal()))).map(ProtectionEvidence::nodeId).findFirst()
                .orElse(ev.stream().map(ProtectionEvidence::nodeId).filter(x -> x != null).findFirst().orElse(null));
        ElementAccessPath path = null;
        if (widgetNode != null && a.model().contains(widgetNode)) {
            try {
                path = paths.build(a, graph, widgetNode);
            } catch (RuntimeException ignored) {
                // trajetória indisponível
            }
        }
        List<String> chain = new ArrayList<>();
        for (FrameDescriptor f : graph.chain(g.frameId())) {
            if (f.depth() > 0) {
                chain.add(f.title() != null ? f.title() : f.name() != null ? f.name() : f.frameId());
            }
        }
        boolean frameEvidenceOnly = ev.stream().allMatch(e -> "FRAME_RESOURCE".equals(e.type()));
        boolean inspectable = frame == null || frame.inspected();
        if (frameEvidenceOnly) {
            // O widget é o próprio iframe: seu conteúdo só é inspecionável se o frame hospedado tiver sido inventariado.
            boolean anyInspected = false;
            for (ProtectionEvidence e : ev) {
                FrameDescriptor hosted = e.nodeId() == null ? null : graph.frameHostedBy(e.nodeId()).orElse(null);
                anyInspected |= hosted != null && hosted.inspected();
            }
            inspectable = anyInspected;
            if (!anyInspected) {
                limitations.add("Widget/desafio em iframe sem inspeção de conteúdo: somente a URL do iframe foi avaliada; o estado interno é desconhecido");
                if (!"ACCESS_BLOCKED".equals(state) && !"CHALLENGE_ACTIVE".equals(state)) {
                    state = widgetVisible ? "CHALLENGE_PRESENT" : "TECHNOLOGY_DETECTED";
                }
            }
        }
        boolean insideFrame = (frame != null && frame.depth() > 0) || ev.stream().anyMatch(e -> "FRAME_RESOURCE".equals(e.type()));
        String status = "LOW".equals(confidence) ? "POSSIBLE" : "DETECTED";
        return new ProtectionAssessment("protection:" + sig.id() + "@" + g.documentId(), status, sig.provider(), sig.product(),
                sig.category(), confidence, state, sig.id(), sig.signatureVersion(), sig.basis(), ev,
                new ProtectionAssessment.Location(g.documentId(), g.frameId(), insideFrame, chain, inspectable, widgetNode, path),
                reasons, limitations);
    }

    private ProtectionAssessment unknownAssessment(PageAnalysis a, FrameGraph graph, List<ProtectionEvidence> generic) {
        ProtectionEvidence first = generic.stream().filter(e -> !"MENTION".equals(e.signal())).findFirst().orElse(generic.get(0));
        ElementAccessPath path = null;
        if (first.nodeId() != null) {
            try {
                path = paths.build(a, graph, first.nodeId());
            } catch (RuntimeException ignored) {
                // indisponível
            }
        }
        return new ProtectionAssessment("protection:unknown@" + first.documentId(), "INSUFFICIENT_EVIDENCE", "UNKNOWN", "UNKNOWN",
                "HUMAN_VERIFICATION", "UNKNOWN", "UNKNOWN", null, null, "GENERIC", generic,
                new ProtectionAssessment.Location(first.documentId(), first.frameId(), first.frameId() != null && !"f0".equals(first.frameId()),
                        List.of(), true, first.nodeId(), path),
                List.of("somente indicadores genéricos: nenhuma assinatura específica de fornecedor"),
                List.of("Fornecedor não identificado: nenhum fornecedor é inferido a partir de indicadores genéricos"));
    }

    // ------------------------------------------------------------------------------------ observações

    private static List<UrlObs> urlObservations(PageAnalysis a, FrameGraph graph) {
        RawSnapshot snap = a.model().snapshot();
        List<UrlObs> out = new ArrayList<>();
        for (ResourceRef r : snap.resources()) {
            String kind = "SCRIPT".equals(r.kind()) ? "SCRIPT" : "IFRAME".equals(r.kind()) ? "FRAME" : "RESOURCE";
            boolean simulated = INERT_SCRIPT_TYPE.equalsIgnoreCase(r.type() == null ? "" : r.type().trim());
            String frame = r.frameId() == null ? "f0" : r.frameId();
            String url = r.url();
            if (url != null && !url.contains("://") && !url.startsWith("about:")) {
                // referência relativa: resolvida contra a URL do documento que a contém
                FrameDescriptor fd = graph.frame(frame).orElse(null);
                String base = fd != null && fd.effectiveUrl() != null ? fd.effectiveUrl() : (snap.document() == null ? null : snap.document().url());
                url = UrlSanitizer.sanitize(UrlSanitizer.resolve(base, url));
            }
            out.add(obs(kind, url, r.queryKeys(), null, r.observedBy(), frame, r.nodeId(), simulated, null));
            if ("RESOURCE_TIMING".equals(r.observedBy()) && "SCRIPT".equals(kind)) {
                out.add(obs("RESOURCE", url, r.queryKeys(), null, r.observedBy(), frame, null, false, null));
            }
        }
        for (ObservedRequest q : snap.requests()) {
            out.add(obs("RESOURCE", UrlSanitizer.sanitize(q.url()), UrlSanitizer.queryKeys(q.url()), null, "RESOURCE_TIMING", "f0", null, false, null));
        }
        for (RawNode n : a.model().nodes()) {
            if (!n.tag().equals("iframe") && !n.tag().equals("frame")) {
                continue;
            }
            Boolean visible = n.state() == null ? null : n.state().visible();
            FrameInfo parent = null;
            for (FrameInfo f : snap.frames()) {
                if (f.frameId().equals(n.frameId())) {
                    parent = f;
                }
            }
            String base = parent == null ? (snap.document() == null ? null : snap.document().url()) : parent.url();
            String src = n.attr("src");
            if (src != null && !src.isBlank() && !src.startsWith("about:")) {
                String abs = UrlSanitizer.resolve(base, src);
                out.add(obs("FRAME", UrlSanitizer.sanitize(abs), UrlSanitizer.queryKeys(abs), fragment(abs), "DOM", n.frameId(), n.id(), false, visible));
            }
            String sim = n.attr(SIMULATED_SRC_ATTRIBUTE);
            if (sim != null && !sim.isBlank()) {
                out.add(obs("FRAME", UrlSanitizer.sanitize(sim), UrlSanitizer.queryKeys(sim), fragment(sim), "DOM", n.frameId(), n.id(), true, visible));
            }
            FrameDescriptor hosted = graph.frameHostedBy(n.id()).orElse(null);
            if (hosted != null && hosted.effectiveUrl() != null && !hosted.effectiveUrl().startsWith("about:")) {
                out.add(obs("FRAME", hosted.effectiveUrl(), List.of(), null, "FRAME_METADATA", n.frameId(), n.id(), false, visible));
            }
            BrowserFrameMetadata m = hosted == null ? null : hosted.browser();
            if (m != null && m.url() != null && !m.url().startsWith("about:")) {
                out.add(obs("FRAME", m.url(), List.of(), null, "BROWSER_METADATA", n.frameId(), n.id(), false, visible));
            }
        }
        return out;
    }

    private static UrlObs obs(String kind, String url, List<String> keys, String fragment, String source, String frameId, String nodeId,
                              boolean simulated, Boolean visible) {
        String host = null;
        String path = null;
        if (url != null) {
            try {
                URI u = URI.create(com.nekkora.extension.core.privacy.CapturePrivacyPolicy.forUri(url));
                host = u.getHost() == null ? null : u.getHost().toLowerCase(Locale.ROOT);
                path = u.getRawPath();
            } catch (IllegalArgumentException ignored) {
                // URL inválida: sem host/caminho
            }
        }
        return new UrlObs(kind, url, host, path, keys == null ? List.of() : keys, fragment, source, frameId, nodeId, simulated, visible);
    }

    private static boolean urlMatches(SignatureCatalog.Indicator ind, UrlObs u) {
        if (u.host() == null || u.path() == null) {
            return false;
        }
        if (ind.host() != null && !Pattern.compile(ind.host(), Pattern.CASE_INSENSITIVE).matcher(u.host()).find()) {
            return false;
        }
        if (ind.path() != null && !Pattern.compile(ind.path()).matcher(u.path()).find()) {
            return false;
        }
        if (ind.requiresQueryKey() != null && !u.queryKeys().contains(ind.requiresQueryKey())) {
            return false;
        }
        return ind.requiresFragment() == null || (u.rawFragment() != null && u.rawFragment().contains(ind.requiresFragment()));
    }

    private static String fragment(String url) {
        int h = url == null ? -1 : url.indexOf('#');
        return h < 0 ? null : url.substring(h + 1);
    }

    private static boolean hasClass(RawNode n, String token) {
        String c = n.attr("class");
        if (c == null) {
            return false;
        }
        for (String t : c.trim().split("\\s+")) {
            if (t.equals(token)) {
                return true;
            }
        }
        return false;
    }

    private static String describeNode(RawNode n) {
        StringBuilder sb = new StringBuilder();
        if (n.attr("id") != null) {
            sb.append('#').append(n.attr("id"));
        }
        if (n.attr("class") != null) {
            sb.append('.').append(n.attr("class").trim().replaceAll("\\s+", "."));
        }
        return sb.length() > 80 ? sb.substring(0, 80) : sb.toString();
    }

    private static void add(Map<String, Group> groups, SignatureCatalog.Signature sig, String frameId, ProtectionEvidence e) {
        String key = sig.id() + "@" + frameId;
        groups.computeIfAbsent(key, k -> new Group(sig, "doc:" + frameId, frameId, new ArrayList<>())).evidence().add(e);
    }

    private static List<ProtectionEvidence> dedupe(List<ProtectionEvidence> ev) {
        Map<String, ProtectionEvidence> out = new LinkedHashMap<>();
        for (ProtectionEvidence e : ev) {
            out.putIfAbsent(e.indicatorId() + "|" + e.nodeId() + "|" + e.detail(), e);
        }
        return new ArrayList<>(out.values());
    }

    private static String channel(String type) {
        return switch (type) {
            case "SCRIPT_RESOURCE", "FRAME_RESOURCE", "NETWORK_RESOURCE" -> "RESOURCE";
            case "DOCUMENT_TITLE" -> "DOCUMENT";
            default -> "DOM";
        };
    }

    private static String abbreviate(String t) {
        String s = t.replaceAll("\\s+", " ").trim();
        return s.length() > 120 ? s.substring(0, 117) + "..." : s;
    }
}
