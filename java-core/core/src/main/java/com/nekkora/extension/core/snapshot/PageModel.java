package com.nekkora.extension.core.snapshot;

import com.nekkora.extension.core.text.TextNormalizer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Visão indexada e imutável de um {@link RawSnapshot}.
 *
 * <p>Concentra as consultas estruturais (pai, filhos, ancestrais, escopo, índices por id/tag/atributo)
 * para que classificador, grafo de contexto, localizadores e resolvedor não percorram a lista crua.
 */
public final class PageModel {

    private final RawSnapshot snapshot;
    private final Map<String, RawNode> byId;
    private final Map<String, Integer> order;
    private final Map<String, Integer> subtreeEnd;
    private final Map<String, List<String>> idAttrIndex;     // scope|id -> nodes
    private final Map<String, List<String>> tagIndex;        // tag -> nodes
    private final Map<String, List<String>> nodesByScope;    // scope -> nodes
    private final Map<String, String> textCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, String> visibleTextCache = new java.util.concurrent.ConcurrentHashMap<>();

    private PageModel(RawSnapshot snapshot) {
        this.snapshot = snapshot;
        int n = snapshot.nodes().size();
        this.byId = new LinkedHashMap<>(n * 2);
        this.order = new HashMap<>(n * 2);
        this.idAttrIndex = new HashMap<>();
        this.tagIndex = new HashMap<>();
        this.nodesByScope = new HashMap<>();
        for (RawNode node : snapshot.nodes()) {
            byId.put(node.id(), node);
        }
        this.subtreeEnd = new HashMap<>(n * 2);
        computeOrder();
        for (RawNode node : snapshot.nodes()) {
            tagIndex.computeIfAbsent(node.tag(), k -> new ArrayList<>()).add(node.id());
            nodesByScope.computeIfAbsent(node.scopeId(), k -> new ArrayList<>()).add(node.id());
            String idAttr = node.attr("id");
            if (idAttr != null && !idAttr.isEmpty()) {
                idAttrIndex.computeIfAbsent(node.scopeId() + "|" + idAttr, k -> new ArrayList<>()).add(node.id());
            }
        }
    }

    /**
     * Ordem de documento calculada por DFS a partir das raízes (nós sem pai presente), seguindo
     * {@code childIds}. Não depende da ordem da lista recebida, o que mantém a ordem correta após
     * aplicação de deltas.
     */
    private void computeOrder() {
        int[] counter = {0};
        for (RawNode node : snapshot.nodes()) {
            if (node.parentId() == null || !byId.containsKey(node.parentId())) {
                java.util.ArrayDeque<Object[]> stack = new java.util.ArrayDeque<>();
                stack.push(new Object[]{node, 0});
                order.put(node.id(), counter[0]++);
                while (!stack.isEmpty()) {
                    Object[] frame = stack.peek();
                    RawNode cur = (RawNode) frame[0];
                    int idx = (int) frame[1];
                    if (idx < cur.childIds().size()) {
                        frame[1] = idx + 1;
                        RawNode child = byId.get(cur.childIds().get(idx));
                        if (child != null && !order.containsKey(child.id())) {
                            order.put(child.id(), counter[0]++);
                            stack.push(new Object[]{child, 0});
                        }
                    } else {
                        subtreeEnd.put(cur.id(), counter[0] - 1);
                        stack.pop();
                    }
                }
            }
        }
        // Nós órfãos/inalcançáveis (não deveria ocorrer) recebem ordem ao final.
        for (RawNode node : snapshot.nodes()) {
            if (!order.containsKey(node.id())) {
                order.put(node.id(), counter[0]);
                subtreeEnd.put(node.id(), counter[0]++);
            }
        }
    }

    /** Quantidade de descendentes (árvore composta). */
    public int subtreeSize(RawNode node) {
        Integer end = subtreeEnd.get(node.id());
        return end == null ? 0 : Math.max(0, end - orderOf(node.id()));
    }

    /** Quantidade de elementos com a tag informada. */
    public int countByTag(String tag) {
        return tagIndex.getOrDefault(tag, List.of()).size();
    }

    /** {@code true} quando {@code node} está na subárvore (composta) de {@code ancestor}, excluindo o próprio. */
    public boolean inSubtree(RawNode ancestor, RawNode node) {
        int a = orderOf(ancestor.id());
        int d = orderOf(node.id());
        Integer end = subtreeEnd.get(ancestor.id());
        return end != null && d > a && d <= end;
    }

    public static PageModel of(RawSnapshot snapshot) {
        return new PageModel(snapshot);
    }

    public RawSnapshot snapshot() {
        return snapshot;
    }

    public int size() {
        return byId.size();
    }

    public Iterable<RawNode> nodes() {
        return Collections.unmodifiableCollection(byId.values());
    }

    public Optional<RawNode> find(String nodeId) {
        return Optional.ofNullable(nodeId == null ? null : byId.get(nodeId));
    }

    public RawNode node(String nodeId) {
        RawNode n = byId.get(nodeId);
        if (n == null) {
            throw new IllegalArgumentException("Nó inexistente no snapshot: " + nodeId);
        }
        return n;
    }

    public boolean contains(String nodeId) {
        return byId.containsKey(nodeId);
    }

    /** Posição em ordem de documento (pré-ordem). */
    public int orderOf(String nodeId) {
        Integer o = order.get(nodeId);
        return o == null ? Integer.MAX_VALUE : o;
    }

    public Optional<RawNode> parent(RawNode node) {
        return find(node.parentId());
    }

    public List<RawNode> children(RawNode node) {
        List<RawNode> out = new ArrayList<>(node.childIds().size());
        for (String c : node.childIds()) {
            RawNode child = byId.get(c);
            if (child != null) {
                out.add(child);
            }
        }
        return out;
    }

    /** Ancestrais na árvore composta, do pai até a raiz (atravessa shadow hosts e iframes). */
    public List<RawNode> ancestors(RawNode node) {
        List<RawNode> out = new ArrayList<>();
        RawNode cur = byId.get(node.parentId());
        int guard = 0;
        while (cur != null && guard++ < 10_000) {
            out.add(cur);
            cur = byId.get(cur.parentId());
        }
        return out;
    }

    /** Ancestrais dentro do mesmo escopo de consulta (não atravessa shadow root nem iframe). */
    public List<RawNode> ancestorsInScope(RawNode node) {
        List<RawNode> out = new ArrayList<>();
        for (RawNode a : ancestors(node)) {
            if (!a.scopeId().equals(node.scopeId())) {
                break;
            }
            out.add(a);
        }
        return out;
    }

    public boolean isAncestor(RawNode ancestor, RawNode node) {
        return inSubtree(ancestor, node);
    }

    /** Nós em ordem de documento. */
    public List<RawNode> inDocumentOrder() {
        List<RawNode> list = new ArrayList<>(byId.values());
        list.sort(java.util.Comparator.comparingInt(n -> orderOf(n.id())));
        return list;
    }

    /** Descendentes em ordem de documento (árvore composta). */
    public List<RawNode> descendants(RawNode node) {
        List<RawNode> out = new ArrayList<>();
        collect(node, out);
        return out;
    }

    private void collect(RawNode node, List<RawNode> out) {
        for (String c : node.childIds()) {
            RawNode child = byId.get(c);
            if (child != null) {
                out.add(child);
                collect(child, out);
            }
        }
    }

    public List<RawNode> siblings(RawNode node) {
        return parent(node).map(p -> {
            List<RawNode> s = new ArrayList<>();
            for (RawNode c : children(p)) {
                if (!c.id().equals(node.id())) {
                    s.add(c);
                }
            }
            return s;
        }).orElse(List.of());
    }

    public List<RawNode> byIdAttribute(String scopeId, String idValue) {
        List<String> ids = idAttrIndex.getOrDefault(scopeId + "|" + idValue, List.of());
        return ids.stream().map(byId::get).toList();
    }

    public List<RawNode> byTag(String tag) {
        return tagIndex.getOrDefault(tag, List.of()).stream().map(byId::get).toList();
    }

    public List<RawNode> inScope(String scopeId) {
        return nodesByScope.getOrDefault(scopeId, List.of()).stream().map(byId::get).toList();
    }

    public List<String> scopes() {
        return List.copyOf(nodesByScope.keySet());
    }

    /** Elemento referenciado por id dentro do mesmo escopo (IDREF). Devolve vazio se ausente OU ambíguo. */
    public Optional<RawNode> resolveIdRef(RawNode from, String idValue) {
        List<RawNode> found = byIdAttribute(from.scopeId(), idValue);
        return found.size() == 1 ? Optional.of(found.get(0)) : Optional.empty();
    }

    /**
     * Alvos de uma relação declarada ({@code for}, {@code aria-labelledby}, {@code aria-controls}…), um grupo por
     * referência. CHROME-SECURITY-001: quando a página resolveu a relação ({@code refs}, ids temporários de nó), ela é
     * usada — o valor do atributo não atravessa; senão (captura antiga ou teste), o valor do atributo é resolvido por id.
     * Um grupo com mais de um nó é uma referência ambígua (id duplicado); vazio, ausente.
     */
    public List<List<RawNode>> refTargets(RawNode from, String attribute) {
        List<String> ids = from.refs().get(attribute);
        List<List<RawNode>> out = new ArrayList<>();
        if (ids != null) {
            for (String id : ids) {
                find(id).ifPresent(n -> out.add(List.of(n)));
            }
            return out;
        }
        String v = from.attr(attribute);
        if (v == null || v.isBlank()) {
            return out;
        }
        for (String ref : v.trim().split("\\s+")) {
            out.add(resolveIdRefAll(from, ref));
        }
        return out;
    }

    /** Descrição da referência para evidências: o id temporário do nó (nunca o valor do atributo da página). */
    public static String refLabel(RawNode from, String attribute, List<RawNode> group, int index) {
        if (from.refs().containsKey(attribute) || group.isEmpty()) {
            return attribute + (group.isEmpty() ? " (não resolvido)" : " → " + group.get(0).id());
        }
        String v = from.attr(attribute);
        String[] parts = v == null ? new String[0] : v.trim().split("\\s+");
        return attribute + "=" + (index < parts.length ? parts[index] : "?");
    }

    /** Todas as ocorrências de um IDREF (permite detectar referências ambíguas por id duplicado). */
    public List<RawNode> resolveIdRefAll(RawNode from, String idValue) {
        return byIdAttribute(from.scopeId(), idValue);
    }

    public boolean isHidden(RawNode node) {
        return NodeState.isFalse(node.state().visible());
    }

    /** Texto do nó e de todos os descendentes (normalizado). Equivale a textContent. */
    public String textContent(RawNode node) {
        return textCache.computeIfAbsent(node.id(), k -> TextNormalizer.normalize(gather(node, false)));
    }

    /** Texto renderizado: ignora descendentes ocultos. Usado no nome acessível por conteúdo. */
    public String visibleText(RawNode node) {
        return visibleTextCache.computeIfAbsent(node.id(), k -> TextNormalizer.normalize(gather(node, true)));
    }

    private String gather(RawNode node, boolean visibleOnly) {
        StringBuilder sb = new StringBuilder();
        appendText(node, visibleOnly, sb, 0);
        return sb.toString();
    }

    /**
     * {@code visibleOnly=false} espelha {@code textContent}/XPath {@code normalize-space(.)}: só o mesmo
     * escopo (XPath não atravessa shadow root) e inclui opções de select.
     * {@code visibleOnly=true} aproxima o texto renderizado: ignora ocultos e selects, inclui shadow aberto.
     */
    private void appendText(RawNode node, boolean visibleOnly, StringBuilder sb, int depth) {
        if (depth > 512 || (visibleOnly && isHidden(node))) {
            return;
        }
        if (isNonTextual(node.tag()) || (visibleOnly && node.tag().equals("select"))) {
            return;
        }
        if (!node.ownText().isEmpty()) {
            sb.append(' ').append(node.ownText());
        }
        for (String c : node.childIds()) {
            RawNode child = byId.get(c);
            if (child == null) {
                continue;
            }
            if (child.scopeId().equals(node.scopeId())) {
                appendText(child, visibleOnly, sb, depth + 1);
            } else if (visibleOnly && child.scopeId().startsWith("shadow:")) {
                // Conteúdo de shadow root aberto compõe o texto renderizado do host.
                appendText(child, visibleOnly, sb, depth + 1);
            }
        }
    }

    private static boolean isNonTextual(String tag) {
        return switch (tag) {
            case "script", "style", "noscript", "template", "iframe", "frame", "svg" -> true;
            default -> false;
        };
    }
}
