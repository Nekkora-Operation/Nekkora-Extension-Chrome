package com.nekkora.extension.core.locator.strategy;

import com.nekkora.extension.core.locator.LocatorStrategyKind;
import com.nekkora.extension.core.locator.query.LocatorQuery;
import com.nekkora.extension.core.locator.query.LocatorStep;
import com.nekkora.extension.core.locator.query.Predicate;
import com.nekkora.extension.core.semantic.NameSource;
import com.nekkora.extension.core.snapshot.RawNode;

import java.util.ArrayList;
import java.util.List;

/** Estratégias baseadas em um único atributo ou propriedade do próprio elemento. */
public final class AttributeStrategies {

    private AttributeStrategies() {
    }

    /** {@code tag#id}. */
    public static final class IdStrategy implements LocatorStrategy {
        @Override
        public List<LocatorCandidate> propose(TargetContext t) {
            String id = t.node().attr("id");
            if (!StepFactory.nonBlank(id)) {
                return List.of();
            }
            return List.of(LocatorCandidate.of(LocatorStrategyKind.ID,
                    LocatorQuery.of(LocatorStep.descendant(t.node().tag(), new Predicate.AttrEquals("id", id)))));
        }
    }

    /** Atributos dedicados a testes (data-testid, data-test, data-qa, data-cy). */
    public static final class TestAttributeStrategy implements LocatorStrategy {
        @Override
        public List<LocatorCandidate> propose(TargetContext t) {
            List<LocatorCandidate> out = new ArrayList<>();
            for (String a : StepFactory.TEST_ATTRIBUTES) {
                String v = t.node().attr(a);
                if (StepFactory.nonBlank(v)) {
                    out.add(LocatorCandidate.of(LocatorStrategyKind.TEST_ATTRIBUTE,
                            LocatorQuery.of(LocatorStep.descendant(t.node().tag(), new Predicate.AttrEquals(a, v)))));
                }
            }
            return out;
        }
    }

    /** {@code tag[name]} (e {@code [value]} para radios/checkboxes). */
    public static final class NameStrategy implements LocatorStrategy {
        @Override
        public List<LocatorCandidate> propose(TargetContext t) {
            RawNode n = t.node();
            String name = n.attr("name");
            if (!StepFactory.nonBlank(name) || !StepFactory.isFormControl(n.tag())) {
                return List.of();
            }
            List<LocatorCandidate> out = new ArrayList<>();
            String type = n.attr("type");
            if (("radio".equals(type) || "checkbox".equals(type)) && StepFactory.nonBlank(n.attr("value"))) {
                out.add(LocatorCandidate.of(LocatorStrategyKind.NAME, LocatorQuery.of(LocatorStep.descendant(n.tag(),
                        new Predicate.AttrEquals("name", name), new Predicate.AttrEquals("value", n.attr("value"))))));
            }
            out.add(LocatorCandidate.of(LocatorStrategyKind.NAME,
                    LocatorQuery.of(LocatorStep.descendant(n.tag(), new Predicate.AttrEquals("name", name)))));
            return out;
        }
    }

    /** Papel + nome acessível, avaliado pelo motor (notação semântica). */
    public static final class RoleNameStrategy implements LocatorStrategy {
        @Override
        public List<LocatorCandidate> propose(TargetContext t) {
            String role = t.classification().role();
            String name = t.classification().name().name();
            if (role == null || name.isEmpty() || role.equals("generic")) {
                return List.of();
            }
            return List.of(LocatorCandidate.of(LocatorStrategyKind.ROLE_NAME, LocatorQuery.of(
                    LocatorStep.descendant(null, new Predicate.RoleIs(role), new Predicate.NameEquals(name))),
                    "avaliado pelo motor (papel/nome calculados), sem equivalente CSS/XPath"));
        }
    }

    /** Controle pelo texto do label associado. */
    public static final class LabelStrategy implements LocatorStrategy {
        @Override
        public List<LocatorCandidate> propose(TargetContext t) {
            NameSource src = t.classification().name().source();
            if (src != NameSource.LABEL_ELEMENT && src != NameSource.WRAPPING_LABEL) {
                return List.of();
            }
            List<LocatorCandidate> out = new ArrayList<>();
            for (String labelId : t.classification().name().sourceNodeIds()) {
                t.graph().model().find(labelId).ifPresent(label -> {
                    String text = t.graph().model().textContent(label);
                    if (!text.isEmpty() && text.length() <= 120) {
                        out.add(LocatorCandidate.of(LocatorStrategyKind.LABEL, LocatorQuery.of(LocatorStep.descendant(
                                t.node().tag(), new Predicate.LabelTextEquals(text, src == NameSource.WRAPPING_LABEL)))));
                    }
                });
                break;
            }
            return out;
        }
    }

    /** {@code tag} + texto normalizado. */
    public static final class TextStrategy implements LocatorStrategy {
        @Override
        public List<LocatorCandidate> propose(TargetContext t) {
            for (LocatorStep s : StepFactory.simpleSteps(t.node(), t.graph())) {
                if (s.predicates().size() == 1 && s.predicates().get(0) instanceof Predicate.TextEquals) {
                    return List.of(LocatorCandidate.of(LocatorStrategyKind.TEXT, LocatorQuery.of(s)));
                }
            }
            return List.of();
        }
    }

    /** Atributos semânticos (aria-label, placeholder, title, alt, href, type). */
    public static final class SemanticAttributeStrategy implements LocatorStrategy {
        @Override
        public List<LocatorCandidate> propose(TargetContext t) {
            List<LocatorCandidate> out = new ArrayList<>();
            RawNode n = t.node();
            for (String a : List.of("aria-label", "placeholder", "title", "alt", "href")) {
                String v = n.attr(a);
                if (StepFactory.nonBlank(v) && v.length() <= 200) {
                    out.add(LocatorCandidate.of(LocatorStrategyKind.ATTRIBUTE,
                            LocatorQuery.of(LocatorStep.descendant(n.tag(), new Predicate.AttrEquals(a, v)))));
                }
            }
            if (n.tag().equals("input") && StepFactory.nonBlank(n.attr("type"))) {
                out.add(LocatorCandidate.of(LocatorStrategyKind.ATTRIBUTE,
                        LocatorQuery.of(LocatorStep.descendant("input", new Predicate.AttrEquals("type", n.attr("type"))))));
            }
            return out;
        }
    }
}
