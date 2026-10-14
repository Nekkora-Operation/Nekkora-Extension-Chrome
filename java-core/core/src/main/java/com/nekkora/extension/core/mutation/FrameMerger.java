package com.nekkora.extension.core.mutation;

import com.nekkora.extension.core.snapshot.FrameInfo;
import com.nekkora.extension.core.snapshot.RawNode;
import com.nekkora.extension.core.snapshot.RawSnapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Anexa ao snapshot o documento de um iframe capturado pelo content script do PRÓPRIO frame (origem
 * local permitida, mas diferente da página principal). O host iframe é localizado pelo handshake
 * feito no navegador; aqui apenas a estrutura é recomposta — uma nova anexação da mesma fonte
 * substitui a anterior (documento do iframe recarregado/substituído).
 */
public final class FrameMerger {

    /**
     * @param base         snapshot atual (já contém o nó iframe hospedeiro)
     * @param hostNodeId   nó {@code iframe} hospedeiro
     * @param child        snapshot do documento do frame (ids prefixados pela fonte)
     * @param childSource  fonte do frame ({@code x<frameId>})
     * @param captureId    id da captura resultante
     */
    public RawSnapshot attach(RawSnapshot base, String hostNodeId, RawSnapshot child, String childSource, String captureId) {
        Map<String, RawNode> nodes = new LinkedHashMap<>();
        for (RawNode n : base.nodes()) {
            if (!Sources.sourceOf(n.id()).equals(childSource)) {
                nodes.put(n.id(), n);
            }
        }
        RawNode host = nodes.get(hostNodeId);
        if (host == null) {
            throw new IllegalArgumentException("Host do frame não existe na captura atual: " + hostNodeId);
        }
        if (child.nodes().isEmpty()) {
            throw new IllegalArgumentException("Snapshot do frame vazio");
        }
        RawNode root = child.nodes().get(0);
        List<String> hostChildren = new ArrayList<>();
        for (String c : host.childIds()) {
            if (nodes.containsKey(c)) {
                hostChildren.add(c);
            }
        }
        hostChildren.add(root.id());
        nodes.put(host.id(), host.withChildIds(hostChildren));
        for (RawNode n : child.nodes()) {
            nodes.put(n.id(), n.id().equals(root.id()) ? n.withParentId(host.id()) : n);
        }

        List<FrameInfo> frames = new ArrayList<>();
        for (FrameInfo f : base.frames()) {
            if (!Sources.frameOwner(f.frameId()).equals(childSource)) {
                frames.add(f);
            }
        }
        String hostFrame = host.frameId();
        for (FrameInfo f : child.frames()) {
            boolean childRoot = f.parentFrameId() == null;
            frames.add(childRoot
                    ? new FrameInfo(f.frameId(), hostFrame, hostNodeId, f.url(), true, null,
                    f.title() != null ? f.title() : (child.document() == null ? null : child.document().title()),
                    f.readyState() != null ? f.readyState() : (child.document() == null ? null : child.document().readyState()))
                    : f);
        }
        List<com.nekkora.extension.core.snapshot.ShadowRootInfo> shadows =
                Sources.mergeShadowRoots(base.shadowRoots(), child.shadowRoots(), childSource);
        return new RawSnapshot(captureId, child.capturedAt(), base.document(), Sources.dropPlaceholdersOfAttached(frames),
                shadows, new ArrayList<>(nodes.values()), base.requests(), base.stats(),
                Sources.mergeResources(base.resources(), child.resources(), childSource));
    }
}
