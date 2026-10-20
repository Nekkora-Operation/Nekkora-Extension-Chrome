package com.nekkora.extension.core.resolve;

import com.nekkora.extension.core.context.ContextGraph;
import com.nekkora.extension.core.identity.NodeAnchor;
import com.nekkora.extension.core.snapshot.RawNode;

import java.util.ArrayList;
import java.util.List;

/**
 * CHROME-SECURITY-001 (decisão D7): CONTINUIDADE TÉCNICA do objeto DOM, separada da resolução lógica.
 *
 * <p>{@code LIVE_NODE_CONTINUITY} significa: o mesmo objeto DOM continua identificável na mesma sessão documental (mesma
 * época do registro de nós, mesmo frame e escopo, mesmo tipo). <b>Não</b> significa que a identidade lógica foi
 * revalidada: o mesmo nó pode ter sido reaproveitado para outro registro (virtualização). Por isso a continuidade nunca
 * preenche {@code resolvedNodeId}, nunca gera localizador persistente e nunca autoriza ação automática — ela serve para
 * reconhecer o nó físico, consultar seu estado estrutural atual, destacá-lo e acompanhar mudanças.
 *
 * @param status      {@code LIVE_NODE_CONTINUITY}, {@code CONTINUITY_BROKEN} ou {@code NOT_PROVEN}
 * @param nodeId      o nó (só com {@code LIVE_NODE_CONTINUITY})
 * @param proof       o que foi comprovado
 * @param limitations o que invalida ou limita (ex.: {@code CONTENT_CHANGED}, {@code REGISTRY_EPOCH_CHANGED})
 */
public record TechnicalContinuity(String status, String nodeId, List<String> proof, List<String> limitations) {

    public static final String LIVE = "LIVE_NODE_CONTINUITY";
    public static final String BROKEN = "CONTINUITY_BROKEN";
    public static final String NOT_PROVEN = "NOT_PROVEN";
    /** Lembrete fixo de toda continuidade viva: ela não é identidade lógica. */
    public static final String NOT_LOGICAL = "LOGICAL_IDENTITY_NOT_REVALIDATED";

    public TechnicalContinuity {
        proof = proof == null ? List.of() : List.copyOf(proof);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
    }

    public boolean live() {
        return LIVE.equals(status);
    }

    public static TechnicalContinuity evaluate(NodeAnchor a, ContextGraph graph) {
        if (a == null) {
            return new TechnicalContinuity(NOT_PROVEN, null, List.of(), List.of("NO_ANCHOR: alvo registrado sem âncora técnica"));
        }
        if (a.registryEpoch() != null && !NodeAnchor.hasFrame(graph.model(), a.frameId())) {
            return broken("FRAME_GONE: o documento do frame ancorado foi substituído ou removido");
        }
        String epoch = NodeAnchor.epochOf(graph.model(), a.frameId());
        if (a.registryEpoch() == null || epoch == null) {
            return new TechnicalContinuity(NOT_PROVEN, null, List.of(),
                    List.of("EPOCH_UNAVAILABLE: o produtor não informa a época do registro de nós; o id sozinho não prova o mesmo objeto"));
        }
        if (!epoch.equals(a.registryEpoch())) {
            return broken("REGISTRY_EPOCH_CHANGED: documento ou frame recarregado, ou registro de nós reinicializado");
        }
        RawNode n = graph.model().find(a.nodeId()).orElse(null);
        if (n == null) {
            return broken("NODE_GONE: o objeto DOM ancorado não existe mais");
        }
        if (!a.frameId().equals(n.frameId())) {
            return broken("FRAME_CHANGED");
        }
        if (!a.scopeId().equals(n.scopeId())) {
            return broken("SCOPE_CHANGED");
        }
        if (!a.tag().equals(n.tag()) || !a.compatibility().equals(graph.semantics().get(n.id()).kind().compatibilityFamily(n.tag()))) {
            return broken("TYPE_CHANGED: o tipo ou a finalidade do elemento mudou");
        }
        List<String> proof = List.of("mesma época do registro de nós (" + a.registryEpoch() + ")", "mesmo frame e escopo", "mesmo tipo e tag");
        List<String> limits = new ArrayList<>();
        limits.add(NOT_LOGICAL);
        if (NodeAnchor.revision(graph.model(), n) != a.contentRevision()) {
            limits.add("CONTENT_CHANGED: o conteúdo do nó mudou desde o registro");
        }
        RawNode row = NodeAnchor.rowOf(graph.model(), n);
        String rowId = row == null ? null : row.id();
        if (!java.util.Objects.equals(rowId, a.rowNodeId())) {
            limits.add("ROW_CHANGED: o nó está em outra linha/item");
        } else if (row != null && NodeAnchor.revision(graph.model(), row) != a.rowRevision()) {
            limits.add("ROW_CONTENT_CHANGED: a linha/item foi reaproveitada ou alterada (virtualização?)");
        }
        return new TechnicalContinuity(LIVE, n.id(), proof, limits);
    }

    private static TechnicalContinuity broken(String reason) {
        return new TechnicalContinuity(BROKEN, null, List.of(), List.of(reason));
    }
}
