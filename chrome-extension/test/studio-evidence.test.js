// Pipeline de EVIDÊNCIAS observadas (STUDIO-005) no service worker: capacidade, vínculo com a etapa, ordem, ambiguidade,
// 2ª barreira de privacidade, janelas exatamente uma vez, navegação, entrega confiável, limites e ciclo de vida.
// Todo payload emitido é conferido contra o contrato (contracts/studio-bridge.schema.json).
import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath, pathToFileURL } from 'node:url';
import path from 'node:path';
import { check } from './helpers/mini-schema.js';

const here = path.dirname(fileURLToPath(import.meta.url));
// eslint-disable-next-line no-new-func
new Function(readFileSync(path.join(here, '../src/shared/privacy.js'), 'utf8'))();

let store;
let clock;
globalThis.chrome = {
  storage: { session: { get: async () => ({ ...store }), set: async (o) => Object.assign(store, JSON.parse(JSON.stringify(o))), remove: async (k) => { delete store[k]; } } },
  tabs: { get: async (id) => ({ id, title: 'Login de teste' }), query: async () => [{ id: 1, title: 'Login de teste', active: true }], onRemoved: { addListener: () => {} } },
  webNavigation: { getAllFrames: async () => [{ frameId: 0, url: 'x' }] },
  alarms: { create: async () => {}, clear: async () => {}, onAlarm: { addListener: () => {} } },
};

const { createStudioRecorder } = await import(pathToFileURL(path.join(here, '../src/background/studio-recording.js')).href);
const { MAX_EVIDENCE_PENDING } = await import(pathToFileURL(path.join(here, '../src/background/studio-evidence.js')).href);

function fakeCapture() {
  const sessions = new Map();
  const hooks = { onStatus: [], afterBase: [], frameAttached: [], onDocumentReplaced: [] };
  return {
    ACTIVE: new Set(['ACTIVE']),
    hooks,
    get: (tabId) => sessions.get(tabId) || null,
    on: (name, fn) => hooks[name] && hooks[name].push(fn),
    async acquire(tabId, consumer) {
      let rec = sessions.get(tabId);
      if (!rec) {
        rec = { tabId, sessionId: 'cs-1', status: 'ACTIVE', consumers: [], frames: {}, fullCaptures: 1, deltasApplied: 0, resyncs: 0, latestCaptureId: 'cap-1' };
        sessions.set(tabId, rec);
      }
      rec.consumers.push(consumer);
      return rec;
    },
    async release(tabId, consumer) {
      const rec = sessions.get(tabId);
      if (rec) {
        rec.consumers = rec.consumers.filter((c) => c !== consumer);
        if (!rec.consumers.length) {
          sessions.delete(tabId);
        }
      }
      return rec;
    },
    async synchronize() {
      return { syncState: 'SYNCHRONIZED', latestCaptureId: 'cap-2' };
    },
  };
}

const policy = {
  evaluate: () => ({ allowed: true, kind: 'LAB', reason: null }),
  isAllowedUrl: () => true,
  tabUrl: async () => 'http://127.0.0.1:18765/lab/studio005/login-erro.html',
  async assertAllowed(tabId) {
    return { id: tabId, url: 'http://127.0.0.1:18765/lab/studio005/login-erro.html', access: { allowed: true, kind: 'LAB' } };
  },
  onRemoved: () => {},
};

function withLock() {
  const locks = new Map();
  return (key, fn) => {
    const prev = locks.get(key) || Promise.resolve();
    const next = prev.catch(() => {}).then(fn);
    locks.set(key, next.catch(() => {}));
    return next;
  };
}

let contentCalls;
let sensor;
let hostDelay; // Promise que segura a correlação (etapa ainda não emitida)
function fakeContent(tabId, cmd, args, frameId) {
  contentCalls.push({ cmd, args: JSON.parse(JSON.stringify(args || {})), frameId });
  if (cmd === 'cs.recorder.start') {
    sensor = { epoch: args.epoch, mode: args.mode, lastSeq: 0, active: true, lastFeedback: 0 };
    return Promise.resolve({ active: true, epoch: args.epoch, mode: args.mode, lastSeq: 0, lastFeedback: 0 });
  }
  if (cmd === 'cs.recorder.flush' || cmd === 'cs.recorder.status') {
    return Promise.resolve(sensor ? { active: sensor.active, epoch: sensor.epoch, lastSeq: sensor.lastSeq, lastFeedback: sensor.lastFeedback } : { active: false });
  }
  if (cmd === 'cs.recorder.stop') {
    const out = sensor ? { active: false, epoch: sensor.epoch, lastSeq: sensor.lastSeq, lastFeedback: sensor.lastFeedback } : { active: false };
    if (sensor) {
      sensor.active = false;
    }
    return Promise.resolve(out);
  }
  if (cmd === 'cs.recorder.mode') {
    sensor.mode = args.mode;
    return Promise.resolve({ active: true, epoch: sensor.epoch, mode: sensor.mode, lastSeq: sensor.lastSeq });
  }
  if (cmd === 'cs.verifyLocators') {
    return Promise.resolve({ checks: [] });
  }
  return Promise.resolve({ ok: true });
}

async function fakeHost(type, payload) {
  if (hostDelay) {
    await hostDelay;
  }
  if (type === 'recorder.element.describe') {
    return {
      resolution: 'RESOLVED', captureId: 'cap-2', nodeId: payload.nodeId, targetId: 'tgt-' + payload.nodeId, documentId: 'doc:0', kind: 'BUTTON', role: 'button',
      name: 'Entrar', tag: 'button', htmlId: 'entrar', htmlName: null, sensitive: false,
      locators: [{ strategy: 'ID', description: 'id "entrar"', css: '#entrar', xpath: null, matchCount: 1, matchesTarget: true, stability: 'HEURISTIC_STABLE' }],
      preferredLocator: 0, accessPath: [], context: [], lookalikes: 0, notes: [], verification: { primary: { semantic: 's0', css: '#entrar' }, fallbacks: [] },
    };
  }
  return { checks: [] };
}

let capture;
let posted;
let recorder;
const CTRL = 'studio-a';

function build() {
  store = {};
  contentCalls = [];
  sensor = null;
  hostDelay = null;
  capture = fakeCapture();
  posted = [];
  const client = { post: async (m) => { posted.push(JSON.parse(JSON.stringify(m))); }, onHostMessage: () => {} };
  recorder = createStudioRecorder({
    client, broadcast: () => {}, withLock: withLock(), capture, policy, now: () => clock,
    // esperas curtas avançam o relógio; a corrida de 2,5 s da sincronização da captura não "consome" a janela de navegação
    sleep: (ms) => (ms >= 1000 ? new Promise(() => {}) : (clock += ms, Promise.resolve())),
    toContent: fakeContent, host: fakeHost, tabKey: (t) => 'tab-' + t, frameOfNode: () => 0,
    errorInfo: (e) => ({ code: (e && e.code) || 'ERROR', message: String(e && e.message || e), reason: e && e.reason }),
  });
  recorder.init(Promise.resolve());
}

beforeEach(() => {
  clock = 2_000_000;
  build();
});

let seq = 0;
async function send(command, payload, controller = CTRL) {
  const id = 'sc-' + (++seq);
  await recorder.onCommand({ protocol: 'nekkora-nm/1', type: 'studio.command', id, command, payload, controller: { instanceId: controller } });
  const reply = posted.filter((m) => m.type === 'studio.reply' && m.id === id).pop();
  assert.ok(reply, 'resposta para ' + command);
  return reply;
}

async function start(capabilities = ['steps/1', 'evidence/1'], mode = 'CAPTURE_AND_INTERACT') {
  const tabs = await send('browser.tabs.list', {});
  const ref = tabs.payload.tabs[0].tabRef;
  return send('studio.recording.start', { recordingId: 'rec-5', operationId: 'op-1', tabRef: ref, mode, leaseMs: 30000, capabilities, routeRef: 'route-a' });
}

let localSeq = 0;
function interact(action, targetKey = 'n3', extra = {}, frameId = 0) {
  sensor.lastSeq = ++localSeq;
  const res = recorder.onSensor(1, frameId, 'interaction', {
    epoch: sensor.epoch,
    mode: sensor.mode,
    interaction: { localSeq, action, targetKey, observation: sensor.mode === 'CAPTURE_ONLY' ? 'PROPOSED' : 'INTERACTION_OBSERVED', firstAt: clock, lastAt: clock,
      events: 1, trigger: null, option: null, key: null, sensitive: false, evidence: [], notes: [], ...extra },
    element: { tag: 'button', type: null, role: null, htmlId: 'entrar', htmlName: null, name: 'Entrar', sensitive: false, inShadow: false },
    url: 'http://127.0.0.1:18765/lab/studio005/login-erro.html?token=abc123def456ghi789', title: 'Login de teste',
  });
  return { ...res, localSeq };
}

let fseq = 0;
function feedback(data, frameId = 0) {
  sensor.lastFeedback = ++fseq;
  return recorder.onSensor(1, frameId, 'feedback', { epoch: sensor.epoch, fseq, at: clock, document: { childFrame: false, inShadow: false, title: 'Login de teste',
    url: 'http://127.0.0.1:18765/lab/studio005/login-erro.html' }, ...data });
}

function errorMessage(localSeqOf, text = 'Usuário ou senha inválidos.', extra = {}) {
  return feedback({ kind: 'evidence', localSeq: localSeqOf, candidates: 0, type: 'VISIBLE_ERROR_MESSAGE', classification: 'ERROR_INDICATOR',
    confidence: 'TEMPORALLY_ASSOCIATED', region: 'mensagem de erro do formulário login', message: { availability: 'AVAILABLE', text }, field: null, state: null,
    signals: ['ROLE_ALERT', 'ERROR_HINT'], limitations: [], ...extra });
}

const settle = async () => {
  for (let i = 0; i < 40; i++) {
    await new Promise((r) => setImmediate(r));
  }
};
const events = (name) => posted.filter((m) => m.type === 'studio.event' && m.event === name).map((m) => m.payload);
const steps = () => events('studio.recording.step');
const evidences = () => events('studio.recording.evidence');

function assertContract(list) {
  for (const e of list) {
    assert.deepEqual(check('EvidenceEventPayload', e), [], JSON.stringify(e));
  }
}

// ------------------------------------------------------------------------------------------- capacidade

test('S005-003/004 — evidence/1 negociada: o sensor recebe evidence=true; sem a capacidade nada muda (Studio antigo)', async () => {
  const r = await start();
  assert.equal(r.ok, true, JSON.stringify(r));
  assert.equal(contentCalls.find((c) => c.cmd === 'cs.recorder.start').args.evidence, true);
  assert.deepEqual(r.payload.recording.evidence, { enabled: true, lastSequence: 0, ackedSequence: 0, pending: 0, lost: 0 });
  assert.deepEqual(check('Recording', r.payload.recording), []);

  build();
  const old = await start(['steps/1']);
  assert.equal(old.ok, true);
  assert.equal(contentCalls.find((c) => c.cmd === 'cs.recorder.start').args.evidence, false, 'Studio sem evidence/1: observador desligado');
  assert.equal(old.payload.recording.evidence, undefined, 'resposta igual à STUDIO-003');
  const { localSeq: s1 } = interact('CLICK');
  errorMessage(s1);
  await settle();
  assert.equal(evidences().length, 0, 'nenhum evento novo para um Studio que não o conhece');
  assert.equal(steps().length, 1);
});

test('S005-003 — evidence/1 sem steps/1 e capacidade desconhecida são recusadas (BAD_REQUEST)', async () => {
  const a = await start(['evidence/1']);
  assert.equal(a.ok, false);
  assert.equal(a.error.code, 'BAD_REQUEST');
  build();
  const b = await start(['steps/1', 'evidence/2']);
  assert.equal(b.error.code, 'BAD_REQUEST');
});

// ------------------------------------------------------------------------------------------- vínculo e ordem

test('S005-010/023/027 — mensagem de erro depois do clique: evidência vinculada à etapa (eventId, stepSequence), emitida DEPOIS dela', async () => {
  await start();
  const { localSeq: s1 } = interact('CLICK');
  hostDelay = new Promise((res) => setTimeout(res, 5)); // a etapa ainda está em correlação quando a evidência chega
  errorMessage(s1);
  assert.equal(evidences().length, 0, 'evidência espera a etapa (nenhuma evidência órfã antes dela)');
  await new Promise((res) => setTimeout(res, 10));
  await settle();
  const st = steps();
  const ev = evidences();
  assert.equal(st.length, 1);
  assert.equal(ev.length, 1);
  const iStep = posted.findIndex((m) => m.event === 'studio.recording.step');
  const iEv = posted.findIndex((m) => m.event === 'studio.recording.evidence');
  assert.ok(iStep < iEv, 'etapa antes da evidência');
  assert.equal(ev[0].eventId, st[0].eventId);
  assert.equal(ev[0].stepSequence, st[0].sequence);
  assert.equal(ev[0].type, 'VISIBLE_ERROR_MESSAGE');
  assert.equal(ev[0].classification, 'ERROR_INDICATOR');
  assert.equal(ev[0].confidence, 'TEMPORALLY_ASSOCIATED');
  assert.equal(ev[0].relation, 'AFTER_ACTION');
  assert.deepEqual(ev[0].details.message, { availability: 'SUPPRESSED', text: null }, 'modo estrito: a origem mandou texto, o service worker não o repassa');
  assert.ok(ev[0].limitations.includes('MESSAGE_SUPPRESSED'));
  assert.equal(ev[0].evidenceSequence, 1);
  assert.match(ev[0].evidenceId, /^evd-/);
  assert.equal(st[0].evidence.includes('VISIBLE_ERROR_MESSAGE'), false, 'a etapa não muda: evidências estruturadas vêm em evento próprio');
  assertContract(ev);
});

test('S005-021 — feedback ambíguo (duas interações candidatas): AMBIGUOUS_FEEDBACK sem etapa, contado como não atribuído', async () => {
  await start();
  interact('CLICK');
  interact('CLICK', 'n4');
  feedback({ kind: 'evidence', localSeq: null, candidates: 2, type: 'AMBIGUOUS_FEEDBACK', classification: 'STATUS_INDICATOR', confidence: 'AMBIGUOUS',
    region: 'status', message: { availability: 'AVAILABLE', text: 'Processando' }, signals: ['ROLE_STATUS'], limitations: [] });
  await settle();
  const ev = evidences().filter((e) => e.type === 'AMBIGUOUS_FEEDBACK');
  assert.equal(ev.length, 1);
  assert.equal(ev[0].eventId, null);
  assert.equal(ev[0].stepSequence, null);
  assert.equal(ev[0].confidence, 'AMBIGUOUS');
  assert.equal(ev[0].details.candidates, 2);
  assertContract(ev);
  const fin = await send('studio.recording.finish', { recordingId: 'rec-5', operationId: 'op-f' });
  assert.equal(fin.payload.summary.evidence.unattributed, 1);
});

test('S005-042 — evidência de iframe não é atribuída à ação do documento principal', async () => {
  await start();
  const { localSeq: s1 } = interact('CLICK');
  const r = errorMessage(s1, 'Erro no quadro', {}); // mesma seq local, mas vinda de OUTRO documento (frame 3)
  assert.equal(r.ok, true);
  const other = recorder.onSensor(1, 3, 'feedback', { epoch: sensor.epoch, fseq: 99, at: clock, kind: 'evidence', localSeq: s1, type: 'VISIBLE_ALERT',
    document: { childFrame: false }, message: { availability: 'AVAILABLE', text: 'Aviso do iframe' }, signals: ['ROLE_ALERT'], limitations: [] });
  assert.equal(other.ok, false);
  assert.equal(other.error, 'UNKNOWN_STEP');
  await settle();
  assert.deepEqual(evidences().map((e) => [e.type, e.details.message.availability]), [['VISIBLE_ERROR_MESSAGE', 'SUPPRESSED']], 'só a evidência do próprio documento');
});

test('S005-042/044 — documento filho: frame=FRAME e limitação CROSS_DOCUMENT_NOT_ATTRIBUTED preservada', async () => {
  await start();
  const { localSeq: s1 } = interact('CLICK', 'n3', {}, 2);
  recorder.onSensor(1, 2, 'feedback', { epoch: sensor.epoch, fseq: 1, at: clock, kind: 'evidence', localSeq: s1, type: 'VISIBLE_STATUS_MESSAGE',
    classification: 'STATUS_INDICATOR', confidence: 'TEMPORALLY_ASSOCIATED', document: { childFrame: true, inShadow: true, title: 'Quadro', url: 'http://127.0.0.1:18765/lab/studio005/frame.html' },
    region: 'status', message: { availability: 'AVAILABLE', text: 'Salvo no quadro' }, signals: ['ROLE_STATUS'], limitations: ['CROSS_DOCUMENT_NOT_ATTRIBUTED'] });
  await settle();
  const ev = evidences();
  assert.equal(ev.length, 1);
  assert.equal(ev[0].document.frame, 'FRAME');
  assert.equal(ev[0].document.inShadow, true);
  assert.ok(ev[0].limitations.includes('CROSS_DOCUMENT_NOT_ATTRIBUTED'));
  assertContract(ev);
});

// ------------------------------------------------------------------------------------------- privacidade

test('S005-047/051/052 — 2ª barreira: mensagem sensível suprimida, região/campo mascarados, campos extras e sinais desconhecidos descartados', async () => {
  await start();
  const { localSeq: s1 } = interact('CLICK');
  feedback({ kind: 'evidence', localSeq: s1, type: 'VISIBLE_ERROR_MESSAGE', classification: 'ERROR_INDICATOR', confidence: 'TEMPORALLY_ASSOCIATED',
    region: 'alerta de ana.souza@exemplo.com', field: 'CPF 123.456.789-09', message: { availability: 'AVAILABLE', text: 'CPF 123.456.789-09 já cadastrado' },
    signals: ['ROLE_ALERT', 'CSS_CLASS_ERROR'], limitations: ['INVENTADA'], html: '<div role=alert>x</div>', value: 'anateste', pageMap: { nodes: [] },
    document: { childFrame: false, inShadow: false, title: 'Pedido de ana.souza@exemplo.com', url: 'http://127.0.0.1:18765/lab/x.html?token=abc123def456ghi789jkl' } });
  await settle();
  const [ev] = evidences();
  const json = JSON.stringify(ev);
  for (const secret of ['ana.souza@exemplo.com', '123.456.789-09', 'abc123def456ghi789jkl', 'anateste', '<div', 'CSS_CLASS_ERROR', 'INVENTADA']) {
    assert.ok(!json.includes(secret), secret + ' vazou: ' + json);
  }
  assert.deepEqual(ev.details.message, { availability: 'SUPPRESSED', text: null });
  assert.ok(ev.limitations.includes('MESSAGE_SUPPRESSED'));
  assert.deepEqual(ev.details.signals, ['ROLE_ALERT']);
  assertContract([ev]);
});

test('S005-064 — tipo desconhecido, tipo reservado ao service worker e estado inválido são recusados', async () => {
  await start();
  const { localSeq: s1 } = interact('CLICK');
  for (const type of ['BUSINESS_SUCCESS', 'FORM_SUBMITTED', 'OBSERVATION_COMPLETE', 'DOCUMENT_CHANGED']) {
    assert.equal(feedback({ kind: 'evidence', localSeq: s1, type, signals: [] }).ok, false, type);
  }
  assert.equal(feedback({ kind: 'evidence', localSeq: s1, type: 'ELEMENT_STATE_CHANGED', state: { attribute: 'value', from: 'a', to: 'b' } }).ok, false);
  assert.equal(feedback({ kind: 'evidence', localSeq: s1, type: 'ELEMENT_STATE_CHANGED', confidence: 'DIRECTLY_OBSERVED', region: 'elemento',
    state: { attribute: 'aria-expanded', from: 'false', to: 'true' }, signals: ['TARGET_ATTRIBUTE'] }).ok, true);
  await settle();
  const ev = evidences();
  assert.deepEqual(ev.map((e) => e.type), ['ELEMENT_STATE_CHANGED']);
  assert.equal(ev[0].confidence, 'DIRECTLY_OBSERVED');
  assertContract(ev);
});

// ------------------------------------------------------------------------------------------- CAPTURE_ONLY

test('S005-035 — CAPTURE_ONLY (etapa proposta): nenhuma evidência de consequência é aceita', async () => {
  await start(['steps/1', 'evidence/1'], 'CAPTURE_ONLY');
  const { localSeq: s1 } = interact('CLICK');
  const r = errorMessage(s1);
  assert.equal(r.ok, false);
  assert.equal(r.error, 'NOT_INTERACT');
  await settle();
  assert.equal(steps().length, 1);
  assert.equal(evidences().length, 0);
  const fin = await send('studio.recording.finish', { recordingId: 'rec-5', operationId: 'op-f' });
  assert.equal(fin.payload.summary.evidence.produced, 0);
  assert.equal(fin.payload.summary.evidence.incomplete, 0, 'sem janela aberta: nada a declarar incompleto');
});

// ------------------------------------------------------------------------------------------- janelas

test('S005-019/024 — janela termina exatamente uma vez: COMPLETE com sinais observados; repetição recusada', async () => {
  await start();
  const { localSeq: s1 } = interact('CLICK');
  errorMessage(s1);
  assert.equal(feedback({ kind: 'complete', localSeq: s1, signalsObserved: 1, limitations: [] }).ok, true);
  assert.equal(feedback({ kind: 'complete', localSeq: s1, signalsObserved: 1, limitations: [] }).ok, false, 'duplicata');
  assert.equal(feedback({ kind: 'incomplete', localSeq: s1, limitations: ['WINDOW_CUT_BY_NAVIGATION'] }).ok, false);
  await settle();
  const ev = evidences();
  assert.deepEqual(ev.map((e) => e.type), ['VISIBLE_ERROR_MESSAGE', 'OBSERVATION_COMPLETE']);
  assert.equal(ev[1].details.signalsObserved, 1);
  assert.equal(ev[1].confidence, 'DIRECTLY_OBSERVED');
  assertContract(ev);
});

test('S005-036 — janela sem nenhum sinal: OBSERVATION_COMPLETE com 0 sinais (ausência observada ≠ observação indisponível)', async () => {
  await start();
  const { localSeq: s1 } = interact('CLICK');
  feedback({ kind: 'complete', localSeq: s1, signalsObserved: 0, limitations: [] });
  await settle();
  const ev = evidences();
  assert.deepEqual(ev.map((e) => [e.type, e.details.signalsObserved]), [['OBSERVATION_COMPLETE', 0]]);
});

test('S005-037 — pausa com janela aberta: OBSERVATION_INCOMPLETE (RECORDER, WINDOW_CUT_BY_PAUSE); depois da pausa nada é aceito', async () => {
  await start();
  const { localSeq: s1 } = interact('CLICK');
  await settle();
  const p = await send('studio.recording.pause', { recordingId: 'rec-5', operationId: 'op-p' });
  assert.equal(p.ok, true);
  const ev = evidences();
  assert.equal(ev.length, 1);
  assert.equal(ev[0].type, 'OBSERVATION_INCOMPLETE');
  assert.equal(ev[0].source, 'RECORDER');
  assert.equal(ev[0].confidence, 'INCOMPLETE');
  assert.deepEqual(ev[0].limitations, ['WINDOW_CUT_BY_PAUSE']);
  assert.equal(ev[0].eventId, steps()[0].eventId);
  const late = errorMessage(s1);
  assert.equal(late.ok, false);
  assert.equal(late.error, 'STALE_EPOCH');
  assertContract(ev);
});

test('S005-007/045 — documento substituído com janela aberta: DOCUMENT_CHANGED e INCOMPLETE (WINDOW_CUT_BY_NAVIGATION); a conclusão tardia da página é recusada', async () => {
  await start();
  const { localSeq: s1 } = interact('CLICK');
  await settle();
  for (const fn of capture.hooks.onDocumentReplaced) {
    fn({ tabId: 1 }, { url: 'http://127.0.0.1:18765/lab/studio005/painel.html?token=abc123def456ghi789jkl', pageSessionId: 'ps-2' });
  }
  await settle();
  assert.equal(feedback({ kind: 'incomplete', localSeq: s1, limitations: ['WINDOW_CUT_BY_NAVIGATION'] }).ok, false);
  const ev = evidences();
  assert.deepEqual(ev.map((e) => e.type), ['DOCUMENT_CHANGED', 'OBSERVATION_INCOMPLETE'], 'navegação antes do corte da janela');
  assert.deepEqual(ev[1].limitations, ['WINDOW_CUT_BY_NAVIGATION']);
  assert.equal(ev[0].details.navigation.kind, 'NEW_DOCUMENT');
  assert.deepEqual(ev[0].details.signals, ['NEW_DOCUMENT']);
  assert.ok(!JSON.stringify(ev[0]).includes('abc123def456ghi789jkl'));
  assertContract(ev);
});

test('S005-045 — corte informado pela página (pagehide) vira INCOMPLETE com o motivo da página', async () => {
  await start();
  const { localSeq: s1 } = interact('FILL', 'n1');
  feedback({ kind: 'incomplete', localSeq: s1, signalsObserved: 0, limitations: ['WINDOW_CUT_BY_NAVIGATION'] });
  await settle();
  const ev = evidences();
  assert.deepEqual(ev.map((e) => [e.type, e.source, e.limitations.join()]), [['OBSERVATION_INCOMPLETE', 'FEEDBACK_OBSERVER', 'WINDOW_CUT_BY_NAVIGATION']]);
});

// ------------------------------------------------------------------------------------------- formulário e navegação

test('S005-006/022 — FORM_SUBMITTED estruturado (DIRECTLY_OBSERVED, AT_ACTION), também quando o envio chega depois da etapa', async () => {
  await start();
  interact('CLICK', 'n3', { evidence: ['FORM_SUBMITTED'] });
  await settle();
  const { localSeq: s2 } = interact('PRESS_KEY', 'n1');
  await settle();
  recorder.onSensor(1, 0, 'interaction-evidence', { epoch: sensor.epoch, localSeq: s2, evidence: 'FORM_SUBMITTED' });
  recorder.onSensor(1, 0, 'interaction-evidence', { epoch: sensor.epoch, localSeq: s2, evidence: 'FORM_SUBMITTED' });
  await settle();
  const st = steps();
  const ev = evidences().filter((e) => e.type === 'FORM_SUBMITTED');
  assert.equal(st.length, 2, 'nenhuma etapa recriada');
  assert.deepEqual(ev.map((e) => e.eventId), [st[0].eventId, st[1].eventId], 'um FORM_SUBMITTED por etapa');
  assert.ok(ev.every((e) => e.confidence === 'DIRECTLY_OBSERVED' && e.relation === 'AT_ACTION' && e.source === 'INTERACTION_NORMALIZER'));
  assert.deepEqual(ev[0].details.signals, ['SUBMIT_EVENT']);
  assertContract(ev);
});

test('S005-007/008/021 — navegação depois do clique: ROUTE_CHANGED (SPA) e DOCUMENT_CHANGED, URL sanitizada; duas candidatas = ambígua', async () => {
  await start();
  interact('CLICK');
  await settle();
  clock += 200;
  recorder.onNavigation(1, 'SAME_DOCUMENT', 'http://127.0.0.1:18765/lab/studio005/spa.html?session=abc123def456ghi789jkl#/clientes');
  recorder.onNavigation(1, 'SAME_DOCUMENT', 'http://127.0.0.1:18765/lab/studio005/spa.html?session=abc123def456ghi789jkl#/clientes'); // repetida
  await settle();
  let ev = evidences();
  assert.equal(ev.length, 1);
  assert.equal(ev[0].type, 'ROUTE_CHANGED');
  assert.equal(ev[0].details.navigation.kind, 'SAME_DOCUMENT');
  assert.ok(!JSON.stringify(ev[0]).includes('abc123def456ghi789jkl'));
  assert.deepEqual(ev[0].details.signals, ['HISTORY_API']);
  assertContract(ev);

  clock += 5000; // fora da janela: a navegação seguinte não é desta etapa
  interact('CLICK');
  interact('CLICK', 'n4');
  await settle();
  recorder.onNavigation(1, 'NEW_DOCUMENT', 'http://127.0.0.1:18765/lab/studio005/painel.html');
  await settle();
  ev = evidences().slice(1);
  assert.equal(ev.length, 1);
  assert.equal(ev[0].type, 'AMBIGUOUS_FEEDBACK');
  assert.equal(ev[0].details.navigation.kind, 'NEW_DOCUMENT');
  assert.equal(ev[0].eventId, null);
  assertContract(ev);
});

test('S005-008 — navegação sem nenhuma ação recente não é evidência de etapa nenhuma', async () => {
  await start();
  interact('CLICK');
  await settle();
  clock += 10_000;
  recorder.onNavigation(1, 'NEW_DOCUMENT', 'http://127.0.0.1:18765/lab/studio005/outra.html');
  await settle();
  assert.equal(evidences().length, 0);
});

// ------------------------------------------------------------------------------------------- entrega

test('S005-024/025/026 — ACK cumulativo, sync reemite a MESMA identidade, renew informa o estado', async () => {
  await start();
  const { localSeq: s1 } = interact('CLICK');
  errorMessage(s1);
  feedback({ kind: 'complete', localSeq: s1, signalsObserved: 1, limitations: [] });
  await settle();
  const first = evidences();
  assert.equal(first.length, 2);
  const renew = await send('studio.recording.renew', { recordingId: 'rec-5' });
  assert.deepEqual(renew.payload.evidence, { enabled: true, lastSequence: 2, ackedSequence: 0, pending: 2, lost: 0 });
  assert.deepEqual(check('RenewResult', renew.payload), []);
  const ack = await send('studio.recording.evidence.ack', { recordingId: 'rec-5', upToSequence: 1 });
  assert.deepEqual(ack.payload.evidence, { enabled: true, lastSequence: 2, ackedSequence: 1, pending: 1, lost: 0 });
  assert.deepEqual(check('EvidenceAckResult', ack.payload), []);
  const sync = await send('studio.recording.evidence.sync', { recordingId: 'rec-5', afterSequence: 1 });
  assert.equal(sync.payload.resent, 1);
  assert.deepEqual(check('EvidenceSyncResult', sync.payload), []);
  const again = evidences().slice(2);
  assert.deepEqual(again, [first[1]], 'retransmissão idêntica (o Studio deduplica pelo evidenceId)');
  const bad = await send('studio.recording.evidence.ack', { recordingId: 'rec-5', upToSequence: -1 });
  assert.equal(bad.error.code, 'BAD_REQUEST');
});

test('S005-004 — ack/sync de evidências numa gravação sem evidence/1: BAD_REQUEST', async () => {
  await start(['steps/1']);
  const a = await send('studio.recording.evidence.ack', { recordingId: 'rec-5', upToSequence: 0 });
  assert.equal(a.error.code, 'BAD_REQUEST');
  const s = await send('studio.recording.evidence.sync', { recordingId: 'rec-5', afterSequence: 0 });
  assert.equal(s.error.code, 'BAD_REQUEST');
});

test('S005-053/054 — limite de pendentes: perda DECLARADA (lost, EVIDENCE_LOST) sem degradar a gravação', async () => {
  await start();
  const { localSeq: s1 } = interact('CLICK');
  await settle();
  for (let i = 0; i < MAX_EVIDENCE_PENDING + 5; i++) {
    feedback({ kind: 'evidence', localSeq: null, candidates: 2, type: 'AMBIGUOUS_FEEDBACK', region: 'status', message: { availability: 'NONE', text: null },
      signals: ['ROLE_STATUS'], limitations: [] });
  }
  await settle();
  assert.equal(evidences().length, MAX_EVIDENCE_PENDING);
  const view = await send('studio.recording.status', { recordingId: 'rec-5' });
  assert.equal(view.payload.recordings[0].state, 'RECORDING', 'evidência não é etapa: a gravação não degrada');
  assert.equal(view.payload.recordings[0].evidence.lost, 5);
  feedback({ kind: 'complete', localSeq: s1, signalsObserved: 0, limitations: [] });
  await send('studio.recording.steps.ack', { recordingId: 'rec-5', upToSequence: 1 });
  const fin = await send('studio.recording.finish', { recordingId: 'rec-5', operationId: 'op-f' });
  assert.ok(fin.payload.summary.limitations.includes('EVIDENCE_LOST'));
  assert.ok(fin.payload.summary.limitations.includes('EVIDENCE_UNCONFIRMED'));
  assert.equal(fin.payload.summary.evidence.lost, 6, '5 + a conclusão que não coube');
  assert.equal(fin.payload.summary.actionCapture, 'AVAILABLE', 'a captura de ações não muda por causa das evidências');
  assert.deepEqual(check('Summary', fin.payload.summary), []);
});

// ------------------------------------------------------------------------------------------- ciclo de vida

test('S005-040 — cancelamento: evidências retidas e as que esperavam a etapa deixam de existir', async () => {
  await start();
  const { localSeq: s1 } = interact('CLICK');
  hostDelay = new Promise((res) => setTimeout(res, 20)); // a etapa ainda em correlação quando o cancelamento chega
  errorMessage(s1);
  const c = await send('studio.recording.cancel', { recordingId: 'rec-5', operationId: 'op-c' });
  assert.equal(c.ok, true);
  await settle();
  assert.equal(evidences().length, 0);
  assert.equal(c.payload.summary.evidence.unacknowledged, 0);
});

test('S005-039 — finalização: janelas abertas declaradas (WINDOW_CUT_BY_STOP), resumo com as contagens e no contrato', async () => {
  await start();
  const { localSeq: s1 } = interact('CLICK');
  errorMessage(s1);
  await settle();
  const fin = await send('studio.recording.finish', { recordingId: 'rec-5', operationId: 'op-f' });
  const ev = evidences();
  assert.deepEqual(ev.map((e) => e.type), ['VISIBLE_ERROR_MESSAGE', 'OBSERVATION_INCOMPLETE']);
  assert.deepEqual(ev[1].limitations, ['WINDOW_CUT_BY_STOP']);
  const s = fin.payload.summary.evidence;
  assert.deepEqual(s, { produced: 2, acknowledged: 0, rejected: 0, unacknowledged: 2, lost: 0, unattributed: 0, incomplete: 1 });
  assert.deepEqual(check('Summary', fin.payload.summary), []);
  assertContract(ev);
});

test('S005-038 — retomada: nova época com evidence=true; observações da época anterior são recusadas', async () => {
  await start();
  const old = sensor.epoch;
  await send('studio.recording.pause', { recordingId: 'rec-5', operationId: 'op-p' });
  const r = await send('studio.recording.resume', { recordingId: 'rec-5', operationId: 'op-r' });
  assert.equal(r.ok, true);
  const starts = contentCalls.filter((c) => c.cmd === 'cs.recorder.start');
  assert.equal(starts.length, 2);
  assert.equal(starts[1].args.evidence, true);
  assert.notEqual(sensor.epoch, old);
  const res = recorder.onSensor(1, 0, 'feedback', { epoch: old, fseq: 1, kind: 'complete', localSeq: 1 });
  assert.equal(res.error, 'STALE_EPOCH');
});

test('Desempenho (S005-063) — métricas do pipeline: contagens por tipo e latência, sem conteúdo', async () => {
  await start();
  const { localSeq: s1 } = interact('CLICK');
  errorMessage(s1);
  await settle();
  const m = recorder.evidenceMetrics('rec-5');
  assert.equal(m.produced, 1);
  assert.equal(m.byType.VISIBLE_ERROR_MESSAGE, 1);
  assert.equal(m.openWindows, 1);
  assert.ok(!JSON.stringify(m).includes('inválidos'), 'métricas sem texto de mensagem');
});

test('S005-041/046 — degradação (captura encerrada ou permissão revogada): sensores parados, janelas declaradas e observações posteriores recusadas', async () => {
  for (const reason of ['CAPTURE_ENDED', 'PERMISSION_REVOKED']) {
    build();
    await start();
    const { localSeq: s1 } = interact('CLICK');
    await settle();
    const rec = capture.get(1);
    rec.status = 'COMPLETED';
    rec.reason = reason === 'CAPTURE_ENDED' ? null : reason;
    for (const fn of capture.hooks.onStatus) {
      fn(rec);
    }
    for (let i = 0; i < 5; i++) {
      await settle();
    }
    const view = await send('studio.recording.status', { recordingId: 'rec-5' });
    assert.equal(view.payload.recordings[0].state, 'DEGRADED', reason);
    assert.ok(contentCalls.some((c) => c.cmd === 'cs.recorder.stop'), 'sensores (e observador) desligados');
    const ev = evidences();
    assert.deepEqual(ev.map((e) => [e.type, e.source, e.limitations.join()]), [['OBSERVATION_INCOMPLETE', 'RECORDER', 'WINDOW_CUT_BY_STOP']], reason);
    assert.equal(errorMessage(s1).error, 'STALE_EPOCH', 'nada observado depois da degradação');
    assertContract(ev);
  }
});

// ------------------------------------------------------------------------------------------- revisão PR #6 — P1-b

/** "Reinicia" o service worker: instância nova sobre o MESMO storage.session (e a mesma página/sensor), como o Chrome faz. */
async function restartServiceWorker() {
  const client = { post: async (m) => { posted.push(JSON.parse(JSON.stringify(m))); }, onHostMessage: () => {} };
  recorder = createStudioRecorder({
    client, broadcast: () => {}, withLock: withLock(), capture, policy, now: () => clock,
    sleep: (ms) => (ms >= 1000 ? new Promise(() => {}) : (clock += ms, Promise.resolve())),
    toContent: fakeContent, host: fakeHost, tabKey: (t) => 'tab-' + t, frameOfNode: () => 0,
    errorInfo: (e) => ({ code: (e && e.code) || 'ERROR', message: String(e && e.message || e), reason: e && e.reason }),
  });
  recorder.init(Promise.resolve());
  await recorder.restore();
}

test('REV-P1b — reinício do service worker com janela aberta e etapa JÁ emitida: a conclusão sai vinculada à etapa (nunca eventId nulo fora da ambígua)', async () => {
  await start();
  const { localSeq: s1 } = interact('CLICK');
  await settle();
  const [step] = steps();
  assert.ok(step, 'etapa emitida antes do reinício');
  await restartServiceWorker();
  const r = feedback({ kind: 'complete', localSeq: s1, signalsObserved: 0, limitations: [] });
  assert.equal(r.ok, true, JSON.stringify(r));
  await settle();
  const ev = evidences();
  assert.deepEqual(ev.map((e) => [e.type, e.eventId]), [['OBSERVATION_COMPLETE', step.eventId]], 'vínculo técnico persistido, sem proximidade temporal');
  assertContract(ev);
});

test('REV-P1b — reinício com janela aberta e etapa NÃO emitida (perdida no reinício): perda declarada, nenhum evento inválido, fila segue', async () => {
  await start();
  hostDelay = new Promise(() => {}); // a correlação da etapa nunca termina neste processo
  const { localSeq: s1 } = interact('CLICK');
  await settle();
  assert.equal(steps().length, 0);
  hostDelay = null;
  await restartServiceWorker();
  feedback({ kind: 'evidence', localSeq: s1, type: 'VISIBLE_ALERT', confidence: 'TEMPORALLY_ASSOCIATED', region: 'alerta', message: { availability: 'NONE', text: null },
    signals: ['ROLE_ALERT'], limitations: [] });
  feedback({ kind: 'complete', localSeq: s1, signalsObserved: 1, limitations: [] });
  await settle();
  assert.equal(evidences().length, 0, 'nada é emitido sem etapa (e nada inválido entra na outbox)');
  const st = await send('studio.recording.status', { recordingId: 'rec-5' });
  assert.ok(st.payload.recordings[0].evidence.lost >= 2, 'perda declarada: ' + JSON.stringify(st.payload.recordings[0].evidence));
  assert.equal(st.payload.recordings[0].evidence.pending, 0, 'outbox sem item inválido');
  // a entrega continua: nova interação depois do reinício gera evidência normal
  const { localSeq: s2 } = interact('CLICK', 'n4');
  await settle();
  feedback({ kind: 'complete', localSeq: s2, signalsObserved: 0, limitations: [] });
  await settle();
  const ev = evidences();
  assert.equal(ev.length, 1);
  assert.equal(ev[0].eventId, steps()[0].eventId);
  assertContract(ev);
  const fin = await send('studio.recording.finish', { recordingId: 'rec-5', operationId: 'op-f' });
  assert.ok(fin.payload.summary.limitations.includes('EVIDENCE_LOST'));
});
