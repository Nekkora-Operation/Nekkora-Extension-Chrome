package com.nekkora.extension.core.frames;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Grafo documental (CHROME-003): documentos, frames e suas relações.
 *
 * <p>Relações: {@code DOCUMENT_CONTAINS_FRAME}, {@code FRAME_HAS_DOCUMENT}, {@code FRAME_PARENT_OF},
 * {@code FRAME_CHILD_OF}, {@code FRAME_HOSTED_BY_ELEMENT}, {@code SHADOW_ROOT_BELONGS_TO_DOCUMENT}. A relação
 * elemento → documento é representada por {@code ElementDescriptor.documentId} (não por uma aresta por elemento).
 *
 * @param mainDocumentId  documento principal
 * @param documents       documentos
 * @param frames          frames (inclui o documento principal como {@code f0}, profundidade 0)
 * @param relations       relações
 * @param maxDepth        maior profundidade de aninhamento
 * @param counts          contagens ({@code documents}, {@code frames}, {@code inspected}, {@code inaccessible},
 *                        {@code loading}, {@code shadowRootsOpen}, {@code shadowRootsClosed})
 * @param limitations     limites declarados da observação
 */
public record FrameGraph(String mainDocumentId, List<DocumentDescriptor> documents, List<FrameDescriptor> frames,
                         List<Relation> relations, int maxDepth, Map<String, Integer> counts, List<String> limitations) {

    /** Relação tipada entre documentos, frames, elementos e shadow roots. */
    public record Relation(String type, String from, String to) {
    }

    public FrameGraph {
        documents = List.copyOf(documents);
        frames = List.copyOf(frames);
        relations = List.copyOf(relations);
        counts = Collections.unmodifiableMap(new LinkedHashMap<>(counts));
        limitations = List.copyOf(limitations);
    }

    public Optional<FrameDescriptor> frame(String frameId) {
        for (FrameDescriptor f : frames) {
            if (f.frameId().equals(frameId)) {
                return Optional.of(f);
            }
        }
        return Optional.empty();
    }

    public Optional<FrameDescriptor> frameHostedBy(String hostNodeId) {
        for (FrameDescriptor f : frames) {
            if (hostNodeId != null && hostNodeId.equals(f.hostNodeId())) {
                return Optional.of(f);
            }
        }
        return Optional.empty();
    }

    public Optional<DocumentDescriptor> document(String documentId) {
        for (DocumentDescriptor d : documents) {
            if (d.documentId().equals(documentId)) {
                return Optional.of(d);
            }
        }
        return Optional.empty();
    }

    /** Cadeia de frames do documento principal até o frame indicado (inclusive). */
    public List<FrameDescriptor> chain(String frameId) {
        List<FrameDescriptor> out = new ArrayList<>();
        String current = frameId;
        int guard = 0;
        while (current != null && guard++ < 64) {
            Optional<FrameDescriptor> f = frame(current);
            if (f.isEmpty()) {
                break;
            }
            out.add(0, f.get());
            current = f.get().parentFrameId();
        }
        return out;
    }
}
