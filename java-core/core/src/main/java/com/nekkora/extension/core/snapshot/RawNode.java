package com.nekkora.extension.core.snapshot;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Elemento observado na DOM, como enviado pela extensão.
 *
 * <p>O {@code id} é um identificador TEMPORÁRIO de nó DOM, válido apenas durante a vida do nó na
 * página que o capturou. Ele nunca deve ser usado como identidade lógica de um alvo — para isso
 * existe {@code TargetIdentity}.
 *
 * @param id         identificador temporário do nó (ex.: {@code n42})
 * @param parentId   nó pai na árvore composta (o host para raízes de shadow, o iframe para documentos de frame)
 * @param childIds   filhos em ordem de documento
 * @param tag        nome do elemento em minúsculas
 * @param frameId    frame ao qual o nó pertence ({@code f0} é o documento principal)
 * @param scopeId    raiz de consulta do nó: {@code doc:<frameId>} ou {@code shadow:<hostId>}
 * @param attributes atributos já filtrados/redigidos pela extensão
 * @param ownText    texto dos nós de texto FILHOS DIRETOS (não inclui descendentes)
 * @param state      estados observados
 * @param rect       retângulo em coordenadas do viewport do frame, ou {@code null}
 * @param labelIds   ids temporários de labels associados pela API {@code element.labels}
 * @param scroll     métricas de rolagem quando o elemento é rolável, ou {@code null}
 * @param exposedValue valor do controle SOMENTE quando a política permite (campos de pesquisa); senão {@code null}
 * @param textSegments conteúdo MISTO (CHROME-002, opcional): {@code childIds.size()+1} trechos de texto, o i-ésimo
 *                     antes do i-ésimo filho e o último após o último — preserva a ordem texto/elemento; {@code null}
 *                     quando o elemento não tem texto e filhos intercalados
 */
public record RawNode(
        String id,
        String parentId,
        List<String> childIds,
        String tag,
        String frameId,
        String scopeId,
        Map<String, String> attributes,
        String ownText,
        NodeState state,
        Rect rect,
        List<String> labelIds,
        ScrollMetrics scroll,
        String exposedValue,
        List<String> textSegments) {

    /** Construtor CHROME-001 (sem segmentos de texto). */
    public RawNode(String id, String parentId, List<String> childIds, String tag, String frameId, String scopeId,
                   Map<String, String> attributes, String ownText, NodeState state, Rect rect, List<String> labelIds,
                   ScrollMetrics scroll, String exposedValue) {
        this(id, parentId, childIds, tag, frameId, scopeId, attributes, ownText, state, rect, labelIds, scroll, exposedValue, null);
    }

    public RawNode {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tag, "tag");
        childIds = childIds == null ? List.of() : List.copyOf(childIds);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        labelIds = labelIds == null ? List.of() : List.copyOf(labelIds);
        ownText = ownText == null ? "" : ownText;
        state = state == null ? NodeState.UNKNOWN : state;
        frameId = frameId == null ? "f0" : frameId;
        scopeId = scopeId == null ? "doc:" + frameId : scopeId;
        tag = tag.toLowerCase(java.util.Locale.ROOT);
        textSegments = textSegments == null || textSegments.size() != childIds.size() + 1 ? null : List.copyOf(textSegments);
    }

    public String attr(String name) {
        return attributes.get(name);
    }

    public boolean hasAttr(String name) {
        return attributes.containsKey(name);
    }

    public RawNode withChildIds(List<String> newChildIds) {
        // Filhos alterados (ex.: documento de frame anexado): os segmentos deixam de corresponder e são descartados.
        List<String> segs = newChildIds != null && newChildIds.equals(childIds) ? textSegments : null;
        return new RawNode(id, parentId, newChildIds, tag, frameId, scopeId, attributes, ownText, state, rect, labelIds, scroll, exposedValue, segs);
    }

    public RawNode withParentId(String newParentId) {
        return new RawNode(id, newParentId, childIds, tag, frameId, scopeId, attributes, ownText, state, rect, labelIds, scroll, exposedValue, textSegments);
    }
}
