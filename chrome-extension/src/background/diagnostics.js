/*
 * Inteligência documental e diagnóstico (CHROME-003) do lado do navegador:
 *   - metadados de frames do Chrome (frameId, documentId, parentDocumentId, ciclo de vida) associados aos frames
 *     lógicos e enviados ao motor Java (capture.frames.metadata) — nada é inventado: campos ausentes ficam nulos;
 *   - comandos de consulta (grafo documental, trajetória, proteções, prontidão), sempre após a barreira de
 *     sincronização quando há sessão de captura;
 *   - destaque da região do iframe imediato de um elemento (DEV).
 * As regras de análise estão no Java; aqui só há coleta de dados do navegador e orquestração.
 */
import '../shared/privacy.js';
import { host, toContent, tabKey, isAllowedUrl, assertAllowed, frameOfNode, broadcast, HostError } from './runtime.js';
import * as capture from './capture-session.js';

const pendingSync = new Map(); // tabId -> timer

/** Origem + caminho sanitizado (política de privacidade): metadados do Chrome nunca levam consulta/fragmento. */
function sanitize(url) {
  return url ? globalThis.NekkoraPrivacy.originAndPath(url) : null;
}

async function allFrames(tabId) {
  try {
    return await chrome.webNavigation.getAllFrames({ tabId }) || [];
  } catch (e) {
    return [];
  }
}

/** Monta os metadados do navegador para cada frame da aba e os envia ao motor. */
export async function syncFrameMetadata(rec) {
  const all = await allFrames(rec.tabId);
  const entries = new Map(Object.values(rec.frames || {}).map((e) => [e.frameId, e]));
  const frames = all.map((f) => {
    const top = f.frameId === 0;
    const e = entries.get(f.frameId);
    let inspection;
    if (top) {
      inspection = 'TOP';
    } else if (e) {
      inspection = e.status; // ATTACHED, CAPTURED_BY_PARENT, NOT_ATTACHED, FAILED, PENDING
    } else if (isAllowedUrl(f.url)) {
      inspection = 'PENDING';
    } else if (/^https?:/.test(f.url || '')) {
      inspection = 'NO_PERMISSION';
    } else {
      inspection = 'NO_CONTENT_SCRIPT';
    }
    return {
      browserFrameId: f.frameId,
      browserDocumentId: f.documentId || null,
      parentBrowserFrameId: f.parentFrameId,
      parentDocumentId: f.parentDocumentId || null,
      url: sanitize(f.url),
      frameType: f.frameType || null,
      documentLifecycle: f.documentLifecycle || null,
      hostNodeId: top ? null : (e && e.hostNodeId) || null,
      mapping: top ? 'TOP' : (e && e.hostNodeId ? 'HANDSHAKE' : 'UNMAPPED'),
      inspection,
      source: top ? 'top' : (e && e.status === 'ATTACHED' ? e.source : null),
      reason: e ? e.reason || null : null,
    };
  });
  const res = await host('capture.frames.metadata', { tabKey: tabKey(rec.tabId), frames });
  rec.frameSummary = res.counts || null;
  return { frames: frames.length, counts: res.counts };
}

/** Sincronização com espera (navegações de subframes chegam em rajadas). */
export function scheduleFrameSync(tabId, delayMs = 250) {
  const prev = pendingSync.get(tabId);
  if (prev) {
    clearTimeout(prev);
  }
  pendingSync.set(tabId, setTimeout(() => {
    pendingSync.delete(tabId);
    const rec = capture.get(tabId);
    if (rec && rec.status === 'ACTIVE') {
      syncFrameMetadata(rec).then((r) => broadcast('frames-updated', { tabId, counts: r.counts })).catch(() => {});
    }
  }, delayMs));
}

/** Prepara a consulta: barreira (com geometria) + metadados de frames atuais. */
async function prepare(tabId) {
  await assertAllowed(tabId); // somente páginas permitidas pela política central (a permissão nunca é presumida)
  const rec = capture.get(tabId);
  if (rec && capture.isActive(tabId)) {
    const sync = await capture.synchronize(tabId);
    await syncFrameMetadata(rec).catch(() => {});
    return sync;
  }
  return { syncState: 'NO_SESSION' };
}

export async function frameGraph(tabId) {
  const sync = await prepare(tabId);
  return { ...(await host('frames.graph', { tabKey: tabKey(tabId) })), syncState: sync.syncState };
}

export async function accessPath(tabId, nodeId) {
  if (!nodeId) {
    throw new HostError('BAD_REQUEST', 'nodeId obrigatório');
  }
  await prepare(tabId);
  return host('element.accessPath', { tabKey: tabKey(tabId), nodeId });
}

export async function protection(tabId) {
  const sync = await prepare(tabId);
  return { ...(await host('protection.assess', { tabKey: tabKey(tabId) })), syncState: sync.syncState };
}

export async function readiness(tabId) {
  const sync = await prepare(tabId);
  return { ...(await host('diagnostics.readiness', { tabKey: tabKey(tabId) })), syncState: sync.syncState };
}

/** Resumo para o painel (sem PageMap): contagens, frames, proteções e prontidão. */
export async function diagnose(tabId) {
  const t0 = performance.now();
  const sync = await prepare(tabId);
  const res = await host('page.diagnose', { tabKey: tabKey(tabId) });
  return { ...res, syncState: sync.syncState, browserMs: Math.round((performance.now() - t0) * 100) / 100 };
}

/** Destaca (DEV) a região ocupada pelo iframe imediato do elemento, no documento que contém o iframe. */
export async function highlightFrame(tabId, nodeId) {
  const path = await accessPath(tabId, nodeId);
  const frames = path.steps.filter((s) => s.type === 'IFRAME');
  if (!frames.length) {
    return { highlighted: false, reason: 'ELEMENT_IN_MAIN_DOCUMENT' };
  }
  const immediate = frames[frames.length - 1];
  await toContent(tabId, 'cs.highlight', { nodeId: immediate.nodeId, kind: 'frame', label: 'iframe: ' + immediate.label },
    frameOfNode(immediate.nodeId));
  return { highlighted: true, hostNodeId: immediate.nodeId, label: immediate.label, depth: frames.length };
}

capture.on('afterBase', async (rec) => {
  await syncFrameMetadata(rec).catch(() => {});
});
capture.on('frameAttached', async (rec) => {
  scheduleFrameSync(rec.tabId);
});

// Navegações de subframes (inserção, troca de src, navegação interna, substituição de documento).
for (const evt of ['onCommitted', 'onCompleted', 'onErrorOccurred']) {
  chrome.webNavigation[evt].addListener((d) => {
    if (d.frameId !== 0 && capture.isActive(d.tabId)) {
      scheduleFrameSync(d.tabId);
    }
  });
}
