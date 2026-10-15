/*
 * Pipeline de ETAPAS gravadas (STUDIO-003) — do sensor de interações ao evento studio.recording.step.
 *
 *   sensor (content) ──interaction──► sequência (ordem de recepção) ──► correlação do elemento (motor Java)
 *        ──► studio.recording.step (outbox retida) ──► Studio ──ack (após o domínio aceitar)──► sai da outbox
 *
 * Garantias:
 * - sequência explícita por gravação, atribuída AQUI na chegada; dentro de um documento a ordem do sensor é preservada
 *   (seq local); entre documentos diferentes vale a ordem de recepção (documentada, nunca timestamps);
 * - correlação e emissão SERIALIZADAS por gravação: as etapas saem em ordem de sequência;
 * - outbox persistida (sem valores digitados) até a confirmação do Studio; retransmissão por sync; nada é descartado em
 *   silêncio: o limite de pendentes leva a gravação a DEGRADED (STEP_BACKLOG_FULL) e desliga os sensores;
 * - épocas: cada ativação dos sensores tem uma época; eventos de épocas encerradas (depois de pausar, cancelar,
 *   finalizar) são descartados e contados (STALE_EPOCH); o documento substituído mantém a época aceita para a
 *   consolidação feita na saída da página;
 * - ativação CONFIRMADA: o sensor do documento principal precisa responder {active, epoch, mode} iguais aos pedidos
 *   (início, retomada, troca de modo, troca de rota). Sem isso nada é dado como feito; sensor de iframe sem
 *   confirmação = cobertura parcial declarada;
 * - rota: cada época pertence a UMA rota. Trocar de rota consolida a época anterior e abre uma época nova; a etapa
 *   leva a rota da época em que a interação aconteceu, nunca a vigente na chegada (um evento atrasado além da espera
 *   da fronteira continua na sessão anterior);
 * - limite rígido: ao atingir MAX_PENDING a admissão fecha na hora (síncrona); o que chegar depois é recusado e
 *   contado como perda declarada, enquanto a gravação degrada (STEP_BACKLOG_FULL).
 */

export const MAX_PENDING = 100;
export const AFTER_WINDOW_MS = 400;
export const FLUSH_WAIT_MS = 1500;
export const FINISH_ACK_WAIT_MS = 5000;
const MODES = new Set(['CAPTURE_ONLY', 'CAPTURE_AND_INTERACT']);
const ACTIONS = new Set(['CLICK', 'FILL', 'SELECT_OPTION', 'CHECK', 'UNCHECK', 'PRESS_KEY', 'UNKNOWN']);
const EVIDENCE = new Set(['FORM_SUBMITTED', 'DOUBLE_CLICK', 'VIA_KEYBOARD', 'VIA_LABEL', 'FOCUS_CLICK_ABSORBED', 'CUSTOM_COMBOBOX', 'NAVIGATION_FOLLOWED',
  'LOCATORS_VERIFIED_LIVE']);
const NOTES = new Set(['AMBIGUOUS_TARGET', 'OPTION_NOT_IDENTIFIED', 'OPTION_NOT_CHOSEN', 'OPTION_WITHOUT_PROVEN_COMBOBOX', 'ELEMENT_UNRESOLVED', 'NO_UNIQUE_LOCATOR',
  'INDISTINGUISHABLE_LOOKALIKES', 'ACCESS_RESTRICTED', 'LIVE_CHECK_UNAVAILABLE', 'CROSS_FRAME_ORDER_BY_RECEPTION', 'RESENT',
  // CHROME-SECURITY-001: limitações declaradas da política nekkora-privacy/2
  'OPTION_WITHHELD', 'LOCATORS_WITHHELD', 'SAFE_LOCATOR_UNAVAILABLE']);
const STRATEGIES = new Set(['TEST_ATTRIBUTE', 'ID', 'NAME', 'LABEL', 'ROLE_NAME', 'CONTEXTUAL', 'ROW_CONTEXT', 'ATTRIBUTE', 'TEXT', 'XPATH_RELATIVE', 'POSITIONAL']);
const STABILITY = new Set(['OBSERVED_STABLE', 'HEURISTIC_STABLE', 'UNKNOWN', 'HEURISTIC_UNSTABLE', 'OBSERVED_UNSTABLE']);
const PATH_TYPES = new Set(['MAIN_DOCUMENT', 'IFRAME', 'SHADOW_ROOT', 'ELEMENT']);
const RELATIONS = new Set(['IN_DIALOG', 'IN_ROW', 'IN_LIST_ITEM', 'IN_FORM']);
const ID = /^[A-Za-z0-9._:-]{1,80}$/;
const KIND = /^[A-Z_]{1,40}$/;
const TAG = /^[a-z][a-z0-9-]{0,40}$/;
export const DISCARD_KEYS = ['UNTRUSTED', 'NON_INTERACTIVE', 'FOCUS_ONLY', 'PROGRAMMATIC_SUBMIT', 'PROGRAMMATIC_CHANGE', 'ABSORBED', 'AFTER_STOP', 'STALE_EPOCH',
  'NOT_RECORDING'];

/**
 * @param deps { client, broadcast, capture, toContent, host, tabKey, frameOfNode, now, sleep, privacy, emitEvent, onBacklogFull, evidence }
 *   evidence: pipeline de evidências (STUDIO-005, studio-evidence.js), opcional — vínculos, janelas e emissão após a etapa.
 */
export function createStepPipeline(deps) {
  const { capture, toContent, host, tabKey, frameOfNode } = deps;
  const now = deps.now || (() => Date.now());
  const sleep = deps.sleep || ((ms) => new Promise((r) => setTimeout(r, ms)));
  const chains = new Map(); // recordingId -> Promise (fila de correlação; não persistida)
  const pendingEvidence = new Map(); // `${epoch}|${frameId}|${localSeq}` -> entrada ainda não emitida
  const ev = deps.evidence || null;

  function wantsEvidence(r) {
    return !!(ev && ev.enabled(r));
  }

  // --------------------------------------------------------------------------------- estado

  function fresh(routeRef) {
    const discarded = {};
    for (const k of DISCARD_KEYS) {
      discarded[k] = 0;
    }
    return { enabled: true, routeRef, nextSeq: 0, acked: 0, acknowledged: 0, rejected: 0, lost: 0, outbox: [], epochs: [], epoch: null,
      epochRoute: {}, blocked: false, sensorGaps: 0,
      frames: {}, open: {}, discarded, sensorDiscards: {}, consolidated: 0, lastNav: null, stats: { correlationMs: [], resolved: 0, unresolved: 0, liveVerified: 0 } };
  }

  function enable(r, routeRef) {
    r.steps = fresh(routeRef && ID.test(routeRef) ? routeRef : 'route-1');
  }

  function enabled(r) {
    return !!(r && r.steps && r.steps.enabled);
  }

  /** StepsState do contrato. */
  function state(r) {
    if (!enabled(r)) {
      return { enabled: false, lastSequence: 0, ackedSequence: 0, pending: 0, lost: 0 };
    }
    return { enabled: true, lastSequence: r.steps.nextSeq, ackedSequence: r.steps.acked, pending: r.steps.outbox.length, lost: r.steps.lost };
  }

  function summary(r) {
    if (!enabled(r)) {
      return null;
    }
    const s = r.steps;
    return { produced: s.nextSeq, acknowledged: s.acknowledged, rejected: s.rejected, unacknowledged: s.outbox.length, lost: s.lost,
      discarded: discards(r) };
  }

  /** Descartes do service worker + os do normalizador de cada documento (contagens cumulativas por sensor). */
  function discards(r) {
    const out = { ...r.steps.discarded };
    for (const per of Object.values(r.steps.sensorDiscards || {})) {
      for (const [k, v] of Object.entries(per || {})) {
        if (DISCARD_KEYS.includes(k) && Number.isInteger(v) && v > 0) {
          out[k] = (out[k] || 0) + v;
        }
      }
    }
    return out;
  }

  function noteSensorStats(r, key, discarded) {
    if (discarded && typeof discarded === 'object') {
      r.steps.sensorDiscards[key] = discarded;
    }
  }

  function discard(r, reason, n = 1) {
    if (enabled(r)) {
      r.steps.discarded[reason] = (r.steps.discarded[reason] || 0) + n;
    }
  }

  // --------------------------------------------------------------------------------- sensores

  function newEpoch() {
    const a = 'abcdefghijklmnopqrstuvwxyz0123456789';
    const b = new Uint8Array(10);
    crypto.getRandomValues(b);
    return 'e-' + Array.from(b, (x) => a[x % a.length]).join('');
  }

  function sensorFrames(tabId) {
    const live = capture.get(tabId);
    return [0, ...(live ? Object.values(live.frames || {}).filter((f) => f.status === 'ATTACHED').map((f) => f.frameId) : [])];
  }

  /** Resposta do sensor confirma exatamente a época e o modo pedidos (resposta ausente, recusa ou inativo = não). */
  function confirms(st, epoch, mode) {
    return !!(st && st.active === true && st.epoch === epoch && st.mode === mode);
  }

  /**
   * Liga os sensores em todos os documentos acompanhados numa época NOVA, pertencente à rota vigente. Devolve true só
   * se o sensor do documento principal confirmou {active, epoch, mode}; iframes sem confirmação viram cobertura parcial.
   */
  async function startSensors(r) {
    if (!enabled(r)) {
      return true;
    }
    const s = r.steps;
    s.epochRoute = s.epochRoute || {}; // estado persistido antes desta versão
    const epoch = newEpoch();
    s.epoch = epoch;
    s.epochs = [...s.epochs.slice(-8), epoch];
    s.epochRoute[epoch] = s.routeRef;
    for (const k of Object.keys(s.epochRoute)) {
      if (!s.epochs.includes(k)) {
        delete s.epochRoute[k];
      }
    }
    if (s.nextSeq - s.acked < MAX_PENDING) {
      s.blocked = false; // a fila drenou: a admissão reabre na nova ativação
    }
    let main = false;
    for (const f of sensorFrames(r.tabId)) {
      const st = await toContent(r.tabId, 'cs.recorder.start', { epoch, mode: r.mode, evidence: wantsEvidence(r) }, f).catch(() => null);
      if (f === 0) {
        main = confirms(st, epoch, r.mode);
      } else if (!confirms(st, epoch, r.mode)) {
        s.sensorGaps++;
      }
    }
    deps.persist && deps.persist(r);
    return main;
  }

  /** Sensor de um frame que passou a ser acompanhado depois do início (sem confirmação: cobertura parcial). */
  async function startFrame(r, frameId) {
    if (enabled(r) && r.steps.epoch && r.state === 'RECORDING') {
      const epoch = r.steps.epoch;
      const st = await toContent(r.tabId, 'cs.recorder.start', { epoch, mode: r.mode, evidence: wantsEvidence(r) }, frameId).catch(() => null);
      if (frameId !== 0 && !confirms(st, epoch, r.mode)) {
        r.steps.sensorGaps++;
      }
    }
  }

  /**
   * Consolida o que está aberto em todos os documentos e ESPERA as interações consolidadas chegarem (a resposta do
   * flush e as mensagens do sensor correm por canais diferentes): fronteira exata para rota, modo, pausa e finalização.
   */
  async function flush(r, reason) {
    if (!enabled(r) || !r.steps.epoch) {
      return;
    }
    const marks = [];
    for (const f of sensorFrames(r.tabId)) {
      const st = await toContent(r.tabId, 'cs.recorder.flush', { reason }, f).catch(() => null);
      if (st && st.active && st.epoch) {
        marks.push({ key: st.epoch + '|' + f, lastSeq: st.lastSeq || 0, lastFeedback: st.lastFeedback || 0 });
        noteSensorStats(r, st.epoch + '|' + f, st.stats && st.stats.discarded);
      }
    }
    await waitArrivals(r, marks);
  }

  async function waitArrivals(r, marks) {
    const deadline = now() + FLUSH_WAIT_MS;
    // interações consolidadas e, com evidence/1, as mensagens do observador (cortes de janela enviados na fronteira)
    while (marks.some((m) => (r.steps.frames[m.key] || 0) < m.lastSeq || (ev && !ev.arrived(r, m.key, m.lastFeedback))) && now() < deadline) {
      await sleep(15);
    }
  }

  /**
   * Desliga os sensores (cancelamento: discard=true descarta o que estava aberto). Depois disso a época é encerrada.
   * cutReason: limitação das janelas de observação que a página não concluiu (STUDIO-005; padrão WINDOW_CUT_BY_STOP).
   */
  async function stopSensors(r, discardOpen, cutReason) {
    if (!enabled(r)) {
      return;
    }
    const marks = [];
    for (const f of sensorFrames(r.tabId)) {
      const st = await toContent(r.tabId, 'cs.recorder.stop', { discard: !!discardOpen }, f).catch(() => null);
      if (st && st.epoch) {
        noteSensorStats(r, st.epoch + '|' + f, st.stats && st.stats.discarded);
      }
      if (st && st.epoch && !discardOpen) {
        marks.push({ key: st.epoch + '|' + f, lastSeq: st.lastSeq || 0, lastFeedback: st.lastFeedback || 0 });
      }
    }
    await waitArrivals(r, marks);
    if (wantsEvidence(r) && !discardOpen) {
      ev.cut(r, cutReason || 'WINDOW_CUT_BY_STOP'); // janela sem conclusão da página: INCOMPLETA declarada (exatamente uma vez)
    }
    r.steps.epoch = null;
    r.steps.epochs = []; // eventos atrasados de épocas encerradas passam a ser descartados (STALE_EPOCH)
    r.steps.open = {};
  }

  /**
   * Troca de modo: consolida no modo ANTERIOR e pede o novo ao sensor principal. O modo da gravação só muda depois da
   * confirmação {active, epoch, mode}. Sem ela, os sensores são religados no modo anterior (nova época) para o documento
   * voltar a um estado conhecido. Devolve {ok} ou {ok:false, restored}.
   */
  async function setMode(r, mode) {
    if (!enabled(r) || !r.steps.epoch) {
      r.mode = mode; // sem sensores ativos (pausa, interrupção): vale na próxima ativação
      return { ok: true };
    }
    await flush(r, 'MODE_CHANGE');
    const epoch = r.steps.epoch;
    const main = await toContent(r.tabId, 'cs.recorder.mode', { epoch, mode }, 0).catch(() => null);
    if (!confirms(main, epoch, mode)) {
      const restored = await startSensors(r); // r.mode continua o anterior
      return { ok: false, restored };
    }
    r.mode = mode;
    for (const f of sensorFrames(r.tabId).filter((x) => x !== 0)) {
      const st = await toContent(r.tabId, 'cs.recorder.mode', { epoch, mode }, f).catch(() => null);
      if (!confirms(st, epoch, mode)) {
        // iframe em modo desconhecido não pode continuar executando cliques em CAPTURE_ONLY: sensor desligado
        await toContent(r.tabId, 'cs.recorder.stop', { discard: false }, f).catch(() => null);
        r.steps.sensorGaps++;
      }
    }
    return { ok: true };
  }

  // ------------------------------------------------------------------------------ recepção

  /**
   * Interação consolidada pelo sensor de um documento. Devolve {ok} ao content script. A sequência é atribuída já
   * aqui; a correlação e a emissão seguem a fila da gravação.
   */
  function accept(r, frameId, data) {
    const s = r.steps;
    if (!data || typeof data.epoch !== 'string' || !s.epochs.includes(data.epoch)) {
      discard(r, 'STALE_EPOCH');
      return { ok: false, error: 'STALE_EPOCH' };
    }
    if (r.state !== 'RECORDING') {
      discard(r, 'NOT_RECORDING');
      return { ok: false, error: 'NOT_RECORDING' };
    }
    const i = data.interaction || {};
    if (!ACTIONS.has(i.action) || typeof i.targetKey !== 'string') {
      discard(r, 'NON_INTERACTIVE');
      return { ok: false, error: 'BAD_INTERACTION' };
    }
    const key = data.epoch + '|' + frameId;
    s.frames[key] = Math.max(s.frames[key] || 0, Number(i.localSeq) || 0);
    noteSensorStats(r, key, data.discarded);
    // Limite rígido: a admissão fecha de forma síncrona; a degradação (assíncrona) só desliga os sensores depois.
    if (s.blocked || s.nextSeq - s.acked >= MAX_PENDING) {
      s.lost++; // recusada = perda declarada (resumo INTERACTIONS_LOST), nunca descarte silencioso
      closeAdmission(r);
      return { ok: false, error: 'STEP_BACKLOG_FULL' };
    }
    s.consolidated += Math.max(0, (Number(i.events) || 1) - 1);
    const entry = {
      sequence: ++s.nextSeq,
      eventId: 'ev-' + r.recordingId.slice(-24) + '-' + s.nextSeq,
      routeRef: (s.epochRoute && s.epochRoute[data.epoch]) || s.routeRef, // rota da época da interação, não a vigente na chegada
      mode: MODES.has(data.mode) ? data.mode : r.mode, // modo aplicado de fato pelo sensor
      frameId,
      receivedAt: now(),
      interaction: i,
      descriptor: data.element || null,
      url: data.url || null,
      title: data.title || null,
      evidence: new Set((i.evidence || []).filter((e) => EVIDENCE.has(e))),
      multiFrame: Object.keys(s.frames).some((k) => !k.endsWith('|' + frameId)),
    };
    entry.key = data.epoch + '|' + frameId + '|' + i.localSeq;
    pendingEvidence.set(entry.key, entry);
    if (ev) {
      ev.onEntry(r, entry.key, entry);
    }
    if (pendingEvidence.size > 200) {
      pendingEvidence.delete(pendingEvidence.keys().next().value);
    }
    const prev = chains.get(r.recordingId) || Promise.resolve();
    const next = prev.catch(() => {}).then(() => process(r, entry));
    chains.set(r.recordingId, next.catch(() => {}));
    // Pendentes = produzidas e ainda não confirmadas (em correlação + na outbox), não só as já emitidas.
    if (s.nextSeq - s.acked >= MAX_PENDING) {
      closeAdmission(r); // sem descarte: a gravação degrada e os sensores param ao atingir o limite
    }
    return { ok: true, sequence: entry.sequence };
  }

  function closeAdmission(r) {
    if (!r.steps.blocked) {
      r.steps.blocked = true;
      if (deps.onBacklogFull) {
        deps.onBacklogFull(r);
      }
    }
  }

  /** Evidência posterior (ex.: FORM_SUBMITTED) de uma interação ainda não emitida. */
  function evidence(r, frameId, data) {
    const key = data && data.epoch + '|' + frameId + '|' + data.localSeq;
    const e = data && pendingEvidence.get(key);
    if (e && EVIDENCE.has(data.evidence)) {
      e.evidence.add(data.evidence);
      return { ok: true };
    }
    if (data && data.evidence === 'FORM_SUBMITTED' && wantsEvidence(r)) {
      ev.lateForm(r, key); // etapa já emitida: o envio tardio vira evidência estruturada (a etapa não é recriada)
      return { ok: true };
    }
    return { ok: false };
  }

  function open(r, frameId, data) {
    if (data && r.steps.epochs.includes(data.epoch)) {
      r.steps.open[frameId] = !!data.open;
    }
    return { ok: true };
  }

  /** Documento do frame substituído com um preenchimento ainda aberto: perda DECLARADA (nunca inventada nem omitida). */
  function documentReplaced(r, frameId) {
    if (enabled(r) && r.steps.open[frameId]) {
      r.steps.lost++;
      r.steps.open[frameId] = false;
      deps.broadcast('studio-steps', { tabId: r.tabId, event: 'interaction-lost', lost: r.steps.lost });
    }
  }

  function navigation(r, kind, url) {
    if (enabled(r)) {
      r.steps.lastNav = { at: now(), kind, url: url || null };
    }
  }

  async function drain(r) {
    await (chains.get(r.recordingId) || Promise.resolve());
  }

  // ------------------------------------------------------------------------------ correlação

  async function process(r, entry) {
    const t0 = now();
    const element = await correlate(r, entry).catch(() => unresolved(entry));
    r.steps.stats.correlationMs.push(now() - t0);
    if (r.steps.stats.correlationMs.length > 200) {
      r.steps.stats.correlationMs.shift();
    }
    // Contexto posterior (envio, navegação): só para ações que podem provocá-lo, dentro da janela de correlação e
    // sem atrasar a etapa seguinte (se o usuário já seguiu, a etapa sai).
    if (entry.interaction.action === 'CLICK' || entry.interaction.action === 'PRESS_KEY') {
      const until = entry.receivedAt + AFTER_WINDOW_MS;
      while (now() < until && r.steps.nextSeq === entry.sequence) {
        await sleep(25);
      }
    }
    const event = build(r, entry, element);
    pendingEvidence.forEach((v, k) => {
      if (v === entry) {
        pendingEvidence.delete(k);
      }
    });
    if (r.steps.halted) {
      return; // gravação cancelada durante a correlação: a etapa não é emitida (o Studio descarta a gravação)
    }
    r.steps.outbox.push({ sequence: entry.sequence, event });
    deps.persist(r);
    deps.emitEvent(r, event);
    if (ev) {
      ev.onStepEmitted(r, entry, entry.key); // evidências que esperavam esta etapa saem depois dela
    }
  }

  async function correlate(r, entry) {
    const nodeId = entry.interaction.targetKey;
    const sync = await Promise.race([capture.synchronize(r.tabId).catch(() => null), sleep(2500).then(() => null)]);
    const live = capture.get(r.tabId);
    const captureId = (sync && sync.latestCaptureId) || (live && live.latestCaptureId) || undefined;
    let d;
    try {
      d = await host('recorder.element.describe', { tabKey: tabKey(r.tabId), captureId, nodeId }, { timeoutMs: 8000 });
    } catch (e) {
      r.steps.stats.unresolved++;
      return unresolved(entry);
    }
    r.steps.stats.resolved++;
    let validation = d.locators.map((l, i) => (i === d.preferredLocator ? 'SNAPSHOT' : (l.matchCount === 1 && l.matchesTarget ? 'SNAPSHOT' : 'NONE')));
    const notes = [...(d.notes || [])];
    try {
      const liveChecks = await toContent(r.tabId, 'cs.verifyLocators', { nodeId, locators: d.verification }, frameOfNode(nodeId));
      const verdict = await host('recorder.element.verify', { tabKey: tabKey(r.tabId), captureId: d.captureId, nodeId, checks: liveChecks.checks }, { timeoutMs: 5000 });
      validation = validation.map((v, i) => {
        const row = (verdict.checks || []).find((c) => c.index === i);
        return row && row.liveUnique ? 'LIVE' : v;
      });
      if (validation.includes('LIVE')) {
        entry.evidence.add('LOCATORS_VERIFIED_LIVE');
        r.steps.stats.liveVerified++;
      }
    } catch (e) {
      notes.push('LIVE_CHECK_UNAVAILABLE'); // documento já substituído (ex.: navegação): validação só no snapshot
    }
    return { d, validation, notes };
  }

  function unresolved(entry) {
    return { d: null, validation: [], notes: ['ELEMENT_UNRESOLVED'] };
  }

  // -------------------------------------------------------------------------- montagem (2ª barreira)

  /**
   * 2ª barreira (CHROME-SECURITY-001, nekkora-privacy/2): reaplica a política a tudo que vai ao Studio, mesmo que o
   * motor ou o content script tenham falhado. Sem a política carregada, nada textual sai.
   */
  function policy() {
    return globalThis.NekkoraCapturePolicy || null;
  }

  /** Nome/rótulo de interface: só se o valor inteiro for do vocabulário controlado; senão ''. */
  function name(v) {
    const p = policy();
    return (p && v != null && p.interfaceText(v)) || '';
  }

  function safeAttr(v, sensitive) {
    const p = policy();
    return sensitive || v == null || v === '' || !p ? null : p.identifier(String(v));
  }

  function url(v) {
    const p = policy();
    return v && p ? p.urlTemplate(String(v)) : null;
  }

  function title(v) {
    const p = policy();
    return v && p ? p.title(String(v)) : null;
  }

  /** Expressão de localizador transportável (todos os literais pela política). */
  function expressionOk(v) {
    const p = policy();
    return !!p && p.expressionSafe(v == null ? '' : String(v));
  }

  function id(v) {
    return typeof v === 'string' && ID.test(v) ? v : undefined;
  }

  function elementRef(entry, c) {
    const desc = entry.descriptor || {};
    if (!c.d) {
      const sensitive = !!desc.sensitive || !!entry.interaction.sensitive;
      return {
        resolution: 'UNRESOLVED',
        kind: 'UNKNOWN',
        role: desc.role ? String(desc.role).slice(0, 40) : null,
        name: name(desc.name),
        tag: TAG.test(desc.tag || '') ? desc.tag : 'unknown',
        htmlId: safeAttr(desc.htmlId, sensitive),
        htmlName: safeAttr(desc.htmlName, sensitive),
        sensitive,
        locators: [],
        preferredLocator: -1,
        accessPath: [],
        context: [],
        lookalikes: 0,
      };
    }
    const d = c.d;
    const sensitive = !!d.sensitive || !!entry.interaction.sensitive;
    // Um localizador só vai ao Studio se TODA a expressão (CSS, XPath e descrição) tiver só literais da política;
    // senão é descartado e a limitação é declarada — nunca reescrito nem "consertado".
    const offered = (d.locators || []).slice(0, 5).map((l, i) => ({ l, i }));
    const kept = offered.filter(({ l }) => expressionOk(l.css) && expressionOk(l.xpath) && expressionOk(l.description));
    if (kept.length < offered.length) {
      c.notes.push('LOCATORS_WITHHELD');
    }
    const locators = kept.map(({ l, i }) => ({
      strategy: STRATEGIES.has(l.strategy) ? l.strategy : 'ATTRIBUTE',
      description: l.description ? String(l.description).slice(0, 200) : '',
      css: l.css ? String(l.css).slice(0, 500) : null,
      xpath: l.xpath ? String(l.xpath).slice(0, 500) : null,
      matchCount: Math.max(0, Number(l.matchCount) || 0),
      matchesTarget: !!l.matchesTarget,
      stability: STABILITY.has(l.stability) ? l.stability : 'UNKNOWN',
      validation: c.validation[i] || 'NONE',
    }));
    if (!locators.some((l) => l.matchCount === 1 && l.matchesTarget)) {
      c.notes.push('SAFE_LOCATOR_UNAVAILABLE'); // sem localizador seguro e único: declarado, não inventado
    }
    const pref = kept.findIndex(({ i }) => i === d.preferredLocator);
    let preferred = pref >= 0 ? pref : -1;
    const live = locators.findIndex((l) => l.validation === 'LIVE');
    if (live >= 0) {
      preferred = live; // o primeiro validado na DOM real tem precedência
    }
    const correlation = {};
    for (const [k, v] of [['targetId', d.targetId], ['nodeId', d.nodeId], ['captureId', d.captureId], ['documentId', d.documentId]]) {
      if (id(v)) {
        correlation[k] = v;
      }
    }
    return {
      resolution: 'RESOLVED',
      kind: KIND.test(d.kind || '') ? d.kind : 'UNKNOWN',
      role: d.role ? String(d.role).slice(0, 40) : null,
      name: name(d.name),
      tag: TAG.test(d.tag || '') ? d.tag : 'unknown',
      htmlId: safeAttr(d.htmlId, sensitive),
      htmlName: safeAttr(d.htmlName, sensitive),
      sensitive,
      locators,
      preferredLocator: preferred,
      accessPath: (d.accessPath || []).filter((s) => PATH_TYPES.has(s.type)).slice(0, 12).map((s) => ({
        type: s.type, label: name(s.label), locator: s.locator && expressionOk(s.locator) ? String(s.locator).slice(0, 500) : null,
        restriction: s.restriction ? String(s.restriction).slice(0, 40) : null })),
      context: (d.context || []).filter((a) => RELATIONS.has(a.relation)).slice(0, 4).map((a) => ({
        relation: a.relation, kind: KIND.test(a.kind || '') ? a.kind : 'UNKNOWN', name: name(a.name) })),
      lookalikes: Math.max(0, Number(d.lookalikes) || 0),
      correlation,
    };
  }

  function build(r, entry, c) {
    const i = entry.interaction;
    const notes = new Set([...(i.notes || []), ...(c.notes || [])].filter((n) => NOTES.has(n)));
    if (entry.multiFrame) {
      notes.add('CROSS_FRAME_ORDER_BY_RECEPTION');
    }
    const element = elementRef(entry, c);
    for (const n of c.notes || []) {
      if (NOTES.has(n)) {
        notes.add(n); // limitações declaradas pela 2ª barreira (localizadores retidos, sem localizador seguro)
      }
    }
    // A opção escolhida é valor do usuário / conteúdo de negócio: nunca vai ao Studio (CHROME-SECURITY-001).
    const option = null;
    if (i.action === 'SELECT_OPTION' && i.option) {
      notes.add('OPTION_WITHHELD');
    }
    const context = { url: url(entry.url), title: title(entry.title), frame: entry.frameId === 0 ? 'MAIN' : 'FRAME' };
    const nav = r.steps.lastNav;
    if (nav && nav.at >= entry.receivedAt - 50 && (i.action === 'CLICK' || i.action === 'PRESS_KEY')) {
      context.after = { navigation: nav.kind === 'NEW_DOCUMENT' ? 'NEW_DOCUMENT' : 'SAME_DOCUMENT', url: url(nav.url) };
      entry.evidence.add('NAVIGATION_FOLLOWED');
    }
    return {
      recordingId: r.recordingId,
      eventId: entry.eventId,
      sequence: entry.sequence,
      routeRef: entry.routeRef,
      observedAt: new Date(Number(i.lastAt) || entry.receivedAt).toISOString(),
      mode: entry.mode,
      observation: i.observation === 'PROPOSED' ? 'PROPOSED' : 'INTERACTION_OBSERVED',
      action: i.action,
      element,
      parameters: { valueCaptured: false, option, key: i.action === 'PRESS_KEY' ? 'Enter' : null },
      context,
      evidence: Array.from(entry.evidence).filter((e) => EVIDENCE.has(e)).slice(0, 10),
      notes: Array.from(notes).slice(0, 12),
      events: Math.min(100000, Math.max(1, Number(i.events) || 1)),
    };
  }

  // ------------------------------------------------------------------------------ entrega

  /** ACK cumulativo do Studio: só depois do domínio processar. rejected = sequências recusadas pelo domínio. */
  function ack(r, upTo, rejected) {
    const s = r.steps;
    const rej = new Set((rejected || []).filter((n) => Number.isInteger(n)));
    const keep = [];
    for (const o of s.outbox) {
      if (o.sequence <= upTo) {
        if (rej.has(o.sequence)) {
          s.rejected++;
        } else {
          s.acknowledged++;
        }
      } else {
        keep.push(o);
      }
    }
    s.outbox = keep;
    s.acked = Math.max(s.acked, Math.min(upTo, s.nextSeq));
    deps.persist(r);
    return state(r);
  }

  /** Reemite, em ordem, as etapas retidas depois de afterSequence (perda detectada pelo Studio, reconexão). */
  function sync(r, afterSequence) {
    let resent = 0;
    for (const o of r.steps.outbox) {
      if (o.sequence > afterSequence) {
        const notes = new Set(o.event.notes);
        notes.add('RESENT');
        deps.emitEvent(r, { ...o.event, notes: Array.from(notes).slice(0, 12) });
        resent++;
      }
    }
    return { resent, steps: state(r) };
  }

  /**
   * Troca de rota: consolida a época da rota anterior e abre uma época nova, da rota nova, confirmada pelo sensor
   * principal. Eventos da época anterior, mesmo chegando depois da espera da fronteira, continuam na rota anterior.
   * Sem a confirmação, a rota volta à anterior (as épocas abertas na tentativa também) e os sensores são religados
   * nela. Devolve {ok, boundary} ou {ok:false, restored}. boundary é informativa (sequência no momento da troca).
   */
  async function route(r, routeRef) {
    const s = r.steps;
    const prev = s.routeRef;
    if (!s.epoch || r.state !== 'RECORDING') {
      s.routeRef = routeRef; // sem sensores ativos: a próxima ativação já nasce na rota nova
      deps.persist(r);
      return { ok: true, boundary: s.nextSeq };
    }
    await flush(r, 'ROUTE_CHANGE');
    const boundary = s.nextSeq;
    s.routeRef = routeRef;
    if (await startSensors(r)) {
      deps.persist(r);
      return { ok: true, boundary };
    }
    s.routeRef = prev;
    for (const [epoch, ref] of Object.entries(s.epochRoute)) {
      if (ref === routeRef) {
        s.epochRoute[epoch] = prev; // a troca não foi confirmada: nada é atribuído à rota nova
      }
    }
    const restored = await startSensors(r);
    deps.persist(r);
    return { ok: false, restored };
  }

  /** Espera as confirmações das etapas emitidas (finalização), dentro do limite. */
  async function awaitAcks(r, maxMs) {
    const deadline = now() + (maxMs == null ? FINISH_ACK_WAIT_MS : maxMs);
    while (r.steps.outbox.length && now() < deadline) {
      await sleep(25);
    }
  }

  /** Cancelamento: as etapas retidas deixam de existir (o Studio descarta as dele). */
  function discardOutbox(r) {
    if (enabled(r)) {
      r.steps.halted = true;
      r.steps.outbox = [];
      deps.persist(r);
    }
  }

  function metrics(r) {
    if (!enabled(r)) {
      return null;
    }
    const ms = r.steps.stats.correlationMs.slice().sort((a, b) => a - b);
    const pct = (p) => (ms.length ? ms[Math.min(ms.length - 1, Math.floor(p * ms.length))] : null);
    return { produced: r.steps.nextSeq, consolidatedEvents: r.steps.consolidated, discarded: discards(r), lost: r.steps.lost,
      resolved: r.steps.stats.resolved, unresolved: r.steps.stats.unresolved, liveVerified: r.steps.stats.liveVerified,
      correlationMs: { p50: pct(0.5), p95: pct(0.95), max: ms.length ? ms[ms.length - 1] : null, samples: ms.length },
      pending: r.steps.outbox.length };
  }

  return { enable, enabled, state, summary, discard, startSensors, startFrame, flush, stopSensors, setMode, accept, evidence, open,
    documentReplaced, navigation, drain, ack, sync, route, awaitAcks, discardOutbox, metrics, build };
}
