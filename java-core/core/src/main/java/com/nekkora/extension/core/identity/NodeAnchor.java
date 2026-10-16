package com.nekkora.extension.core.identity;

import com.nekkora.extension.core.context.ContextGraph;
import com.nekkora.extension.core.snapshot.FrameInfo;
import com.nekkora.extension.core.snapshot.PageModel;
import com.nekkora.extension.core.snapshot.RawNode;

/**
 * CHROME-SECURITY-001 (decisão D7): âncora TÉCNICA de um alvo — o objeto DOM observado, não a identidade lógica.
 *
 * <p>O id de nó é temporário: vale enquanto o mesmo registro de nós do content script existir. Por isso a âncora guarda a
 * <b>época</b> desse registro ({@code registryEpoch}, token aleatório renovado a cada documento, recarga de frame e
 * reinicialização do registro): sem a mesma época, o mesmo id NÃO prova o mesmo objeto. Guarda também frame, escopo, tag e
 * família de compatibilidade (mudança de tipo/finalidade quebra a continuidade) e contadores de revisão de texto do nó e
 * da linha que o contém (virtualização que reaproveita o nó para outro registro aparece como conteúdo alterado). Nenhum
 * texto, valor ou hash de conteúdo da página.
 *
 * @param contentRevision soma das revisões de texto do nó e da subárvore (contadores mantidos na página, sem conteúdo)
 * @param rowNodeId       linha/item que contém o nó (tr, li, role=row/listitem/option/treeitem), ou null
 * @param rowRevision     soma das revisões de texto da subárvore da linha
 */
public record NodeAnchor(String nodeId, String frameId, String scopeId, String registryEpoch, String tag, String compatibility,
                         int contentRevision, String rowNodeId, int rowRevision) {

    public static NodeAnchor of(ContextGraph graph, RawNode node) {
        PageModel m = graph.model();
        RawNode row = rowOf(m, node);
        return new NodeAnchor(node.id(), node.frameId(), node.scopeId(), epochOf(m, node.frameId()), node.tag(),
                graph.semantics().get(node.id()).kind().compatibilityFamily(), revision(m, node), row == null ? null : row.id(),
                row == null ? 0 : revision(m, row));
    }

    /** Época do registro de nós do content script que produziu os nós do frame (null quando o produtor não a informa). */
    public static String epochOf(PageModel m, String frameId) {
        if (m.snapshot().frames() == null) {
            return null;
        }
        for (FrameInfo f : m.snapshot().frames()) {
            if (f.frameId().equals(frameId)) {
                return f.registryEpoch();
            }
        }
        return null;
    }

    /** O frame ainda existe no snapshot? */
    public static boolean hasFrame(PageModel m, String frameId) {
        return m.snapshot().frames() != null && m.snapshot().frames().stream().anyMatch(f -> f.frameId().equals(frameId));
    }

    public static int revision(PageModel m, RawNode n) {
        int sum = n.textRevision() == null ? 0 : n.textRevision();
        for (RawNode d : m.descendants(n)) {
            sum += d.textRevision() == null ? 0 : d.textRevision();
        }
        return sum;
    }

    public static RawNode rowOf(PageModel m, RawNode node) {
        RawNode cur = node;
        for (int i = 0; i < 24 && cur != null; i++) {
            String role = cur.attr("role");
            if (cur.tag().equals("tr") || cur.tag().equals("li") || "row".equals(role) || "listitem".equals(role) || "option".equals(role)
                    || "treeitem".equals(role)) {
                return cur;
            }
            cur = cur.parentId() == null ? null : m.find(cur.parentId()).orElse(null);
        }
        return null;
    }
}
