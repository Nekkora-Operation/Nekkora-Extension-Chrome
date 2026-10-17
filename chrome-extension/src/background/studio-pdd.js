/*
 * STUDIO-010 — captura visual SANITIZADA para o PDD (capacidade pdd-capture/1, política nekkora-visual/1).
 *
 * Máquina de estados (a concessão do activeTab sozinha não basta — ela persiste depois do gesto):
 *   1. studio.pdd.capture.arm  — o Studio arma UMA captura para um bloco: aba LAB, etapas (targetId + descritor),
 *      validade curta. Nada é capturado ainda; o ícone da extensão mostra "PDD".
 *   2. Gesto: o atalho "nk-pdd-capturar" (Alt+Shift+K) no Chrome. Só vale com armação válida, aba ativa = aba armada,
 *      origem LAB, mesma época da página e uso único.
 *   3. Resolução das etapas pelo motor (somente leitura, por identidade), plano visual do content script, ficha de
 *      layout, captureVisibleTab, ficha de novo (página mudou → descarta), sanitização por lista de permissão
 *      (visual-sanitizer.js), autoverificação de pixels.
 *      captureVisibleTab captura a aba ATIVA da janela: a identidade da aba armada é conferida antes do plano, logo antes
 *      e logo depois da captura (ativa na mesma janela, mesma janela, nenhuma troca de aba na janela desde o gesto —
 *      inclusive A→B→A). Qualquer divergência descarta a imagem: ACTIVE_TAB_CHANGED.
 *   4. studio.pdd.captured — PNG JÁ sanitizado + regiões (tipo e retângulo) + anotações normalizadas; ou só o código da
 *      falha. A imagem bruta nunca é gravada, registrada, enviada nem guardada em storage: existe só dentro de
 *      shoot(), e é liberada no finally (inclusive em exceção).
 */
import { VISUAL_POLICY, MAX_PNG_BYTES, codeError, compose, deviceRegions, violations } from './visual-sanitizer.js';

const PROTOCOL = 'nekkora-nm/1';
const ID = /^[A-Za-z0-9._:-]{1,80}$/;
const TAB_REF = /^t-[A-Za-z0-9_-]{6,40}$/;
const ACTIONS = new Set(['CLICK', 'FILL', 'SELECT_OPTION', 'CHECK', 'UNCHECK', 'PRESS_KEY', 'NAVIGATE', 'WAIT_FOR', 'OBSERVE', 'UNKNOWN']);
const MAX_STEPS = 32;
export const SHORTCUT = 'nk-pdd-capturar';
export const FAILURES = new Set(['VISUAL_CAPTURE_NOT_ALLOWED', 'NOT_ARMED', 'ARM_EXPIRED', 'TAB_MISMATCH', 'PAGE_CHANGED_DURING_CAPTURE',
  'GEOMETRY_MISMATCH', 'SANITIZATION_CHECK_FAILED', 'SANITIZED_IMAGE_TOO_LARGE', 'TAB_CLOSED', 'PAGE_CHANGED', 'PERMISSION_REVOKED', 'CAPTURE_BUSY',
  'CAPTURE_DENIED', 'REGION_OUT_OF_VIEWPORT', 'CARRIER_TOO_LARGE', 'CARRIER_OVERLAPS_FIELD', 'TOO_MANY_REGIONS', 'REGION_KIND_UNKNOWN', 'CONTENT_UNAVAILABLE',
  'CANCELLED', 'ACTIVE_TAB_CHANGED', 'INTERNAL']);
const OMIT = new Set(['NOT_FOUND', 'AMBIGUOUS', 'IDENTITY_MISMATCH', 'STALE_TARGET', 'SCOPE_UNAVAILABLE', 'INSUFFICIENT_EVIDENCE', 'CONTEXT_UNAVAILABLE',
  'NODE_NOT_CONNECTED', 'TARGET_OUTSIDE_VIEWPORT', 'TARGET_IN_FRAME', 'NO_TARGET', 'RESOLUTION_FAILED']);

export function createStudioPdd(deps) {
  const { client, policy, tabs } = deps;
  const chromeApi = deps.chrome || globalThis.chrome;
  const host = deps.host;
  const toContent = deps.toContent;
  const tabKey = deps.tabKey || ((tabId) => 'tab-' + tabId);
  const frameOfNode = deps.frameOfNode || (() => 0);
  const currentCapture = deps.currentCapture;
  const imaging = deps.imaging || browserImaging(); // decodificar/codificar PNG (OffscreenCanvas no service worker)
  const now = () => (deps.now ? deps.now() : Date.now());
  let armed = null; // { captureId, controller, tabId, tabRef, steps, expiresAt }
  let running = false;
  let inflight = null; // { captureId, controller, tabId, windowId, switched, cancelled } — do gesto até o evento

  const COMMANDS = {
    'studio.pdd.capture.arm': arm,
    'studio.pdd.capture.cancel': cancel,
  };

  function fail(code, message, extra = {}) {
    const e = new Error(code + ': ' + message);
    e.code = code;
    Object.assign(e, extra);
    return e;
  }

  function assertOnly(p, allowed) {
    if (!p || typeof p !== 'object' || Array.isArray(p)) {
      throw fail('BAD_REQUEST', 'payload inválido');
    }
    for (const k of Object.keys(p)) {
      if (!allowed.includes(k)) {
        throw fail('BAD_REQUEST', 'campo não permitido');
      }
    }
  }

  function badge(tabId, text) {
    try {
      chromeApi.action.setBadgeText({ tabId, text }).catch(() => {});
    } catch (e) {
      // sem ícone (teste)
    }
  }

  function postEvent(controller, payload) {
    if (typeof client.status !== 'function' || client.status().connected) {
      client.post({ protocol: PROTOCOL, type: 'studio.event', event: 'studio.pdd.captured', payload, controller: { instanceId: controller } })
        .catch(() => {});
    }
  }

  function failed(a, code) {
    postEvent(a.controller, { captureId: a.captureId, status: 'FAILED', code: FAILURES.has(code) ? code : 'INTERNAL', observedAt: new Date(now()).toISOString() });
  }

  function disarm(reasonCode) {
    const a = armed;
    armed = null;
    if (a) {
      badge(a.tabId, '');
      if (reasonCode) {
        failed(a, reasonCode);
      }
    }
    return a;
  }

  async function arm(p, controller) {
    assertOnly(p, ['captureId', 'tabRef', 'steps', 'leaseMs']);
    if (typeof p.captureId !== 'string' || !ID.test(p.captureId)) throw fail('BAD_REQUEST', 'captureId inválido');
    if (typeof p.tabRef !== 'string' || !TAB_REF.test(p.tabRef)) throw fail('BAD_REQUEST', 'tabRef inválido');
    if (!Number.isInteger(p.leaseMs) || p.leaseMs < 5000 || p.leaseMs > 120000) throw fail('BAD_REQUEST', 'leaseMs inválido');
    if (!Array.isArray(p.steps) || p.steps.length < 1 || p.steps.length > MAX_STEPS) throw fail('BAD_REQUEST', 'etapas inválidas');
    const steps = p.steps.map((s) => {
      assertOnly(s, ['stepRef', 'targetId', 'descriptor', 'actionType']);
      if (typeof s.stepRef !== 'string' || !ID.test(s.stepRef)) throw fail('BAD_REQUEST', 'stepRef inválido');
      if (s.targetId != null && (typeof s.targetId !== 'string' || !ID.test(s.targetId))) throw fail('BAD_REQUEST', 'targetId inválido');
      if (!ACTIONS.has(s.actionType)) throw fail('BAD_REQUEST', 'actionType inválido');
      return { stepRef: s.stepRef, targetId: s.targetId || null, descriptor: deps.descriptorOf ? deps.descriptorOf(s.descriptor) : s.descriptor || null,
        actionType: s.actionType };
    });
    if (armed && armed.captureId === p.captureId && armed.controller === controller) {
      return { captureId: p.captureId, armed: true, duplicate: true, shortcut: await shortcut() };
    }
    if (running || armed) throw fail('INVALID_STATE', 'Já há uma captura armada ou em andamento.', { reason: 'CAPTURE_BUSY' });
    const tabId = tabs.tabOf(p.tabRef);
    const tab = tabId == null ? null : await chromeApi.tabs.get(tabId).catch(() => null);
    if (!tab) throw fail('TAB_NOT_FOUND', 'Aba não encontrada.');
    const url = await policy.tabUrl(tabId).catch(() => null);
    if (policy.evaluate(url).kind !== 'LAB') {
      throw fail('VISUAL_CAPTURE_NOT_ALLOWED', 'A captura de tela para o PDD só existe no laboratório local.', { reason: 'NOT_LAB_ORIGIN' });
    }
    if (tabs.recordingOn && tabs.recordingOn(tabId)) throw fail('INVALID_STATE', 'Há uma gravação nesta aba.', { reason: 'RECORDING_ACTIVE' });
    armed = { captureId: p.captureId, controller, tabId, tabRef: p.tabRef, steps, expiresAt: now() + p.leaseMs };
    badge(tabId, 'PDD');
    return { captureId: p.captureId, armed: true, duplicate: false, shortcut: await shortcut() };
  }

  async function cancel(p, controller) {
    assertOnly(p, ['captureId']);
    if (armed && armed.captureId === p.captureId && armed.controller === controller) {
      disarm('CANCELLED');
      return { captureId: p.captureId, cancelled: true };
    }
    if (inflight && inflight.captureId === p.captureId && inflight.controller === controller) {
      inflight.cancelled = true; // a captura em andamento termina em CANCELLED: nenhuma imagem é publicada
      return { captureId: p.captureId, cancelled: true };
    }
    return { captureId: typeof p.captureId === 'string' ? p.captureId.slice(0, 80) : '', cancelled: false };
  }

  async function shortcut() {
    try {
      const all = await chromeApi.commands.getAll();
      const c = all.find((x) => x.name === SHORTCUT);
      return c && c.shortcut ? c.shortcut : '';
    } catch (e) {
      return '';
    }
  }

  /** Gesto do usuário no Chrome (chrome.commands). Sem armação válida para ESTA aba: nada é capturado. */
  async function onShortcut(command, tab) {
    if (command !== SHORTCUT) return { ignored: true };
    const a = armed;
    if (!a || running) return { ignored: true };
    if (!tab || tab.id !== a.tabId) {
      disarm('TAB_MISMATCH');
      return { failed: 'TAB_MISMATCH' };
    }
    if (now() > a.expiresAt) {
      disarm('ARM_EXPIRED');
      return { failed: 'ARM_EXPIRED' };
    }
    armed = null; // uso único: daqui em diante o gesto foi consumido
    running = true;
    inflight = { captureId: a.captureId, controller: a.controller, tabId: a.tabId, windowId: tab.windowId, switched: false, cancelled: false };
    try {
      const out = await shoot(a, tab);
      await sameTab(); // conferência final: cancelamento ou troca durante a sanitização → nada é publicado
      postEvent(a.controller, out);
      return { captured: out.status === 'CAPTURED' };
    } catch (e) {
      const code = e && FAILURES.has(e.code) ? e.code : 'INTERNAL';
      failed(a, code);
      return { failed: code };
    } finally {
      running = false;
      inflight = null;
      badge(a.tabId, '');
      toContent(a.tabId, 'cs.pdd.release', {}, 0).catch(() => {});
    }
  }

  /**
   * A aba armada continua sendo EXATAMENTE a aba ativa da janela do gesto, e nenhuma troca de aba aconteceu nessa
   * janela desde o gesto (uma ida e volta A→B→A também conta: não se sabe qual aba a captura pegou)?
   */
  async function sameTab() {
    const f = inflight;
    if (!f) throw codeError('INTERNAL');
    if (f.cancelled) throw codeError('CANCELLED');
    if (f.switched) throw codeError('ACTIVE_TAB_CHANGED');
    const t = await chromeApi.tabs.get(f.tabId).catch(() => null);
    if (!t) throw codeError('TAB_CLOSED');
    if (!t.active || t.windowId !== f.windowId) throw codeError('ACTIVE_TAB_CHANGED');
    const act = await chromeApi.tabs.query({ active: true, windowId: f.windowId }).catch(() => null);
    if (!Array.isArray(act) || act.length !== 1 || act[0].id !== f.tabId) throw codeError('ACTIVE_TAB_CHANGED');
    if (f.cancelled) throw codeError('CANCELLED');
    if (f.switched) throw codeError('ACTIVE_TAB_CHANGED');
  }

  async function shoot(a, tab) {
    await sameTab();
    const url = await policy.tabUrl(a.tabId).catch(() => null);
    if (policy.evaluate(url).kind !== 'LAB') throw codeError('VISUAL_CAPTURE_NOT_ALLOWED');
    // 1. alvos das etapas pelo motor (somente leitura, por identidade — nunca por localizador)
    const resolved = new Map();
    let cap = null;
    try {
      cap = await currentCapture(a.tabId);
    } catch (e) {
      cap = null;
    }
    for (const s of a.steps) {
      if (!s.targetId && !s.descriptor) {
        resolved.set(s.stepRef, { reason: 'NO_TARGET' });
        continue;
      }
      if (!cap) {
        resolved.set(s.stepRef, { reason: 'RESOLUTION_FAILED' });
        continue;
      }
      try {
        const r = await host('debug.resolve', { tabKey: tabKey(a.tabId), captureId: cap.captureId, targetId: s.targetId, actionType: s.actionType,
          descriptor: s.descriptor }, { timeoutMs: 10000 });
        if (r.status === 'RESOLVED' && r.resolvedNodeId) {
          resolved.set(s.stepRef, frameOfNode(r.resolvedNodeId) === 0 ? { nodeId: r.resolvedNodeId } : { reason: 'TARGET_IN_FRAME' });
        } else {
          resolved.set(s.stepRef, { reason: OMIT.has(r.status) ? r.status : 'RESOLUTION_FAILED' });
        }
      } catch (e) {
        resolved.set(s.stepRef, { reason: 'RESOLUTION_FAILED' });
      }
    }
    // 2. plano visual + retângulos dos alvos, na MESMA ficha de layout
    const plan = await toContent(a.tabId, 'cs.pdd.plan', {}, 0).catch(() => null);
    if (!plan || !plan.ok) throw codeError('CONTENT_UNAVAILABLE');
    const nodeIds = [...resolved.values()].filter((x) => x.nodeId).map((x) => x.nodeId);
    const rects = nodeIds.length ? await toContent(a.tabId, 'cs.pdd.rects', { nodeIds }, 0).catch(() => null) : { ok: true, rects: [], token: plan.token };
    if (!rects || !rects.ok || rects.token !== plan.token) throw codeError('PAGE_CHANGED_DURING_CAPTURE');
    // 3. a imagem bruta: só aqui dentro, entre duas conferências da identidade da aba
    let raw = null;
    let decoded = null;
    try {
      await sameTab();
      try {
        raw = await chromeApi.tabs.captureVisibleTab(tab.windowId, { format: 'png' });
      } catch (e) {
        throw codeError('CAPTURE_DENIED');
      }
      await sameTab(); // falhou → o finally descarta a imagem bruta sem decodificar
      const after = await toContent(a.tabId, 'cs.pdd.token', {}, 0).catch(() => null);
      if (!after || after.token !== plan.token) throw codeError('PAGE_CHANGED_DURING_CAPTURE');
      decoded = await imaging.decode(raw);
      raw = null;
      const regions = deviceRegions(plan, decoded.width, decoded.height);
      const pixels = compose(decoded.pixels, decoded.width, decoded.height, regions);
      decoded.release();
      decoded = { width: decoded.width, height: decoded.height, release() {} };
      if (violations(pixels, decoded.width, decoded.height, regions) !== 0) throw codeError('SANITIZATION_CHECK_FAILED');
      const png = await imaging.encode(pixels, decoded.width, decoded.height);
      if (png.length > MAX_PNG_BYTES) throw codeError('SANITIZED_IMAGE_TOO_LARGE');
      const vw = plan.viewport.width;
      const vh = plan.viewport.height;
      const byNode = new Map((rects.rects || []).map((r) => [r.nodeId, r]));
      const annotations = a.steps.map((s) => {
        const res = resolved.get(s.stepRef) || { reason: 'RESOLUTION_FAILED' };
        const r = res.nodeId ? byNode.get(res.nodeId) : null;
        if (!r || r.reason) {
          return { stepRef: s.stepRef, status: 'OMITTED', x: 0, y: 0, width: 0, height: 0, reason: r && r.reason ? r.reason : res.reason || 'RESOLUTION_FAILED' };
        }
        return { stepRef: s.stepRef, status: 'PLACED', x: round6(r.x / vw), y: round6(r.y / vh), width: round6(r.width / vw), height: round6(r.height / vh),
          reason: '' };
      });
      const limitations = [];
      if (plan.rejected && Object.keys(plan.rejected).length) limitations.push('SOME_LABELS_HIDDEN');
      if (plan.hasFrames) limitations.push('FRAMES_HIDDEN');
      return {
        captureId: a.captureId, status: 'CAPTURED', policy: VISUAL_POLICY, observedAt: new Date(now()).toISOString(),
        width: decoded.width, height: decoded.height, devicePixelRatio: plan.viewport.dpr,
        png: base64(png), sha256: await sha256(png),
        regions: [...regions.carriers.map((c) => ({ kind: 'CARRIER', x: c.x, y: c.y, width: c.x2 - c.x, height: c.y2 - c.y })),
          ...regions.fields.map((f) => ({ kind: 'FIELD', x: f.x, y: f.y, width: f.x2 - f.x, height: f.y2 - f.y }))],
        annotations, limitations,
      };
    } finally {
      raw = null;
      if (decoded) decoded.release();
      decoded = null;
    }
  }

  /** Navegação/aba fechada/permissão: a armação não vale mais. */
  function onPageChanged(tabId) {
    if (armed && armed.tabId === tabId) disarm('PAGE_CHANGED');
  }

  function onTabRemoved(tabId) {
    if (armed && armed.tabId === tabId) disarm('TAB_CLOSED');
    if (inflight && inflight.tabId === tabId) inflight.switched = true;
  }

  /** chrome.tabs.onActivated: qualquer troca de aba na janela da captura em andamento a invalida. */
  function onTabActivated(info) {
    if (inflight && info && (info.windowId === inflight.windowId || info.tabId === inflight.tabId)) inflight.switched = true;
  }

  /** chrome.tabs.onDetached/onAttached: a aba armada mudou de janela. */
  function onTabMoved(tabId) {
    if (inflight && inflight.tabId === tabId) inflight.switched = true;
  }

  function checkLease() {
    if (armed && now() > armed.expiresAt) disarm('ARM_EXPIRED');
  }

  function state() {
    return { armed: !!armed, running, captureId: armed ? armed.captureId : inflight ? inflight.captureId : null };
  }

  return { COMMANDS, onShortcut, onPageChanged, onTabRemoved, onTabActivated, onTabMoved, checkLease, state };
}

function round6(v) {
  return Math.round(Math.min(1, Math.max(0, v)) * 1e6) / 1e6;
}

function base64(bytes) {
  let s = '';
  for (let i = 0; i < bytes.length; i += 0x8000) {
    s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
  }
  return btoa(s);
}

async function sha256(bytes) {
  const d = new Uint8Array(await crypto.subtle.digest('SHA-256', bytes));
  return 'sha256:' + Array.from(d, (b) => b.toString(16).padStart(2, '0')).join('');
}

/** PNG ⇄ pixels no service worker (OffscreenCanvas). Os canvases são zerados e descartados depois do uso. */
function browserImaging() {
  return {
    async decode(dataUrl) {
      const blob = await (await fetch(dataUrl)).blob();
      const bmp = await createImageBitmap(blob);
      const c = new OffscreenCanvas(bmp.width, bmp.height);
      const ctx = c.getContext('2d', { willReadFrequently: true });
      ctx.drawImage(bmp, 0, 0);
      const img = ctx.getImageData(0, 0, bmp.width, bmp.height);
      const out = { width: bmp.width, height: bmp.height, pixels: img.data,
        release() {
          ctx.clearRect(0, 0, c.width, c.height);
          img.data.fill(0);
          c.width = 1;
          c.height = 1;
        } };
      bmp.close();
      return out;
    },
    async encode(pixels, width, height) {
      const c = new OffscreenCanvas(width, height);
      const ctx = c.getContext('2d');
      ctx.putImageData(new ImageData(pixels, width, height), 0, 0);
      const blob = await c.convertToBlob({ type: 'image/png' });
      return new Uint8Array(await blob.arrayBuffer());
    },
  };
}
