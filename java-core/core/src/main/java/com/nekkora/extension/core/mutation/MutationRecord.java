package com.nekkora.extension.core.mutation;

import java.util.List;

/**
 * Registro de alterações relevantes observadas entre duas capturas (completa ou incremental).
 *
 * @param baseCaptureId    captura anterior
 * @param captureId        captura atual
 * @param mode             {@code INCREMENTAL} (delta) ou {@code FULL} (comparação de capturas completas)
 * @param rawMutationCount registros brutos do MutationObserver (0 em comparações completas)
 * @param batchCount       lotes agregados no navegador
 * @param added            nós adicionados (até 50)
 * @param addedCount       total de nós adicionados
 * @param removed          nós removidos (até 50)
 * @param removedCount     total de nós removidos
 * @param changed          alterações de nós existentes (até 50, somente relevantes)
 * @param changedCount     total de nós alterados
 * @param replaced         pares {@code removido→adicionado} que representam o mesmo elemento lógico re-renderizado
 * @param relevant         a alteração afeta elementos interativos, textuais ou estruturais
 * @param relevanceReasons motivos da relevância
 * @param affectedTargets  alvos lógicos cuja última referência foi removida ou alterada
 * @param processingMs     tempo de processamento no motor Java
 */
public record MutationRecord(
        String baseCaptureId,
        String captureId,
        String mode,
        int rawMutationCount,
        int batchCount,
        List<String> added,
        int addedCount,
        List<String> removed,
        int removedCount,
        List<NodeChange> changed,
        int changedCount,
        List<List<String>> replaced,
        boolean relevant,
        List<String> relevanceReasons,
        List<String> affectedTargets,
        double processingMs) {

    public MutationRecord {
        added = List.copyOf(added);
        removed = List.copyOf(removed);
        changed = List.copyOf(changed);
        replaced = replaced.stream().map(List::copyOf).toList();
        relevanceReasons = List.copyOf(relevanceReasons);
        affectedTargets = List.copyOf(affectedTargets);
    }

    public MutationRecord withTargets(List<String> targets, double ms) {
        return new MutationRecord(baseCaptureId, captureId, mode, rawMutationCount, batchCount, added, addedCount, removed,
                removedCount, changed, changedCount, replaced, relevant, relevanceReasons, targets, ms);
    }
}
