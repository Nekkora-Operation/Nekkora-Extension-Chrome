package com.nekkora.extension.core.mutation;

import com.nekkora.extension.core.snapshot.FrameInfo;
import com.nekkora.extension.core.snapshot.ShadowRootInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Fontes de captura. Cada documento é capturado por UMA fonte (evita inventário duplicado):
 * <ul>
 *   <li>{@code top} — content script do documento principal (inclui iframes de mesma origem e shadow roots abertos);
 *       ids de nó {@code n123}, frames {@code f0, f1…} e marcadores de frames inacessíveis {@code x<hostId>};</li>
 *   <li>{@code x<id>} — content script de um iframe de outra origem PERMITIDA, identificado pelo frameId do Chrome;
 *       ids {@code x<id>n123} e frames {@code c<id>…}.</li>
 * </ul>
 */
public final class Sources {

    public static final String TOP = "top";

    private Sources() {
    }

    /** Fonte que produziu o nó (pelo prefixo do id temporário). */
    public static String sourceOf(String nodeId) {
        if (nodeId != null && nodeId.startsWith("x")) {
            int n = nodeId.indexOf('n');
            return n > 0 ? nodeId.substring(0, n) : TOP;
        }
        return TOP;
    }

    public static boolean sameSource(String a, String b) {
        return sourceOf(a).equals(sourceOf(b));
    }

    /** Fonte dona de uma entrada de frame (pelo prefixo do frameId). */
    public static String frameOwner(String frameId) {
        if (frameId != null && frameId.startsWith("c")) {
            int i = 1;
            while (i < frameId.length() && Character.isDigit(frameId.charAt(i))) {
                i++;
            }
            return "x" + frameId.substring(1, i);
        }
        return TOP;
    }

    /** Substitui as entradas pertencentes à fonte do delta; preserva as das demais fontes. */
    public static List<FrameInfo> mergeFrames(List<FrameInfo> base, List<FrameInfo> fromSource, String source) {
        List<FrameInfo> out = new ArrayList<>();
        for (FrameInfo f : base) {
            if (!frameOwner(f.frameId()).equals(source)) {
                out.add(f);
            }
        }
        out.addAll(fromSource);
        return dropPlaceholdersOfAttached(out);
    }

    /** Remove marcadores "inacessível" de hosts cujo documento foi anexado por outra fonte. */
    public static List<FrameInfo> dropPlaceholdersOfAttached(List<FrameInfo> frames) {
        Set<String> attachedHosts = new HashSet<>();
        for (FrameInfo f : frames) {
            if (f.accessible() && f.hostNodeId() != null && !frameOwner(f.frameId()).equals(sourceOf(f.hostNodeId()))) {
                attachedHosts.add(f.hostNodeId());
            }
        }
        List<FrameInfo> out = new ArrayList<>();
        for (FrameInfo f : frames) {
            boolean placeholder = !f.accessible() && f.hostNodeId() != null && attachedHosts.contains(f.hostNodeId());
            if (!placeholder) {
                out.add(f);
            }
        }
        return out;
    }

    public static List<ShadowRootInfo> mergeShadowRoots(List<ShadowRootInfo> base, List<ShadowRootInfo> fromSource, String source) {
        List<ShadowRootInfo> out = new ArrayList<>();
        for (ShadowRootInfo s : base) {
            if (!sourceOf(s.hostNodeId()).equals(source)) {
                out.add(s);
            }
        }
        out.addAll(fromSource);
        return out;
    }
}
