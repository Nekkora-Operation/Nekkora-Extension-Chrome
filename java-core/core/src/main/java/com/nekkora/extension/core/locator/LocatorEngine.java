package com.nekkora.extension.core.locator;

import com.nekkora.extension.core.context.ContextGraph;
import com.nekkora.extension.core.context.ElementFamily;
import com.nekkora.extension.core.locator.query.EvalContext;
import com.nekkora.extension.core.locator.query.LocatorEvaluator;
import com.nekkora.extension.core.locator.query.LocatorQuery;
import com.nekkora.extension.core.locator.query.LocatorStep;
import com.nekkora.extension.core.locator.query.Predicate;
import com.nekkora.extension.core.locator.strategy.AttributeStrategies;
import com.nekkora.extension.core.locator.strategy.ContextualStrategy;
import com.nekkora.extension.core.locator.strategy.LocatorCandidate;
import com.nekkora.extension.core.locator.strategy.LocatorStrategy;
import com.nekkora.extension.core.locator.strategy.StructuralStrategies;
import com.nekkora.extension.core.locator.strategy.TargetContext;
import com.nekkora.extension.core.semantic.Classification;
import com.nekkora.extension.core.snapshot.FrameInfo;
import com.nekkora.extension.core.snapshot.RawNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gera múltiplos localizadores para um alvo, avalia cada um no snapshot e os ordena por uma
 * pontuação que combina peso da estratégia, unicidade e estabilidade. Não existe hierarquia
 * absoluta: um id instável ou duplicado é superado por um localizador contextual estável.
 */
public final class LocatorEngine {

    private static final int MAX_FALLBACKS = 8;

    private final List<LocatorStrategy> strategies;
    private final LocatorEvaluator evaluator = new LocatorEvaluator();
    private final StabilityAssessor stability = new StabilityAssessor();

    public LocatorEngine() {
        this(defaultStrategies());
    }

    public LocatorEngine(List<LocatorStrategy> strategies) {
        this.strategies = List.copyOf(strategies);
    }

    public static List<LocatorStrategy> defaultStrategies() {
        return List.of(
                new AttributeStrategies.TestAttributeStrategy(),
                new AttributeStrategies.IdStrategy(),
                new AttributeStrategies.NameStrategy(),
                new AttributeStrategies.LabelStrategy(),
                new AttributeStrategies.RoleNameStrategy(),
                new ContextualStrategy(),
                new AttributeStrategies.SemanticAttributeStrategy(),
                new AttributeStrategies.TextStrategy(),
                new StructuralStrategies.RelativePathStrategy(),
                new StructuralStrategies.PositionalStrategy());
    }

    /** Sessão de geração ligada a um snapshot; compartilha caches entre alvos. */
    public Session session(ContextGraph graph) {
        return new Session(graph);
    }

    public LocatorEvaluator evaluator() {
        return evaluator;
    }

    public final class Session {
        private final ContextGraph graph;
        private final EvalContext eval;
        private final Map<String, LocatorScope> scopeCache = new HashMap<>();

        private Session(ContextGraph graph) {
            this.graph = graph;
            this.eval = new EvalContext(graph.model(), graph.semantics());
        }

        /** Nova sessão sobre o mesmo grafo (caches próprios), para uso em outra thread. */
        public Session fork() {
            return new Session(graph);
        }

        public EvalContext eval() {
            return eval;
        }

        public LocatorSet generate(String nodeId) {
            return generate(nodeId, StabilityObservations.NONE);
        }

        public LocatorSet generate(String nodeId, StabilityObservations observations) {
            RawNode node = graph.model().node(nodeId);
            Classification cls = graph.semantics().get(nodeId);
            ElementFamily family = graph.family(nodeId);
            TargetContext target = new TargetContext(node, cls, family, graph, eval, evaluator);
            LocatorScope scope = scopeOf(node);
            ExpectedTarget expected = expected(cls, family);

            Map<String, LocatorDescriptor> unique = new LinkedHashMap<>();
            for (LocatorStrategy strategy : strategies) {
                for (LocatorCandidate c : strategy.propose(target)) {
                    LocatorDescriptor d = describe(c, node, scope, expected, observations);
                    if (d != null) {
                        unique.merge(d.semantic(), d, (a, b) -> a.score() >= b.score() ? a : b);
                    }
                }
            }
            List<LocatorDescriptor> ranked = new ArrayList<>(unique.values());
            ranked.sort(Comparator.comparingDouble(LocatorDescriptor::score).reversed());

            List<String> warnings = new ArrayList<>();
            if (ranked.isEmpty()) {
                warnings.add("NO_LOCATOR: nenhuma estratégia produziu localizador que inclua o alvo");
                return new LocatorSet(nodeId, null, List.of(), warnings);
            }
            if (ranked.stream().noneMatch(LocatorDescriptor::unique)) {
                warnings.add("NO_UNIQUE_LOCATOR: nenhum localizador seleciona apenas o alvo; a resolução exigirá validação de identidade");
            }
            if (!scope.type().equals("MAIN_DOCUMENT")) {
                warnings.add("SCOPED: executar dentro de " + scope.type() + " (" + scope.chain().size() + " salto(s))");
            }
            LocatorDescriptor primary = ranked.get(0);
            List<LocatorDescriptor> fallbacks = ranked.subList(1, Math.min(ranked.size(), MAX_FALLBACKS + 1));
            return new LocatorSet(nodeId, primary, fallbacks, warnings);
        }

        private LocatorDescriptor describe(LocatorCandidate c, RawNode node, LocatorScope scope, ExpectedTarget expected,
                                           StabilityObservations observations) {
            List<RawNode> matches = evaluator.evaluate(c.query(), node.scopeId(), eval);
            boolean matchesTarget = matches.stream().anyMatch(n -> n.id().equals(node.id()));
            if (!matchesTarget) {
                return null;
            }
            StabilityAssessor.Result st = stability.assess(c.query(), observations);
            List<String> warnings = new ArrayList<>(c.notes());
            int count = matches.size();
            if (count > 1) {
                warnings.add("AMBIGUOUS_MATCH: " + count + " elementos correspondem");
            }
            for (LocatorStep s : c.query().steps()) {
                for (Predicate p : s.predicates()) {
                    if (p instanceof Predicate.AttrEquals eq && eq.name().equals("id")) {
                        int dup = graph.model().byIdAttribute(node.scopeId(), eq.value()).size();
                        if (dup > 1) {
                            warnings.add("DUPLICATE_ID: id \"" + eq.value() + "\" aparece " + dup + " vezes no escopo");
                        }
                    }
                    if (p instanceof Predicate.NthOfType) {
                        warnings.add("POSITIONAL_DEPENDENCY");
                    }
                }
            }
            if (st.level() == StabilityLevel.HEURISTIC_UNSTABLE || st.level() == StabilityLevel.OBSERVED_UNSTABLE) {
                warnings.add("UNSTABLE: " + String.join("; ", st.reasons()));
            }
            String css = c.query().css();
            String xpath = scope.type().equals("SHADOW_ROOT") ? null : c.query().xpath();
            if (css == null && xpath == null) {
                warnings.add("ENGINE_ONLY: sem equivalente CSS/XPath; verificável apenas pelo motor");
            }
            double uniqueness = count == 1 ? 1.0 : 0.35 / count;
            double verifiable = css != null ? 1.0 : xpath != null ? 0.98 : 0.95;
            double score = round(c.kind().baseWeight() * st.level().factor() * uniqueness * verifiable);
            List<String> ids = matches.stream().limit(20).map(RawNode::id).toList();
            return new LocatorDescriptor(c.kind(), css, xpath, c.query().semantic(), c.query(), scope, count, true, ids,
                    st.level(), st.basis(), st.reasons(), score, expected, dedupe(warnings), null);
        }

        /** Escopo do nó com a cadeia de saltos (iframes e shadow hosts) desde o documento principal. */
        public LocatorScope scopeOf(RawNode node) {
            LocatorScope cached = scopeCache.get(node.scopeId());
            if (cached == null) {
                cached = buildScope(node); // pode recursar para o host: não usar computeIfAbsent
                scopeCache.put(node.scopeId(), cached);
            }
            return cached;
        }

        private LocatorScope buildScope(RawNode node) {
            String scopeId = node.scopeId();
            if (scopeId.equals("doc:f0")) {
                return LocatorScope.main();
            }
            RawNode host;
            String kind;
            String type;
            if (scopeId.startsWith("shadow:")) {
                host = graph.model().find(scopeId.substring("shadow:".length())).orElse(null);
                kind = "SHADOW_HOST";
                type = "SHADOW_ROOT";
            } else {
                String frameId = scopeId.substring("doc:".length());
                String hostId = graph.model().snapshot().frames().stream()
                        .filter(f -> f.frameId().equals(frameId)).map(FrameInfo::hostNodeId).findFirst().orElse(null);
                host = graph.model().find(hostId).orElse(null);
                kind = "FRAME";
                type = "FRAME";
            }
            if (host == null) {
                return new LocatorScope(type, scopeId, node.frameId(), List.of());
            }
            LocatorScope parentScope = scopeOf(host);
            LocatorSet hostSet = generate(host.id());
            LocatorDescriptor hostCss = hostSet.all().stream().filter(d -> d.css() != null).findFirst().orElse(null);
            List<LocatorScope.Hop> chain = new ArrayList<>(parentScope.chain());
            chain.add(new LocatorScope.Hop(kind, host.id(), hostCss == null ? null : hostCss.css(),
                    hostCss == null ? null : hostCss.xpath(), hostCss == null ? 0 : hostCss.matchCount(),
                    hostCss == null ? null : hostCss.query()));
            return new LocatorScope(type, scopeId, node.frameId(), chain);
        }

        /** Executa um localizador estruturado no snapshot (usado pelo resolvedor). */
        public List<RawNode> evaluate(LocatorQuery q, String scopeId) {
            return evaluator.evaluate(q, scopeId, eval);
        }
    }

    static ExpectedTarget expected(Classification cls, ElementFamily f) {
        return new ExpectedTarget(cls.kind().name(), cls.role(), cls.name().name(),
                f.form() == null ? null : label(f.form().idAttr(), f.form().name(), f.form().keyAttributes().get("name")),
                f.table() == null ? null : label(f.table().idAttr(), f.table().name(), null),
                f.row() == null ? Map.of() : f.row().rowKey(),
                f.dialog() == null ? null : label(f.dialog().idAttr(), f.dialog().name(), null),
                f.containerHeading());
    }

    private static String label(String id, String name, String nameAttr) {
        if (id != null && !id.isBlank()) {
            return "#" + id;
        }
        if (nameAttr != null && !nameAttr.isBlank()) {
            return "[name=" + nameAttr + "]";
        }
        return name == null || name.isBlank() ? null : name;
    }

    private static List<String> dedupe(List<String> in) {
        return new ArrayList<>(new java.util.LinkedHashSet<>(in));
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
