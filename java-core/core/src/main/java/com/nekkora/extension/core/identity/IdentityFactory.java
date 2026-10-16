package com.nekkora.extension.core.identity;

import com.nekkora.extension.core.context.ContextGraph;
import com.nekkora.extension.core.locator.IdentifierHeuristics;
import com.nekkora.extension.core.locator.LocatorEngine;
import com.nekkora.extension.core.locator.LocatorSet;
import com.nekkora.extension.core.locator.StabilityLevel;
import com.nekkora.extension.core.locator.StabilityObservations;
import com.nekkora.extension.core.semantic.Classification;
import com.nekkora.extension.core.snapshot.DocumentInfo;
import com.nekkora.extension.core.snapshot.RawNode;
import com.nekkora.extension.core.text.TextNormalizer;

import java.net.URI;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Registra a identidade lógica de um alvo: assinatura, localizadores e — principalmente — os
 * DISCRIMINADORES: características que o diferenciavam, no momento do registro, dos elementos
 * aparentemente idênticos (mesmo tipo e mesmo nome).
 */
public final class IdentityFactory {

    /** Discriminadores estruturais: divergência refuta a identidade. */
    public static final Set<IdentityFeature> HARD_DISCRIMINATORS = EnumSet.of(
            IdentityFeature.TEST_ATTRIBUTE, IdentityFeature.ROW_KEY, IdentityFeature.TABLE, IdentityFeature.DIALOG,
            IdentityFeature.FORM, IdentityFeature.FIELD_GROUP, IdentityFeature.LABEL, IdentityFeature.CONTAINER_HEADING,
            IdentityFeature.ROW_TEXTS, IdentityFeature.SCOPE);

    private static final Set<IdentityFeature> NEVER_DISCRIMINATORS = EnumSet.of(
            IdentityFeature.NAME, IdentityFeature.TEXT, IdentityFeature.TYPE_ATTRIBUTE);

    private final SignatureExtractor extractor = new SignatureExtractor();
    private final FeatureComparator comparator = new FeatureComparator();
    private final IdentifierHeuristics heuristics = new IdentifierHeuristics();
    private final Supplier<String> idGenerator;
    private final Supplier<String> clock;

    public IdentityFactory(Supplier<String> idGenerator, Supplier<String> clock) {
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    public IdentityFactory() {
        this(() -> "tgt-" + java.util.UUID.randomUUID(), () -> java.time.Instant.now().toString());
    }

    public TargetIdentity register(String nodeId, ContextGraph graph, LocatorEngine.Session locators, String captureId) {
        IdentitySignature sig = extractor.extract(nodeId, graph);
        List<String> warnings = new ArrayList<>();
        Set<IdentityFeature> discriminators = EnumSet.noneOf(IdentityFeature.class);

        List<String> lookalikes = lookalikes(nodeId, sig, graph);
        // CHROME-003: um parecido em OUTRO frame cuja identidade documental difere é distinguido pela hierarquia
        // (a resolução valida a cadeia de frames antes dos candidatos); só permanecem os de frames equivalentes.
        RawNode self = graph.model().node(nodeId);
        String selfChain = chainKey(graph, self);
        List<String> otherFrames = new ArrayList<>();
        for (String other : lookalikes) {
            RawNode o = graph.model().node(other);
            if (!o.frameId().equals(self.frameId()) && !chainKey(graph, o).equals(selfChain)) {
                otherFrames.add(other);
            }
        }
        lookalikes.removeAll(otherFrames);
        if (!otherFrames.isEmpty()) {
            warnings.add("DISTINGUISHED_BY_FRAME: " + otherFrames.size() + " elemento(s) parecido(s) em frame(s) de identidade diferente");
        }
        List<String> indistinguishable = new ArrayList<>();
        for (String other : lookalikes) {
            IdentitySignature o = extractor.extract(other, graph);
            Set<IdentityFeature> diff = distinguishing(sig, o);
            if (diff.isEmpty()) {
                indistinguishable.add(other);
            }
            discriminators.addAll(diff);
        }
        if (!indistinguishable.isEmpty()) {
            warnings.add("INDISTINGUISHABLE_AT_REGISTRATION: " + indistinguishable.size()
                    + " elemento(s) sem nenhuma característica diferenciadora: " + indistinguishable);
        }
        if (!lookalikes.isEmpty() && discriminators.isEmpty()) {
            warnings.add("NO_DISCRIMINATOR");
        }

        Map<String, List<String>> history = new LinkedHashMap<>();
        RawNode node = graph.model().node(nodeId);
        history.put("id", new ArrayList<>(java.util.Collections.singletonList(node.attr("id"))));
        history.put("name", new ArrayList<>(java.util.Collections.singletonList(node.attr("name"))));
        LocatorSet set = locators.generate(nodeId, StabilityObservations.NONE);
        DocumentInfo doc = graph.model().snapshot().document();
        return new TargetIdentity(idGenerator.get(), TargetIdentity.SCHEMA_VERSION, clock.get(), originOf(doc),
                sig, discriminators, lookalikes.size(), set, nodeId, doc == null ? null : doc.pageSessionId(),
                captureId, history, warnings, com.nekkora.extension.core.frames.FrameIdentities.chainFor(graph, node),
                NodeAnchor.of(graph, node));
    }

    private static String chainKey(ContextGraph graph, RawNode node) {
        StringBuilder sb = new StringBuilder();
        for (var f : com.nekkora.extension.core.frames.FrameIdentities.chainFor(graph, node)) {
            sb.append(f.key()).append(" > ");
        }
        return sb.toString();
    }

    /** Elementos da mesma família de compatibilidade com o mesmo nome (ou mesmo texto, sem nome). */
    public List<String> lookalikes(String nodeId, IdentitySignature sig, ContextGraph graph) {
        List<String> out = new ArrayList<>();
        String name = sig.name() == null ? "" : TextNormalizer.fold(sig.name());
        String text = sig.text() == null ? "" : TextNormalizer.fold(sig.text());
        for (Classification c : graph.semantics().all()) {
            if (c.nodeId().equals(nodeId) || !c.kind().compatibilityFamily().equals(sig.compatibility())) {
                continue;
            }
            String otherName = TextNormalizer.fold(c.name().name());
            boolean same;
            if (!name.isEmpty()) {
                same = name.equals(otherName);
            } else {
                RawNode other = graph.model().node(c.nodeId());
                same = otherName.isEmpty() && other.tag().equals(sig.tag())
                        && text.equals(TextNormalizer.fold(TextNormalizer.truncate(graph.model().textContent(other), 120)));
            }
            if (same) {
                out.add(c.nodeId());
            }
        }
        return out;
    }

    Set<IdentityFeature> distinguishing(IdentitySignature target, IdentitySignature other) {
        Set<IdentityFeature> diff = EnumSet.noneOf(IdentityFeature.class);
        for (IdentityFeature f : IdentityFeature.values()) {
            if (NEVER_DISCRIMINATORS.contains(f)) {
                continue;
            }
            if ((f == IdentityFeature.ID_ATTRIBUTE && unstable(target.idAttribute()))
                    || (f == IdentityFeature.NAME_ATTRIBUTE && unstable(target.nameAttribute()))) {
                continue;
            }
            FeatureResult r = comparator.compare(f, target, other);
            if (r.outcome() == FeatureOutcome.MISMATCH || r.outcome() == FeatureOutcome.ABSENT) {
                diff.add(f);
            }
        }
        return diff;
    }

    private boolean unstable(String value) {
        return value != null && heuristics.assess(value).level() == StabilityLevel.HEURISTIC_UNSTABLE;
    }

    static String originOf(DocumentInfo doc) {
        if (doc == null || doc.url() == null) {
            return null;
        }
        try {
            URI u = URI.create(com.nekkora.extension.core.privacy.CapturePrivacyPolicy.forUri(doc.url()));
            return u.getScheme() + "://" + u.getAuthority() + (u.getPath() == null ? "" : u.getPath());
        } catch (IllegalArgumentException e) {
            return doc.url();
        }
    }
}
