/*
 * Contrato de integração com a gravação (futuro Nekkora Studio / Platform). NÃO é um gravador: apenas reage
 * aos eventos de ciclo de vida de uma gravação, hoje simulados pelo harness de testes.
 *
 *   recording.session.started   { type, sessionId, tabId }                 → sessão de captura garantida (consumidor)
 *   recording.session.finished  { type, sessionId, tabId, reason }         → sincroniza, consolida, valida, persiste
 *   recording.session.cancelled { type, sessionId, tabId, reason }         → libera sem exportar
 *
 * Idempotente por gravação (sessionId da gravação = operationId da exportação): finalizações repetidas ou
 * simultâneas devolvem o MESMO resultado. "Exportar" = persistir o PageMap consolidado no diretório do motor
 * (escrita temporária + movimento atômico, SHA-256); não é um download.
 */
import { host, broadcast, errorInfo, HostError } from './runtime.js';
import * as capture from './capture-session.js';

const RESULT_LIMIT = 50;
const recordings = new Map(); // recordingId -> { tabId, status, startedAt, result, finishing }

function consumerOf(id) {
  return 'recording:' + id;
}

function remember(id, r) {
  recordings.set(id, r);
  if (recordings.size > RESULT_LIMIT) {
    recordings.delete(recordings.keys().next().value);
  }
}

function failed(recordingId, tabId, code, message, extra = {}) {
  return {
    recordingId, sessionId: extra.sessionId || null, tabId, status: 'FAILED', schemaVersion: null, captureId: null, elementCount: 0, path: null,
    complete: false, coverage: null, syncState: extra.syncState || 'UNKNOWN', validation: null, errors: [{ code, message }], sha256: null,
    duplicate: false, finishedAt: new Date().toISOString(),
  };
}

export async function started(evt) {
  if (!evt || !evt.sessionId || evt.tabId == null) {
    throw new HostError('INVALID_EVENT', 'recording.session.started exige sessionId e tabId');
  }
  const prev = recordings.get(evt.sessionId);
  if (prev && prev.status === 'RECORDING') {
    return { recordingId: evt.sessionId, status: 'RECORDING', duplicate: true, capture: capture.describe(capture.get(prev.tabId)) };
  }
  if (prev) {
    throw new HostError('RECORDING_ALREADY_ENDED', 'gravação ' + evt.sessionId + ' já terminou (' + prev.status + ')');
  }
  const rec = await capture.acquire(evt.tabId, consumerOf(evt.sessionId));
  remember(evt.sessionId, { tabId: evt.tabId, status: 'RECORDING', startedAt: new Date().toISOString(), captureSessionId: rec.sessionId, result: null, finishing: null });
  broadcast('recording', { tabId: evt.tabId, recordingId: evt.sessionId, status: 'RECORDING' });
  return { recordingId: evt.sessionId, status: 'RECORDING', capture: capture.describe(rec) };
}

export async function finished(evt) {
  const id = evt && evt.sessionId;
  const r = id ? recordings.get(id) : null;
  if (!r) {
    return failed(id || null, evt && evt.tabId, 'RECORDING_UNKNOWN', 'gravação inexistente: ' + id);
  }
  if (r.result) {
    return { ...r.result, duplicate: true };
  }
  if (r.finishing) {
    const res = await r.finishing; // finalização simultânea: aguarda a mesma operação
    return { ...res, duplicate: true };
  }
  if (r.status === 'CANCELLED') {
    return failed(id, r.tabId, 'RECORDING_CANCELLED', 'gravação cancelada não é exportada');
  }
  r.finishing = finish(id, r, evt.reason || 'USER_FINISHED');
  try {
    return await r.finishing;
  } finally {
    r.finishing = null;
  }
}

async function finish(id, r, reason) {
  r.status = 'FINALIZING';
  broadcast('recording', { tabId: r.tabId, recordingId: id, status: 'FINALIZING' });
  const rec = capture.get(r.tabId);
  let result;
  if (!rec || rec.sessionId !== r.captureSessionId) {
    result = failed(id, r.tabId, 'SNAPSHOT_MISSING', 'sessão de captura da gravação não existe mais (aba fechada ou documento descartado)');
  } else {
    try {
      // Barreira: alterações pendentes (inclusive as feitas até este instante) entram antes da consolidação.
      const sync = await capture.synchronize(r.tabId);
      if (rec.status === 'ACTIVE') {
        rec.status = 'FINALIZING';
      }
      const res = await host('export.session', { sessionId: rec.sessionId, operationId: id, reason: 'RECORDING_' + reason, syncState: sync.syncState }, { timeoutMs: 180000 });
      const e = res.export;
      result = {
        recordingId: id, sessionId: rec.sessionId, tabId: r.tabId, status: e.status, schemaVersion: e.schemaVersion, captureId: e.captureId,
        elementCount: e.elementCount, path: e.path, complete: e.complete, coverage: e.coverageLimitations, syncState: sync.syncState,
        validation: e.validation, errors: e.error ? [e.error] : [], sha256: e.sha256, bytes: e.bytes, exportMs: e.exportMs,
        duplicate: !!e.duplicate, finishedAt: new Date().toISOString(),
      };
    } catch (err) {
      const info = errorInfo(err);
      result = failed(id, r.tabId, info.code, info.message, { sessionId: rec.sessionId });
    } finally {
      if (capture.get(r.tabId) === rec && rec.status === 'FINALIZING') {
        rec.status = 'ACTIVE';
      }
    }
    rec.lastExport = { recordingId: id, status: result.status, path: result.path, elementCount: result.elementCount, sha256: result.sha256, at: result.finishedAt };
  }
  if (result.status === 'EXPORTED') {
    r.status = 'EXPORTED';
    r.result = result; // somente o sucesso é memorizado: uma falha pode ser tentada novamente
    await capture.release(r.tabId, consumerOf(id), 'RECORDING_FINISHED').catch(() => {});
  } else {
    r.status = 'RECORDING';
  }
  broadcast('recording', { tabId: r.tabId, recordingId: id, status: result.status, result });
  return result;
}

export async function cancelled(evt) {
  const r = evt && recordings.get(evt.sessionId);
  if (!r) {
    return { recordingId: evt && evt.sessionId, status: 'UNKNOWN' };
  }
  if (r.status === 'EXPORTED' || r.status === 'CANCELLED') {
    return { recordingId: evt.sessionId, status: r.status, duplicate: true };
  }
  r.status = 'CANCELLED';
  await capture.release(r.tabId, consumerOf(evt.sessionId), 'RECORDING_CANCELLED').catch(() => {});
  broadcast('recording', { tabId: r.tabId, recordingId: evt.sessionId, status: 'CANCELLED' });
  return { recordingId: evt.sessionId, status: 'CANCELLED' };
}

export function dispatch(evt) {
  switch (evt && evt.type) {
    case 'recording.session.started':
      return started(evt);
    case 'recording.session.finished':
      return finished(evt);
    case 'recording.session.cancelled':
      return cancelled(evt);
    default:
      throw new HostError('UNKNOWN_EVENT', String(evt && evt.type));
  }
}

export function list() {
  return Array.from(recordings.entries()).map(([id, r]) => ({ recordingId: id, tabId: r.tabId, status: r.status, startedAt: r.startedAt, result: r.result }));
}
