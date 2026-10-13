package com.nekkora.extension.core.resolve;

import com.nekkora.extension.core.context.ContextGraph;
import com.nekkora.extension.core.identity.FeatureComparator;
import com.nekkora.extension.core.identity.FeatureOutcome;
import com.nekkora.extension.core.identity.FeatureResult;
import com.nekkora.extension.core.identity.IdentifierStability;
import com.nekkora.extension.core.identity.IdentityFactory;
import com.nekkora.extension.core.identity.IdentityFeature;
import com.nekkora.extension.core.identity.IdentitySignature;
import com.nekkora.extension.core.identity.SignatureExtractor;
import com.nekkora.extension.core.identity.TargetIdentity;
import com.nekkora.extension.core.locator.LocatorDescriptor;
import com.nekkora.extension.core.locator.LocatorEngine;
import com.nekkora.extension.core.locator.LocatorScope;
import com.nekkora.extension.core.locator.LocatorSet;
import com.nekkora.extension.core.locator.LocatorStrategyKind;
import com.nekkora.extension.core.locator.StabilityObservations;
import com.nekkora.extension.core.semantic.Classification;
import com.nekkora.extension.core.snapshot.DocumentInfo;
import com.nekkora.extension.core.snapshot.FrameInfo;
import com.nekkora.extension.core.snapshot.RawNode;
import com.nekkora.extension.core.snapshot.ShadowRootInfo;
import com.nekkora.extension.core.text.TextMatch;
import com.nekkora.extension.core.text.TextNormalizer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Reencontra um alvo lógico em um novo snapshot.
 *
 * <p>Princípio: um seletor que retorna um elemento NÃO é uma resolução. Cada candidato é confrontado
 * com a identidade registrada (tipo, nome, família e discriminadores). Quando duas alternativas
 * permanecem plausíveis o resultado é {@link ResolutionStatus#AMBIGUOUS} — nunca o primeiro índice.
 */
public final class TargetResolver {

    /** Pontuação mínima para confirmar uma identidade. */
    public static final double CONFIRM_THRESHOLD = 0.55;
    /** Vantagem mínima do melhor candidato sobre o segundo para desempate. */
    public static final double TIE_MARGIN = 0.15;
    private static final int MAX_BROAD_CANDIDATES = 400;
    private static final Set<LocatorStrategyKind> STRONG = EnumSet.of(
            LocatorStrategyKind.ID, LocatorStrategyKind.TEST_ATTRIBUTE, LocatorStrategyKind.NAME);

    private final SignatureExtractor extractor = new SignatureExtractor();
    private final FeatureComparator comparator = new FeatureComparator();

    public ResolutionResult resolve(TargetIdentity identity, ContextGraph graph, LocatorEngine.Session session) {
        String captureId = graph.model().snapshot().captureId();
        List<String> evidence = new ArrayList<>();
        IdentitySignature expected = identity.signature();

        // 1) Escopo
        ScopeResolution scope = resolveScopes(identity, graph, session);
        if (scope.scopes().isEmpty()) {
            ResolutionStatus st = scope.unavailable() ? ResolutionStatus.SCOPE_UNAVAILABLE : ResolutionStatus.NOT_FOUND;
            evidence.add(scope.description());
            return result(identity, captureId, st, null, 0, false, scope.description(), List.of(), List.of(), Map.of(), evidence, null, null);
        }

        // 2) Localizadores registrados
        Map<String, Set<String>> foundBy = new LinkedHashMap<>();
        Set<String> strongHits = new LinkedHashSet<>();
        List<LocatorCheck> checks = new ArrayList<>();
        for (LocatorDescriptor d : identity.locators().all()) {
            if (d.query() == null) {
                continue;
            }
            List<String> ids = new ArrayList<>();
            for (String s : scope.scopes()) {
                for (RawNode n : session.evaluate(d.query(), s)) {
                    ids.add(n.id());
                }
            }
            checks.add(new LocatorCheck(d.strategy().name(), d.semantic(), ids.size(), ids.stream().limit(20).toList()));
            for (String id : ids) {
                foundBy.computeIfAbsent(id, k -> new LinkedHashSet<>()).add(d.strategy().name());
                if (STRONG.contains(d.strategy())) {
                    strongHits.add(id);
                }
            }
        }

        // 2b) Sonda de endereço: elementos que hoje ocupam o id/name registrados, de QUALQUER tipo.
        //     Serve para detectar que o "endereço" do alvo passou a conter outro controle.
        for (String s : scope.scopes()) {
            if (expected.idAttribute() != null) {
                for (RawNode n : graph.model().byIdAttribute(s, expected.idAttribute())) {
                    foundBy.computeIfAbsent(n.id(), k -> new LinkedHashSet<>()).add("ID_PROBE");
                    strongHits.add(n.id());
                }
            }
            if (expected.nameAttribute() != null) {
                for (RawNode n : graph.model().inScope(s)) {
                    if (expected.nameAttribute().equals(n.attr("name"))) {
                        foundBy.computeIfAbsent(n.id(), k -> new LinkedHashSet<>()).add("NAME_PROBE");
                        strongHits.add(n.id());
                    }
                }
            }
        }

        // 3) Busca ampla por elementos da mesma família de compatibilidade (cobre ids dinâmicos e re-renderização)
        int broad = 0;
        for (String s : scope.scopes()) {
            for (RawNode n : graph.model().inScope(s)) {
                Classification c = graph.semantics().get(n.id());
                if (!c.kind().compatibilityFamily().equals(expected.compatibility())) {
                    continue;
                }
                if (expected.name() != null && TextNormalizer.compare(expected.name(), c.name().name()) == TextMatch.NONE
                        && !foundBy.containsKey(n.id())) {
                    continue; // nome incompatível: só interessa se um localizador forte apontou para ele
                }
                if (broad++ < MAX_BROAD_CANDIDATES) {
                    foundBy.computeIfAbsent(n.id(), k -> new LinkedHashSet<>()).add("BROAD_SEARCH");
                }
            }
        }
        if (broad > MAX_BROAD_CANDIDATES) {
            evidence.add("Busca ampla limitada a " + MAX_BROAD_CANDIDATES + " de " + broad + " candidatos compatíveis");
        }

        // 4) Avaliação
        double idWeight = IdentifierStability.weightFor("id", expected.idAttribute(), identity.attributeHistory());
        double nameAttrWeight = IdentifierStability.weightFor("name", expected.nameAttribute(), identity.attributeHistory());
        List<CandidateEvaluation> evaluations = new ArrayList<>();
        boolean mismatchAtAddress = false;
        for (Map.Entry<String, Set<String>> e : foundBy.entrySet()) {
            String nodeId = e.getKey();
            Classification c = graph.semantics().get(nodeId);
            List<String> reasons = new ArrayList<>();
            if (!c.kind().compatibilityFamily().equals(expected.compatibility())) {
                reasons.add("tipo incompatível: esperado " + expected.kind() + ", encontrado " + c.kind());
                evaluations.add(new CandidateEvaluation(nodeId, "INCOMPATIBLE", 0, List.copyOf(e.getValue()), List.of(), reasons));
                if (strongHits.contains(nodeId)) {
                    mismatchAtAddress = true;
                }
                continue;
            }
            IdentitySignature actual = extractor.extract(nodeId, graph);
            List<FeatureResult> features = new ArrayList<>();
            double num = 0;
            double den = 0;
            for (IdentityFeature f : IdentityFeature.values()) {
                FeatureResult r = comparator.compare(f, expected, actual);
                features.add(r);
                if (r.outcome() == FeatureOutcome.NOT_APPLICABLE) {
                    continue;
                }
                double w = f == IdentityFeature.ID_ATTRIBUTE ? idWeight : f == IdentityFeature.NAME_ATTRIBUTE ? nameAttrWeight : f.weight();
                den += w;
                num += w * (r.outcome() == FeatureOutcome.PARTIAL ? Math.min(r.quality(), 0.4)
                        : r.outcome() == FeatureOutcome.MATCH ? r.quality() : 0);
            }
            double score = den == 0 ? 0 : Math.round(num / den * 1000.0) / 1000.0;
            String verdict = verdict(identity, features, score, reasons);
            if ("DISQUALIFIED".equals(verdict) && strongHits.contains(nodeId)
                    && outcome(features, IdentityFeature.NAME) == FeatureOutcome.MISMATCH) {
                mismatchAtAddress = true;
            }
            evaluations.add(new CandidateEvaluation(nodeId, verdict, score, List.copyOf(e.getValue()), features, reasons));
        }
        evaluations.sort(Comparator.comparingDouble(CandidateEvaluation::score).reversed());

        List<CandidateEvaluation> confirmed = evaluations.stream().filter(c -> c.verdict().equals("CONFIRMED")).toList();
        List<CandidateEvaluation> viable = evaluations.stream()
                .filter(c -> c.verdict().equals("CONFIRMED") || c.verdict().equals("VIABLE")).toList();
        evidence.add(foundBy.size() + " candidato(s) avaliado(s): " + confirmed.size() + " confirmado(s), "
                + (viable.size() - confirmed.size()) + " plausível(is) sem comprovação");

        // 5) Decisão
        ResolutionStatus status;
        CandidateEvaluation chosen = null;
        if (!confirmed.isEmpty()) {
            if (identity.indistinguishableAtRegistration()) {
                status = confirmed.size() > 1 ? ResolutionStatus.AMBIGUOUS : ResolutionStatus.INSUFFICIENT_EVIDENCE;
                evidence.add("O alvo já era indistinguível de outro elemento no registro; nenhuma escolha é segura");
            } else if (confirmed.size() == 1) {
                status = ResolutionStatus.RESOLVED;
                chosen = confirmed.get(0);
            } else if (confirmed.get(0).score() - confirmed.get(1).score() >= TIE_MARGIN) {
                status = ResolutionStatus.RESOLVED;
                chosen = confirmed.get(0);
                evidence.add("Mais de um candidato confirmado; o melhor supera o segundo em "
                        + round(confirmed.get(0).score() - confirmed.get(1).score()) + " (margem mínima " + TIE_MARGIN + ")");
            } else {
                status = ResolutionStatus.AMBIGUOUS;
                evidence.add("Candidatos " + confirmed.get(0).nodeId() + " e " + confirmed.get(1).nodeId()
                        + " são igualmente plausíveis (" + confirmed.get(0).score() + " vs " + confirmed.get(1).score() + ")");
            }
        } else if (viable.size() >= 2 && viable.get(0).score() - viable.get(1).score() < TIE_MARGIN) {
            status = ResolutionStatus.AMBIGUOUS;
            evidence.add("Nenhum candidato comprovado e " + viable.size() + " igualmente plausíveis");
        } else if (!viable.isEmpty()) {
            status = ResolutionStatus.INSUFFICIENT_EVIDENCE;
            evidence.add("Candidato " + viable.get(0).nodeId() + " é plausível mas não comprova a identidade: "
                    + String.join("; ", viable.get(0).reasons()));
        } else if (mismatchAtAddress) {
            status = ResolutionStatus.IDENTITY_MISMATCH;
            evidence.add("O(s) localizador(es) fortes da identidade agora apontam para elemento semanticamente incompatível");
        } else if (sameSession(identity, graph) && identity.lastNodeId() != null && !graph.model().contains(identity.lastNodeId())) {
            status = ResolutionStatus.STALE_TARGET;
            evidence.add("O nó " + identity.lastNodeId() + " foi removido da página nesta sessão e não há substituto confirmável");
        } else {
            status = ResolutionStatus.NOT_FOUND;
            evidence.add("Nenhum elemento compatível com a identidade foi encontrado");
        }

        if (chosen == null) {
            double best = evaluations.isEmpty() ? 0 : evaluations.get(0).score();
            return result(identity, captureId, status, null, best, false, scope.description(), checks, evaluations, Map.of(), evidence, null, null);
        }

        RawNode node = graph.model().node(chosen.nodeId());
        boolean replaced = !sameSession(identity, graph) || !Objects.equals(identity.lastNodeId(), node.id());
        if (replaced) {
            evidence.add(sameSession(identity, graph)
                    ? "Elemento re-renderizado/substituído: nó " + identity.lastNodeId() + " → " + node.id() + " (identidade revalidada)"
                    : "Nova sessão de página: identidade revalidada no nó " + node.id());
        }
        Map<String, List<String>> changes = new LinkedHashMap<>();
        Map<String, List<String>> history = new LinkedHashMap<>();
        for (String attr : List.of("id", "name")) {
            List<String> h = new ArrayList<>(identity.attributeHistory().getOrDefault(attr, List.of()));
            String before = h.isEmpty() ? null : h.get(h.size() - 1);
            String now = node.attr(attr);
            if (!Objects.equals(before, now)) {
                changes.put(attr, java.util.Arrays.asList(before, now));
                evidence.add("Atributo " + attr + " mudou: \"" + before + "\" → \"" + now + "\"");
            }
            h.add(now);
            history.put(attr, h);
        }
        LocatorSet updated = session.generate(node.id(), StabilityObservations.of(history));
        DocumentInfo doc = graph.model().snapshot().document();
        TargetIdentity updatedIdentity = new TargetIdentity(identity.targetId(), identity.schemaVersion(),
                identity.registeredAt(), identity.origin(), identity.signature(), identity.discriminators(),
                identity.lookalikeCount(), updated, node.id(), doc == null ? null : doc.pageSessionId(), captureId,
                history, identity.warnings());
        return result(identity, captureId, status, node.id(), chosen.score(), replaced, scope.description(), checks,
                evaluations, changes, evidence, updated, updatedIdentity);
    }

    private static String verdict(TargetIdentity identity, List<FeatureResult> features, double score, List<String> reasons) {
        boolean disqualified = false;
        if (outcome(features, IdentityFeature.NAME) == FeatureOutcome.MISMATCH) {
            reasons.add("nome acessível divergente: " + detail(features, IdentityFeature.NAME));
            disqualified = true;
        }
        if (outcome(features, IdentityFeature.TEST_ATTRIBUTE) == FeatureOutcome.MISMATCH) {
            reasons.add("atributo de teste divergente");
            disqualified = true;
        }
        String keySource = identity.signature().rowKeySource();
        boolean strongRowKey = keySource != null && !keySource.equals("FIRST_CELL_TEXT") && !keySource.equals("NONE");
        if (strongRowKey && outcome(features, IdentityFeature.ROW_KEY) == FeatureOutcome.MISMATCH) {
            reasons.add("pertence a outra linha: " + detail(features, IdentityFeature.ROW_KEY));
            disqualified = true;
        }
        boolean unproven = false;
        boolean anyDiscriminatorMatch = false;
        for (IdentityFeature d : identity.discriminators()) {
            FeatureOutcome o = outcome(features, d);
            boolean hard = IdentityFactory.HARD_DISCRIMINATORS.contains(d);
            if (o == FeatureOutcome.MATCH) {
                anyDiscriminatorMatch = true;
            } else if (o == FeatureOutcome.MISMATCH && hard) {
                reasons.add("discriminador " + d + " divergente: " + detail(features, d));
                disqualified = true;
            } else if (o == FeatureOutcome.ABSENT && hard) {
                reasons.add("discriminador " + d + " não observável no candidato");
                unproven = true;
            }
        }
        if (disqualified) {
            return "DISQUALIFIED";
        }
        if (!identity.discriminators().isEmpty() && !anyDiscriminatorMatch) {
            reasons.add("nenhum discriminador confirmado");
            unproven = true;
        }
        if (score < CONFIRM_THRESHOLD) {
            reasons.add("pontuação " + score + " abaixo do limiar " + CONFIRM_THRESHOLD);
            unproven = true;
        }
        if (unproven) {
            return "VIABLE";
        }
        reasons.add("identidade confirmada (pontuação " + score + ")");
        return "CONFIRMED";
    }

    private static FeatureOutcome outcome(List<FeatureResult> features, IdentityFeature f) {
        return features.stream().filter(r -> r.feature() == f).map(FeatureResult::outcome).findFirst().orElse(FeatureOutcome.NOT_APPLICABLE);
    }

    private static String detail(List<FeatureResult> features, IdentityFeature f) {
        return features.stream().filter(r -> r.feature() == f).map(r -> "\"" + r.expected() + "\" ≠ \"" + r.actual() + "\"")
                .findFirst().orElse("");
    }

    private static boolean sameSession(TargetIdentity identity, ContextGraph graph) {
        DocumentInfo doc = graph.model().snapshot().document();
        return doc != null && identity.lastPageSessionId() != null && identity.lastPageSessionId().equals(doc.pageSessionId());
    }

    // ----------------------------------------------------------------------------------- escopo

    private record ScopeResolution(List<String> scopes, boolean unavailable, String description) {
    }

    private ScopeResolution resolveScopes(TargetIdentity identity, ContextGraph graph, LocatorEngine.Session session) {
        LocatorDescriptor primary = identity.locators().primary();
        if (primary == null || primary.scope() == null || primary.scope().chain().isEmpty()) {
            return new ScopeResolution(List.of("doc:f0"), false, "AVAILABLE: documento principal");
        }
        List<String> current = List.of("doc:f0");
        for (LocatorScope.Hop hop : primary.scope().chain()) {
            if (hop.query() == null) {
                return new ScopeResolution(List.of(), true, "UNAVAILABLE: host de escopo sem localizador");
            }
            List<String> next = new ArrayList<>();
            List<String> unavailableReasons = new ArrayList<>();
            int hosts = 0;
            for (String s : current) {
                for (RawNode host : session.evaluate(hop.query(), s)) {
                    hosts++;
                    if (hop.kind().equals("FRAME")) {
                        FrameInfo fi = graph.model().snapshot().frames().stream()
                                .filter(f -> host.id().equals(f.hostNodeId())).findFirst().orElse(null);
                        if (fi != null && fi.accessible()) {
                            next.add("doc:" + fi.frameId());
                        } else {
                            unavailableReasons.add("iframe " + host.id() + ": " + (fi == null ? "não inventariado" : fi.reason()));
                        }
                    } else {
                        ShadowRootInfo si = graph.model().snapshot().shadowRoots().stream()
                                .filter(r -> host.id().equals(r.hostNodeId())).findFirst().orElse(null);
                        if (si != null && si.inspected()) {
                            next.add("shadow:" + host.id());
                        } else {
                            unavailableReasons.add("shadow root de " + host.id() + ": "
                                    + (si == null ? "não detectado" : si.mode() + (si.reason() == null ? "" : " — " + si.reason())));
                        }
                    }
                }
            }
            if (hosts == 0) {
                return new ScopeResolution(List.of(), false, "HOST_NOT_FOUND: host do escopo (" + hop.css() + ") não encontrado");
            }
            if (next.isEmpty()) {
                return new ScopeResolution(List.of(), true, "UNAVAILABLE: " + String.join("; ", unavailableReasons));
            }
            current = next;
        }
        return new ScopeResolution(current, false, "AVAILABLE: " + current);
    }

    private static ResolutionResult result(TargetIdentity identity, String captureId, ResolutionStatus status, String nodeId,
                                           double score, boolean replaced, String scope, List<LocatorCheck> checks,
                                           List<CandidateEvaluation> candidates, Map<String, List<String>> changes,
                                           List<String> evidence, LocatorSet updated, TargetIdentity updatedIdentity) {
        return new ResolutionResult(TargetIdentity.SCHEMA_VERSION, identity.targetId(), captureId, status, nodeId, score,
                replaced, scope, checks, candidates, changes, evidence, updated, updatedIdentity);
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
