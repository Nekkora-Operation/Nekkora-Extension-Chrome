package com.nekkora.extension.host.capture;

import com.nekkora.extension.core.analysis.PageAnalysis;
import com.nekkora.extension.core.identity.TargetIdentity;
import com.nekkora.extension.core.pagemap.PageMap;
import com.nekkora.extension.core.pagemap.PageMapOptions;
import com.nekkora.extension.host.json.JsonCodec;
import com.nekkora.extension.host.json.PageMapSchema;
import com.nekkora.extension.host.json.StreamingPageMapWriter;
import com.nekkora.extension.host.persistence.DataDirectory;
import com.nekkora.extension.host.session.HostSession;
import com.nekkora.extension.host.session.TabState;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Exportação consolidada do PageMap de uma sessão.
 *
 * <ul>
 *   <li><b>Idempotente</b> por (sessão, operação): a repetição devolve o resultado já confirmado ({@code duplicate=true}).</li>
 *   <li><b>Serializada</b> por sessão: deltas e outras finalizações aguardam (o conjunto exportado não muda durante a escrita).</li>
 *   <li><b>Atômica</b>: escrita em arquivo temporário, checksum SHA-256, validação contra o schema e só então
 *       substituição atômica. Falhas não são memorizadas (permitem nova tentativa) e nunca deixam arquivo parcial.</li>
 * </ul>
 */
public final class ExportService {

    private static final java.time.format.DateTimeFormatter PARTIAL_STAMP =
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS").withZone(java.time.ZoneOffset.UTC);
    private static final java.util.concurrent.atomic.AtomicLong PARTIALS = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.function.Function<TabState, com.nekkora.extension.core.pagemap.PageMapAssembler.DiagnosticsInput> diagnostics;
    private static final Logger LOG = Logger.getLogger(ExportService.class.getName());
    private static final Pattern SAFE = Pattern.compile("[^A-Za-z0-9._-]");

    private final HostSession session;
    private final DataDirectory dataDir;
    private final StreamingPageMapWriter writer;

    public ExportService(HostSession session, DataDirectory dataDir, JsonCodec json) {
        this(session, dataDir, json, tab -> com.nekkora.extension.core.pagemap.PageMapAssembler.DiagnosticsInput.none());
    }

    /** @param diagnostics entradas do diagnóstico documental/proteções por aba (CHROME-003) */
    public ExportService(HostSession session, DataDirectory dataDir, JsonCodec json,
                         java.util.function.Function<TabState, com.nekkora.extension.core.pagemap.PageMapAssembler.DiagnosticsInput> diagnostics) {
        this.diagnostics = diagnostics;
        this.session = session;
        this.dataDir = dataDir;
        PageMapSchema schema;
        try {
            schema = new PageMapSchema();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Schema do PageMap indisponível: exportações não serão validadas", e);
            schema = null;
        }
        this.writer = new StreamingPageMapWriter(json.mapper(), schema);
    }

    /** Exportação FINAL (finalização normal). */
    public ExportResult export(HostCaptureSession s, String operationId, String reason, String syncState, int parallelism) {
        return export(s, operationId, reason, syncState, parallelism, false);
    }

    /**
     * @param diagnostic {@code false} (FINAL): exige estado {@code SYNCHRONIZED}; somente um resultado
     *                   {@code EXPORTED} completo é memorizado para a operação. {@code true} (DIAGNOSTIC): aceita
     *                   estado não confirmado e produz um arquivo {@code PARTIAL} distinto, nunca memorizado.
     */
    public ExportResult export(HostCaptureSession s, String operationId, String reason, String syncState, int parallelism, boolean diagnostic) {
        synchronized (s) {
            ExportResult previous = s.export(operationId);
            if (previous != null) {
                return previous.asDuplicate();
            }
            if (!diagnostic && !"SYNCHRONIZED".equals(syncState)) {
                return failed(s, operationId, reason, syncState, "SYNC_NOT_CONFIRMED",
                        "Finalização exige sincronização confirmada de todas as fontes (estado: " + syncState + ")", System.nanoTime());
            }
            HostCaptureSession.Status before = s.status();
            s.status(HostCaptureSession.Status.FINALIZING);
            long t0 = System.nanoTime();
            try {
                ExportResult r = doExport(s, operationId, reason, syncState, parallelism, t0, diagnostic);
                if ("EXPORTED".equals(r.status()) && r.complete()) {
                    s.exported(operationId, r); // somente o resultado final completo é memorizado
                }
                return r;
            } finally {
                s.status(before == HostCaptureSession.Status.STOPPED ? before : HostCaptureSession.Status.ACTIVE);
            }
        }
    }

    private ExportResult doExport(HostCaptureSession s, String operationId, String reason, String syncState, int parallelism, long t0,
                                  boolean diagnostic) {
        TabState tab = session.tab(s.tabKey());
        PageAnalysis a = tab.capture(s.latestCaptureId()).orElse(null);
        if (a == null) {
            return failed(s, operationId, reason, syncState, "SNAPSHOT_MISSING", "Sessão sem captura consolidada", t0);
        }
        if (dataDir == null) {
            return failed(s, operationId, reason, syncState, "EXPORT_UNAVAILABLE", "Host sem diretório de dados", t0);
        }
        Path folder;
        Path tmp;
        String base = "session-" + SAFE.matcher(s.sessionId()).replaceAll("_") + "-" + SAFE.matcher(operationId).replaceAll("_");
        try {
            folder = dataDir.dir("pagemaps");
            // O nome final só é decidido DEPOIS de conhecer o resultado (completo × parcial).
            tmp = folder.resolve(base + "." + System.nanoTime() + ".tmp");
        } catch (IOException e) {
            return failed(s, operationId, reason, syncState, "EXPORT_WRITE_FAILED", "Diretório de PageMaps indisponível: " + e.getMessage(), t0);
        }
        try {
            String url = a.model().snapshot().document() == null ? null : a.model().snapshot().document().url();
            List<TargetIdentity> targets = new ArrayList<>();
            for (TargetIdentity t : session.targets().ownedBySession(s.sessionId())) { // isolamento por sessão
                if (url != null && t.origin() != null && url.startsWith(t.origin())) {
                    targets.add(t);
                }
            }
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            StreamingPageMapWriter.Outcome o;
            try (OutputStream out = new DigestOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp), 1 << 16), sha)) {
                o = writer.write(out, a, PageMapOptions.defaults(), tab.optionHistory(), targets, tab.resolutions(),
                        tab.mutations(), tab.evidence(), Instant.now().toString(), "FULL", parallelism,
                        diagnostics.apply(tab));
            }
            ExportResult.Validation validation = new ExportResult.Validation(o.validationErrors().isEmpty(),
                    o.validatedElements(), o.validationErrors());
            if (!validation.valid()) {
                Files.deleteIfExists(tmp);
                return new ExportResult(s.sessionId(), operationId, "FAILED", reason, PageMap.VERSION, o.captureId(), o.elements(),
                        null, -1, null, false, syncState, o.coverageLimitations(), validation, false,
                        new ExportResult.Error("CONTRACT_VIOLATION", String.join("; ", o.validationErrors())), ms(t0), Instant.now().toString());
            }
            long bytes = Files.size(tmp);
            boolean complete = !o.truncated() && "SYNCHRONIZED".equals(syncState) && !diagnostic;
            // Completo: um arquivo por (sessão, operação). Parcial (truncado ou diagnóstico): nome próprio e único —
            // nunca ocupa o caminho do arquivo completo nem é sobrescrito por uma tentativa posterior.
            Path target = complete ? folder.resolve(base + ".json")
                    : folder.resolve(base + "-partial-" + PARTIAL_STAMP.format(Instant.now()) + "-" + PARTIALS.incrementAndGet() + ".json");
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            // Incompleto (truncado ou diagnóstico): PARTIAL — arquivo distinto, nunca memorizado como concluído.
            return new ExportResult(s.sessionId(), operationId, complete ? "EXPORTED" : "PARTIAL", reason, PageMap.VERSION, o.captureId(), o.elements(),
                    target.toString(), bytes, HexFormat.of().formatHex(sha.digest()), complete, syncState,
                    o.coverageLimitations(), validation, false, null, ms(t0), Instant.now().toString());
        } catch (IOException | RuntimeException | NoSuchAlgorithmException e) {
            LOG.log(Level.WARNING, "Exportação falhou para " + s.sessionId(), e);
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // melhor esforço: nunca deixar arquivo parcial
            }
            return failed(s, operationId, reason, syncState, "EXPORT_WRITE_FAILED", e.getClass().getSimpleName() + ": " + e.getMessage(), t0);
        }
    }

    private static ExportResult failed(HostCaptureSession s, String op, String reason, String sync, String code, String msg, long t0) {
        return new ExportResult(s.sessionId(), op, "FAILED", reason, PageMap.VERSION, s.latestCaptureId(), 0, null, -1, null,
                false, sync, List.of(), new ExportResult.Validation(false, 0, List.of()), false,
                new ExportResult.Error(code, msg), ms(t0), Instant.now().toString());
    }

    private static double ms(long t0) {
        return Math.round((System.nanoTime() - t0) / 10_000.0) / 100.0;
    }
}
