/*
 * Contrato de integração com a gravação (futuro Nekkora Studio / Platform). NÃO é um gravador: apenas reage
 * aos eventos de ciclo de vida de uma gravação, hoje simulados pelo harness de testes.
 *
 *   recording.session.started   { type, sessionId, tabId }                 → sessão de captura garantida (consumidor)
 *   recording.session.finished  { type, sessionId, tabId, reason }         → sincroniza, consolida, valida, persiste
 *   recording.session.cancelled { type, sessionId, tabId, reason }         → libera sem exportar
 *
 * Garantias:
 * - eventos de uma mesma gravação são SERIALIZADOS (exclusão mútua por gravação): finalizações repetidas ou
 *   simultâneas devolvem o mesmo resultado; finalização × cancelamento simultâneos têm um único desfecho;
 * - a finalização só é concluída com sincronização CONFIRMADA de todas as fontes e exportação completa; caso
 *   contrário devolve FAILED (SYNC_INCOMPLETE) ou PARTIAL, nada é memorizado e a gravação pode ser finalizada de novo;
 * - o estado mínimo de cada gravação fica em chrome.storage.session e é restaurado após reiniciar o service worker;
 * - a retenção descarta somente registros ENCERRADOS (exportados/cancelados), nunca gravações em andamento.
 *
 * "Exportar" = persistir o PageMap consolidado no diretório do motor (escrita temporária + movimento atômico,
 * SHA-256); não é um download.
 */
import { host, broadcast, errorInfo, withLock, HostError } from './runtime.js';
import * as capture from './capture-session.js';

const ENDED_RETENTION = 50; // registros encerrados mantidos para idempotência
const KEY = 'nk.rec.';
const ENDED = new Set(['EXPORTED', 'CANCELLED']);
const recordings = new Map(); // recordingId -> { tabId, status, startedAt, captureSessionId, result, endedAt }

function consumerOf(id) {
  return 'recording:' + id;
}

function persist(id, r) {
  return chrome.storage.session.set({ [KEY + id]: { tabId: r.tabId, status: r.status, startedAt: r.startedAt,
    captureSessionId: r.captureSessionId, result: r.result, endedAt: r.endedAt || null } }).catch(() => {});
}

function forget(id) {
  recordings.delete(id);
  chrome.storage.session.remove(KEY + id).catch(() => {});
}

/** Mantém no máximo ENDED_RETENTION registros encerrados (os mais antigos saem); os ativos nunca são descartados. */
function enforceRetention() {
  const ended = Array.from(recordings.entries()).filter(([, r]) => ENDED.has(r.status))
    .sort((a, b) => String(a[1].endedAt).localeCompare(String(b[1].endedAt)));
  for (let i = 0; i < ended.length - ENDED_RETENTION; i++) {
    forget(ended[i][0]);
  }
}

async function store(id, r) {
  recordings.set(id, r);
  await persist(id, r);
  enforceRetention();
}

function failed(recordingId, tabId, code, message, extra = {}) {
  return {
    recordingId, sessionId: extra.sessionId || null, tabId, status: 'FAILED', schemaVersion: null, captureId: null, elementCount: 0, path: null,
    complete: false, coverage: null, syncState: extra.syncState || 'UNKNOWN', validation: null,
    errors: [{ code, message, ...(extra.detail ? { detail: extra.detail } : {}) }],
    sha256: null, duplicate: false, finishedAt: new Date().toISOString(),
  };
}

function lock(id, fn) {
  return withLock('rec:' + id, fn);
}

export function started(evt) {
  if (!evt || !evt.sessionId || evt.tabId == null) {
    throw new HostError('INVALID_EVENT', 'recording.session.started exige sessionId e tabId');
  }
  const id = evt.sessionId;
  return lock(id, async () => {
    const prev = recordings.get(id);
    if (prev && !ENDED.has(prev.status)) {
      return { recordingId: id, status: 'RECORDING', duplicate: true, capture: capture.describe(capture.get(prev.tabId)) };
    }
    if (prev) {
      throw new HostError('RECORDING_ALREADY_ENDED', 'gravação ' + id + ' já terminou (' + prev.status + ')');
    }
    const rec = await capture.acquire(evt.tabId, consumerOf(id));
    await store(id, { tabId: evt.tabId, status: 'RECORDING', startedAt: new Date().toISOString(), captureSessionId: rec.sessionId, result: null });
    broadcast('recording', { tabId: evt.tabId, recordingId: id, status: 'RECORDING' });
    return { recordingId: id, status: 'RECORDING', capture: capture.describe(rec) };
  });
}

export function finished(evt) {
  const id = evt && evt.sessionId;
  if (!id) {
    return failed(null, evt && evt.tabId, 'RECORDING_UNKNOWN', 'evento sem sessionId');
  }
  return lock(id, async () => {
    const r = recordings.get(id);
    if (!r) {
      return failed(id, evt.tabId, 'RECORDING_UNKNOWN', 'gravação inexistente: ' + id);
    }
    if (r.status === 'EXPORTED' && r.result) {
      return { ...r.result, duplicate: true }; // finalização repetida/simultânea: mesmo resultado
    }
    if (r.status === 'CANCELLED') {
      return failed(id, r.tabId, 'RECORDING_CANCELLED', 'gravação cancelada não é exportada');
    }
    return finish(id, r, evt.reason || 'USER_FINISHED');
  });
}

async function finish(id, r, reason) {
  r.status = 'FINALIZING';
  await persist(id, r);
  broadcast('recording', { tabId: r.tabId, recordingId: id, status: 'FINALIZING' });
  const rec = capture.get(r.tabId);
  let result;
  if (!rec || rec.sessionId !== r.captureSessionId) {
    result = failed(id, r.tabId, 'SNAPSHOT_MISSING', 'sessão de captura da gravação não existe mais (aba fechada ou documento descartado)');
  } else {
    try {
      // Barreira: alterações pendentes (inclusive as feitas até este instante) entram antes da consolidação.
      const sync = await capture.synchronize(r.tabId, { geometry: true });
      if (sync.syncState !== 'SYNCHRONIZED') {
        // Finalização normal exige confirmação de TODAS as fontes: nada é exportado nem memorizado.
        result = failed(id, r.tabId, 'SYNC_INCOMPLETE', 'sincronização não confirmada (' + sync.syncState + ')',
          { sessionId: rec.sessionId, syncState: sync.syncState, detail: sync.unconfirmed });
      } else {
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
      }
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
  if (result.status === 'EXPORTED' && result.complete) {
    r.status = 'EXPORTED';
    r.result = result; // somente a exportação final COMPLETA é memorizada
    r.endedAt = new Date().toISOString();
    await store(id, r);
    await capture.release(r.tabId, consumerOf(id), 'RECORDING_FINISHED').catch(() => {});
  } else {
    r.status = 'RECORDING'; // FAILED ou PARTIAL: a gravação continua e pode ser finalizada de novo
    await persist(id, r);
  }
  broadcast('recording', { tabId: r.tabId, recordingId: id, status: result.status, result });
  return result;
}

export function cancelled(evt) {
  const id = evt && evt.sessionId;
  return lock(id, async () => {
    const r = recordings.get(id);
    if (!r) {
      return { recordingId: id, status: 'UNKNOWN' };
    }
    if (ENDED.has(r.status)) {
      // Já encerrada (ex.: finalização concluída antes): o cancelamento não desfaz a exportação.
      return { recordingId: id, status: r.status, duplicate: true };
    }
    r.status = 'CANCELLED';
    r.endedAt = new Date().toISOString();
    await store(id, r);
    await capture.release(r.tabId, consumerOf(id), 'RECORDING_CANCELLED').catch(() => {});
    broadcast('recording', { tabId: r.tabId, recordingId: id, status: 'CANCELLED' });
    return { recordingId: id, status: 'CANCELLED' };
  });
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
  return Array.from(recordings.entries()).map(([id, r]) => ({ recordingId: id, tabId: r.tabId, status: r.status, startedAt: r.startedAt,
    captureSessionId: r.captureSessionId, result: r.result }));
}

/**
 * Reinício do service worker: restaura as gravações. Uma finalização interrompida volta a RECORDING (a sessão de
 * captura, restaurada com o mesmo id e o consumidor da gravação, recebe nova base na reconciliação).
 */
export async function restore() {
  try {
    const all = await chrome.storage.session.get(null);
    for (const [k, v] of Object.entries(all)) {
      if (!k.startsWith(KEY) || !v || recordings.has(k.slice(KEY.length))) {
        continue;
      }
      recordings.set(k.slice(KEY.length), { ...v, status: v.status === 'FINALIZING' ? 'RECORDING' : v.status });
    }
  } catch (e) {
    // armazenamento indisponível
  }
  return recordings.size;
}
