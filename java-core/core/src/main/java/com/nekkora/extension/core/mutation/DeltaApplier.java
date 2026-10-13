package com.nekkora.extension.core.mutation;

import com.nekkora.extension.core.snapshot.RawNode;
import com.nekkora.extension.core.snapshot.RawSnapshot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Aplica um {@link SnapshotDelta} a um snapshot base, produzindo um novo snapshot consistente. */
public final class DeltaApplier {

    private static final int MAX_REQUESTS = 500;

    public RawSnapshot apply(RawSnapshot base, SnapshotDelta delta) {
        if (!base.captureId().equals(delta.baseCaptureId())) {
            throw new IllegalArgumentException("Delta " + delta.captureId() + " se aplica a " + delta.baseCaptureId()
                    + ", não a " + base.captureId());
        }
        Map<String, RawNode> nodes = new LinkedHashMap<>();
        for (RawNode n : base.nodes()) {
            nodes.put(n.id(), n);
        }
        for (String root : delta.removed()) {
            removeSubtree(nodes, root);
        }
        for (RawNode n : delta.upserts()) {
            nodes.put(n.id(), n);
        }
        // Descarta nós que deixaram de ser alcançáveis (pai atualizado sem eles).
        Set<String> reachable = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        for (RawNode n : nodes.values()) {
            if (n.parentId() == null || !nodes.containsKey(n.parentId())) {
                stack.push(n.id());
            }
        }
        List<RawNode> ordered = new ArrayList<>(nodes.size());
        // DFS preservando a ordem dos filhos.
        List<String> roots = new ArrayList<>(stack);
        java.util.Collections.reverse(roots);
        for (String r : roots) {
            visit(nodes, r, reachable, ordered);
        }
        List<com.nekkora.extension.core.snapshot.ObservedRequest> requests = new ArrayList<>(base.requests());
        requests.addAll(delta.requests());
        if (requests.size() > MAX_REQUESTS) {
            requests = requests.subList(requests.size() - MAX_REQUESTS, requests.size());
        }
        return new RawSnapshot(delta.captureId(), delta.capturedAt(),
                delta.document() == null ? base.document() : delta.document(),
                delta.frames() == null ? base.frames() : delta.frames(),
                delta.shadowRoots() == null ? base.shadowRoots() : delta.shadowRoots(),
                ordered, requests, delta.stats() == null ? base.stats() : delta.stats());
    }

    private static void visit(Map<String, RawNode> nodes, String rootId, Set<String> reachable, List<RawNode> out) {
        Deque<String> stack = new ArrayDeque<>();
        stack.push(rootId);
        while (!stack.isEmpty()) {
            String id = stack.pop();
            RawNode n = nodes.get(id);
            if (n == null || !reachable.add(id)) {
                continue;
            }
            out.add(n);
            List<String> children = n.childIds();
            for (int i = children.size() - 1; i >= 0; i--) {
                RawNode child = nodes.get(children.get(i));
                if (child != null && id.equals(child.parentId())) {
                    stack.push(child.id());
                }
            }
        }
    }

    private static void removeSubtree(Map<String, RawNode> nodes, String root) {
        Deque<String> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            RawNode n = nodes.remove(stack.pop());
            if (n != null) {
                n.childIds().forEach(stack::push);
            }
        }
    }
}
