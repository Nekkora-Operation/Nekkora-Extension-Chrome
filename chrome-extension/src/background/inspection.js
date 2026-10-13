/*
 * InspectionSession — estado da inspeção visual de uma aba (botão único do painel) e seleção de elementos.
 *
 *   INACTIVE → ACTIVATING → ACTIVE → DEACTIVATING → INACTIVE
 *                    ↘ ERROR (falha: nunca indica ativo)      ACTIVE ⇄ DEGRADED (motor indisponível / captura em erro)
 *
 * A inspeção é UM consumidor da sessão de captura: ativar garante a captura automática; desativar libera o
 * consumidor (uma gravação em andamento mantém a captura). A seleção reutiliza o snapshot mantido pelos
 * deltas, após a barreira de sincronização — sem captura completa por clique.
 */
import { host, toContent, tabKey, broadcast, withLock, assertAllowed, frameOfNode, errorInfo, HostError } from './runtime.js';
import * as capture from './capture-session.js';

const KEY = 'nk.insp.';
const states = new Map(); // tabId -> estado
let selectionSeq = 0;

function stateOf(tabId) {
  let s = states.get(tabId);
  if (!s) {
    s = { tabId, state: 'INACTIVE', since: Date.now(), error: null, selected: null, lastInspection: null, lastResolution: null,
      captures: [], selections: 0, reusedSnapshots: 0, fullCapturesForSelection: 0 };
    states.set(tabId, s);
  }
  return s;
}

export function get(tabId) {
  return stateOf(tabId);
}

function setState(s, state, error) {
  s.state = state;
  s.since = Date.now();
  s.error = error || null;
  chrome.storage.session.set({ [KEY + s.tabId]: { state, since: s.since } }).catch(() => {});
  broadcast('inspection-state', describe(s.tabId));
}

export function describe(tabId) {
  const s = stateOf(tabId);
  const rec = capture.get(tabId);
  return {
    tabId, state: s.state, active: s.state === 'ACTIVE' || s.state === 'DEGRADED', since: s.since, error: s.error,
    selected: s.selected, selections: s.selections, reusedSnapshots: s.reusedSnapshots, fullCapturesForSelection: s.fullCapturesForSelection,
    capture: capture.describe(rec),
  };
}

function framesOf(tabId) {
  const rec = capture.get(tabId);
  return [0, ...(rec ? Object.values(rec.frames).filter((f) => f.status === 'ATTACHED').map((f) => f.frameId) : [])];
}

async function startInspector(tabId) {
  for (const f of framesOf(tabId)) {
    await toContent(tabId, 'cs.inspect.start', {}, f).catch((e) => {
      if (f === 0) {
        throw e;
      }
    });
  }
}

async function stopInspector(tabId) {
  for (const f of framesOf(tabId)) {
    await toContent(tabId, 'cs.inspect.stop', {}, f).catch(() => {});
    await toContent(tabId, 'cs.overlay.destroy', {}, f).catch(() => {});
  }
}

/** Ativa: página permitida → motor → sessão de captura (base automática + rastreamento) → ouvintes/destaque. */
export function activate(tabId) {
  return withLock('insp:' + tabId, async () => {
    const s = stateOf(tabId);
    if (s.state === 'ACTIVE' || s.state === 'DEGRADED') {
      return describe(tabId);
    }
    setState(s, 'ACTIVATING');
    try {
      await assertAllowed(tabId);
      await host('host.ping', {}, { timeoutMs: 10000 });
      await capture.acquire(tabId, 'inspection');
      await startInspector(tabId);
      setState(s, 'ACTIVE');
      return describe(tabId);
    } catch (e) {
      await stopInspector(tabId);
      await capture.release(tabId, 'inspection', 'INSPECTION_FAILED').catch(() => {});
      setState(s, 'ERROR', errorInfo(e));
      throw e;
    }
  });
}

/** Desativa: remove ouvintes e destaques e libera o consumidor (não finaliza gravação nem exporta). */
export function deactivate(tabId, reason) {
  return withLock('insp:' + tabId, async () => {
    const s = stateOf(tabId);
    if (s.state === 'INACTIVE' || s.state === 'ERROR') {
      setState(s, 'INACTIVE');
      return describe(tabId);
    }
    setState(s, 'DEACTIVATING');
    await stopInspector(tabId);
    const ended = await capture.release(tabId, 'inspection', reason || 'INSPECTION_DEACTIVATED').catch((e) => errorInfo(e));
    s.selected = null;
    setState(s, 'INACTIVE');
    return { ...describe(tabId), released: ended };
  });
}

export async function toggle(tabId) {
  const s = stateOf(tabId);
  if (s.state === 'ACTIVATING' || s.state === 'DEACTIVATING') {
    return describe(tabId); // transição em andamento: clique repetido é ignorado
  }
  return (s.state === 'ACTIVE' || s.state === 'DEGRADED') ? deactivate(tabId) : activate(tabId);
}

export function forget(tabId) {
  states.delete(tabId);
  chrome.storage.session.remove(KEY + tabId).catch(() => {});
}

// ------------------------------------------------------------------------------ captura avulsa (sem sessão)

/** Captura completa avulsa (fluxo CHROME-001), usada somente quando não há sessão automática na aba. */
export async function captureOnce(tabId, options = {}) {
  await assertAllowed(tabId);
  const s = stateOf(tabId);
  const t0 = performance.now();
  const snapshot = await toContent(tabId, 'cs.capture', { maxNodes: options.maxNodes, navigationKind: options.navigationKind });
  const t1 = performance.now();
  const res = await host('snapshot.submit', { tabKey: tabKey(tabId), snapshot, compareWithPrevious: !!options.compareWithPrevious });
  const t2 = performance.now();
  s.captures.push(res.captureId);
  if (s.captures.length > 20) {
    s.captures.shift();
  }
  res.browser = {
    captureMs: snapshot.stats.durationMs,
    roundTripMs: Math.round((t1 - t0) * 100) / 100,
    hostRoundTripMs: Math.round((t2 - t1) * 100) / 100,
    snapshotBytes: JSON.stringify(snapshot).length,
  };
  broadcast('captured', { tabId, captureId: res.captureId, nodes: res.nodes, browser: res.browser, automatic: false });
  return res;
}

/**
 * Snapshot para uma operação de análise: com sessão ativa ⇒ barreira + captura corrente fixada como
 * referência (nenhuma captura completa); sem sessão ⇒ captura avulsa.
 */
export async function currentCapture(tabId) {
  const s = stateOf(tabId);
  if (capture.isActive(tabId)) {
    const sync = await capture.synchronize(tabId);
    if (sync.syncState === 'NO_SESSION') {
      return { captureId: (await captureOnce(tabId)).captureId, reused: false, sync };
    }
    const pinned = await host('capture.pin', { tabKey: tabKey(tabId) });
    const rec = capture.get(tabId);
    if (rec) {
      rec.referenceCaptureId = pinned.referenceCaptureId;
    }
    s.reusedSnapshots++;
    return { captureId: pinned.referenceCaptureId, reused: true, sync };
  }
  s.fullCapturesForSelection++;
  const cap = await captureOnce(tabId);
  return { captureId: cap.captureId, reused: false, sync: { syncState: 'FULL_CAPTURE' } };
}

// ------------------------------------------------------------------------------------- seleção

async function highlightOnly(tabId, nodeId, label, targetId) {
  const frame = frameOfNode(nodeId);
  for (const f of framesOf(tabId)) {
    if (f !== frame) {
      await toContent(tabId, 'cs.clearHighlight', { kind: 'selection' }, f).catch(() => {});
    }
  }
  return toContent(tabId, 'cs.highlight', { nodeId, kind: 'selection', label, targetId }, frame);
}

export async function selectNode(tabId, nodeId) {
  const s = stateOf(tabId);
  const seq = ++selectionSeq;
  const t0 = performance.now();
  const cap = await currentCapture(tabId);
  const t1 = performance.now();
  const frame = frameOfNode(nodeId);
  const inspection = await host('element.inspect', { tabKey: tabKey(tabId), captureId: cap.captureId, nodeId });
  const identity = await host('target.register', { tabKey: tabKey(tabId), captureId: cap.captureId, nodeId });
  const live = await toContent(tabId, 'cs.verifyLocators', { nodeId, locators: inspection.locators }, frame);
  const verified = await host('locators.verify', { tabKey: tabKey(tabId), captureId: cap.captureId, nodeId, checks: live.checks });
  const label = inspection.classification.kind + (inspection.classification.name.name ? ' · ' + inspection.classification.name.name : '');
  await highlightOnly(tabId, nodeId, label, identity.targetId);
  s.selections++;
  s.selected = { nodeId, targetId: identity.targetId, frameId: frame };
  s.lastInspection = {
    captureId: cap.captureId, inspection, identity, verification: verified, liveChecks: live.checks,
    sync: { ...cap.sync, reusedSnapshot: cap.reused, syncMs: Math.round((t1 - t0) * 100) / 100, totalMs: Math.round((performance.now() - t0) * 100) / 100, seq },
  };
  await chrome.storage.session.set({ ['inspection:' + tabId]: s.lastInspection }).catch(() => {});
  broadcast('inspection', { tabId, ...s.lastInspection });
  return s.lastInspection;
}

export async function resolveTarget(tabId, targetId, options = {}) {
  const s = stateOf(tabId);
  const id = targetId || (s.selected && s.selected.targetId);
  if (!id) {
    throw new HostError('NO_TARGET', 'Nenhum alvo selecionado/registrado');
  }
  let captureId;
  if (options.reuseCapture && !capture.isActive(tabId) && s.captures.length) {
    captureId = s.captures[s.captures.length - 1];
  } else {
    captureId = (await currentCapture(tabId)).captureId;
  }
  const res = await host('target.resolve', { tabKey: tabKey(tabId), captureId, targetId: id });
  const r = res.result;
  if (r.status === 'RESOLVED') {
    // Destaque somente com reencontro comprovado.
    await highlightOnly(tabId, r.resolvedNodeId, 'RESOLVED · ' + r.score, id);
    s.selected = { nodeId: r.resolvedNodeId, targetId: id, frameId: frameOfNode(r.resolvedNodeId) };
  } else {
    for (const f of framesOf(tabId)) {
      await toContent(tabId, 'cs.clearHighlight', { kind: 'selection' }, f).catch(() => {});
    }
  }
  s.lastResolution = r;
  broadcast('resolution', { tabId, result: r, timings: res.timings });
  return res;
}

// --------------------------------------------------------------------------- integração com a captura

capture.on('onAutoResolution', async (rec, list) => {
  const s = stateOf(rec.tabId);
  for (const r of list) {
    if (s.selected && r.targetId === s.selected.targetId) {
      if (r.status === 'RESOLVED') {
        await highlightOnly(rec.tabId, r.resolvedNodeId, 'RESOLVED · re-render', r.targetId).catch(() => {});
        s.selected = { nodeId: r.resolvedNodeId, targetId: r.targetId, frameId: frameOfNode(r.resolvedNodeId) };
      } else {
        for (const f of framesOf(rec.tabId)) {
          await toContent(rec.tabId, 'cs.clearHighlight', { kind: 'selection' }, f).catch(() => {});
        }
      }
      s.lastResolution = r;
      broadcast('resolution', { tabId: rec.tabId, result: r, automatic: true });
    }
  }
});

/** Nova base (início, recarga, ressincronização): reinstala ouvintes se a inspeção estiver ativa. */
capture.on('afterBase', async (rec) => {
  const s = stateOf(rec.tabId);
  if (s.state === 'ACTIVE' || s.state === 'DEGRADED' || s.state === 'ACTIVATING') {
    if (s.state !== 'ACTIVATING') {
      await startInspector(rec.tabId);
    }
  }
});

capture.on('frameAttached', async (rec, entry) => {
  const s = stateOf(rec.tabId);
  if (entry.status === 'ATTACHED' && (s.state === 'ACTIVE' || s.state === 'DEGRADED')) {
    await toContent(rec.tabId, 'cs.inspect.start', {}, entry.frameId).catch(() => {});
  }
});

capture.on('onDocumentReplaced', async (rec) => {
  const s = stateOf(rec.tabId);
  s.selected = null;
});

capture.on('onStatus', (rec) => {
  const s = states.get(rec.tabId);
  if (!s) {
    return;
  }
  if (s.state === 'ACTIVE' && (rec.hostState === 'DEGRADED' || rec.status === 'ERROR' || rec.status === 'PAUSED')) {
    setState(s, 'DEGRADED', rec.error);
  } else if (s.state === 'DEGRADED' && rec.status === 'ACTIVE' && rec.hostState === 'OK') {
    setState(s, 'ACTIVE');
  } else if ((s.state === 'ACTIVE' || s.state === 'DEGRADED') && rec.status === 'COMPLETED' && !rec.consumers.includes('inspection')) {
    stopInspector(rec.tabId).catch(() => {});
    setState(s, 'INACTIVE', rec.reason === 'INSPECTION_DEACTIVATED' ? null : { code: rec.reason, message: 'sessão de captura encerrada' });
  }
});

/** Reinício do service worker: restaura o estado da inspeção. */
export async function restore() {
  try {
    const all = await chrome.storage.session.get(null);
    for (const [k, v] of Object.entries(all)) {
      if (k.startsWith(KEY) && v) {
        const s = stateOf(Number(k.slice(KEY.length)));
        s.state = v.state === 'ACTIVATING' ? 'ERROR' : v.state === 'DEACTIVATING' ? 'INACTIVE' : v.state;
        s.since = v.since;
      }
    }
  } catch (e) {
    // ignore
  }
}
