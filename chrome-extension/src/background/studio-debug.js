/*
 * Studio Guided Debug (STUDIO-009, capacidade guided-debug/1) — o Studio pede, a extensão INSPECIONA e DESTACA.
 *
 * Uma sessão de debug é um consumidor próprio ("studio-debug:<debugId>") da CaptureSession de UMA aba autorizada (LAB ou
 * AUTHORIZED). Comandos:
 *   start     abre a sessão (concessão renovável, como a gravação);
 *   inspect   reencontra o alvo de uma etapa pelo motor (debug.resolve — SOMENTE LEITURA: identidade registrada ou
 *             descritor) e destaca em rosa pelo overlay (pointer-events:none). Só RESOLVED recebe o destaque sólido; um
 *             único candidato plausível recebe o tracejado "requer revisão"; vários candidatos, nenhum;
 *   highlight destaca um candidato de uma inspeção da MESMA época, ou limpa;
 *   pick      START arma o inspetor (o clique é interceptado na fase de captura: a ação do elemento NÃO acontece);
 *             CANCEL desarma; ADOPT_CANDIDATE descreve um candidato. O resultado chega no evento studio.debug.picked;
 *   finish    limpa o destaque, desarma e libera o consumidor.
 *
 * Nada aqui clica, preenche, navega, envia ou executa JavaScript da página. Navegação/substituição de documento muda a
 * época (pageEpoch): candidatos e resultados anteriores ficam velhos (CANDIDATE_EXPIRED). Aba fechada, permissão
 * revogada e concessão vencida encerram a sessão; o reinício do service worker a deixa DEGRADED (EXTENSION_RESTARTED).
 * Estado mínimo em chrome.storage.session (ids, estados, contadores) — nada da página.
 */
import { HostError } from './native-client.js';

const PROTOCOL = 'nekkora-nm/1';
const KEY = 'nk.sdbg.';
const ID = /^[A-Za-z0-9._:-]{1,80}$/;
const TAB_REF = /^t-[A-Za-z0-9]{8,40}$/;
const ACTIONS = new Set(['CLICK', 'FILL', 'SELECT_OPTION', 'CHECK', 'UNCHECK', 'PRESS_KEY', 'WAIT_FOR', 'OBSERVE', 'UNKNOWN']);
const STRATEGIES = new Set(['TEST_ATTRIBUTE', 'ID', 'NAME', 'LABEL', 'ROLE_NAME', 'CONTEXTUAL', 'ROW_CONTEXT', 'ATTRIBUTE', 'TEXT', 'XPATH_RELATIVE',
  'POSITIONAL']);
const PATH_TYPES = new Set(['MAIN_DOCUMENT', 'IFRAME', 'SHADOW_ROOT', 'ELEMENT']);
const STATUSES = new Set(['RESOLVED', 'NOT_FOUND', 'AMBIGUOUS', 'IDENTITY_MISMATCH', 'STALE_TARGET', 'SCOPE_UNAVAILABLE', 'INSUFFICIENT_EVIDENCE']);
const REASONS = new Set(['TARGET_UNKNOWN_IN_REGISTRY', 'DESCRIPTOR_ONLY', 'DESCRIPTOR_CANNOT_CROSS_SCOPE', 'INDISTINGUISHABLE_AT_REGISTRATION',
  'LOOKALIKES_PRESENT', 'NODE_REPLACED', 'ID_ATTRIBUTE_CHANGED', 'NAME_ATTRIBUTE_CHANGED', 'KIND_INCOMPATIBLE_AT_ADDRESS', 'FRAME_NOT_FOUND',
  'FRAME_AMBIGUOUS', 'FRAME_DOCUMENT_CHANGED', 'FRAME_PERMISSION_DENIED', 'FRAME_SANDBOX_RESTRICTED', 'FRAME_LOADING', 'SHADOW_ROOT_CLOSED',
  'SHADOW_HOST_NOT_FOUND', 'TARGET_NOT_MATERIALIZED', 'TARGET_AMBIGUOUS', 'HIDDEN', 'OUT_OF_VIEWPORT', 'DISABLED', 'READ_ONLY', 'INACCESSIBLE',
  'NOT_EDITABLE', 'KIND_UNUSUAL_FOR_ACTION', 'PAGE_CHANGED_DURING_CHECK', 'CAPTURE_UNAVAILABLE', 'HIGHLIGHT_FAILED']);
const CHECK_KEYS = ['identity', 'unique', 'visible', 'enabled', 'editable', 'kindMatchesAction', 'frameReachable', 'shadowReachable'];
const CHECK_VALUES = new Set(['PASS', 'FAIL', 'UNKNOWN', 'NOT_APPLICABLE']);
const CONTINUITY = new Set(['LIVE_NODE_CONTINUITY', 'CONTINUITY_BROKEN', 'NOT_PROVEN']);
const KIND = /^[A-Z_]{1,40}$/;
const REQUEST_RETENTION = 50;
const OPS_RETENTION = 20;

/** Rótulos curtos do destaque (desenhados DENTRO da página pelo overlay; nunca atravessam limite). Só códigos. */
const LABELS = { confirmed: 'Nekkora · alvo da etapa', review: 'Nekkora · requer revisão', candidate: 'Nekkora · candidato' };

/**
 * Fábrica (dependências injetáveis para os testes em Node).
 * deps.tabs = { tabOf(tabRef) → tabId|null, refOf(tabId) → tabRef, recordingOn(tabId) → boolean, title(tab), host(url) }.
 */
export function createStudioDebug(deps) {
  const { client, broadcast, capture, policy, steps, tabs } = deps;
  const chromeApi = deps.chrome || globalThis.chrome;
  const host = deps.host;
  const toContent = deps.toContent;
  const tabKey = deps.tabKey || ((tabId) => 'tab-' + tabId);
  const frameOfNode = deps.frameOfNode || (() => 0);
  const currentCapture = deps.currentCapture;
  const sessions = new Map(); // debugId -> sessão
  const timers = new Map();

  const COMMANDS = {
    'studio.debug.start': start,
    'studio.debug.inspect': inspect,
    'studio.debug.highlight': highlight,
    'studio.debug.pick': pick,
    'studio.debug.finish': finish,
    'studio.debug.status': status,
    'studio.debug.renew': renew,
  };

  function now() {
    return deps.now ? deps.now() : Date.now();
  }

  function fail(code, message, extra = {}) {
    const e = new HostError(code, message);
    Object.assign(e, extra);
    return e;
  }

  function consumerOf(debugId) {
    return 'studio-debug:' + debugId;
  }

  function persist(s) {
    const { requests, ops, candidates, ...min } = s; // resultados e candidatos não precisam sobreviver ao reinício
    return chromeApi.storage.session.set({ [KEY + s.debugId]: { ...min, candidates: {} } }).catch(() => {});
  }

  function view(s) {
    return {
      debugId: s.debugId,
      protocol: 'guided-debug/1',
      state: s.state,
      tab: { tabRef: s.tabRef, title: s.title, host: s.host },
      pageEpoch: s.pageEpoch,
      picking: !!s.picking,
      lease: { leaseMs: s.leaseMs, expiresInMs: s.state === 'ENDED' ? 0 : Math.max(0, s.leaseExpiresAt - now()) },
      reason: s.reason || null,
    };
  }

  function postEvent(s, event, payload) {
    broadcast('studio-debug', { tabId: s.tabId, event, state: s.state, pageEpoch: s.pageEpoch });
    if (!s.controller) {
      return;
    }
    if (typeof client.status !== 'function' || client.status().connected) {
      client.post({ protocol: PROTOCOL, type: 'studio.event', event, payload, controller: { instanceId: s.controller } }).catch(() => {});
    }
  }

  function changed(s, reason) {
    s.reason = reason;
    persist(s);
    postEvent(s, 'studio.debug.changed', { debug: view(s) });
  }

  // ------------------------------------------------------------------------------------------ validação

  function assertOnly(payload, allowed) {
    if (!payload || typeof payload !== 'object' || Array.isArray(payload)) {
      throw fail('BAD_REQUEST', 'payload inválido');
    }
    for (const k of Object.keys(payload)) {
      if (!allowed.includes(k)) {
        throw fail('BAD_REQUEST', 'campo não permitido: ' + k);
      }
    }
  }

  function assertId(v, field) {
    if (typeof v !== 'string' || !ID.test(v)) {
      throw fail('BAD_REQUEST', field + ' inválido');
    }
  }

  function owned(debugId, controller) {
    assertId(debugId, 'debugId');
    const s = sessions.get(debugId);
    if (!s) {
      throw fail('DEBUG_NOT_FOUND', 'Sessão de debug inexistente.');
    }
    if (s.controller !== controller) {
      throw fail('NOT_CONTROLLER', 'A sessão de debug pertence a outra instância do Studio.');
    }
    return s;
  }

  function live(s) {
    if (s.state === 'ENDED') {
      throw fail('DEBUG_ENDED', 'A sessão de debug foi encerrada.', { reason: s.reason ? String(s.reason) : undefined });
    }
    return s;
  }

  function descriptorOf(d) {
    assertOnly(d, ['kind', 'tag', 'name', 'htmlId', 'htmlName', 'locators', 'accessPath']);
    if (typeof d.kind !== 'string' || !KIND.test(d.kind) || !Array.isArray(d.locators) || d.locators.length > 5 || !Array.isArray(d.accessPath)
      || d.accessPath.length > 12) {
      throw fail('BAD_REQUEST', 'descritor inválido');
    }
    const str = (v, max) => (v == null ? null : typeof v === 'string' && v.length <= max ? v : (() => { throw fail('BAD_REQUEST', 'descritor inválido'); })());
    return {
      kind: d.kind,
      tag: str(d.tag, 41) || 'unknown',
      name: str(d.name, 120) || '',
      htmlId: str(d.htmlId, 120),
      htmlName: str(d.htmlName, 120),
      locators: d.locators.map((l) => {
        assertOnly(l, ['strategy', 'css', 'xpath', 'description']);
        if (!STRATEGIES.has(l.strategy)) {
          throw fail('BAD_REQUEST', 'estratégia inválida');
        }
        return { strategy: l.strategy, css: str(l.css, 500), xpath: str(l.xpath, 500), description: str(l.description, 200) || '' };
      }),
      accessPath: d.accessPath.map((p) => {
        assertOnly(p, ['type', 'locator']);
        if (!PATH_TYPES.has(p.type)) {
          throw fail('BAD_REQUEST', 'trajetória inválida');
        }
        return { type: p.type, locator: str(p.locator, 500) };
      }),
    };
  }

  // ---------------------------------------------------------------------------------------- concessão

  function armLease(s) {
    clearTimeout(timers.get(s.debugId));
    if (s.state === 'ENDED') {
      return;
    }
    timers.set(s.debugId, setTimeout(() => {
      if (s.state !== 'ENDED' && now() >= s.leaseExpiresAt) {
        end(s, 'LEASE_EXPIRED').catch(() => {});
      }
    }, Math.max(0, s.leaseExpiresAt - now()) + 50));
  }

  function checkLeases() {
    for (const s of sessions.values()) {
      if (s.state !== 'ENDED' && now() >= s.leaseExpiresAt) {
        end(s, 'LEASE_EXPIRED').catch(() => {});
      }
    }
  }

  // ---------------------------------------------------------------------------------------- comandos

  async function start(p, controller) {
    assertOnly(p, ['debugId', 'operationId', 'tabRef', 'leaseMs', 'capabilities']);
    assertId(p.debugId, 'debugId');
    assertId(p.operationId, 'operationId');
    if (typeof p.tabRef !== 'string' || !TAB_REF.test(p.tabRef)) {
      throw fail('BAD_REQUEST', 'tabRef inválido');
    }
    if (!Number.isInteger(p.leaseMs) || p.leaseMs < 5000 || p.leaseMs > 300000) {
      throw fail('BAD_REQUEST', 'leaseMs inválido');
    }
    if (!Array.isArray(p.capabilities) || !p.capabilities.includes('guided-debug/1') || p.capabilities.some((c) => c !== 'guided-debug/1')) {
      throw fail('BAD_REQUEST', 'capacidade guided-debug/1 ausente');
    }
    const existing = sessions.get(p.debugId);
    if (existing) {
      if (existing.controller !== controller) {
        throw fail('NOT_CONTROLLER', 'A sessão de debug pertence a outra instância do Studio.');
      }
      if (existing.ops.has(p.operationId)) {
        return { debug: view(existing), duplicate: true };
      }
      throw fail('OPERATION_CONFLICT', 'debugId já usado.');
    }
    const tabId = tabs.tabOf(p.tabRef);
    const tab = tabId == null ? null : await chromeApi.tabs.get(tabId).catch(() => null);
    if (!tab) {
      throw fail('TAB_NOT_FOUND', 'Aba não encontrada.');
    }
    const url = await policy.tabUrl(tabId).catch(() => null);
    const v = policy.evaluate(url);
    if (!v.allowed) {
      throw fail('ORIGIN_NOT_ALLOWED', 'A extensão não tem acesso a esta página.', { reason: v.reason });
    }
    if (tabs.recordingOn(tabId)) {
      throw fail('INVALID_STATE', 'Há uma gravação do Studio nesta aba: o debug guiado espera a gravação terminar.', { reason: 'RECORDING_ACTIVE' });
    }
    if (Array.from(sessions.values()).some((x) => x.tabId === tabId && x.state !== 'ENDED')) {
      throw fail('INVALID_STATE', 'Já existe uma sessão de debug nesta aba.', { reason: 'DEBUG_ACTIVE' });
    }
    await capture.acquire(tabId, consumerOf(p.debugId)).catch((e) => {
      throw fail('CAPTURE_FAILED', 'Não foi possível observar a página.', { reason: e && e.code ? String(e.code).slice(0, 60) : undefined });
    });
    const s = {
      debugId: p.debugId, tabId, tabRef: p.tabRef, title: tabs.title(tab), host: tabs.host(url), controller, state: 'ACTIVE', reason: null,
      pageEpoch: 1, picking: false, pickRequestId: null, pickAction: null, leaseMs: p.leaseMs, leaseExpiresAt: now() + p.leaseMs,
      startedAt: now(), requests: new Map(), ops: new Map(), candidates: new Map(), candidateSeq: 0, inspections: 0,
    };
    sessions.set(s.debugId, s);
    s.ops.set(p.operationId, true);
    armLease(s);
    persist(s);
    broadcast('studio-debug', { tabId, event: 'started', state: s.state });
    return { debug: view(s), duplicate: false };
  }

  function remember(s, requestId, result) {
    s.requests.set(requestId, result);
    while (s.requests.size > REQUEST_RETENTION) {
      s.requests.delete(s.requests.keys().next().value);
    }
    return result;
  }

  function candidateRef(s, nodeId, captureId) {
    s.candidateSeq++;
    const ref = 'cand-' + s.pageEpoch + '-' + s.candidateSeq;
    s.candidates.set(ref, { nodeId, captureId, pageEpoch: s.pageEpoch });
    return ref;
  }

  async function viewportGeometry(tabId) {
    return toContent(tabId, 'cs.debug.viewport', {}, 0).catch(() => null);
  }

  /** Destaca um nó e devolve a caixa (px do viewport + posição aproximada na tela); null se o nó não está mais conectado. */
  async function mark(s, nodeId, style) {
    const frame = frameOfNode(nodeId);
    await unmark(s);
    const r = await toContent(s.tabId, 'cs.debug.mark', { nodeId, style, label: LABELS[style] }, frame).catch(() => null);
    if (!r || !r.ok) {
      return null;
    }
    s.marked = { nodeId, frame };
    const vp = await viewportGeometry(s.tabId);
    const b = r.box;
    const sx = vp ? vp.screenX + vp.chromeLeft : 0;
    const sy = vp ? vp.screenY + vp.chromeTop : 0;
    return {
      x: Math.round(b.x), y: Math.round(b.y), width: Math.max(0, Math.round(b.width)), height: Math.max(0, Math.round(b.height)),
      viewportWidth: Math.max(0, Math.round(vp ? vp.innerWidth : b.viewportWidth)), viewportHeight: Math.max(0, Math.round(vp ? vp.innerHeight : b.viewportHeight)),
      devicePixelRatio: Math.max(0, Number(vp ? vp.devicePixelRatio : 1) || 1), screenX: Math.round(sx + b.x), screenY: Math.round(sy + b.y),
    };
  }

  async function unmark(s) {
    const frames = [0, ...(s.marked && s.marked.frame ? [s.marked.frame] : [])];
    for (const f of new Set(frames)) {
      await toContent(s.tabId, 'cs.debug.unmark', {}, f).catch(() => {});
    }
    s.marked = null;
  }

  function clean(list, allowed) {
    return Array.from(new Set((list || []).filter((x) => allowed.has(x))));
  }

  function checksOf(c) {
    const out = {};
    for (const k of CHECK_KEYS) {
      out[k] = c && CHECK_VALUES.has(c[k]) ? c[k] : 'UNKNOWN';
    }
    return out;
  }

  async function inspect(p, controller) {
    assertOnly(p, ['debugId', 'requestId', 'stepRef', 'actionType', 'targetId', 'descriptor', 'highlight']);
    const s = live(owned(p.debugId, controller));
    assertId(p.requestId, 'requestId');
    assertId(p.stepRef, 'stepRef');
    if (!ACTIONS.has(p.actionType)) {
      throw fail('BAD_REQUEST', 'actionType inválido');
    }
    if (p.targetId !== null && p.targetId !== undefined) {
      assertId(p.targetId, 'targetId');
    }
    if (s.requests.has(p.requestId)) {
      return s.requests.get(p.requestId); // idempotência: mesmo pedido, mesma resposta, nada repetido
    }
    const descriptor = descriptorOf(p.descriptor);
    const epoch = s.pageEpoch;
    s.inspections++;
    let r;
    let cap;
    try {
      cap = await currentCapture(s.tabId);
      r = await host('debug.resolve', { tabKey: tabKey(s.tabId), captureId: cap.captureId, targetId: p.targetId || null, actionType: p.actionType,
        descriptor }, { timeoutMs: 10000 });
    } catch (e) {
      const out = {
        debug: view(s), requestId: p.requestId, stepRef: p.stepRef, observedAt: new Date(now()).toISOString(), pageEpoch: s.pageEpoch,
        identitySource: p.targetId ? 'TARGET_ID' : 'DESCRIPTOR', status: 'CONTEXT_UNAVAILABLE', score: 0, reasons: ['CAPTURE_UNAVAILABLE'],
        checks: checksOf({ identity: 'UNKNOWN', unique: 'UNKNOWN', visible: 'UNKNOWN', enabled: 'UNKNOWN', editable: 'UNKNOWN', kindMatchesAction: 'UNKNOWN',
          frameReachable: 'UNKNOWN', shadowReachable: 'UNKNOWN' }), element: null, candidates: [], highlighted: false, box: null, continuity: null,
      };
      await unmark(s);
      return remember(s, p.requestId, out); // nada é promovido: contexto indisponível
    }
    const reasons = clean(r.reasons, REASONS);
    if (s.pageEpoch !== epoch || s.state === 'ENDED') {
      reasons.push('PAGE_CHANGED_DURING_CHECK'); // a página mudou no meio: o resultado não vale para o documento atual
    }
    const status = s.pageEpoch !== epoch ? 'CONTEXT_UNAVAILABLE' : STATUSES.has(r.status) ? r.status : 'CONTEXT_UNAVAILABLE';
    const candidates = (r.candidates || []).slice(0, 5).map((c) => ({
      candidateRef: candidateRef(s, c.nodeId, cap.captureId),
      verdict: c.verdict === 'CONFIRMED' ? 'CONFIRMED' : 'VIABLE',
      score: Math.min(1, Math.max(0, Number(c.score) || 0)),
      kind: KIND.test(c.kind || '') ? c.kind : 'UNKNOWN',
      name: steps.interfaceName(c.name),
      chosen: !!c.chosen,
    }));
    let highlighted = false;
    let box = null;
    const subject = r.subjectNodeId || null;
    if (p.highlight && status !== 'CONTEXT_UNAVAILABLE' && subject) {
      box = await mark(s, subject, status === 'RESOLVED' ? 'confirmed' : 'review');
      highlighted = !!box;
      if (!box) {
        reasons.push('HIGHLIGHT_FAILED');
      }
    } else {
      await unmark(s);
    }
    const chosen = subject ? (r.candidates || []).find((c) => c.nodeId === subject) : null;
    const out = {
      debug: view(s), requestId: p.requestId, stepRef: p.stepRef, observedAt: new Date(now()).toISOString(), pageEpoch: epoch,
      identitySource: r.identitySource === 'TARGET_ID' ? 'TARGET_ID' : 'DESCRIPTOR', status, score: Math.min(1, Math.max(0, Number(r.score) || 0)),
      reasons: clean(reasons, REASONS).slice(0, 16), checks: checksOf(r.checks),
      element: chosen ? { kind: KIND.test(chosen.kind || '') ? chosen.kind : 'UNKNOWN', name: steps.interfaceName(chosen.name),
        tag: r.subjectTag && /^[a-z][a-z0-9-]{0,40}$/.test(r.subjectTag) ? r.subjectTag : 'unknown', sensitive: !!r.subjectSensitive } : null,
      candidates, highlighted, box, continuity: CONTINUITY.has(r.continuity) ? r.continuity : null,
    };
    persist(s);
    return remember(s, p.requestId, out);
  }

  async function highlight(p, controller) {
    assertOnly(p, ['debugId', 'requestId', 'candidateRef']);
    const s = live(owned(p.debugId, controller));
    assertId(p.requestId, 'requestId');
    if (s.requests.has(p.requestId)) {
      return s.requests.get(p.requestId);
    }
    if (p.candidateRef === null) {
      await unmark(s);
      return remember(s, p.requestId, { debug: view(s), requestId: p.requestId, highlighted: false, box: null });
    }
    assertId(p.candidateRef, 'candidateRef');
    const c = s.candidates.get(p.candidateRef);
    if (!c || c.pageEpoch !== s.pageEpoch) {
      throw fail('CANDIDATE_EXPIRED', 'O candidato pertence a uma página anterior: inspecione de novo.');
    }
    const box = await mark(s, c.nodeId, 'candidate');
    if (!box) {
      throw fail('CANDIDATE_EXPIRED', 'O candidato não está mais na página.');
    }
    return remember(s, p.requestId, { debug: view(s), requestId: p.requestId, highlighted: true, box });
  }

  async function startInspector(s) {
    const live = capture.get(s.tabId);
    const frames = [0, ...(live ? Object.values(live.frames || {}).filter((f) => f.status === 'ATTACHED').map((f) => f.frameId) : [])];
    for (const f of frames) {
      await toContent(s.tabId, 'cs.inspect.start', {}, f).catch((e) => {
        if (f === 0) {
          throw e;
        }
      });
    }
  }

  async function stopInspector(s) {
    const live = capture.get(s.tabId);
    const frames = [0, ...(live ? Object.values(live.frames || {}).filter((f) => f.status === 'ATTACHED').map((f) => f.frameId) : [])];
    for (const f of frames) {
      await toContent(s.tabId, 'cs.inspect.stop', {}, f).catch(() => {});
    }
  }

  async function pick(p, controller) {
    assertOnly(p, ['debugId', 'requestId', 'action', 'candidateRef', 'actionType']);
    const s = live(owned(p.debugId, controller));
    assertId(p.requestId, 'requestId');
    if (!ACTIONS.has(p.actionType)) {
      throw fail('BAD_REQUEST', 'actionType inválido');
    }
    if (s.requests.has(p.requestId)) {
      return s.requests.get(p.requestId);
    }
    if (p.action === 'START') {
      await unmark(s);
      await startInspector(s).catch((e) => {
        throw fail('CAPTURE_FAILED', 'Não foi possível armar a seleção nesta página.', { reason: 'INSPECTOR_UNAVAILABLE' });
      });
      s.picking = true;
      s.pickRequestId = p.requestId;
      s.pickAction = p.actionType;
      persist(s);
      return remember(s, p.requestId, { debug: view(s), requestId: p.requestId, picking: true });
    }
    if (p.action === 'CANCEL') {
      s.picking = false;
      s.pickRequestId = null;
      await stopInspector(s);
      persist(s);
      return remember(s, p.requestId, { debug: view(s), requestId: p.requestId, picking: false });
    }
    if (p.action === 'ADOPT_CANDIDATE') {
      assertId(p.candidateRef, 'candidateRef');
      const c = s.candidates.get(p.candidateRef);
      if (!c || c.pageEpoch !== s.pageEpoch) {
        throw fail('CANDIDATE_EXPIRED', 'O candidato pertence a uma página anterior: inspecione de novo.');
      }
      const result = remember(s, p.requestId, { debug: view(s), requestId: p.requestId, picking: false });
      describeAndEmit(s, c.nodeId, p.requestId, p.actionType, 'ADOPTED_CANDIDATE').catch(() => {});
      return result;
    }
    throw fail('BAD_REQUEST', 'action inválida');
  }

  /** Descreve o nó escolhido com as MESMAS barreiras de uma etapa gravada e emite studio.debug.picked. */
  async function describeAndEmit(s, nodeId, requestId, actionType, origin) {
    const epoch = s.pageEpoch;
    const cap = await currentCapture(s.tabId);
    const d = await host('recorder.element.describe', { tabKey: tabKey(s.tabId), captureId: cap.captureId, nodeId }, { timeoutMs: 8000 });
    let validation = d.locators.map((l, i) => (i === d.preferredLocator ? 'SNAPSHOT' : (l.matchCount === 1 && l.matchesTarget ? 'SNAPSHOT' : 'NONE')));
    const notes = [...(d.notes || [])];
    try {
      const liveChecks = await toContent(s.tabId, 'cs.verifyLocators', { nodeId, locators: d.verification }, frameOfNode(nodeId));
      const verdict = await host('recorder.element.verify', { tabKey: tabKey(s.tabId), captureId: d.captureId, nodeId, checks: liveChecks.checks },
        { timeoutMs: 5000 });
      validation = validation.map((v, i) => {
        const row = (verdict.checks || []).find((c) => c.index === i);
        return row && row.liveUnique ? 'LIVE' : v;
      });
    } catch (e) {
      notes.push('LIVE_CHECK_UNAVAILABLE');
    }
    const c = { d, validation, notes: [] };
    const element = steps.elementRefOf({ descriptor: {}, interaction: { sensitive: false } }, c);
    const state = await host('debug.node.state', { tabKey: tabKey(s.tabId), captureId: d.captureId, nodeId, actionType }, { timeoutMs: 5000 })
      .catch(() => null);
    const box = await mark(s, nodeId, 'confirmed');
    if (s.pageEpoch !== epoch || s.state === 'ENDED') {
      return; // a página mudou antes de terminar: nada é entregue como escolhido
    }
    const payload = {
      debugId: s.debugId, requestId, pageEpoch: epoch, observedAt: new Date(now()).toISOString(), origin, element,
      checks: checksOf(state ? state.checks : null), box,
    };
    const kept = clean([...notes, ...c.notes], new Set(['NO_UNIQUE_LOCATOR', 'INDISTINGUISHABLE_LOOKALIKES', 'ACCESS_RESTRICTED', 'LIVE_CHECK_UNAVAILABLE',
      'LOCATORS_WITHHELD', 'SAFE_LOCATOR_UNAVAILABLE']));
    if (kept.length) {
      payload.notes = kept.slice(0, 12);
    }
    postEvent(s, 'studio.debug.picked', payload);
  }

  async function end(s, reason) {
    if (s.state === 'ENDED') {
      return view(s);
    }
    s.state = 'ENDED';
    s.picking = false;
    clearTimeout(timers.get(s.debugId));
    if (reason !== 'TAB_CLOSED') {
      await stopInspector(s).catch(() => {});
      await unmark(s).catch(() => {});
    }
    await capture.release(s.tabId, consumerOf(s.debugId), 'STUDIO_DEBUG_' + reason).catch(() => {});
    changed(s, reason);
    return view(s);
  }

  async function finish(p, controller) {
    assertOnly(p, ['debugId', 'operationId']);
    const s = owned(p.debugId, controller);
    assertId(p.operationId, 'operationId');
    if (s.ops.has(p.operationId)) {
      return { debug: view(s), duplicate: true };
    }
    s.ops.set(p.operationId, true);
    while (s.ops.size > OPS_RETENTION) {
      s.ops.delete(s.ops.keys().next().value);
    }
    const already = s.state === 'ENDED';
    await end(s, 'FINISHED');
    return { debug: view(s), duplicate: already };
  }

  async function status(p, controller) {
    assertOnly(p, ['debugId']);
    return { debug: view(owned(p.debugId, controller)), duplicate: false };
  }

  async function renew(p, controller) {
    assertOnly(p, ['debugId']);
    const s = live(owned(p.debugId, controller));
    s.leaseExpiresAt = now() + s.leaseMs;
    armLease(s);
    persist(s);
    return { debug: view(s), duplicate: false };
  }

  // ------------------------------------------------------------------------------------ sinais da aba

  function active(tabId) {
    return Array.from(sessions.values()).find((s) => s.tabId === tabId && s.state !== 'ENDED') || null;
  }

  /** Clique interceptado pelo inspetor: se o debug armou a seleção nesta aba, o nó é dele (e só uma vez). */
  function onElementSelected(tabId, nodeId) {
    const s = active(tabId);
    if (!s || !s.picking) {
      return false;
    }
    s.picking = false;
    const requestId = s.pickRequestId;
    s.pickRequestId = null;
    stopInspector(s).catch(() => {});
    describeAndEmit(s, nodeId, requestId, s.pickAction || 'UNKNOWN', 'BROWSER_SELECTION').catch(() => {
      changed(s, s.reason); // a seleção falhou: o Studio vê o estado (picking=false) e pode tentar de novo
    });
    return true;
  }

  /** Navegação ou documento substituído: nova época; candidatos e destaque anteriores não valem mais. */
  function onPageChanged(tabId, reason) {
    const s = active(tabId);
    if (!s) {
      return;
    }
    s.pageEpoch++;
    s.candidates.clear();
    s.marked = null;
    if (s.picking) {
      s.picking = false;
      s.pickRequestId = null;
    }
    changed(s, reason);
  }

  async function onTabRemoved(tabId) {
    const s = active(tabId);
    if (s) {
      await end(s, 'TAB_CLOSED');
    }
  }

  async function onPermissionsRemoved() {
    for (const s of sessions.values()) {
      if (s.state === 'ENDED') {
        continue;
      }
      const url = await policy.tabUrl(s.tabId).catch(() => null);
      if (!policy.evaluate(url).allowed) {
        await end(s, 'PERMISSION_REVOKED');
      }
    }
  }

  function onRecordingStarted(tabId) {
    const s = active(tabId);
    if (s) {
      end(s, 'RECORDING_STARTED').catch(() => {});
    }
  }

  /** Reinício do service worker: o overlay e o inspetor podem ter sumido — a sessão volta DEGRADED, nunca "como estava". */
  async function restore() {
    try {
      const all = await chromeApi.storage.session.get(null);
      for (const [k, v] of Object.entries(all)) {
        if (k.startsWith(KEY) && v && v.debugId && !sessions.has(v.debugId)) {
          const s = { ...v, requests: new Map(), ops: new Map(), candidates: new Map(), marked: null };
          sessions.set(s.debugId, s);
          if (s.state !== 'ENDED') {
            s.state = 'DEGRADED';
            s.picking = false;
            s.pageEpoch++;
            changed(s, 'EXTENSION_RESTARTED');
            armLease(s);
          }
        }
      }
    } catch (e) {
      // armazenamento indisponível
    }
    return sessions.size;
  }

  function list() {
    return Array.from(sessions.values()).map((s) => ({ debugId: s.debugId, tabId: s.tabId, state: s.state, pageEpoch: s.pageEpoch, picking: s.picking,
      reason: s.reason, inspections: s.inspections }));
  }

  return { COMMANDS, onElementSelected, onPageChanged, onTabRemoved, onPermissionsRemoved, onRecordingStarted, restore, checkLeases, list, sessions,
    consumerOf };
}
