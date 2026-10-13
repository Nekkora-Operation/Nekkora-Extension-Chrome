package com.nekkora.extension.host.capture;

import java.util.List;

/**
 * Resultado de uma exportação de PageMap ligada a uma sessão.
 *
 * @param sessionId           sessão de captura
 * @param operationId         operação (chave de idempotência: ex. id da gravação finalizada)
 * @param status              {@code EXPORTED} ou {@code FAILED}
 * @param reason              motivo da exportação ({@code MANUAL_DEV}, {@code RECORDING_FINISHED}...)
 * @param schemaVersion       versão do contrato
 * @param captureId           captura consolidada usada
 * @param elementCount        elementos exportados
 * @param path                arquivo persistido (somente após substituição atômica)
 * @param bytes               tamanho do arquivo
 * @param sha256              checksum do arquivo
 * @param complete            captura não truncada e sincronizada
 * @param syncState           estado de sincronização informado pela extensão no momento da finalização
 * @param coverageLimitations limitações de cobertura declaradas
 * @param validation          resultado da validação contra o JSON Schema
 * @param duplicate           esta resposta é a repetição de uma exportação já confirmada (idempotência)
 * @param error               erro (somente quando {@code FAILED})
 * @param exportMs            duração
 * @param exportedAt          instante
 */
public record ExportResult(
        String sessionId,
        String operationId,
        String status,
        String reason,
        String schemaVersion,
        String captureId,
        int elementCount,
        String path,
        long bytes,
        String sha256,
        boolean complete,
        String syncState,
        List<String> coverageLimitations,
        Validation validation,
        boolean duplicate,
        Error error,
        double exportMs,
        String exportedAt) {

    /** @param valid conformidade; @param validatedElements elementos validados; @param errors primeiras violações */
    public record Validation(boolean valid, int validatedElements, List<String> errors) {
    }

    /** @param code código estável; @param message detalhe */
    public record Error(String code, String message) {
    }

    public ExportResult asDuplicate() {
        return new ExportResult(sessionId, operationId, status, reason, schemaVersion, captureId, elementCount, path, bytes,
                sha256, complete, syncState, coverageLimitations, validation, true, error, exportMs, exportedAt);
    }
}
