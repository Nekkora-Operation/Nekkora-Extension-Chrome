package com.nekkora.extension.core.identity;

import com.nekkora.extension.core.context.ContextGraph;
import com.nekkora.extension.core.context.ElementFamily;
import com.nekkora.extension.core.context.NodeRef;
import com.nekkora.extension.core.semantic.Classification;
import com.nekkora.extension.core.snapshot.RawNode;
import com.nekkora.extension.core.text.TextNormalizer;

import java.util.ArrayList;
import java.util.List;

/** Extrai a {@link IdentitySignature} de um elemento a partir do grafo de contexto. */
public final class SignatureExtractor {

    private static final List<String> TEST_ATTRIBUTES = List.of("data-testid", "data-test-id", "data-test", "data-qa", "data-cy");

    public IdentitySignature extract(String nodeId, ContextGraph graph) {
        RawNode node = graph.model().node(nodeId);
        Classification c = graph.semantics().get(nodeId);
        ElementFamily f = graph.family(nodeId);

        String testAttr = null;
        for (String a : TEST_ATTRIBUTES) {
            if (node.attr(a) != null && !node.attr(a).isBlank()) {
                testAttr = a + "=" + node.attr(a);
                break;
            }
        }
        String label = f.labels().isEmpty() ? null : f.labels().get(0).name();
        String table = null;
        if (f.table() != null) {
            table = containerKey(f.table());
            if (table == null) {
                String heading = graph.family(f.table().nodeId()).containerHeading();
                table = heading == null ? null : "heading:" + heading;
            }
        }
        List<String> scope = new ArrayList<>();
        for (NodeRef host : f.scopeChain()) {
            scope.add(host.tag() + (host.idAttr() != null ? "#" + host.idAttr()
                    : host.keyAttributes().containsKey("name") ? "[name=" + host.keyAttributes().get("name") + "]" : ""));
        }
        String text = graph.model().textContent(node);
        return new IdentitySignature(
                c.kind().name(),
                c.kind().compatibilityFamily(),
                c.role(),
                node.tag(),
                emptyToNull(c.name().name()),
                emptyToNull(TextNormalizer.truncate(text, 120)),
                emptyToNull(node.attr("id")),
                emptyToNull(node.attr("name")),
                testAttr,
                emptyToNull(node.attr("type")),
                emptyToNull(label),
                f.form() == null ? null : containerKeyOrName(f.form()),
                f.fieldGroup() == null ? null : containerKeyOrName(f.fieldGroup()),
                table,
                f.row() == null ? null : f.row().rowKey(),
                f.row() == null ? null : f.row().rowKeySource(),
                f.row() == null ? null : f.row().cellTexts(),
                f.column() == null ? null : emptyToNull(f.column().headerText()),
                f.dialog() == null ? null : containerKeyOrName(f.dialog()),
                f.region() == null ? null : containerKeyOrName(f.region()),
                emptyToNull(f.containerHeading()),
                f.contextualTexts(),
                scope);
    }

    private static String containerKey(NodeRef r) {
        if (r.idAttr() != null && !r.idAttr().isBlank()) {
            return "#" + r.idAttr();
        }
        String name = r.keyAttributes().get("name");
        if (name != null && !name.isBlank()) {
            return "[name=" + name + "]";
        }
        return null;
    }

    private static String containerKeyOrName(NodeRef r) {
        String k = containerKey(r);
        if (k != null) {
            return k;
        }
        return r.name().isBlank() ? null : "name:" + r.name();
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
