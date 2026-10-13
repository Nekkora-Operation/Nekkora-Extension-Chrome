package com.nekkora.extension.core.locator.query;

import com.nekkora.extension.core.semantic.NameSource;
import com.nekkora.extension.core.snapshot.RawNode;
import com.nekkora.extension.core.text.TextNormalizer;

import java.util.List;

/**
 * Predicado de um passo de localizador. Cada predicado sabe avaliar-se sobre o snapshot e,
 * quando possível, renderizar-se em CSS e/ou XPath com a MESMA semântica — o que permite
 * verificar no navegador real a contagem calculada no motor Java.
 */
public sealed interface Predicate {

    boolean test(RawNode node, EvalContext ctx);

    /** Fragmento CSS equivalente, ou {@code null} se não expressável em CSS. */
    String css();

    /** Predicado XPath equivalente (sem colchetes), ou {@code null} se não expressável. */
    String xpath();

    String describe();

    /** {@code [name="value"]} — comparação exata do valor do atributo. */
    record AttrEquals(String name, String value) implements Predicate {
        @Override
        public boolean test(RawNode node, EvalContext ctx) {
            return value.equals(node.attr(name));
        }

        @Override
        public String css() {
            if (name.equals("id") && Escaping.isCssIdentifier(value)) {
                return "#" + value;
            }
            return "[" + name + "=" + Escaping.cssString(value) + "]";
        }

        @Override
        public String xpath() {
            return "@" + name + "=" + Escaping.xpathLiteral(value);
        }

        @Override
        public String describe() {
            return name + "=" + value;
        }
    }

    /** {@code [name]} — atributo presente. */
    record AttrPresent(String name) implements Predicate {
        @Override
        public boolean test(RawNode node, EvalContext ctx) {
            return node.hasAttr(name);
        }

        @Override
        public String css() {
            return "[" + name + "]";
        }

        @Override
        public String xpath() {
            return "@" + name;
        }

        @Override
        public String describe() {
            return "has " + name;
        }
    }

    /** {@code normalize-space(.) = text}: textContent com espaços normalizados (inclui texto oculto). */
    record TextEquals(String text) implements Predicate {
        @Override
        public boolean test(RawNode node, EvalContext ctx) {
            return text.equals(ctx.model().textContent(node));
        }

        @Override
        public String css() {
            return null;
        }

        @Override
        public String xpath() {
            return "normalize-space(.)=" + Escaping.xpathLiteral(text);
        }

        @Override
        public String describe() {
            return "text=\"" + text + "\"";
        }
    }

    /** Possui FILHO direto {@code tag} cujo texto normalizado é igual a {@code text}. */
    record HasChildText(String tag, String text) implements Predicate {
        @Override
        public boolean test(RawNode node, EvalContext ctx) {
            for (RawNode c : ctx.model().children(node)) {
                if (c.scopeId().equals(node.scopeId()) && (tag == null || c.tag().equals(tag))
                        && text.equals(ctx.model().textContent(c))) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public String css() {
            return null;
        }

        @Override
        public String xpath() {
            return (tag == null ? "*" : tag) + "[normalize-space(.)=" + Escaping.xpathLiteral(text) + "]";
        }

        @Override
        public String describe() {
            return "has child <" + (tag == null ? "*" : tag) + "> \"" + text + "\"";
        }
    }

    /** Posição entre irmãos de mesma tag ({@code :nth-of-type(n)} / {@code tag[n]}), base 1. */
    record NthOfType(int position) implements Predicate {
        @Override
        public boolean test(RawNode node, EvalContext ctx) {
            return ctx.nthOfType(node) == position;
        }

        @Override
        public String css() {
            return ":nth-of-type(" + position + ")";
        }

        @Override
        public String xpath() {
            return Integer.toString(position);
        }

        @Override
        public String describe() {
            return "nth-of-type " + position;
        }
    }

    /** Controle cujo label (associado por {@code for} ou por envolvimento) tem o texto informado. */
    record LabelTextEquals(String text, boolean wrapping) implements Predicate {
        @Override
        public boolean test(RawNode node, EvalContext ctx) {
            var cls = ctx.semantics().find(node.id()).orElse(null);
            if (cls == null) {
                return false;
            }
            NameSource expected = wrapping ? NameSource.WRAPPING_LABEL : NameSource.LABEL_ELEMENT;
            if (cls.name().source() != expected) {
                return false;
            }
            List<String> ids = cls.name().sourceNodeIds();
            for (String id : ids) {
                RawNode label = ctx.model().find(id).orElse(null);
                if (label != null && text.equals(ctx.model().textContent(label))) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public String css() {
            return null;
        }

        @Override
        public String xpath() {
            String lit = Escaping.xpathLiteral(text);
            return wrapping
                    ? "ancestor::label[normalize-space(.)=" + lit + "]"
                    : "@id=//label[normalize-space(.)=" + lit + "]/@for";
        }

        @Override
        public String describe() {
            return "label=\"" + text + "\"";
        }
    }

    /** Papel acessível calculado pelo motor (sem equivalente CSS/XPath). */
    record RoleIs(String role) implements Predicate {
        @Override
        public boolean test(RawNode node, EvalContext ctx) {
            return ctx.semantics().find(node.id()).map(c -> role.equals(c.role())).orElse(false);
        }

        @Override
        public String css() {
            return null;
        }

        @Override
        public String xpath() {
            return null;
        }

        @Override
        public String describe() {
            return "role=" + role;
        }
    }

    /** Nome acessível calculado pelo motor (comparação normalizada exata). */
    record NameEquals(String name) implements Predicate {
        @Override
        public boolean test(RawNode node, EvalContext ctx) {
            return ctx.semantics().find(node.id())
                    .map(c -> TextNormalizer.normalize(name).equals(c.name().name())).orElse(false);
        }

        @Override
        public String css() {
            return null;
        }

        @Override
        public String xpath() {
            return null;
        }

        @Override
        public String describe() {
            return "name=\"" + name + "\"";
        }
    }
}
