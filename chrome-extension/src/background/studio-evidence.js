/*
 * Pipeline de EVIDÊNCIAS observadas (STUDIO-005) — do observador da página ao evento studio.recording.evidence.
 *
 *   feedback-observer (content) ──feedback──► 2ª barreira (revalidação + política de mensagem) ──► vínculo com a etapa
 *        ──► studio.recording.evidence (outbox própria, sequência própria) ──► Studio ──ack (após o domínio)──► sai da outbox
 *
 * Garantias:
 * - evidência é acontecimento observado; NUNCA resultado de negócio (não existe campo para isso no contrato);
 * - a evidência só sai DEPOIS da etapa a que pertence (fila por etapa enquanto a etapa é correlacionada); a evidência
 *   ambígua (mais de uma interação candidata) sai sem etapa (eventId null) e é contada como não atribuída;
 * - sequência própria (evidenceSequence) e identidade estável (evidenceId); retransmissão por sync; ACK cumulativo;
 * - limite de pendentes: acima dele a evidência não é emitida e a perda é DECLARADA (lost; EVIDENCE_LOST no resumo) —
 *   evidência não é etapa: a gravação não degrada por isso;
 * - toda janela de observação aberta termina com OBSERVATION_COMPLETE ou OBSERVATION_INCOMPLETE (exatamente uma vez):
 *   se o documento for substituído, a gravação pausar ou parar antes da conclusão vinda da página, o próprio service
 *   worker declara a observação INCOMPLETA (source RECORDER) com o motivo;
 * - CAPTURE_ONLY e etapas PROPOSED não têm evidências de consequência (a ação não foi executada).
 */

export const MAX_EVIDENCE_PENDING = 200;
export const NAV_WINDOW_MS = 3000;
export const MAX_QUEUED_PER_STEP = 40;
const LINKS_RETAINED = 300;

const TYPES = new Set(['FORM_SUBMITTED', 'ROUTE_CHANGED', 'DOCUMENT_CHANGED', 'VISIBLE_ALERT', 'VISIBLE_ERROR_MESSAGE', 'VISIBLE_STATUS_MESSAGE',
  'VALIDATION_FEEDBACK', 'LOADING_INDICATOR', 'LOADING_FINISHED', 'ELEMENT_STATE_CHANGED', 'CONTENT_CHANGED', 'OBSERVATION_COMPLETE',
  'OBSERVATION_INCOMPLETE', 'AMBIGUOUS_FEEDBACK']);
/** Tipos que o observador da página pode produzir (os demais são produzidos aqui). */
const PAGE_TYPES = new Set(['VISIBLE_ALERT', 'VISIBLE_ERROR_MESSAGE', 'VISIBLE_STATUS_MESSAGE', 'VALIDATION_FEEDBACK', 'LOADING_INDICATOR',
  'LOADING_FINISHED', 'ELEMENT_STATE_CHANGED', 'CONTENT_CHANGED', 'AMBIGUOUS_FEEDBACK']);
const CONFIDENCE = new Set(['DIRECTLY_OBSERVED', 'TEMPORALLY_ASSOCIATED', 'AMBIGUOUS', 'INCOMPLETE']);
const CLASSIFICATION = new Set(['ERROR_INDICATOR', 'STATUS_INDICATOR', 'VALIDATION_INDICATOR', 'UNKNOWN_FEEDBACK']);
const SIGNALS = new Set(['ROLE_ALERT', 'ROLE_ALERTDIALOG', 'ROLE_STATUS', 'ARIA_LIVE_ASSERTIVE', 'ARIA_LIVE_POLITE', 'ARIA_INVALID', 'NATIVE_INVALID',
  'ARIA_ERRORMESSAGE', 'ERROR_HINT', 'ARIA_BUSY', 'ROLE_PROGRESSBAR', 'SUBMIT_EVENT', 'HISTORY_API', 'NEW_DOCUMENT', 'TARGET_ATTRIBUTE']);
const LIMITATIONS = new Set(['MESSAGE_SUPPRESSED', 'WINDOW_CUT_BY_NAVIGATION', 'WINDOW_CUT_BY_PAUSE', 'WINDOW_CUT_BY_STOP', 'EVIDENCE_LIMIT_REACHED',
  'CROSS_DOCUMENT_NOT_ATTRIBUTED', 'PARTIAL_COVERAGE', 'ORDER_BY_RECEPTION']);
const STATE_ATTRS = new Set(['aria-expanded', 'aria-pressed', 'aria-checked', 'aria-selected', 'aria-disabled', 'disabled']);
const STATE_VALUES = new Set(['true', 'false', 'mixed', 'absent']);
const OPERATIONS = new Set(['CLICK', 'PRESS_KEY', 'SELECT_OPTION', 'CHECK', 'UNCHECK', 'UNKNOWN']);
const WINDOW_ACTIONS = new Set([...OPERATIONS, 'FILL']);

/**
 * @param deps { now, privacy, rules, persist, emitEvidence, broadcast }
 */
export function createEvidencePipeline(deps) {
  const now = deps.now || (() => Date.now());
  const privacy = () => deps.privacy || globalThis.NekkoraPrivacy;
  const rules = () => deps.rules || globalThis.NekkoraFeedback;
  const links = new Map(); // recordingId -> Map(`${epoch}|${frameId}|${localSeq}` -> vínculo com a interação/etapa)

  // --------------------------------------------------------------------------------- estado

  function enable(r) {
    r.evidence = { enabled: true, nextSeq: 0, acked: 0, acknowledged: 0, rejected: 0, lost: 0, unattributed: 0, incomplete: 0, outbox: [],
      fframes: {}, open: {}, dropped: 0, byType: {}, lastObservedAt: null, latencyMs: [] };
  }

  function enabled(r) {
    return !!(r && r.evidence && r.evidence.enabled);
  }

  /** EvidenceState do contrato. */
  function state(r) {
    if (!enabled(r)) {
      return { enabled: false, lastSequence: 0, ackedSequence: 0, pending: 0, lost: 0 };
    }
    const e = r.evidence;
    return { enabled: true, lastSequence: e.nextSeq, ackedSequence: e.acked, pending: Math.min(1000, e.outbox.length), lost: e.lost };
  }

  /** EvidenceSummary do contrato (finalização). */
  function summary(r) {
    if (!enabled(r)) {
      return null;
    }
    const e = r.evidence;
    return { produced: e.nextSeq, acknowledged: e.acknowledged, rejected: e.rejected, unacknowledged: e.outbox.length, lost: e.lost,
      unattributed: e.unattributed, incomplete: e.incomplete };
  }

  function linksOf(r) {
    let m = links.get(r.recordingId);
    if (!m) {
      m = new Map();
      links.set(r.recordingId, m);
    }
    return m;
  }

  // --------------------------------------------------------------------------------- vínculos

  /** Interação aceita pelo pipeline de etapas: registra o vínculo e, se for o caso, a janela de observação aberta. */
  function onEntry(r, key, entry) {
    if (!enabled(r)) {
      return;
    }
    const m = linksOf(r);
    const interact = entry.mode === 'CAPTURE_AND_INTERACT' && entry.interaction.observation !== 'PROPOSED';
    const link = { key, frameId: entry.frameId, action: entry.interaction.action, interact, receivedAt: entry.receivedAt, url: entry.url, title: entry.title,
      eventId: entry.eventId, sequence: entry.sequence, emitted: false, queue: [], formSent: false, navKinds: [] };
    m.set(key, link);
    if (m.size > LINKS_RETAINED) {
      m.delete(m.keys().next().value);
    }
    if (interact && WINDOW_ACTIONS.has(link.action)) {
      // vínculo técnico MÍNIMO persistido com a janela (revisão do PR #6): sobrevive a um reinício do service worker
      r.evidence.open[key] = { frameId: entry.frameId, at: entry.receivedAt, eventId: entry.eventId, sequence: entry.sequence, action: link.action,
        emitted: false };
      deps.persist && deps.persist(r); // a janela aberta sobrevive a um reinício (e a perda, se houver, é declarada)
    }
  }

  /**
   * Vínculo da interação: o da memória ou, depois de um reinício do service worker (memória vazia), o reconstruído do
   * registro PERSISTIDO da janela — só se a etapa já tinha sido emitida (marcado na emissão, nunca deduzido por
   * sequência ou por proximidade temporal). Etapa não emitida antes do reinício não existe mais: {lost: true}.
   */
  function linkFor(r, key) {
    const m = linksOf(r);
    const known = m.get(key);
    if (known) {
      return known;
    }
    const w = r.evidence.open[key];
    if (!w || typeof w.eventId !== 'string') {
      return null;
    }
    if (!w.emitted) {
      return { lost: true };
    }
    const link = { key, frameId: w.frameId, action: w.action, interact: true, receivedAt: w.at, url: null, title: null, eventId: w.eventId,
      sequence: w.sequence, emitted: true, queue: [], formSent: true, navKinds: [], restored: true };
    m.set(key, link);
    return link;
  }

  /** Observação de uma interação cuja etapa se perdeu num reinício: perda DECLARADA, nada é emitido sem etapa. */
  function declareLost(r, key, closing) {
    r.evidence.lost++;
    if (closing) {
      delete r.evidence.open[key];
    }
    deps.persist && deps.persist(r);
  }

  /** A etapa saiu: as evidências que esperavam por ela saem agora, em ordem. */
  function onStepEmitted(r, entry, key) {
    if (!enabled(r)) {
      return;
    }
    const link = linksOf(r).get(key);
    if (r.evidence.open[key]) {
      r.evidence.open[key].emitted = true; // a etapa existe para o Studio: o vínculo pode ser reconstruído depois de um reinício
      deps.persist && deps.persist(r);
    }
    if (!link) {
      return;
    }
    link.emitted = true;
    if (link.interact && entry.evidence && entry.evidence.has('FORM_SUBMITTED')) {
      formSubmitted(r, link, entry.receivedAt);
    }
    const q = link.queue;
    link.queue = [];
    for (const f of q) {
      emit(r, link, f);
    }
  }

  /** Envio de formulário observado pelo normalizador (no momento da ação ou tardio). */
  function formSubmitted(r, link, at) {
    if (link.formSent || !link.interact) {
      return;
    }
    link.formSent = true;
    produce(r, link, { at, type: 'FORM_SUBMITTED', source: 'INTERACTION_NORMALIZER', relation: 'AT_ACTION', confidence: 'DIRECTLY_OBSERVED',
      classification: null, document: docOf(link), region: null, message: none(), field: null, state: null, navigation: null, signals: ['SUBMIT_EVENT'],
      candidates: 0, signalsObserved: 0, limitations: [] });
  }

  function lateForm(r, key) {
    const link = enabled(r) && linksOf(r).get(key);
    if (link && link.emitted) {
      formSubmitted(r, link, now());
    }
  }

  // ------------------------------------------------------------------------- 2ª barreira

  function none() {
    return { availability: 'NONE', text: null };
  }

  function mask(v, max) {
    if (v == null || v === '') {
      return null;
    }
    const s = String(v).replace(/\s+/g, ' ').trim().slice(0, max);
    const p = privacy();
    if (!s || (p && p.looksSecret(s))) {
      return null;
    }
    return p ? p.maskText(s).slice(0, max) : s;
  }

  function url(v) {
    if (!v) {
      return null;
    }
    const s = privacy() ? privacy().sanitizeUrl(String(v)) : null;
    return s ? s.slice(0, 2000) : null;
  }

  function message(m) {
    if (!m || m.availability === 'NONE' || (m.availability !== 'AVAILABLE' && m.availability !== 'SUPPRESSED')) {
      return none();
    }
    if (m.availability === 'SUPPRESSED' || typeof m.text !== 'string') {
      return { availability: 'SUPPRESSED', text: null };
    }
    const again = rules().messagePolicy(m.text, []);
    return again.availability === 'AVAILABLE' ? again : { availability: 'SUPPRESSED', text: null };
  }

  function docOf(link, d) {
    const child = !!(d && d.childFrame);
    return {
      frame: link && link.frameId === 0 && !child ? 'MAIN' : (link || child ? 'FRAME' : 'MAIN'),
      inShadow: !!(d && d.inShadow),
      title: mask(d ? d.title : link && link.title, 120),
      url: url(d ? d.url : link && link.url),
    };
  }

  function clean(list, allowed, max) {
    return Array.from(new Set((Array.isArray(list) ? list : []).filter((x) => allowed.has(x)))).slice(0, max);
  }

  /** Mensagem 'feedback' do observador da página (a época já foi conferida pelo chamador). */
  function feedback(r, frameId, data) {
    if (!enabled(r) || !data || typeof data !== 'object') {
      return { ok: false, error: 'EVIDENCE_DISABLED' };
    }
    const e = r.evidence;
    const fkey = data.epoch + '|' + frameId;
    if (Number.isInteger(data.fseq)) {
      e.fframes[fkey] = Math.max(e.fframes[fkey] || 0, data.fseq);
    }
    const at = Number.isFinite(data.at) ? data.at : now();
    if (data.kind === 'complete' || data.kind === 'incomplete') {
      const key = fkey + '|' + data.localSeq;
      if (!e.open[key]) {
        return { ok: false, error: 'WINDOW_NOT_OPEN' }; // já encerrada (ex.: pelo próprio service worker) — exatamente uma vez
      }
      const link = linkFor(r, key);
      if (!link || link.lost) {
        declareLost(r, key, true); // etapa perdida no reinício (ou desconhecida): a observação não é emitida sem vínculo
        return { ok: false, error: 'LINK_LOST' };
      }
      closeWindow(r, key, link, data.kind === 'complete' ? null : 'page', {
        at, signalsObserved: Math.max(0, Math.min(50, Number(data.signalsObserved) || 0)), limitations: clean(data.limitations, LIMITATIONS, 8),
        document: docOf(link || { frameId }, data.document),
      });
      return { ok: true };
    }
    if (data.kind !== 'evidence' || !PAGE_TYPES.has(data.type)) {
      e.dropped++;
      return { ok: false, error: 'BAD_EVIDENCE' };
    }
    const ambiguous = data.localSeq == null || data.type === 'AMBIGUOUS_FEEDBACK';
    const link = ambiguous ? null : linkFor(r, fkey + '|' + data.localSeq);
    if (!ambiguous && link && link.lost) {
      declareLost(r, fkey + '|' + data.localSeq, false);
      return { ok: false, error: 'LINK_LOST' };
    }
    if (!ambiguous && (!link || !link.interact)) {
      e.dropped++; // interação desconhecida (recusada, retenção) ou não executada (CAPTURE_ONLY/PROPOSED): sem consequência
      return { ok: false, error: link ? 'NOT_INTERACT' : 'UNKNOWN_STEP' };
    }
    const msg = message(data.message);
    const limitations = clean(data.limitations, LIMITATIONS, 8);
    if (msg.availability === 'SUPPRESSED' && !limitations.includes('MESSAGE_SUPPRESSED')) {
      limitations.push('MESSAGE_SUPPRESSED');
    }
    let st = null;
    if (data.type === 'ELEMENT_STATE_CHANGED') {
      const s = data.state || {};
      if (!STATE_ATTRS.has(s.attribute) || !STATE_VALUES.has(s.from) || !STATE_VALUES.has(s.to)) {
        e.dropped++;
        return { ok: false, error: 'BAD_EVIDENCE' };
      }
      st = { attribute: s.attribute, from: s.from, to: s.to };
    }
    const type = ambiguous ? 'AMBIGUOUS_FEEDBACK' : data.type;
    produce(r, link, {
      at,
      type,
      source: type === 'VALIDATION_FEEDBACK' ? 'FORM_OBSERVER' : 'FEEDBACK_OBSERVER',
      relation: 'AFTER_ACTION',
      confidence: ambiguous ? 'AMBIGUOUS' : (CONFIDENCE.has(data.confidence) && data.confidence !== 'AMBIGUOUS' && data.confidence !== 'INCOMPLETE' ? data.confidence : 'TEMPORALLY_ASSOCIATED'),
      classification: CLASSIFICATION.has(data.classification) ? data.classification : (ambiguous ? 'UNKNOWN_FEEDBACK' : null),
      document: docOf(link || { frameId }, data.document),
      region: mask(data.region, 120),
      message: msg,
      field: mask(data.field, 120),
      state: st,
      navigation: null,
      signals: clean(data.signals, SIGNALS, 8),
      candidates: ambiguous ? Math.max(2, Math.min(20, Number(data.candidates) || 2)) : 0,
      signalsObserved: 0,
      limitations: limitations.slice(0, 8),
    });
    return { ok: true };
  }

  /** Encerra uma janela exatamente uma vez. reason: null (concluída), 'page' (INCOMPLETA pela página) ou a limitação. */
  function closeWindow(r, key, link, reason, f) {
    const e = r.evidence;
    if (!e.open[key]) {
      return;
    }
    const frameId = e.open[key].frameId;
    delete e.open[key];
    const complete = reason === null && !(f.limitations || []).includes('PARTIAL_COVERAGE');
    const limitations = clean([...(f.limitations || []), ...(reason && reason !== 'page' ? [reason] : [])], LIMITATIONS, 8);
    if (!complete) {
      e.incomplete++;
    }
    produce(r, link, {
      at: f.at, type: complete ? 'OBSERVATION_COMPLETE' : 'OBSERVATION_INCOMPLETE', source: reason && reason !== 'page' ? 'RECORDER' : 'FEEDBACK_OBSERVER',
      relation: 'AFTER_ACTION', confidence: complete ? 'DIRECTLY_OBSERVED' : 'INCOMPLETE', classification: null,
      document: f.document || docOf(link || { frameId }), region: null, message: none(), field: null, state: null, navigation: null, signals: [],
      candidates: 0, signalsObserved: f.signalsObserved || 0, limitations,
    });
  }

  /** Janelas abertas de um frame (ou de todos) declaradas INCOMPLETAS pelo service worker. */
  function cut(r, limitation, frameId) {
    if (!enabled(r)) {
      return 0;
    }
    let n = 0;
    for (const key of Object.keys(r.evidence.open)) {
      if (frameId == null || r.evidence.open[key].frameId === frameId) {
        const link = linkFor(r, key);
        if (!link || link.lost) {
          declareLost(r, key, true);
        } else {
          closeWindow(r, key, link, limitation, { at: now(), signalsObserved: 0, limitations: [] });
        }
        n++;
      }
    }
    return n;
  }

  function documentReplaced(r, frameId) {
    return cut(r, 'WINDOW_CUT_BY_NAVIGATION', frameId);
  }

  /**
   * Navegação na aba (documento principal). Candidatas: interações que operam a página, executadas de fato, no
   * documento principal, recebidas até NAV_WINDOW_MS antes. Uma: ROUTE_CHANGED/DOCUMENT_CHANGED associada; várias:
   * AMBIGUOUS_FEEDBACK sem etapa; nenhuma: não é evidência de nenhuma etapa.
   */
  function navigation(r, kind, rawUrl) {
    if (!enabled(r) || r.state !== 'RECORDING') {
      return;
    }
    const t = now();
    const k = kind === 'NEW_DOCUMENT' ? 'NEW_DOCUMENT' : 'SAME_DOCUMENT';
    const u = url(rawUrl);
    const cands = Array.from(linksOf(r).values()).filter((l) => l.interact && l.frameId === 0 && OPERATIONS.has(l.action) && l.receivedAt <= t
      && t - l.receivedAt <= NAV_WINDOW_MS && !l.navKinds.includes(k + '|' + u));
    if (!cands.length) {
      return;
    }
    for (const l of cands) {
      l.navKinds.push(k + '|' + u);
    }
    const ambiguous = cands.length > 1;
    produce(r, ambiguous ? null : cands[0], {
      at: t, type: ambiguous ? 'AMBIGUOUS_FEEDBACK' : (k === 'NEW_DOCUMENT' ? 'DOCUMENT_CHANGED' : 'ROUTE_CHANGED'), source: 'NAVIGATION_OBSERVER',
      relation: 'AFTER_ACTION', confidence: ambiguous ? 'AMBIGUOUS' : 'TEMPORALLY_ASSOCIATED', classification: ambiguous ? 'UNKNOWN_FEEDBACK' : null,
      document: { frame: 'MAIN', inShadow: false, title: null, url: u }, region: null, message: none(), field: null, state: null,
      navigation: { kind: k, url: u }, signals: [k === 'NEW_DOCUMENT' ? 'NEW_DOCUMENT' : 'HISTORY_API'], candidates: ambiguous ? Math.min(20, cands.length) : 0,
      signalsObserved: 0, limitations: [],
    });
  }

  // ------------------------------------------------------------------------------ emissão

  function produce(r, link, f) {
    if (link && !link.emitted) {
      if (link.queue.length >= MAX_QUEUED_PER_STEP) {
        r.evidence.lost++; // perda declarada
        return;
      }
      link.queue.push(f);
      return;
    }
    emit(r, link, f);
  }

  function emit(r, link, f) {
    const e = r.evidence;
    if (e.halted) {
      return;
    }
    if (!link && f.type !== 'AMBIGUOUS_FEEDBACK') {
      e.lost++; // invariante do contrato: só a ambígua sai sem etapa — qualquer outra é perda declarada, nunca evento inválido
      return;
    }
    if (e.outbox.length >= MAX_EVIDENCE_PENDING) {
      e.lost++; // limite de pendentes: perda DECLARADA (EVIDENCE_LOST), nunca descarte silencioso
      return;
    }
    const seq = ++e.nextSeq;
    const payload = {
      recordingId: r.recordingId,
      evidenceId: 'evd-' + r.recordingId.slice(-24) + '-' + seq,
      evidenceSequence: seq,
      eventId: link ? link.eventId : null,
      stepSequence: link ? link.sequence : null,
      observedAt: new Date(f.at).toISOString(),
      type: f.type,
      source: f.source,
      relation: f.relation,
      confidence: f.confidence,
      classification: f.classification,
      document: f.document,
      details: { region: f.region, message: f.message, field: f.field, state: f.state, navigation: f.navigation, signals: f.signals,
        candidates: f.candidates, signalsObserved: f.signalsObserved },
      limitations: f.limitations,
    };
    if (!link) {
      e.unattributed++;
    }
    e.byType[f.type] = (e.byType[f.type] || 0) + 1;
    e.lastObservedAt = payload.observedAt;
    e.latencyMs.push(Math.max(0, now() - f.at));
    if (e.latencyMs.length > 200) {
      e.latencyMs.shift();
    }
    e.outbox.push({ sequence: seq, event: payload });
    deps.persist && deps.persist(r);
    deps.emitEvidence(r, payload);
  }

  // ------------------------------------------------------------------------------ entrega

  function ack(r, upTo, rejected) {
    const e = r.evidence;
    const rej = new Set((rejected || []).filter((n) => Number.isInteger(n)));
    const keep = [];
    for (const o of e.outbox) {
      if (o.sequence <= upTo) {
        if (rej.has(o.sequence)) {
          e.rejected++;
        } else {
          e.acknowledged++;
        }
      } else {
        keep.push(o);
      }
    }
    e.outbox = keep;
    e.acked = Math.max(e.acked, Math.min(upTo, e.nextSeq));
    deps.persist && deps.persist(r);
    return state(r);
  }

  /** Reemite, em ordem, as evidências retidas depois de afterSequence (mesma identidade: o Studio deduplica). */
  function sync(r, afterSequence) {
    let resent = 0;
    for (const o of r.evidence.outbox) {
      if (o.sequence > afterSequence) {
        deps.emitEvidence(r, o.event);
        resent++;
      }
    }
    return { resent, evidence: state(r) };
  }

  /** Chegada das mensagens do observador até a marca (fronteiras: pausa, parada, troca de modo). */
  function arrived(r, key, lastFeedback) {
    return !enabled(r) || !lastFeedback || (r.evidence.fframes[key] || 0) >= lastFeedback;
  }

  async function awaitAcks(r, maxMs, sleep) {
    const deadline = now() + maxMs;
    while (enabled(r) && r.evidence.outbox.length && now() < deadline) {
      await sleep(25);
    }
  }

  /** Cancelamento: as evidências retidas e as que esperam etapa deixam de existir. */
  function discardOutbox(r) {
    if (enabled(r)) {
      r.evidence.halted = true;
      r.evidence.outbox = [];
      r.evidence.open = {};
      links.delete(r.recordingId);
      deps.persist && deps.persist(r);
    }
  }

  function forget(recordingId) {
    links.delete(recordingId);
  }

  function metrics(r) {
    if (!enabled(r)) {
      return null;
    }
    const e = r.evidence;
    const ms = e.latencyMs.slice().sort((a, b) => a - b);
    const pct = (p) => (ms.length ? ms[Math.min(ms.length - 1, Math.floor(p * ms.length))] : null);
    return { produced: e.nextSeq, pending: e.outbox.length, lost: e.lost, unattributed: e.unattributed, incomplete: e.incomplete, dropped: e.dropped,
      openWindows: Object.keys(e.open).length, byType: { ...e.byType }, lastObservedAt: e.lastObservedAt,
      emitLatencyMs: { p50: pct(0.5), p95: pct(0.95), max: ms.length ? ms[ms.length - 1] : null, samples: ms.length } };
  }

  return { enable, enabled, state, summary, onEntry, onStepEmitted, lateForm, feedback, cut, documentReplaced, navigation, ack, sync, arrived, awaitAcks,
    discardOutbox, forget, metrics };
}
