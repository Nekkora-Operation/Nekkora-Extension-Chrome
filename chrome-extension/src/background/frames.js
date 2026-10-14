/*
 * Iframes de OUTRA origem local permitida (ex.: página em 127.0.0.1:8765 com iframe de localhost:8766).
 *
 * O documento principal não consegue ler esses frames; o content script injetado NO PRÓPRIO frame
 * (all_frames) captura o documento com ids prefixados ({@code x<frameId>n…}) e o motor Java o anexa ao
 * nó iframe hospedeiro. O hospedeiro é descoberto por handshake: o frame publica um token ao pai
 * (postMessage) e o content script do pai identifica qual iframe tem aquele contentWindow.
 *
 * Política (sem duplicidade, sem ampliar permissões):
 *   - frame acessível pelo pai (mesma origem) ⇒ já capturado pelo pai, não é anexado;
 *   - iframe com atributo sandbox ⇒ não anexado (POLICY_SANDBOX);
 *   - origem sem permissão ⇒ não há content script; permanece declarado como inacessível pelo pai.
 */
import { host, toContent, isAllowedUrl, broadcast, errorInfo, withLock, HostError } from './runtime.js';
import * as capture from './capture-session.js';

const MAX_FRAMES_PER_TAB = 20;

function token() {
  return 'nkf-' + Math.random().toString(36).slice(2) + Date.now().toString(36);
}

async function frameDetails(tabId) {
  try {
    return await chrome.webNavigation.getAllFrames({ tabId }) || [];
  } catch (e) {
    return [];
  }
}

/**
 * Frame cujo content script capturou o documento {@code frameId}: o próprio frame quando anexado; o dono da
 * captura do pai quando o documento foi capturado pelo pai (mesma origem); 0 para o documento principal.
 */
function captureOwner(rec, frameId) {
  for (let id = frameId, guard = 0; guard < 20; guard++) {
    if (!id) {
      return 0;
    }
    const e = Object.values(rec.frames).find((x) => x.frameId === id);
    if (!e || e.status === 'ATTACHED') {
      return id;
    }
    if (e.status !== 'CAPTURED_BY_PARENT') {
      return id;
    }
    id = e.parentFrameId;
  }
  return 0;
}

/** Anexa um frame de outra origem permitida à sessão da aba. Idempotente para a época corrente. */
export async function attach(rec, frameId, info) {
  const src = 'x' + frameId;
  const existing = rec.frames[src];
  if (existing && existing.epoch === rec.epoch && existing.status === 'ATTACHED' && (!info || !info.documentId || existing.documentId === info.documentId)) {
    return existing;
  }
  const entry = { frameId, source: src, status: 'PENDING', epoch: rec.epoch, hostNodeId: null, url: info && info.url, reason: null,
    documentId: info && info.documentId, parentFrameId: info && info.parentFrameId != null ? info.parentFrameId : 0 };
  rec.frames[src] = entry;
  try {
    if (Object.keys(rec.frames).length > MAX_FRAMES_PER_TAB) {
      throw new HostError('FRAME_LIMIT', 'limite de ' + MAX_FRAMES_PER_TAB + ' frames anexados por aba');
    }
    const parentFrameId = info && info.parentFrameId != null ? info.parentFrameId : 0;
    const t = token();
    await toContent(rec.tabId, 'cs.frame.announce', { token: t }, frameId);
    // O iframe hospedeiro é procurado no frame DONO da captura do pai (o id do nó precisa ser o do estado do motor).
    const owner = captureOwner(rec, parentFrameId);
    await toContent(rec.tabId, 'cs.session.flush', {}, owner).catch(() => {});
    const look = await toContent(rec.tabId, 'cs.frame.lookup', { token: t, waitMs: 1500 }, owner);
    if (!look.found) {
      throw new HostError('FRAME_HOST_NOT_FOUND', 'handshake sem resposta do frame ' + frameId);
    }
    entry.hostNodeId = look.hostNodeId;
    if (look.accessibleFromParent) {
      entry.status = 'CAPTURED_BY_PARENT';
      return entry;
    }
    if (look.sandboxed) {
      entry.status = 'NOT_ATTACHED';
      entry.reason = 'POLICY_SANDBOX';
      await toContent(rec.tabId, 'cs.session.stop', {}, frameId).catch(() => {});
      return entry;
    }
    const snapshot = await capture.captureBase(rec.tabId, frameId, rec.sessionId, src, { navigationKind: 'FRAME' });
    const res = await host('capture.session.attachFrame', { sessionId: rec.sessionId, hostNodeId: look.hostNodeId, source: src, snapshot }, { timeoutMs: 60000 });
    await toContent(rec.tabId, 'cs.session.confirmBase', { sessionId: rec.sessionId, epoch: rec.epoch }, frameId);
    entry.status = 'ATTACHED';
    entry.nodes = snapshot.nodes.length;
    entry.url = snapshot.document.url;
    entry.documentId = snapshot.document.pageSessionId;
    rec.latestCaptureId = res.captureId;
    rec.nodes = res.nodes;
    broadcast('frame-attached', { tabId: rec.tabId, frame: entry });
    await capture.notify('frameAttached', rec, entry);
    return entry;
  } catch (e) {
    entry.status = 'FAILED';
    entry.reason = errorInfo(e).code;
    entry.error = errorInfo(e);
    await toContent(rec.tabId, 'cs.session.stop', {}, frameId).catch(() => {});
    broadcast('frame-attach-failed', { tabId: rec.tabId, frame: entry });
    return entry;
  }
}

/** Após cada base: anexa todos os frames de outra origem permitida presentes na aba (do mais raso ao mais profundo). */
export async function attachAll(rec) {
  const all = await frameDetails(rec.tabId);
  const byId = new Map(all.map((f) => [f.frameId, f]));
  const depth = (f) => {
    let d = 0;
    for (let p = f; p && p.parentFrameId > 0 && d < 20; p = byId.get(p.parentFrameId)) {
      d++;
    }
    return d;
  };
  const candidates = all.filter((f) => f.frameId !== 0 && isAllowedUrl(f.url)).sort((a, b) => depth(a) - depth(b));
  for (const f of candidates) {
    await attach(rec, f.frameId, { parentFrameId: f.parentFrameId, url: f.url });
  }
  return Object.values(rec.frames);
}

/** Content script de um subframe ficou pronto (iframe dinâmico, substituído ou recarregado). */
export async function onFrameReady(tabId, frameId, data) {
  const rec = capture.get(tabId);
  if (!rec || rec.status !== 'ACTIVE' || !isAllowedUrl(data.url)) {
    return null;
  }
  return withLock('tab:' + tabId, async () => {
    if (capture.get(tabId) !== rec || rec.status !== 'ACTIVE') {
      return null;
    }
    const all = await frameDetails(tabId);
    const f = all.find((x) => x.frameId === frameId);
    return attach(rec, frameId, { parentFrameId: f ? f.parentFrameId : 0, url: data.url, documentId: data.pageSessionId });
  });
}

capture.on('afterBase', async (rec) => {
  await attachAll(rec);
});
