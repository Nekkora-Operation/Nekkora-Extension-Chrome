package com.nekkora.extension.core.mutation;

import com.nekkora.extension.core.snapshot.RawNode;

/** Acesso de teste ao diff de um nó (pacote do comparador). */
public final class SnapshotComparatorAccess {

    private SnapshotComparatorAccess() {
    }

    public static NodeChange diff(RawNode a, RawNode b) {
        return SnapshotComparator.diff(a, b);
    }
}
