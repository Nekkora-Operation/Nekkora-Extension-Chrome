/*
 * Studio Recording Controller (STUDIO-002) — gravação TÉCNICA controlada pelo Nekkora Studio.
 *
 * Gravar é manter um consumidor próprio ("studio-recorder:<recordingId>") inscrito na CaptureSession da aba: a página
 * é observada de verdade (captura-base + deltas no motor Java). Com a capacidade steps/1 (STUDIO-003) os sensores de
 * interação também ficam ligados e cada interação consolidada vira uma etapa (studio-steps.js); sem ela, a gravação é
 * só técnica, como na STUDIO-002. Finalizar NÃO exporta PageMap (D-STUDIO-002-03).
 *
 *   start   ─► RECORDING ─pause─► PAUSED ─resume─► RECORDING
 *                 │  ▲                │
 *                 │  └──resume (recovered)── DEGRADED ◄── aba fechada / permissão revogada / captura em erro
 *                 ▼
 *   finish ─► COMPLETED      cancel ─► CANCELLED      concessão vencida ─► EXPIRED          (terminais)
 *
 * Garantias:
 * - comandos SOMENTE da lista explícita, com payload validado, vindos do controlador dono da gravação;
 * - todas as mutações são serializadas (uma fila única): finalizar × cancelar simultâneos têm um único desfecho;
 * - idempotência: o mesmo operationId devolve o mesmo resultado (duplicate=true) sem repetir efeitos; uma gravação
 *   nunca é iniciada duas vezes; no máximo uma gravação do Studio por aba;
 * - pausar libera SOMENTE o consumidor do Recorder: inspeção manual e gravações legadas continuam (otherConsumers);
 * - concessão temporária renovada pelo Studio (studio.recording.renew): sem renovação, a gravação expira e o
 *   consumidor é liberado — Studio travado ou encerrado abruptamente não deixa captura órfã;
 * - estado mínimo em chrome.storage.session (ids, estados, contadores; nada da página) — sobrevive ao reinício do
 *   service worker e é reconciliado.
 * Identificadores do Chrome não saem daqui: as abas são referidas por tabRef opaco.
 */
import { HostError } from './native-client.js';
import { createStepPipeline } from './studio-steps.js';
import { createEvidencePipeline } from './studio-evidence.js';
import '../shared/feedback-rules.js';

const PROTOCOL = 'nekkora-nm/1';
const KEY = 'nk.srec.';
const REFS_KEY = 'nk.tabrefs';
const LOCK = 'studio-recorder';
const MODES = new Set(['CAPTURE_ONLY', 'CAPTURE_AND_INTERACT']);
const CAPABILITIES = new Set(['steps/1', 'evidence/1']);
/** Limitações que tornam a captura de AÇÕES parcial (as demais descrevem só a captura técnica). */
const ACTION_LIMITS = new Set(['PARTIAL_COVERAGE', 'INTERRUPTED', 'NEVER_ATTACHED', 'STEPS_UNCONFIRMED', 'STEPS_REJECTED', 'INTERACTIONS_LOST',
  'STEP_BACKLOG_FULL']);
const TERMINAL = new Set(['COMPLETED', 'CANCELLED', 'EXPIRED', 'FAILED']);
const ID = /^[A-Za-z0-9._:-]{1,80}$/;
const TAB_REF = /^t-[A-Za-z0-9]{8,40}$/;
const TERMINAL_RETENTION = 20;
const OPS_RETENTION = 20;
const LEASE_ALARM = 'nekkora.studio.lease';

/**
 * Fábrica do controlador (dependências injetáveis para os testes em Node). A extensão usa a instância padrão, com o
 * cliente nativo, a CaptureSession, a política de acesso e as APIs reais do Chrome.
 */
export function createStudioRecorder(deps) {
  const { client, broadcast, withLock, capture, policy } = deps;
  const chromeApi = deps.chrome || globalThis.chrome;
  const errorInfo = deps.errorInfo;

  const recordings = new Map(); // recordingId -> registro
  const refToTab = new Map(); // tabRef -> tabId
  const tabToRef = new Map(); // tabId -> tabRef
  const releasing = new Set(); // tabIds cujo consumidor do Recorder está sendo liberado por nós (não é degradação)
  const leaseTimers = new Map();
  let ready = Promise.resolve();

  const COMMANDS = {
    'browser.tabs.list': listTabs,
    'studio.recording.start': start,
    'studio.recording.pause': pause,
    'studio.recording.resume': resume,
    'studio.recording.mode': setMode,
    'studio.recording.finish': finish,
    'studio.recording.cancel': cancel,
    'studio.recording.status': status,
    'studio.recording.renew': renew,
    'studio.recording.steps.ack': stepsAck,
    'studio.recording.steps.sync': stepsSync,
    'studio.recording.route': route,
    'studio.recording.evidence.ack': evidenceAck,
    'studio.recording.evidence.sync': evidenceSync,
  };
  /** Comandos atendidos fora da fila (só atualizam prazo/entrega; nunca esperam outra operação). */
  const UNQUEUED = new Set(['studio.recording.renew', 'browser.tabs.list', 'studio.recording.steps.ack', 'studio.recording.steps.sync',
    'studio.recording.evidence.ack', 'studio.recording.evidence.sync']);

  /** Evidências observadas (STUDIO-005): só com evidence/1, que exige steps/1 (a evidência pertence a uma etapa). */
  const evidence = createEvidencePipeline({
    now: () => now(),
    persist: (r) => persist(r),
    broadcast,
    emitEvidence: (r, payload) => postEvent(r, 'studio.recording.evidence', payload),
  });

  const steps = createStepPipeline({
    evidence,
    capture,
    toContent: deps.toContent || (() => Promise.reject(new Error('sem content'))),
    host: deps.host || (() => Promise.reject(new HostError('HOST_UNAVAILABLE', 'sem host'))),
    tabKey: deps.tabKey || ((tabId) => 'tab-' + tabId),
    frameOfNode: deps.frameOfNode || (() => 0),
    now: () => now(),
    sleep: deps.sleep,
    broadcast,
    persist: (r) => persist(r),
    emitEvent: (r, payload) => postEvent(r, 'studio.recording.step', payload),
    onBacklogFull: (r) => {
      degrade(r, 'STEP_BACKLOG_FULL');
    },
  });

  function consumerOf(recordingId) {
    return 'studio-recorder:' + recordingId;
  }

  function fail(code, message, extra = {}) {
    const e = new HostError(code, message);
    Object.assign(e, extra);
    return e;
  }

  /** Sensor do documento principal sem confirmação de {active, epoch, mode}: nada é dado como feito. */
  function sensorNotConfirmed(message) {
    return fail('CAPTURE_FAILED', message, { reason: 'SENSOR_NOT_CONFIRMED' });
  }

  function now() {
    return deps.now ? deps.now() : Date.now();
  }

  function iso(t) {
    return new Date(t).toISOString();
  }

  // ------------------------------------------------------------------------------------------- abas

  function randomRef() {
    const a = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
    const b = new Uint8Array(14);
    crypto.getRandomValues(b);
    return 't-' + Array.from(b, (x) => a[x % a.length]).join('');
  }

  function refOf(tabId) {
    let ref = tabToRef.get(tabId);
    if (!ref) {
      ref = randomRef();
      tabToRef.set(tabId, ref);
      refToTab.set(ref, tabId);
      persistRefs();
    }
    return ref;
  }

  function persistRefs() {
    const o = {};
    for (const [ref, tabId] of refToTab) {
      o[ref] = tabId;
    }
    chromeApi.storage.session.set({ [REFS_KEY]: o }).catch(() => {});
  }

  function hostOf(url) {
    try {
      const u = new URL(url);
      return u.protocol === 'http:' || u.protocol === 'https:' ? u.hostname.slice(0, 253) : null;
    } catch (e) {
      return null;
    }
  }

  /** Título exibível: mascarado pela política de privacidade e limitado (nunca a URL). */
  function safeTitle(title) {
    if (!title || typeof title !== 'string') {
      return null;
    }
    const masked = globalThis.NekkoraPrivacy ? globalThis.NekkoraPrivacy.maskText(title.trim()) : title.trim();
    return masked ? masked.slice(0, 120) : null;
  }

  async function listTabs() {
    const all = await chromeApi.tabs.query({});
    const out = [];
    for (const tab of all) {
      if (out.length >= 100) {
        break;
      }
      if (tab.id == null || tab.id < 0) {
        continue;
      }
      const url = await policy.tabUrl(tab.id).catch(() => null);
      const v = policy.evaluate(url);
      out.push({
        tabRef: refOf(tab.id),
        title: v.allowed ? safeTitle(tab.title) : null,
        host: v.reason === 'BROWSER_INTERNAL' || v.reason === 'UNSUPPORTED_SCHEME' ? null : hostOf(url),
        access: v.allowed ? v.kind : v.reason,
        active: !!tab.active,
        recording: Array.from(recordings.values()).some((r) => r.tabId === tab.id && !TERMINAL.has(r.state)),
      });
    }
    return { tabs: out, truncated: all.length > out.length };
  }

  // ------------------------------------------------------------------------------------- registros

  function persist(r) {
    return chromeApi.storage.session.set({ [KEY + r.recordingId]: r }).catch((e) => {
      // Sem o estado mínimo a gravação não sobrevive a um reinício do service worker: o problema fica visível.
      broadcast('error', { tabId: r.tabId, where: 'studio-recording.persist', code: 'STORAGE_UNAVAILABLE', message: String(e && e.message || e) });
    });
  }

  function forget(id) {
    recordings.delete(id);
    chromeApi.storage.session.remove(KEY + id).catch(() => {});
  }

  function enforceRetention() {
    const ended = Array.from(recordings.values()).filter((r) => TERMINAL.has(r.state)).sort((a, b) => (a.endedAt || 0) - (b.endedAt || 0));
    for (let i = 0; i < ended.length - TERMINAL_RETENTION; i++) {
      forget(ended[i].recordingId);
    }
  }

  function zero() {
    return { segments: 0, technicalCaptures: 0, synchronizations: 0, recoveries: 0 };
  }

  function captureCounters(rec) {
    return rec ? { sessionId: rec.sessionId, fullCaptures: rec.fullCaptures, deltasApplied: rec.deltasApplied, resyncs: rec.resyncs } : null;
  }

  /** Contadores durante a participação do Recorder: trechos fechados + trecho aberto. */
  function counters(r) {
    const c = { ...r.counters };
    const live = r.segment ? capture.get(r.tabId) : null;
    if (r.segment && live && live.sessionId === r.segment.sessionId) {
      c.technicalCaptures += Math.max(0, live.fullCaptures - r.segment.fullCaptures);
      c.synchronizations += Math.max(0, live.deltasApplied - r.segment.deltasApplied);
      c.recoveries += Math.max(0, live.resyncs - r.segment.resyncs);
    }
    return c;
  }

  function openSegment(r, before) {
    const live = capture.get(r.tabId);
    // Sessão criada pelo próprio Recorder: a captura-base inicial pertence a este trecho.
    const base = before && live && before.sessionId === live.sessionId ? before
      : { sessionId: live ? live.sessionId : null, fullCaptures: 0, deltasApplied: 0, resyncs: 0 };
    r.segment = base;
    r.counters.segments++;
    r.attached = true;
  }

  function closeSegment(r) {
    r.counters = counters(r);
    r.segment = null;
    r.attached = false;
  }

  async function coverage(r) {
    const live = capture.get(r.tabId);
    if (!live || !r.attached) {
      return { state: 'UNKNOWN', framesAttached: 0, framesUnavailable: 0 };
    }
    let attached = 0;
    let unavailable = 0;
    const frames = await chromeApi.webNavigation.getAllFrames({ tabId: r.tabId }).catch(() => []);
    const known = Object.values(live.frames || {});
    for (const f of frames || []) {
      if (f.frameId === 0) {
        continue;
      }
      const entry = known.find((x) => x.frameId === f.frameId);
      if (entry && entry.status === 'ATTACHED') {
        attached++;
      } else if (entry && entry.status === 'CAPTURED_BY_PARENT') {
        // documento acompanhado pela captura do pai
      } else if (/^(about:|data:|blob:)/.test(f.url || '') && !entry) {
        // documento local do pai (srcdoc/about:blank): acompanhado pelo pai
      } else if (!policy.isAllowedUrl(f.url) || (entry && (entry.status === 'FAILED' || entry.status === 'NOT_ATTACHED'))) {
        unavailable++; // origem sem autorização, sandbox ou falha: a página NÃO está totalmente coberta
      }
    }
    return { state: unavailable ? 'PARTIAL' : 'COMPLETE', framesAttached: attached, framesUnavailable: unavailable };
  }

  async function view(r) {
    const live = capture.get(r.tabId);
    return {
      recordingId: r.recordingId,
      state: r.state,
      mode: r.mode,
      tab: { tabRef: r.tabRef, title: r.title, host: r.host },
      capture: {
        recorderAttached: !!(r.attached && live && live.consumers.includes(consumerOf(r.recordingId))),
        otherConsumers: live ? live.consumers.filter((c) => c !== consumerOf(r.recordingId)).length : 0,
        captureStatus: live && capture.ACTIVE.has(live.status) || (live && live.status === 'ERROR') ? live.status : null,
      },
      coverage: await coverage(r),
      counters: counters(r),
      lease: { leaseMs: r.leaseMs, expiresInMs: TERMINAL.has(r.state) ? 0 : Math.max(0, r.leaseExpiresAt - now()) },
      startedAt: iso(r.startedAt),
      updatedAt: iso(r.updatedAt),
      reason: r.reason || null,
      ...(steps.enabled(r) ? { steps: steps.state(r) } : {}),
      ...(evidence.enabled(r) ? { evidence: evidence.state(r) } : {}),
    };
  }

  async function summary(r) {
    const c = counters(r);
    const cov = r.lastCoverage || { state: 'UNKNOWN', framesAttached: 0, framesUnavailable: 0 };
    const sum = steps.summary(r);
    const limitations = sum ? [] : ['ACTION_CAPTURE_NOT_AVAILABLE'];
    if (cov.state === 'PARTIAL') {
      limitations.push('PARTIAL_COVERAGE');
    }
    if (r.degradedOnce) {
      limitations.push('INTERRUPTED');
    }
    if (c.recoveries > 0) {
      limitations.push('RECOVERIES_OCCURRED');
    }
    if (c.segments === 0) {
      limitations.push('NEVER_ATTACHED');
    }
    let actionCapture = 'NOT_AVAILABLE';
    if (sum) {
      if (sum.unacknowledged > 0) {
        limitations.push('STEPS_UNCONFIRMED');
      }
      if (sum.rejected > 0) {
        limitations.push('STEPS_REJECTED');
      }
      if (sum.lost > 0) {
        limitations.push('INTERACTIONS_LOST');
      }
      if (r.backlogFull) {
        limitations.push('STEP_BACKLOG_FULL');
      }
      if (r.steps.sensorGaps > 0 && !limitations.includes('PARTIAL_COVERAGE')) {
        limitations.push('PARTIAL_COVERAGE'); // sensor de iframe sem confirmação: interações desse documento não observadas
      }
      // RECOVERIES_OCCURRED é fato da captura técnica (ex.: nova base depois de navegar), não perda de ações.
      actionCapture = limitations.some((l) => ACTION_LIMITS.has(l)) ? 'PARTIAL' : 'AVAILABLE';
    }
    const out = {
      state: r.state, durationMs: Math.max(0, (r.endedAt || now()) - r.startedAt), counters: c, coverage: cov,
      pageMapExported: false, actionCapture, limitations,
    };
    const evSum = evidence.summary(r);
    if (evSum) {
      // limitações das evidências descrevem a OBSERVAÇÃO, não a captura de ações (actionCapture não muda)
      if (evSum.unacknowledged > 0) {
        limitations.push('EVIDENCE_UNCONFIRMED');
      }
      if (evSum.lost > 0) {
        limitations.push('EVIDENCE_LOST');
      }
    }
    if (sum) {
      out.steps = sum;
    }
    if (evSum) {
      out.evidence = evSum;
    }
    return out;
  }

  async function remember(r, operationId, command, result) {
    if (operationId) {
      r.ops[operationId] = { command, result };
      const keys = Object.keys(r.ops);
      for (let i = 0; i < keys.length - OPS_RETENTION; i++) {
        delete r.ops[keys[i]];
      }
    }
    r.updatedAt = now();
    recordings.set(r.recordingId, r);
    await persist(r);
  }

  function emit(r, event, operationId, extra = {}) {
    view(r).then((recording) => {
      const payload = { recordingId: r.recordingId, operationId: operationId || null, recording, ...extra };
      broadcast('studio-recording', { tabId: r.tabId, event, state: r.state });
      postEvent(r, event, payload);
    });
  }

  /** Só com a porta nativa aberta: um evento sem Studio para recebê-lo não deve iniciar um processo do host. */
  function postEvent(r, event, payload) {
    if (event === 'studio.recording.step') {
      broadcast('studio-step', { tabId: r.tabId, sequence: payload.sequence, action: payload.action });
    }
    if (event === 'studio.recording.evidence') {
      broadcast('studio-evidence', { tabId: r.tabId, sequence: payload.evidenceSequence, type: payload.type });
    }
    if (typeof client.status !== 'function' || client.status().connected) {
      client.post({ protocol: PROTOCOL, type: 'studio.event', event, payload, controller: { instanceId: r.controller } }).catch(() => {});
    }
  }

  // --------------------------------------------------------------------------------------- validação

  function assertId(v, field) {
    if (typeof v !== 'string' || !ID.test(v)) {
      throw fail('BAD_REQUEST', field + ' inválido');
    }
  }

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

  function owned(id, controller) {
    const r = recordings.get(id);
    if (!r) {
      throw fail('RECORDING_NOT_FOUND', 'Gravação inexistente.');
    }
    if (r.controller !== controller) {
      throw fail('NOT_CONTROLLER', 'A gravação pertence a outra instância do Studio.');
    }
    return r;
  }

  /** Operação já aplicada (mesmo operationId): devolve o mesmo resultado, sem repetir efeitos. */
  function replay(r, operationId, command) {
    const prev = operationId && r.ops[operationId];
    if (!prev) {
      return null;
    }
    if (prev.command !== command) {
      throw fail('OPERATION_CONFLICT', 'operationId já usado por outro comando.');
    }
    return { ...prev.result, duplicate: true };
  }

  function ended(r) {
    return fail('RECORDING_ENDED', 'A gravação já terminou (' + r.state + ').', { reason: r.state });
  }

  // ----------------------------------------------------------------------------------------- comandos

  async function start(p, controller) {
    assertOnly(p, ['recordingId', 'operationId', 'tabRef', 'mode', 'leaseMs', 'capabilities', 'routeRef']);
    assertId(p.recordingId, 'recordingId');
    assertId(p.operationId, 'operationId');
    if (typeof p.tabRef !== 'string' || !TAB_REF.test(p.tabRef)) {
      throw fail('BAD_REQUEST', 'tabRef inválido');
    }
    if (!MODES.has(p.mode)) {
      throw fail('INVALID_MODE', 'Modo desconhecido.');
    }
    if (!Number.isInteger(p.leaseMs) || p.leaseMs < 5000 || p.leaseMs > 300000) {
      throw fail('BAD_REQUEST', 'leaseMs fora do intervalo 5000..300000');
    }
    if (p.capabilities !== undefined && (!Array.isArray(p.capabilities) || p.capabilities.some((c) => !CAPABILITIES.has(c)))) {
      throw fail('BAD_REQUEST', 'capabilities inválidas');
    }
    if (Array.isArray(p.capabilities) && p.capabilities.includes('evidence/1') && !p.capabilities.includes('steps/1')) {
      throw fail('BAD_REQUEST', 'evidence/1 exige steps/1');
    }
    if (p.routeRef !== undefined) {
      assertId(p.routeRef, 'routeRef');
    }
    const existing = recordings.get(p.recordingId);
    if (existing) {
      if (existing.controller !== controller) {
        throw fail('NOT_CONTROLLER', 'A gravação pertence a outra instância do Studio.');
      }
      const again = replay(existing, p.operationId, 'start');
      if (again) {
        return again;
      }
      if (TERMINAL.has(existing.state)) {
        throw ended(existing);
      }
      return { recording: await view(existing), duplicate: true }; // a mesma gravação nunca é iniciada duas vezes
    }
    const tabId = refToTab.get(p.tabRef);
    let tab = null;
    if (tabId != null) {
      tab = await chromeApi.tabs.get(tabId).catch(() => null);
    }
    if (!tab) {
      throw fail('TAB_NOT_FOUND', 'A aba selecionada não existe mais. Atualize a lista de abas.');
    }
    if (Array.from(recordings.values()).some((r) => r.tabId === tabId && !TERMINAL.has(r.state))) {
      throw fail('RECORDING_ALREADY_ACTIVE', 'Já existe uma gravação do Studio nesta aba.');
    }
    let allowed;
    try {
      allowed = await policy.assertAllowed(tabId);
    } catch (e) {
      throw fail('ORIGIN_NOT_ALLOWED', e.reason === 'PERMISSION_REQUIRED'
        ? 'Este site ainda não está autorizado. Abra a Nekkora Extension no Chrome e conceda acesso ao site.'
        : (e.message || 'Página não permitida.').replace(/^ORIGIN_NOT_ALLOWED: /, ''), { reason: e.reason || null });
    }
    const before = captureCounters(capture.get(tabId));
    try {
      await capture.acquire(tabId, consumerOf(p.recordingId));
    } catch (e) {
      const info = errorInfo(e);
      throw fail(info.code === 'ORIGIN_NOT_ALLOWED' ? 'ORIGIN_NOT_ALLOWED' : 'CAPTURE_FAILED',
        'A captura técnica não pôde ser iniciada: ' + info.message, { reason: info.reason || info.code });
    }
    const t = now();
    const r = {
      recordingId: p.recordingId, controller, tabId, tabRef: p.tabRef, title: safeTitle(tab.title), host: hostOf(allowed.url),
      state: 'RECORDING', mode: p.mode, leaseMs: p.leaseMs, leaseExpiresAt: t + p.leaseMs, startedAt: t, updatedAt: t, endedAt: null,
      reason: null, attached: false, segment: null, counters: zero(), ops: {}, degradedOnce: false, lastCoverage: null,
    };
    openSegment(r, before);
    r.lastCoverage = await coverage(r);
    if (Array.isArray(p.capabilities) && p.capabilities.includes('steps/1')) {
      steps.enable(r, p.routeRef);
      if (p.capabilities.includes('evidence/1')) {
        evidence.enable(r);
      }
      // sensores só depois da captura confirmada; sem a confirmação do sensor principal o início não é confirmado
      if (!(await steps.startSensors(r))) {
        await steps.stopSensors(r, true).catch(() => {});
        await detach(r, 'STUDIO_RECORDER_CANCELLED');
        throw sensorNotConfirmed('Os sensores de interação não confirmaram o início na página. Recarregue a página e tente novamente.');
      }
    }
    const result = { recording: await view(r), duplicate: false };
    await remember(r, p.operationId, 'start', result);
    armLease(r);
    emit(r, 'studio.recording.started', p.operationId);
    return result;
  }

  async function detach(r, reason) {
    if (!r.attached) {
      return;
    }
    r.lastCoverage = await coverage(r);
    closeSegment(r);
    releasing.add(r.tabId);
    try {
      // detachHost: a liberação local é imediata; o motor encerra a sessão quando puder (sem bloquear o controle).
      await capture.release(r.tabId, consumerOf(r.recordingId), reason, { detachHost: true });
    } finally {
      releasing.delete(r.tabId);
    }
  }

  async function attach(r) {
    const tab = await chromeApi.tabs.get(r.tabId).catch(() => null);
    if (!tab) {
      throw fail('TAB_NOT_FOUND', 'A aba da gravação foi fechada.');
    }
    try {
      await policy.assertAllowed(r.tabId);
    } catch (e) {
      throw fail('ORIGIN_NOT_ALLOWED', e.reason === 'PERMISSION_REQUIRED'
        ? 'Este site ainda não está autorizado. Abra a Nekkora Extension no Chrome e conceda acesso ao site.'
        : 'A página atual da aba não pode ser acompanhada.', { reason: e.reason || null });
    }
    const before = captureCounters(capture.get(r.tabId));
    try {
      await capture.acquire(r.tabId, consumerOf(r.recordingId));
    } catch (e) {
      const info = errorInfo(e);
      throw fail('CAPTURE_FAILED', 'A captura técnica não pôde ser retomada: ' + info.message, { reason: info.code });
    }
    openSegment(r, before);
    r.lastCoverage = await coverage(r);
  }

  async function pause(p, controller) {
    assertOnly(p, ['recordingId', 'operationId']);
    assertId(p.recordingId, 'recordingId');
    assertId(p.operationId, 'operationId');
    const r = owned(p.recordingId, controller);
    const again = replay(r, p.operationId, 'pause');
    if (again) {
      return again;
    }
    if (TERMINAL.has(r.state)) {
      throw ended(r);
    }
    if (r.state === 'PAUSED') {
      return { recording: await view(r), duplicate: true }; // pausar duas vezes: sem efeito adicional
    }
    if (r.state !== 'RECORDING') {
      throw fail('INVALID_STATE', 'Só é possível pausar uma gravação ativa (estado atual: ' + r.state + ').');
    }
    await steps.flush(r, 'PAUSE');
    await steps.stopSensors(r, false, 'WINDOW_CUT_BY_PAUSE'); // eventos depois da pausa (época encerrada) são descartados
    await detach(r, 'STUDIO_RECORDER_PAUSED');
    r.state = 'PAUSED';
    r.reason = 'USER_PAUSED';
    const result = { recording: await view(r), duplicate: false };
    await remember(r, p.operationId, 'pause', result);
    emit(r, 'studio.recording.paused', p.operationId);
    return result;
  }

  async function resume(p, controller) {
    assertOnly(p, ['recordingId', 'operationId']);
    assertId(p.recordingId, 'recordingId');
    assertId(p.operationId, 'operationId');
    const r = owned(p.recordingId, controller);
    const again = replay(r, p.operationId, 'resume');
    if (again) {
      return again;
    }
    if (TERMINAL.has(r.state)) {
      throw ended(r);
    }
    if (r.state === 'RECORDING') {
      return { recording: await view(r), duplicate: true }; // continuar duas vezes: sem efeito adicional
    }
    const wasDegraded = r.state === 'DEGRADED';
    await attach(r);
    r.state = 'RECORDING';
    r.backlogFull = false;
    // nova época: nada da pausa é aceito e a última etapa não se repete
    if (!(await steps.startSensors(r))) {
      await degradeNow(r, 'SENSOR_NOT_CONFIRMED');
      throw sensorNotConfirmed('Os sensores de interação não confirmaram a retomada na página. A captura de ações continua interrompida.');
    }
    r.reason = wasDegraded ? 'RECOVERED' : 'USER_RESUMED';
    const result = { recording: await view(r), duplicate: false };
    await remember(r, p.operationId, 'resume', result);
    emit(r, wasDegraded ? 'studio.recording.recovered' : 'studio.recording.resumed', p.operationId);
    return result;
  }

  async function setMode(p, controller) {
    assertOnly(p, ['recordingId', 'operationId', 'mode']);
    assertId(p.recordingId, 'recordingId');
    assertId(p.operationId, 'operationId');
    if (!MODES.has(p.mode)) {
      throw fail('INVALID_MODE', 'Modo desconhecido.');
    }
    const r = owned(p.recordingId, controller);
    const again = replay(r, p.operationId, 'mode');
    if (again) {
      return again;
    }
    if (TERMINAL.has(r.state)) {
      throw ended(r);
    }
    if (r.mode === p.mode) {
      return { recording: await view(r), duplicate: true };
    }
    // consolida no modo anterior e só troca com a confirmação do sensor principal (nunca um modo apresentado sem estar ativo)
    const changed = await steps.setMode(r, p.mode);
    if (!changed.ok) {
      if (!changed.restored) {
        await degradeNow(r, 'SENSOR_NOT_CONFIRMED');
      }
      throw sensorNotConfirmed(changed.restored
        ? 'A página não confirmou a troca de modo. A gravação continua no modo anterior.'
        : 'A página não confirmou a troca de modo nem o modo anterior. A captura de ações foi interrompida.');
    }
    const result = { recording: await view(r), duplicate: false };
    await remember(r, p.operationId, 'mode', result);
    emit(r, 'studio.recording.modeChanged', p.operationId);
    return result;
  }

  async function end(r, state, reason, operationId, command, event) {
    if (steps.enabled(r)) {
      if (state === 'CANCELLED') {
        steps.discardOutbox(r); // só as etapas DESTA gravação; o Studio descarta as dele
        evidence.discardOutbox(r); // e as evidências (nenhuma evidência sobrevive ao cancelamento)
        await steps.stopSensors(r, true);
        await steps.drain(r);
      } else {
        if (r.state === 'RECORDING') {
          await steps.flush(r, state === 'COMPLETED' ? 'FINISH' : 'STOP');
        }
        await steps.stopSensors(r, false);
        await steps.drain(r);
        if (state === 'COMPLETED') {
          await steps.awaitAcks(r); // confirmações dentro do limite; o que faltar fica declarado no resumo
          await evidence.awaitAcks(r, 3000, deps.sleep || ((ms) => new Promise((res) => setTimeout(res, ms))));
        }
      }
    }
    await detach(r, state === 'COMPLETED' ? 'STUDIO_RECORDER_FINISHED' : state === 'CANCELLED' ? 'STUDIO_RECORDER_CANCELLED' : 'STUDIO_RECORDER_EXPIRED');
    r.state = state;
    r.reason = reason;
    r.endedAt = now();
    disarmLease(r);
    const sum = await summary(r);
    const result = { recording: await view(r), summary: sum, duplicate: false };
    await remember(r, operationId, command, result);
    r.result = result;
    await persist(r);
    enforceRetention();
    emit(r, event, operationId, { summary: sum });
    return result;
  }

  async function finish(p, controller) {
    assertOnly(p, ['recordingId', 'operationId']);
    assertId(p.recordingId, 'recordingId');
    assertId(p.operationId, 'operationId');
    const r = owned(p.recordingId, controller);
    const again = replay(r, p.operationId, 'finish');
    if (again) {
      return again;
    }
    if (r.state === 'COMPLETED' && r.result) {
      return { ...r.result, duplicate: true }; // finalização repetida: o mesmo desfecho
    }
    if (TERMINAL.has(r.state)) {
      throw ended(r); // cancelada/expirada antes: um único desfecho
    }
    return end(r, 'COMPLETED', 'USER_FINISHED', p.operationId, 'finish', 'studio.recording.finished');
  }

  async function cancel(p, controller) {
    assertOnly(p, ['recordingId', 'operationId']);
    assertId(p.recordingId, 'recordingId');
    assertId(p.operationId, 'operationId');
    const r = owned(p.recordingId, controller);
    const again = replay(r, p.operationId, 'cancel');
    if (again) {
      return again;
    }
    if (r.state === 'CANCELLED' && r.result) {
      return { ...r.result, duplicate: true };
    }
    if (TERMINAL.has(r.state)) {
      throw ended(r); // finalizada/expirada antes: o cancelamento não desfaz o desfecho
    }
    return end(r, 'CANCELLED', 'USER_CANCELLED', p.operationId, 'cancel', 'studio.recording.cancelled');
  }

  async function status(p, controller) {
    assertOnly(p, ['recordingId']);
    if (p.recordingId !== undefined) {
      assertId(p.recordingId, 'recordingId');
      const r = owned(p.recordingId, controller);
      return { recordings: [await view(r)] };
    }
    const mine = Array.from(recordings.values()).filter((r) => r.controller === controller).slice(-50);
    return { recordings: await Promise.all(mine.map(view)) };
  }

  /**
   * Renovação da concessão (fora da fila: só atualiza o prazo, nunca espera outra operação). O prazo é ABSOLUTO: uma
   * concessão vencida não volta a valer, mesmo que o temporizador de expiração ainda não tenha rodado ou esteja no
   * meio do encerramento. Nesse caso a renovação é recusada e a rotina de expiração (na fila) libera o consumidor.
   */
  async function renew(p, controller) {
    assertOnly(p, ['recordingId']);
    assertId(p.recordingId, 'recordingId');
    const r = owned(p.recordingId, controller);
    if (!TERMINAL.has(r.state) && (r.expiring || now() >= r.leaseExpiresAt)) {
      expireIfDue(r.recordingId).catch(() => {}); // o desfecho chega pelo evento studio.recording.expired
      throw fail('RECORDING_ENDED', 'A concessão expirou; a gravação está sendo encerrada.', { reason: 'LEASE_EXPIRED' });
    }
    if (!TERMINAL.has(r.state)) {
      r.leaseExpiresAt = now() + r.leaseMs;
      persist(r);
      armLease(r);
    }
    const out = { recordingId: r.recordingId, state: r.state, expiresInMs: TERMINAL.has(r.state) ? 0 : r.leaseMs };
    if (steps.enabled(r)) {
      out.steps = steps.state(r);
    }
    if (evidence.enabled(r)) {
      out.evidence = evidence.state(r);
    }
    return out;
  }

  function withSteps(p, controller) {
    const r = owned(p.recordingId, controller);
    if (!steps.enabled(r)) {
      throw fail('BAD_REQUEST', 'A gravação não entrega etapas (capacidade steps/1 ausente).');
    }
    return r;
  }

  /** Confirmação cumulativa do Studio (fora da fila): só depois do domínio do Studio processar as etapas. */
  async function stepsAck(p, controller) {
    assertOnly(p, ['recordingId', 'upToSequence', 'rejected']);
    assertId(p.recordingId, 'recordingId');
    if (!Number.isInteger(p.upToSequence) || p.upToSequence < 0) {
      throw fail('BAD_REQUEST', 'upToSequence inválido');
    }
    if (p.rejected !== undefined && (!Array.isArray(p.rejected) || p.rejected.length > 100 || p.rejected.some((n) => !Number.isInteger(n) || n < 1))) {
      throw fail('BAD_REQUEST', 'rejected inválido');
    }
    const r = withSteps(p, controller);
    return { recordingId: r.recordingId, steps: steps.ack(r, p.upToSequence, p.rejected) };
  }

  /** Reemissão das etapas retidas (perda detectada ou reconexão). Vale também para gravação encerrada (retenção). */
  async function stepsSync(p, controller) {
    assertOnly(p, ['recordingId', 'afterSequence']);
    assertId(p.recordingId, 'recordingId');
    if (!Number.isInteger(p.afterSequence) || p.afterSequence < 0) {
      throw fail('BAD_REQUEST', 'afterSequence inválido');
    }
    const r = withSteps(p, controller);
    const res = steps.sync(r, p.afterSequence);
    return { recordingId: r.recordingId, resent: res.resent, steps: res.steps };
  }

  function withEvidence(p, controller) {
    const r = owned(p.recordingId, controller);
    if (!evidence.enabled(r)) {
      throw fail('BAD_REQUEST', 'A gravação não entrega evidências (capacidade evidence/1 ausente).');
    }
    return r;
  }

  /** Confirmação cumulativa das evidências (fora da fila): só depois do domínio do Studio registrá-las. */
  async function evidenceAck(p, controller) {
    assertOnly(p, ['recordingId', 'upToSequence', 'rejected']);
    assertId(p.recordingId, 'recordingId');
    if (!Number.isInteger(p.upToSequence) || p.upToSequence < 0) {
      throw fail('BAD_REQUEST', 'upToSequence inválido');
    }
    if (p.rejected !== undefined && (!Array.isArray(p.rejected) || p.rejected.length > 100 || p.rejected.some((n) => !Number.isInteger(n) || n < 1))) {
      throw fail('BAD_REQUEST', 'rejected inválido');
    }
    const r = withEvidence(p, controller);
    return { recordingId: r.recordingId, evidence: evidence.ack(r, p.upToSequence, p.rejected) };
  }

  /** Reemissão das evidências retidas (lacuna detectada pelo Studio ou reconexão). */
  async function evidenceSync(p, controller) {
    assertOnly(p, ['recordingId', 'afterSequence']);
    assertId(p.recordingId, 'recordingId');
    if (!Number.isInteger(p.afterSequence) || p.afterSequence < 0) {
      throw fail('BAD_REQUEST', 'afterSequence inválido');
    }
    const r = withEvidence(p, controller);
    const res = evidence.sync(r, p.afterSequence);
    return { recordingId: r.recordingId, resent: res.resent, evidence: res.evidence };
  }

  /** Troca de sessão lógica no Studio: consolida a rota anterior e devolve a fronteira de sequência. */
  async function route(p, controller) {
    assertOnly(p, ['recordingId', 'operationId', 'routeRef']);
    assertId(p.recordingId, 'recordingId');
    assertId(p.operationId, 'operationId');
    assertId(p.routeRef, 'routeRef');
    const r = withSteps(p, controller);
    const again = replay(r, p.operationId, 'route');
    if (again) {
      return again;
    }
    if (TERMINAL.has(r.state)) {
      throw ended(r);
    }
    if (r.steps.routeRef === p.routeRef) {
      return { recording: await view(r), boundarySequence: r.steps.nextSeq, duplicate: true };
    }
    const routed = await steps.route(r, p.routeRef);
    if (!routed.ok) {
      if (!routed.restored) {
        await degradeNow(r, 'SENSOR_NOT_CONFIRMED');
      }
      throw sensorNotConfirmed(routed.restored
        ? 'A página não confirmou a troca de sessão. As etapas continuam na sessão anterior.'
        : 'A página não confirmou a troca de sessão. A captura de ações foi interrompida.');
    }
    const boundarySequence = routed.boundary;
    const result = { recording: await view(r), boundarySequence, duplicate: false };
    await remember(r, p.operationId, 'route', result);
    return result;
  }

  // ------------------------------------------------------------------------------------- concessão

  function armLease(r) {
    disarmLease(r);
    const delay = Math.max(0, r.leaseExpiresAt - now()) + 50;
    const timer = setTimeout(() => expireIfDue(r.recordingId), delay);
    if (timer && typeof timer.unref === 'function') {
      timer.unref(); // Node (testes): o temporizador não mantém o processo vivo; no navegador é um número
    }
    leaseTimers.set(r.recordingId, timer);
    chromeApi.alarms.create(LEASE_ALARM, { periodInMinutes: 0.5 }).catch(() => {});
  }

  function disarmLease(r) {
    const t = leaseTimers.get(r.recordingId);
    if (t) {
      clearTimeout(t);
      leaseTimers.delete(r.recordingId);
    }
  }

  function expireIfDue(id) {
    return withLock(LOCK, async () => {
      const r = recordings.get(id);
      if (!r || TERMINAL.has(r.state)) {
        return;
      }
      if (!r.expiring && now() < r.leaseExpiresAt) {
        armLease(r); // renovada nesse meio-tempo
        return;
      }
      r.expiring = true; // a partir daqui nenhuma renovação reativa a concessão
      await end(r, 'EXPIRED', 'LEASE_EXPIRED', null, 'expire', 'studio.recording.expired');
    });
  }

  function checkLeases() {
    for (const r of recordings.values()) {
      if (!TERMINAL.has(r.state) && now() >= r.leaseExpiresAt) {
        expireIfDue(r.recordingId);
      }
    }
  }

  // ------------------------------------------------------------------------------- eventos do navegador

  /** Gravação perdeu a captura por motivo externo (aba fechada, origem revogada, erro): DEGRADED. */
  function degrade(r, reason) {
    return withLock(LOCK, () => degradeNow(r, reason));
  }

  /** Degradação já dentro da fila de comandos (ex.: sensor sem confirmação durante uma operação). */
  async function degradeNow(r, reason) {
    if (TERMINAL.has(r.state) || r.state === 'DEGRADED' || recordings.get(r.recordingId) !== r) {
      return;
    }
    // Decisões do próprio Recorder (fila cheia, sensor sem confirmação) liberam o consumidor; a retomada religa tudo.
    const own = reason === 'STEP_BACKLOG_FULL' || reason === 'SENSOR_NOT_CONFIRMED';
    if (steps.enabled(r)) {
      await steps.stopSensors(r, false).catch(() => {});
      if (reason === 'STEP_BACKLOG_FULL') {
        r.backlogFull = true;
      }
    }
    if (r.attached) {
      r.lastCoverage = await coverage(r).catch(() => r.lastCoverage);
      closeSegment(r);
      if (own) {
        await capture.release(r.tabId, consumerOf(r.recordingId), reason === 'STEP_BACKLOG_FULL' ? 'STUDIO_RECORDER_BACKLOG' : 'STUDIO_RECORDER_SENSOR',
          { detachHost: true }).catch(() => {});
      }
    }
    r.state = 'DEGRADED';
    r.reason = reason;
    r.degradedOnce = true;
    await remember(r, null, null, null);
    emit(r, 'studio.recording.degraded', null);
  }

  function onCaptureStatus(rec) {
    if (releasing.has(rec.tabId)) {
      return; // liberação feita pelo próprio Recorder
    }
    for (const r of recordings.values()) {
      if (r.tabId !== rec.tabId || TERMINAL.has(r.state) || !r.attached) {
        continue;
      }
      const mine = rec.consumers.includes(consumerOf(r.recordingId));
      if (rec.status === 'COMPLETED' || !mine) {
        degrade(r, rec.reason && /^[A-Z_]{2,40}$/.test(rec.reason) ? rec.reason : 'CAPTURE_ENDED');
      } else if (rec.status === 'ERROR') {
        degrade(r, 'CAPTURE_ERROR');
      }
    }
  }

  function onTabRemoved(tabId) {
    for (const r of recordings.values()) {
      if (r.tabId === tabId && !TERMINAL.has(r.state)) {
        degrade(r, 'TAB_CLOSED');
      }
    }
    const ref = tabToRef.get(tabId);
    if (ref && !Array.from(recordings.values()).some((r) => r.tabRef === ref && !TERMINAL.has(r.state))) {
      tabToRef.delete(tabId);
      refToTab.delete(ref);
      persistRefs();
    }
  }

  /** Permissão revogada: gravações pausadas naquela origem também ficam DEGRADED (não podem ser retomadas). */
  async function onPermissionsRemoved() {
    for (const r of recordings.values()) {
      if (TERMINAL.has(r.state) || r.attached) {
        continue; // as acompanhadas são encerradas pela política central (capture.end) e degradadas pelo hook
      }
      const url = await policy.tabUrl(r.tabId).catch(() => null);
      if (url && !policy.isAllowedUrl(url) && r.state === 'PAUSED') {
        degrade(r, 'PERMISSION_REVOKED');
      }
    }
  }

  // ----------------------------------------------------------------------------------------- entrada

  /** Comando do Studio recebido do host (studio.command). Sempre responde com studio.reply. */
  async function onCommand(msg) {
    const reply = (body) => client.post({ protocol: PROTOCOL, type: 'studio.reply', id: msg.id, ...body }).catch(() => {});
    try {
      await ready;
      const fn = typeof msg.command === 'string' && Object.prototype.hasOwnProperty.call(COMMANDS, msg.command) ? COMMANDS[msg.command] : null;
      if (!fn) {
        throw fail('UNKNOWN_COMMAND', 'Comando não permitido.');
      }
      const controller = msg.controller && typeof msg.controller.instanceId === 'string' && ID.test(msg.controller.instanceId)
        ? msg.controller.instanceId : null;
      if (!controller) {
        throw fail('NOT_CONTROLLER', 'Controlador ausente.');
      }
      const payload = msg.payload || {};
      const result = UNQUEUED.has(msg.command)
        ? await fn(payload, controller)
        : await withLock(LOCK, () => fn(payload, controller));
      await reply({ ok: true, payload: result, error: null });
    } catch (e) {
      const info = errorInfo(e);
      const code = KNOWN_CODES.has(info.code) ? info.code : 'INTERNAL_ERROR';
      const error = { code, message: String(info.message || code).replace(/^[A-Z_]+: /, '').slice(0, 500) };
      if (e && e.reason) {
        error.reason = String(e.reason).slice(0, 60);
      }
      error.retryable = code === 'CAPTURE_FAILED' || code === 'TIMEOUT';
      await reply({ ok: false, payload: null, error });
    }
  }

  const KNOWN_CODES = new Set(['BAD_REQUEST', 'UNKNOWN_COMMAND', 'COMMAND_NOT_ALLOWED', 'TAB_NOT_FOUND', 'ORIGIN_NOT_ALLOWED', 'RECORDING_NOT_FOUND',
    'RECORDING_ALREADY_ACTIVE', 'RECORDING_ENDED', 'INVALID_STATE', 'NOT_CONTROLLER', 'INVALID_MODE', 'CAPTURE_FAILED', 'OPERATION_CONFLICT',
    'TIMEOUT', 'INTERNAL_ERROR']);

  /** Visão para diagnóstico (painel/harness): sem segredos; ids técnicos ficam fora da interface do painel. */
  function list() {
    return Array.from(recordings.values()).map((r) => ({ recordingId: r.recordingId, tabId: r.tabId, state: r.state, mode: r.mode,
      attached: r.attached, reason: r.reason, leaseExpiresInMs: Math.max(0, r.leaseExpiresAt - now()), controller: r.controller }));
  }

  /** Reinício do service worker: restaura registros e referências; expira concessões vencidas; reconcilia abas. */
  async function restore() {
    try {
      const all = await chromeApi.storage.session.get(null);
      const refs = all[REFS_KEY] || {};
      for (const [ref, tabId] of Object.entries(refs)) {
        refToTab.set(ref, tabId);
        tabToRef.set(tabId, ref);
      }
      for (const [k, v] of Object.entries(all)) {
        if (k.startsWith(KEY) && v && v.recordingId && !recordings.has(v.recordingId)) {
          recordings.set(v.recordingId, v);
        }
      }
    } catch (e) {
      // armazenamento indisponível
    }
    return recordings.size;
  }

  /** Depois da reconciliação das sessões de captura: confere se cada gravação acompanhada ainda tem seu consumidor. */
  async function reconcile() {
    for (const r of Array.from(recordings.values())) {
      if (TERMINAL.has(r.state)) {
        continue;
      }
      const tab = await chromeApi.tabs.get(r.tabId).catch(() => null);
      if (!tab) {
        await degrade(r, 'TAB_CLOSED');
        continue;
      }
      const live = capture.get(r.tabId);
      if (r.attached && !(live && live.consumers.includes(consumerOf(r.recordingId)))) {
        await degrade(r, 'CAPTURE_ENDED');
        continue;
      }
      armLease(r);
    }
    checkLeases();
  }

  // --------------------------------------------------------------------------------- sensores (content)

  function recordingOfTab(tabId) {
    return Array.from(recordings.values()).find((r) => r.tabId === tabId && !TERMINAL.has(r.state) && steps.enabled(r)) || null;
  }

  /** Mensagens do sensor de interações (interaction / interaction-evidence / interaction-open / feedback). */
  function onSensor(tabId, frameId, event, data) {
    const r = recordingOfTab(tabId);
    if (!r) {
      return { ok: false, error: 'NOT_RECORDING' };
    }
    if (event === 'feedback') {
      if (!data || typeof data.epoch !== 'string' || !r.steps.epochs.includes(data.epoch)) {
        return { ok: false, error: 'STALE_EPOCH' }; // observação de uma ativação encerrada: não é aceita
      }
      return evidence.feedback(r, frameId, data);
    }
    if (event === 'interaction') {
      return steps.accept(r, frameId, data);
    }
    if (event === 'interaction-evidence') {
      return steps.evidence(r, frameId, data);
    }
    return steps.open(r, frameId, data);
  }

  /** Navegação (SPA ou novo documento) na aba de uma gravação: contexto posterior das etapas e consolidação. */
  function onNavigation(tabId, kind, url) {
    const r = recordingOfTab(tabId);
    if (r) {
      steps.navigation(r, kind, url);
      evidence.navigation(r, kind, url);
      if (kind !== 'NEW_DOCUMENT' && r.state === 'RECORDING') {
        deps.toContent && deps.toContent(tabId, 'cs.recorder.navigation', {}, 0).catch(() => {});
      }
    }
  }

  function stepMetrics(recordingId) {
    const r = recordings.get(recordingId);
    return r ? steps.metrics(r) : null;
  }

  function evidenceMetrics(recordingId) {
    const r = recordings.get(recordingId);
    return r ? evidence.metrics(r) : null;
  }

  function init(readyPromise) {
    ready = readyPromise;
    client.onHostMessage('studio.command', (msg) => {
      onCommand(msg);
    });
    capture.on('onStatus', onCaptureStatus);
    // Documento substituído / nova base / frame anexado: os sensores acompanham o documento atual.
    capture.on('onDocumentReplaced', (rec, data) => {
      const r = recordingOfTab(rec.tabId);
      if (r) {
        steps.documentReplaced(r, 0);
        steps.navigation(r, 'NEW_DOCUMENT', null);
        // novo documento: evidência de navegação (associada à ação recente, se houver uma só) e, depois, as janelas do
        // documento substituído terminam INCOMPLETAS (WINDOW_CUT_BY_NAVIGATION)
        evidence.navigation(r, 'NEW_DOCUMENT', data && typeof data.url === 'string' ? data.url : null);
        evidence.documentReplaced(r, 0);
      }
    });
    capture.on('afterBase', async (rec) => {
      const r = recordingOfTab(rec.tabId);
      if (r && r.state === 'RECORDING' && r.attached && !(await steps.startSensors(r))) {
        await degrade(r, 'SENSOR_NOT_CONFIRMED'); // documento novo sem sensor confirmado: interrupção declarada
      }
    });
    capture.on('frameAttached', async (rec, entry) => {
      const r = recordingOfTab(rec.tabId);
      if (r && entry.status === 'ATTACHED') {
        await steps.startFrame(r, entry.frameId);
      }
    });
    chromeApi.tabs.onRemoved.addListener((tabId) => ready.then(() => onTabRemoved(tabId)).catch(() => {}));
    policy.onRemoved(() => ready.then(onPermissionsRemoved));
    chromeApi.alarms.onAlarm.addListener((a) => {
      if (a.name === LEASE_ALARM) {
        ready.then(checkLeases).catch(() => {});
      }
    });
  }

  return { onCommand, restore, reconcile, list, init, checkLeases, consumerOf, recordings, onSensor, onNavigation, stepMetrics, evidenceMetrics, steps, evidence };
}
