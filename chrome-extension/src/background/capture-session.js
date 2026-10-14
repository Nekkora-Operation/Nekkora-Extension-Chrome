/*
 * CaptureSession — ciclo de vida da captura automática e contínua de UMA aba (CHROME-002).
 *
 *   IDLE → STARTING → ACTIVE ⇄ PAUSED/RECOVERING → COMPLETED        (ERROR: limite de recuperações excedido)
 *                         ↘ FINALIZING (exportação da gravação) ↗
 *
 * - É compartilhada por CONSUMIDORES (inspeção, gravação, rastreamento legado): começa no primeiro e termina
 *   quando o último a libera. Desativar a inspeção NÃO encerra uma gravação em andamento.
 * - Captura-base cooperativa (partes com confirmação) + deltas sequenciados {sessionId, source, epoch, seq}; o
 *   motor Java valida a ordem. Recusa ⇒ ressincronização controlada (nova base, nova época), com limite.
 * - Barreira de sincronização (synchronize) antes de selecionar/exportar: nunca exige captura completa.
 * - Motor indisponível ⇒ PAUSED/DEGRADED, reconexão com espera progressiva e nova base ao voltar.
 * - Reinício do service worker ⇒ estado mínimo restaurado de chrome.storage.session e reconciliado.
 */
import { client, host, toContent, tabKey, broadcast, broadcastThrottled, withLock, isAllowedUrl, errorInfo, HostError } from './runtime.js';
import * as store from './session-store.js';

const RESYNC_WINDOW_MS = 30000;
const RESYNC_LIMIT = 5;
const RECONNECT_STEPS_MS = [1000, 2000, 4000, 8000, 16000, 30000];
const ACTIVE_STATES = new Set(['STARTING', 'ACTIVE', 'PAUSED', 'RECOVERING', 'FINALIZING']);

const sessions = new Map(); // tabId -> registro
const lastEnded = new Map(); // tabId -> resumo da última sessão encerrada
const parts = new Map(); // `${sessionId}|${frameId}` -> partes da captura-base em andamento
const hooks = { afterBase: [], onAutoResolution: [], onDocumentReplaced: [], onStatus: [], frameAttached: [] };
let reconnect = { timer: null, attempt: 0, running: false };
const swStartedAt = Date.now();
let restoredCount = 0;

export function on(name, fn) {
  hooks[name].push(fn);
}

export function notify(name, ...args) {
  return runHooks(name, ...args);
}

async function runHooks(name, ...args) {
  for (const fn of hooks[name]) {
    try {
      await fn(...args);
    } catch (e) {
      broadcast('error', { tabId: args[0] && args[0].tabId, where: name, ...errorInfo(e) });
    }
  }
}

function newSessionId(tabId) {
  return 'cs-' + tabId + '-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 6);
}

function newRecord(tabId, sessionId) {
  return {
    tabId, sessionId, status: 'IDLE', consumers: [], documentId: null, url: null, epoch: 0,
    baseCaptureId: null, latestCaptureId: null, nodes: 0, deltasApplied: 0, deltasRejected: 0, deltasStale: 0,
    lastDeltaAt: null, lastUpdateAt: null, startedAt: new Date().toISOString(), fullCaptures: 0, resyncs: 0,
    resyncTimes: [], hostState: 'UNKNOWN', frames: {}, initialCapture: null, lastCapture: null, lastExport: null,
    reason: null, error: null, resyncPending: false, endedAt: null,
  };
}

/** Persistência mínima: identificadores e estados (nunca conteúdo da página). */
function persist(rec) {
  return store.save(rec.tabId, {
    sessionId: rec.sessionId, status: rec.status, consumers: rec.consumers, documentId: rec.documentId, url: rec.url,
    epoch: rec.epoch, startedAt: rec.startedAt, deltasApplied: rec.deltasApplied, fullCaptures: rec.fullCaptures, resyncs: rec.resyncs,
  });
}

export function describe(rec) {
  if (!rec) {
    return null;
  }
  return {
    sessionId: rec.sessionId, tabId: rec.tabId, status: rec.status, consumers: [...rec.consumers], documentId: rec.documentId,
    url: rec.url, epoch: rec.epoch, baseCaptureId: rec.baseCaptureId, latestCaptureId: rec.latestCaptureId, nodes: rec.nodes,
    deltasApplied: rec.deltasApplied, deltasRejected: rec.deltasRejected, deltasStale: rec.deltasStale, lastDeltaAt: rec.lastDeltaAt,
    lastUpdateAt: rec.lastUpdateAt, startedAt: rec.startedAt, endedAt: rec.endedAt, fullCaptures: rec.fullCaptures, resyncs: rec.resyncs,
    hostState: rec.hostState, frames: Object.values(rec.frames), initialCapture: rec.initialCapture, lastCapture: rec.lastCapture,
    lastExport: rec.lastExport, reason: rec.reason, error: rec.error,
  };
}

function emitStatus(rec, immediate) {
  const data = () => ({ tabId: rec.tabId, session: describe(rec) });
  if (immediate) {
    broadcast('session-status', data());
  } else {
    broadcastThrottled('status:' + rec.tabId, 'session-status', data);
  }
  for (const fn of hooks.onStatus) {
    try {
      fn(rec);
    } catch (e) {
      // ignore
    }
  }
}

export function get(tabId) {
  return sessions.get(tabId) || null;
}

export function isActive(tabId) {
  const rec = sessions.get(tabId);
  return !!(rec && ACTIVE_STATES.has(rec.status));
}

export function status(tabId) {
  const rec = sessions.get(tabId);
  return { session: describe(rec), lastEnded: lastEnded.get(tabId) || null };
}

export function list() {
  return Array.from(sessions.values()).map(describe);
}

export function diagnostics() {
  return { swStartedAt, restoredSessions: restoredCount, sessions: sessions.size, partsInFlight: parts.size, reconnect: { attempt: reconnect.attempt, scheduled: !!reconnect.timer } };
}

// ----------------------------------------------------------------------------------- captura-base

/** Recebe uma parte da captura-base (confirmada ao content script: controle de fluxo). */
export function onCapturePart(tabId, frameId, data) {
  const key = data.sessionId + '|' + frameId;
  const buf = parts.get(key);
  if (!buf) {
    return { ok: false, error: 'NO_CAPTURE_IN_PROGRESS' };
  }
  buf.push(data.nodes);
  return { ok: true };
}

/**
 * Captura-base de um frame: o content script liga o rastreamento em modo BUFFER, percorre a página em
 * fatias e entrega as partes. Devolve o snapshot bruto montado (contrato raw-snapshot).
 */
export async function captureBase(tabId, frameId, sessionId, source, options = {}) {
  const key = sessionId + '|' + frameId;
  parts.set(key, []);
  try {
    const header = await toContent(tabId, 'cs.session.begin', {
      sessionId, source, maxNodes: options.maxNodes, navigationKind: options.navigationKind || 'INITIAL',
      cooperative: options.cooperative !== false, sliceMs: options.sliceMs, partSize: options.partSize,
    }, frameId);
    const nodes = header.nodes || [].concat(...parts.get(key));
    if (nodes.length !== header.stats.nodesEmitted) {
      throw new HostError('CAPTURE_INCOMPLETE', 'partes recebidas ' + nodes.length + ' ≠ emitidas ' + header.stats.nodesEmitted);
    }
    return {
      captureId: header.captureId, capturedAt: header.capturedAt, document: header.document, frames: header.frames,
      shadowRoots: header.shadowRoots, nodes, requests: header.requests, stats: header.stats,
    };
  } finally {
    parts.delete(key);
  }
}

function hostDown(e) {
  return e && (e.code === 'HOST_DISCONNECTED' || e.code === 'HOST_UNAVAILABLE' || e.code === 'TIMEOUT');
}

/** Estabelece (ou restabelece) a base da sessão. Executa sob o bloqueio da aba. */
async function establish(rec, reason, options = {}) {
  const first = rec.fullCaptures === 0;
  rec.status = first ? 'STARTING' : 'RECOVERING';
  rec.reason = reason;
  rec.frames = {};
  emitStatus(rec, true);
  const t0 = performance.now();
  try {
    const snapshot = await captureBase(rec.tabId, 0, rec.sessionId, 'top', options);
    const t1 = performance.now();
    const res = await host('capture.session.start', { sessionId: rec.sessionId, tabKey: tabKey(rec.tabId), snapshot }, { timeoutMs: 120000 });
    const t2 = performance.now();
    const confirm = await toContent(rec.tabId, 'cs.session.confirmBase', { sessionId: rec.sessionId, epoch: res.session.epoch });
    rec.epoch = res.session.epoch;
    rec.documentId = res.pageSessionId;
    rec.url = snapshot.document.url;
    rec.baseCaptureId = res.captureId;
    rec.latestCaptureId = res.captureId;
    rec.referenceCaptureId = res.captureId; // o motor fixa a base como referência de comparação
    rec.nodes = res.nodes;
    rec.fullCaptures++;
    rec.lastCapture = {
      captureId: res.captureId, reason, nodes: res.nodes, interactive: res.interactive, text: res.text,
      captureMs: snapshot.stats.durationMs, mode: snapshot.stats.mode, slices: snapshot.stats.slices, maxSliceMs: snapshot.stats.maxSliceMs, consistency: snapshot.stats.consistency,
      browserMs: round(t1 - t0), hostMs: round(t2 - t1), totalMs: round(performance.now() - t0), buffered: confirm.buffered,
      navigation: res.navigation, coverage: res.coverage, timings: res.timings, at: new Date().toISOString(),
    };
    if (first) {
      rec.initialCapture = rec.lastCapture;
    }
    rec.status = 'ACTIVE';
    rec.hostState = 'OK';
    rec.error = null;
    rec.lastUpdateAt = new Date().toISOString();
    await persist(rec);
    broadcast('captured', { tabId: rec.tabId, sessionId: rec.sessionId, captureId: res.captureId, nodes: res.nodes, automatic: true, reason });
    await runHooks('afterBase', rec);
    emitStatus(rec, true);
    return rec.lastCapture;
  } catch (e) {
    await toContent(rec.tabId, 'cs.session.stop', {}).catch(() => {});
    if (hostDown(e)) {
      markHostDown(rec, e);
    } else {
      rec.status = 'ERROR';
      rec.error = errorInfo(e);
    }
    await persist(rec);
    emitStatus(rec, true);
    throw e;
  }
}

function round(v) {
  return Math.round(v * 100) / 100;
}

// ------------------------------------------------------------------------------- consumidores

/** Garante uma sessão ativa para a aba e registra o consumidor. Lança se a base não puder ser estabelecida. */
export function acquire(tabId, consumer, options = {}) {
  return withLock('tab:' + tabId, async () => {
    let rec = sessions.get(tabId);
    if (rec && ACTIVE_STATES.has(rec.status) && rec.status !== 'PAUSED') {
      if (!rec.consumers.includes(consumer)) {
        rec.consumers.push(consumer);
        await persist(rec);
        emitStatus(rec, true);
      }
      return rec;
    }
    if (!rec || rec.status === 'COMPLETED') {
      rec = newRecord(tabId, newSessionId(tabId));
      sessions.set(tabId, rec);
    }
    if (!rec.consumers.includes(consumer)) {
      rec.consumers.push(consumer);
    }
    try {
      await establish(rec, rec.fullCaptures ? 'RESTART' : 'SESSION_START', options);
    } catch (e) {
      rec.consumers = rec.consumers.filter((c) => c !== consumer);
      if (!rec.consumers.length) {
        await endInner(rec, 'START_FAILED');
      }
      throw e;
    }
    return rec;
  });
}

/** Remove o consumidor; a sessão termina quando não resta nenhum. */
export function release(tabId, consumer, reason) {
  return withLock('tab:' + tabId, async () => {
    const rec = sessions.get(tabId);
    if (!rec) {
      return null;
    }
    rec.consumers = rec.consumers.filter((c) => c !== consumer);
    if (rec.consumers.length) {
      await persist(rec);
      emitStatus(rec, true);
      return describe(rec);
    }
    return endInner(rec, reason || 'RELEASED');
  });
}

/** Encerra a sessão independentemente dos consumidores (aba fechada, desativação total, origem proibida). */
export function end(tabId, reason) {
  return withLock('tab:' + tabId, async () => {
    const rec = sessions.get(tabId);
    return rec ? endInner(rec, reason) : null;
  });
}

async function endInner(rec, reason) {
  for (const f of [0, ...Object.values(rec.frames).map((x) => x.frameId)]) {
    await toContent(rec.tabId, 'cs.session.stop', {}, f).catch(() => {});
  }
  let hostResult = null;
  if (rec.epoch > 0 && client.status().connected) {
    hostResult = await host('capture.session.stop', { sessionId: rec.sessionId, reason, release: true }).catch((e) => errorInfo(e));
  }
  rec.status = 'COMPLETED';
  rec.reason = reason;
  rec.endedAt = new Date().toISOString();
  rec.consumers = [];
  sessions.delete(rec.tabId);
  await store.remove(rec.tabId);
  const summary = { ...describe(rec), host: hostResult };
  lastEnded.set(rec.tabId, summary);
  emitStatus(rec, true);
  return summary;
}

// ------------------------------------------------------------------------------------- deltas

/** Delta de um frame da aba (resposta = confirmação para o content script). */
export async function onDelta(tabId, frameId, delta) {
  const rec = sessions.get(tabId);
  if (!rec || rec.sessionId !== delta.sessionId) {
    // Rastreador órfão (sessão encerrada/substituída): desliga-o para não deixar observadores residuais.
    toContent(tabId, 'cs.session.stop', {}, frameId).catch(() => {});
    return { ok: false, error: 'SESSION_UNKNOWN' };
  }
  if (rec.status === 'STARTING' || rec.status === 'RECOVERING') {
    return { ok: false, error: 'SESSION_RECOVERING' };
  }
  if (delta.epoch < rec.epoch) {
    rec.deltasStale++;
    return { ok: false, error: 'DELTA_STALE' }; // de uma base anterior: a fonte será reanexada
  }
  try {
    const res = await host('capture.session.delta', { sessionId: rec.sessionId, delta, autoResolve: true }, { timeoutMs: 30000 });
    rec.deltasApplied++;
    rec.latestCaptureId = res.captureId;
    rec.nodes = res.nodes;
    rec.lastDeltaAt = new Date().toISOString();
    rec.lastUpdateAt = rec.lastDeltaAt;
    if (res.autoResolutions && res.autoResolutions.length) {
      await runHooks('onAutoResolution', rec, res.autoResolutions);
    }
    broadcast('delta', { tabId, sessionId: rec.sessionId, source: delta.source, seq: delta.seq, captureId: res.captureId, nodes: res.nodes, mutation: res.mutation, timings: res.timings });
    emitStatus(rec, false);
    if (rec.deltasApplied % 20 === 0) {
      persist(rec);
    }
    return { ok: true, captureId: res.captureId };
  } catch (e) {
    rec.deltasRejected++;
    if (hostDown(e)) {
      markHostDown(rec, e);
      return { ok: false, error: e.code };
    }
    scheduleResync(rec, e.code || 'DELTA_FAILED');
    return { ok: false, error: e.code || 'DELTA_FAILED' };
  }
}

/** Ressincronização controlada (fila no bloqueio da aba; limite por janela para impedir laços). */
export function scheduleResync(rec, reason, options = {}) {
  if (rec.resyncPending) {
    return withLock('tab:' + rec.tabId, async () => describe(rec));
  }
  rec.resyncPending = true;
  return withLock('tab:' + rec.tabId, async () => {
    rec.resyncPending = false;
    if (sessions.get(rec.tabId) !== rec) {
      return null;
    }
    const now = Date.now();
    rec.resyncTimes = rec.resyncTimes.filter((t) => now - t < RESYNC_WINDOW_MS);
    if (!options.exempt && rec.resyncTimes.length >= RESYNC_LIMIT) {
      rec.status = 'ERROR';
      rec.error = { code: 'RESYNC_LIMIT', message: RESYNC_LIMIT + ' ressincronizações em ' + (RESYNC_WINDOW_MS / 1000) + ' s (último motivo: ' + reason + ')' };
      for (const f of [0, ...Object.values(rec.frames).map((x) => x.frameId)]) {
        await toContent(rec.tabId, 'cs.session.stop', {}, f).catch(() => {});
      }
      await persist(rec);
      emitStatus(rec, true);
      return describe(rec);
    }
    if (!options.exempt) {
      rec.resyncTimes.push(now);
    }
    rec.resyncs++;
    await establish(rec, 'RESYNC:' + reason, options).catch(() => {});
    return describe(rec);
  });
}

/** Captura completa forçada (DEV) ou reconstrução por navegação/recarga: nova base, mesma sessão. */
export function rebase(tabId, reason, options = {}) {
  const rec = sessions.get(tabId);
  if (!rec) {
    throw new HostError('NO_SESSION', 'Nenhuma sessão de captura ativa na aba ' + tabId);
  }
  return withLock('tab:' + tabId, async () => {
    if (sessions.get(tabId) !== rec) {
      throw new HostError('NO_SESSION', 'sessão encerrada');
    }
    return establish(rec, reason, options);
  });
}

/**
 * Barreira de sincronização: envia as mutações pendentes de TODAS as fontes obrigatórias (documento principal +
 * frames anexados) e aguarda o motor aplicá-las. Só devolve {@code SYNCHRONIZED} quando cada fonte obrigatória
 * confirmou o próprio estado; erro de comunicação, rastreador desligado ou falha de envio tornam o resultado
 * {@code INCOMPLETE} (com a lista de fontes não confirmadas) — nunca uma sincronização "presumida".
 * @returns {{syncState: 'SYNCHRONIZED'|'INCOMPLETE'|'PENDING'|'DEGRADED'|'NO_SESSION'|'ERROR', unconfirmed: object[]}}
 */
export async function synchronize(tabId, options = {}) {
  let rec = sessions.get(tabId);
  if (!rec) {
    return { syncState: 'NO_SESSION', unconfirmed: [] };
  }
  await withLock('tab:' + tabId, async () => {}); // aguarda início/recuperação em andamento
  rec = sessions.get(tabId);
  if (!rec) {
    return { syncState: 'NO_SESSION', unconfirmed: [] };
  }
  const t0 = performance.now();
  let pending = 0;
  let paused = false;
  let unconfirmed = [];
  for (let attempt = 0; attempt < 2; attempt++) {
    pending = 0;
    paused = false;
    unconfirmed = [];
    const required = [{ frameId: 0, source: 'top' }, ...Object.values(rec.frames)
      .filter((x) => x.status === 'ATTACHED').map((x) => ({ frameId: x.frameId, source: x.source }))];
    const results = await Promise.all(required.map((src) => toContent(tabId, 'cs.session.flush', { geometry: !!options.geometry }, src.frameId)
      .then((r) => ({ ok: true, r }), (e) => ({ ok: false, error: e.code || 'FLUSH_FAILED', message: String(e && e.message || e) }))));
    for (let i = 0; i < required.length; i++) {
      const src = required[i];
      const res = results[i];
      if (!res.ok) {
        if (src.frameId !== 0 && res.error === 'CONTENT_UNAVAILABLE' && !(await frameExists(tabId, src.frameId))) {
          // Frame removido da página: o delta do pai já descartou os nós; deixa de ser fonte obrigatória.
          delete rec.frames[src.source];
          continue;
        }
        unconfirmed.push({ ...src, error: res.error, message: res.message });
        continue;
      }
      const r = res.r || {};
      if (r.mode === 'OFF') {
        unconfirmed.push({ ...src, error: 'TRACKER_NOT_RUNNING' }); // fonte sem rastreamento: estado desconhecido
        continue;
      }
      if (r.error) {
        paused = true; // envio recusado: a recuperação foi agendada
      }
      pending += r.pending || 0;
      paused = paused || r.mode === 'PAUSED';
    }
    if (!paused && !rec.resyncPending && !unconfirmed.length) {
      break;
    }
    await withLock('tab:' + tabId, async () => {}); // recuperação agendada pela falha, depois nova tentativa
  }
  let syncState;
  if (rec.status === 'ERROR') {
    syncState = 'ERROR';
  } else if (rec.hostState !== 'OK') {
    syncState = 'DEGRADED';
  } else if (unconfirmed.length) {
    syncState = 'INCOMPLETE';
  } else if (paused || pending) {
    syncState = 'PENDING';
  } else {
    syncState = 'SYNCHRONIZED';
  }
  return { syncState, unconfirmed, sessionId: rec.sessionId, epoch: rec.epoch, latestCaptureId: rec.latestCaptureId, nodes: rec.nodes, pending, ms: round(performance.now() - t0) };
}

async function frameExists(tabId, frameId) {
  try {
    return !!(await chrome.webNavigation.getFrame({ tabId, frameId }));
  } catch (e) {
    return false;
  }
}

// -------------------------------------------------------------------------------- documento / SPA

/** Content script de um frame (re)carregado. frameId 0 = documento principal substituído. */
export async function onContentReady(tabId, frameId, data) {
  const rec = sessions.get(tabId);
  if (!rec || frameId !== 0) {
    return;
  }
  if (!isAllowedUrl(data.url)) {
    await end(tabId, 'ORIGIN_NOT_ALLOWED');
    return;
  }
  if (rec.documentId === data.pageSessionId) {
    return;
  }
  await runHooks('onDocumentReplaced', rec, data);
  await scheduleResync(rec, 'DOCUMENT_REPLACED', { exempt: true, navigationKind: 'RELOAD' });
}

export async function onNavigation(tabId, kind) {
  const rec = sessions.get(tabId);
  if (rec && rec.status === 'ACTIVE') {
    await toContent(tabId, 'cs.session.navigation', { kind }).catch(() => {});
  }
}

// -------------------------------------------------------------------------------- motor Java

function markHostDown(rec, e) {
  rec.hostState = 'DEGRADED';
  if (ACTIVE_STATES.has(rec.status) || rec.status === 'ERROR') {
    rec.status = 'PAUSED';
  }
  rec.error = errorInfo(e);
  emitStatus(rec, true);
  scheduleReconnect();
}

function onHostDisconnected(info) {
  for (const rec of sessions.values()) {
    if (ACTIVE_STATES.has(rec.status)) {
      markHostDown(rec, { code: 'HOST_DISCONNECTED', message: info || 'conexão encerrada' });
      for (const f of [0, ...Object.values(rec.frames).map((x) => x.frameId)]) {
        toContent(rec.tabId, 'cs.session.pause', { reason: 'HOST_DISCONNECTED' }, f).catch(() => {});
      }
    }
  }
}

function scheduleReconnect() {
  if (reconnect.timer || reconnect.running) {
    return;
  }
  const delay = RECONNECT_STEPS_MS[Math.min(reconnect.attempt, RECONNECT_STEPS_MS.length - 1)];
  reconnect.timer = setTimeout(tryReconnect, delay);
  chrome.alarms.create('nekkora.reconnect', { periodInMinutes: 0.5 }).catch(() => {});
}

export async function tryReconnect() {
  if (reconnect.timer) {
    clearTimeout(reconnect.timer);
    reconnect.timer = null;
  }
  const degraded = Array.from(sessions.values()).filter((r) => r.hostState === 'DEGRADED');
  if (!degraded.length) {
    reconnect.attempt = 0;
    chrome.alarms.clear('nekkora.reconnect').catch(() => {});
    return { recovered: 0 };
  }
  reconnect.running = true;
  reconnect.attempt++;
  try {
    await host('host.ping', {}, { timeoutMs: 5000 });
  } catch (e) {
    reconnect.running = false;
    scheduleReconnect();
    return { recovered: 0, error: e.code };
  }
  reconnect.running = false;
  reconnect.attempt = 0;
  chrome.alarms.clear('nekkora.reconnect').catch(() => {});
  let recovered = 0;
  for (const rec of degraded) {
    if (sessions.get(rec.tabId) === rec) {
      await scheduleResync(rec, 'HOST_RECOVERED', { exempt: true });
      recovered += rec.status === 'ACTIVE' ? 1 : 0;
    }
  }
  return { recovered };
}

// ---------------------------------------------------------------------- reinício do service worker

/** Carrega o estado mínimo persistido (rápido; executado antes de qualquer mensagem). */
export async function restore() {
  const records = await store.loadAll();
  for (const r of records) {
    if (!r.sessionId || sessions.has(r.tabId)) {
      continue;
    }
    const rec = newRecord(r.tabId, r.sessionId);
    Object.assign(rec, { status: 'RECOVERING', consumers: r.consumers || [], documentId: r.documentId, url: r.url, epoch: r.epoch || 0,
      startedAt: r.startedAt, deltasApplied: r.deltasApplied || 0, fullCaptures: r.fullCaptures || 1, resyncs: r.resyncs || 0, hostState: 'UNKNOWN' });
    sessions.set(r.tabId, rec);
    restoredCount++;
  }
  return restoredCount;
}

/**
 * Reconcilia as sessões restauradas: aba fechada ⇒ descarte; caso contrário nova base (o processo do motor
 * termina junto com a porta do service worker, portanto o estado no host não sobrevive).
 */
export async function reconcile() {
  const out = [];
  for (const rec of Array.from(sessions.values())) {
    if (rec.status !== 'RECOVERING' || rec.epoch === 0) {
      continue;
    }
    let tab = null;
    try {
      tab = await chrome.tabs.get(rec.tabId);
    } catch (e) {
      tab = null;
    }
    if (!tab || !isAllowedUrl(tab.url || '')) {
      out.push(await end(rec.tabId, tab ? 'ORIGIN_NOT_ALLOWED' : 'TAB_CLOSED'));
      continue;
    }
    await scheduleResync(rec, 'SERVICE_WORKER_RESTART', { exempt: true });
    out.push(describe(rec));
  }
  return out;
}

client.onConnectionLost = onHostDisconnected;
chrome.alarms.onAlarm.addListener((a) => {
  if (a.name === 'nekkora.reconnect') {
    tryReconnect();
  }
});

export const ACTIVE = ACTIVE_STATES;
