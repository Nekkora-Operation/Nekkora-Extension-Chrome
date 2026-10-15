// Pipeline de etapas gravadas (STUDIO-003): sequência, correlação, entrega confiável, rota, ciclo de vida e privacidade.
import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath, pathToFileURL } from 'node:url';
import path from 'node:path';

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
const { HostError } = await import(pathToFileURL(path.join(here, '../src/background/native-client.js')).href);

/** CaptureSession falsa (um consumidor basta) com ganchos. */
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
  tabUrl: async () => 'http://127.0.0.1:18765/lab/studio003/login.html',
  async assertAllowed(tabId) {
    return { id: tabId, url: 'http://127.0.0.1:18765/lab/studio003/login.html', access: { allowed: true, kind: 'LAB' } };
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

/** Content script falso: registra comandos do sensor; o flush devolve o lastSeq informado pelo teste. */
let contentCalls;
let sensor;
function fakeContent(tabId, cmd, args, frameId) {
  contentCalls.push({ cmd, args: JSON.parse(JSON.stringify(args || {})), frameId });
  if (cmd === 'cs.recorder.start') {
    sensor = { epoch: args.epoch, mode: args.mode, lastSeq: 0, active: true };
    return Promise.resolve({ active: true, epoch: args.epoch, mode: args.mode, lastSeq: 0 });
  }
  if (cmd === 'cs.recorder.flush' || cmd === 'cs.recorder.status') {
    return Promise.resolve(sensor ? { active: sensor.active, epoch: sensor.epoch, lastSeq: sensor.lastSeq } : { active: false });
  }
  if (cmd === 'cs.recorder.stop') {
    const out = sensor ? { active: false, epoch: sensor.epoch, lastSeq: sensor.lastSeq } : { active: false };
    if (sensor) {
      sensor.active = false;
    }
    return Promise.resolve(out);
  }
  if (cmd === 'cs.recorder.mode') {
    sensor.mode = args.mode;
    return Promise.resolve({ active: true });
  }
  if (cmd === 'cs.verifyLocators') {
    if (liveFails) {
      return Promise.reject(new HostError('CONTENT_UNAVAILABLE', 'documento substituído'));
    }
    return Promise.resolve({ checks: [{ semantic: 's0', cssCount: 1, xpathCount: 1, cssMatchesTarget: true, xpathMatchesTarget: true }] });
  }
  return Promise.resolve({ ok: true });
}

let hostFails;
let liveFails;
let hostCalls;
function fakeHost(type, payload) {
  hostCalls.push(type);
  if (hostFails) {
    return Promise.reject(new HostError('UNKNOWN_NODE', 'nó inexistente'));
  }
  if (type === 'recorder.element.describe') {
    const names = { n1: 'Usuário', n2: 'Senha', n3: 'Entrar', n4: 'Contato ana@exemplo.com' };
    return Promise.resolve({
      resolution: 'RESOLVED', captureId: 'cap-2', nodeId: payload.nodeId, targetId: 'tgt-' + payload.nodeId, documentId: 'doc:0',
      kind: payload.nodeId === 'n3' ? 'BUTTON' : payload.nodeId === 'n2' ? 'PASSWORD_INPUT' : 'TEXT_INPUT', role: 'textbox',
      name: names[payload.nodeId] || 'Campo', tag: payload.nodeId === 'n3' ? 'button' : 'input',
      htmlId: payload.nodeId === 'n2' ? 'senha' : 'usuario', htmlName: null, sensitive: payload.nodeId === 'n2',
      locators: [{ strategy: 'ID', description: 'id "x"', css: '#x', xpath: null, matchCount: 1, matchesTarget: true, stability: 'HEURISTIC_STABLE' }],
      preferredLocator: 0, accessPath: [{ type: 'MAIN_DOCUMENT', label: 'Documento', locator: null, restriction: null }], context: [], lookalikes: 0,
      notes: [], verification: { primary: { semantic: 's0', css: '#x' }, fallbacks: [] },
    });
  }
  if (type === 'recorder.element.verify') {
    return Promise.resolve({ checks: [{ index: 0, checked: true, liveUnique: true }] });
  }
  return Promise.resolve({});
}

let capture;
let posted;
let recorder;
const CTRL = 'studio-a';

beforeEach(() => {
  store = {};
  clock = 1_000_000;
  contentCalls = [];
  hostCalls = [];
  sensor = null;
  hostFails = false;
  liveFails = false;
  capture = fakeCapture();
  posted = [];
  const client = { post: async (m) => { posted.push(JSON.parse(JSON.stringify(m))); }, onHostMessage: () => {} };
  recorder = createStudioRecorder({
    client, broadcast: () => {}, withLock: withLock(), capture, policy, now: () => clock, sleep: (ms) => { clock += ms; return Promise.resolve(); },
    toContent: fakeContent, host: fakeHost, tabKey: (t) => 'tab-' + t, frameOfNode: () => 0,
    errorInfo: (e) => ({ code: (e && e.code) || 'ERROR', message: String(e && e.message || e), reason: e && e.reason }),
  });
  recorder.init(Promise.resolve());
});

let seq = 0;
async function send(command, payload, controller = CTRL) {
  const id = 'sc-' + (++seq);
  await recorder.onCommand({ protocol: 'nekkora-nm/1', type: 'studio.command', id, command, payload, controller: { instanceId: controller } });
  const reply = posted.filter((m) => m.type === 'studio.reply' && m.id === id).pop();
  assert.ok(reply, 'resposta para ' + command);
  return reply;
}

async function startRecording(extra = { capabilities: ['steps/1'], routeRef: 'route-a' }, mode = 'CAPTURE_AND_INTERACT') {
  const tabs = await send('browser.tabs.list', {});
  const ref = tabs.payload.tabs[0].tabRef;
  const r = await send('studio.recording.start', { recordingId: 'rec-1', operationId: 'op-1', tabRef: ref, mode, leaseMs: 30000, ...extra });
  assert.equal(r.ok, true, JSON.stringify(r));
  return r;
}

let localSeq = 0;
/** O sensor entrega uma interação consolidada (como o content script faria). */
function interact(action, targetKey, extra = {}) {
  sensor.lastSeq = ++localSeq;
  return recorder.onSensor(1, 0, 'interaction', {
    epoch: sensor.epoch,
    interaction: { localSeq, action, targetKey, observation: sensor.mode === 'CAPTURE_ONLY' ? 'PROPOSED' : 'INTERACTION_OBSERVED', firstAt: clock, lastAt: clock,
      events: 3, trigger: null, option: null, key: null, sensitive: false, evidence: [], notes: [], ...extra },
    element: { tag: 'input', type: 'text', role: null, htmlId: 'usuario', htmlName: null, name: 'Usuário', sensitive: false, inShadow: false },
    url: 'http://127.0.0.1:18765/lab/studio003/login.html?token=abc123def456ghi789&cpf=123.456.789-09', title: 'Login de teste',
  });
}

const settle = async () => {
  for (let i = 0; i < 30; i++) {
    await new Promise((r) => setImmediate(r));
  }
};
const steps = () => posted.filter((m) => m.type === 'studio.event' && m.event === 'studio.recording.step').map((m) => m.payload);

test('S003-001/046 — com steps/1 o início liga os sensores; sem a capacidade a gravação é só técnica (STUDIO-002)', async () => {
  await startRecording({});
  assert.equal(contentCalls.filter((c) => c.cmd === 'cs.recorder.start').length, 0, 'Studio antigo: nenhum sensor');
  assert.equal(recorder.recordings.get('rec-1').steps, undefined);
  const fin = await send('studio.recording.finish', { recordingId: 'rec-1', operationId: 'op-f' });
  assert.equal(fin.payload.summary.actionCapture, 'NOT_AVAILABLE');
  assert.equal(fin.payload.summary.steps, undefined);

  beforeEachReset();
  const r = await startRecording();
  const st = contentCalls.find((c) => c.cmd === 'cs.recorder.start');
  assert.ok(st && /^e-[a-z0-9]{10}$/.test(st.args.epoch) && st.args.mode === 'CAPTURE_AND_INTERACT', JSON.stringify(st));
  assert.deepEqual(r.payload.recording.steps, { enabled: true, lastSequence: 0, ackedSequence: 0, pending: 0, lost: 0 });
});

function beforeEachReset() {
  store = {};
  contentCalls = [];
  posted = [];
  sensor = null;
  capture = fakeCapture();
  const client = { post: async (m) => { posted.push(JSON.parse(JSON.stringify(m))); }, onHostMessage: () => {} };
  recorder = createStudioRecorder({
    client, broadcast: () => {}, withLock: withLock(), capture, policy, now: () => clock, sleep: (ms) => { clock += ms; return Promise.resolve(); },
    toContent: fakeContent, host: fakeHost, tabKey: (t) => 'tab-' + t, frameOfNode: () => 0,
    errorInfo: (e) => ({ code: (e && e.code) || 'ERROR', message: String(e && e.message || e), reason: e && e.reason }),
  });
  recorder.init(Promise.resolve());
}

test('S003-028/034 — etapas em ordem de sequência, com rota, elemento correlacionado e localizador validado ao vivo', async () => {
  await startRecording();
  interact('FILL', 'n1');
  interact('FILL', 'n2', { sensitive: true });
  interact('CLICK', 'n3');
  await settle();
  const s = steps();
  assert.deepEqual(s.map((e) => [e.sequence, e.action, e.element.name]), [[1, 'FILL', 'Usuário'], [2, 'FILL', 'Senha'], [3, 'CLICK', 'Entrar']]);
  assert.ok(s.every((e) => e.routeRef === 'route-a' && e.recordingId === 'rec-1' && e.parameters.valueCaptured === false));
  assert.equal(s[2].element.preferredLocator, 0);
  assert.equal(s[2].element.locators[0].validation, 'LIVE', 'localizador executado na DOM real');
  assert.ok(s[2].evidence.includes('LOCATORS_VERIFIED_LIVE'));
  assert.equal(s[2].element.correlation.targetId, 'tgt-n3');
  assert.equal(new Set(s.map((e) => e.eventId)).size, 3);
});

test('S003-013/037 — senha e dados pessoais: sem valor, sem id HTML sensível, URL e textos sanitizados', async () => {
  await startRecording();
  interact('FILL', 'n2', { sensitive: true });
  interact('CLICK', 'n4');
  await settle();
  const [pass, contato] = steps();
  assert.equal(pass.element.sensitive, true);
  assert.equal(pass.element.htmlId, null, 'campo sensível não expõe id HTML');
  const raw = JSON.stringify(steps());
  assert.ok(!raw.includes('abc123def456ghi789'), 'token da URL redigido');
  assert.ok(!raw.includes('123.456.789-09'), 'CPF da URL redigido');
  assert.ok(!raw.includes('ana@exemplo.com'), 'e-mail no nome mascarado');
  assert.ok(!/"value"\s*:\s*"[^"]/.test(raw), 'nenhum valor digitado');
  assert.match(contato.element.name, /Contato/);
});

test('S003-045 — nenhum PageMap trafega: evento compacto (poucos KB) sem snapshot nem nós', async () => {
  await startRecording();
  interact('CLICK', 'n3');
  await settle();
  const e = steps()[0];
  const size = JSON.stringify(e).length;
  assert.ok(size < 4096, 'evento compacto: ' + size + ' bytes');
  for (const k of ['nodes', 'elements', 'snapshot', 'pageMap', 'verification']) {
    assert.ok(!(k in e) && !(k in e.element), 'sem ' + k);
  }
});

test('S003-030 — ACK cumulativo só depois do domínio; sync reemite em ordem o que não foi confirmado', async () => {
  await startRecording();
  interact('FILL', 'n1');
  interact('FILL', 'n2');
  interact('CLICK', 'n3');
  await settle();
  const ack = await send('studio.recording.steps.ack', { recordingId: 'rec-1', upToSequence: 1 });
  assert.deepEqual(ack.payload.steps, { enabled: true, lastSequence: 3, ackedSequence: 1, pending: 2, lost: 0 });
  posted.length = 0;
  const sync = await send('studio.recording.steps.sync', { recordingId: 'rec-1', afterSequence: 1 });
  assert.equal(sync.payload.resent, 2);
  assert.deepEqual(steps().map((e) => e.sequence), [2, 3]);
  assert.ok(steps().every((e) => e.notes.includes('RESENT')));
  const all = await send('studio.recording.steps.ack', { recordingId: 'rec-1', upToSequence: 3, rejected: [3] });
  assert.equal(all.payload.steps.pending, 0);
  const renew = await send('studio.recording.renew', { recordingId: 'rec-1' });
  assert.deepEqual(renew.payload.steps, { enabled: true, lastSequence: 3, ackedSequence: 3, pending: 0, lost: 0 }, 'renovação informa a entrega');
  const fin = await send('studio.recording.finish', { recordingId: 'rec-1', operationId: 'op-f' });
  assert.deepEqual(fin.payload.summary.steps, { produced: 3, acknowledged: 2, rejected: 1, unacknowledged: 0, lost: 0, discarded: fin.payload.summary.steps.discarded });
  assert.equal(fin.payload.summary.actionCapture, 'PARTIAL', 'etapa recusada pelo domínio torna a captura parcial');
  assert.ok(fin.payload.summary.limitations.includes('STEPS_REJECTED'));
});

test('S003-031/032 — pausa desliga sensores (eventos depois dela descartados); retomada abre nova época sem repetir', async () => {
  await startRecording();
  interact('FILL', 'n1');
  const oldEpoch = sensor.epoch;
  const paused = await send('studio.recording.pause', { recordingId: 'rec-1', operationId: 'op-p' });
  assert.equal(paused.payload.recording.state, 'PAUSED');
  assert.ok(contentCalls.some((c) => c.cmd === 'cs.recorder.flush' && c.args.reason === 'PAUSE'), 'consolida antes de pausar');
  assert.ok(contentCalls.some((c) => c.cmd === 'cs.recorder.stop'));
  const late = recorder.onSensor(1, 0, 'interaction', { epoch: oldEpoch, interaction: { localSeq: 99, action: 'CLICK', targetKey: 'n3' } });
  assert.deepEqual(late, { ok: false, error: 'STALE_EPOCH' });
  await send('studio.recording.resume', { recordingId: 'rec-1', operationId: 'op-r' });
  assert.notEqual(sensor.epoch, oldEpoch, 'nova época');
  interact('CLICK', 'n3');
  await settle();
  assert.deepEqual(steps().map((e) => e.action), ['FILL', 'CLICK'], 'nada repetido, nada da pausa');
  assert.equal(recorder.recordings.get('rec-1').steps.discarded.STALE_EPOCH, 1, 'descarte contado');
});

test('S003-005 — troca de modo consolida no modo anterior; etapas seguintes levam o modo novo', async () => {
  await startRecording();
  interact('FILL', 'n1');
  const m = await send('studio.recording.mode', { recordingId: 'rec-1', operationId: 'op-m', mode: 'CAPTURE_ONLY' });
  assert.equal(m.payload.recording.mode, 'CAPTURE_ONLY');
  const order = contentCalls.map((c) => c.cmd).filter((c) => c === 'cs.recorder.flush' || c === 'cs.recorder.mode');
  assert.deepEqual(order, ['cs.recorder.flush', 'cs.recorder.mode']);
  interact('CLICK', 'n3');
  await settle();
  assert.deepEqual(steps().map((e) => [e.mode, e.observation]), [['CAPTURE_AND_INTERACT', 'INTERACTION_OBSERVED'], ['CAPTURE_ONLY', 'PROPOSED']]);
});

test('S003-033 — troca de sessão: fronteira de sequência; atrasados da rota anterior nunca levam a rota nova', async () => {
  await startRecording();
  interact('FILL', 'n1');
  interact('FILL', 'n2');
  const r = await send('studio.recording.route', { recordingId: 'rec-1', operationId: 'op-route', routeRef: 'route-b' });
  assert.equal(r.payload.boundarySequence, 2);
  interact('CLICK', 'n3');
  await settle();
  assert.deepEqual(steps().map((e) => [e.sequence, e.routeRef]), [[1, 'route-a'], [2, 'route-a'], [3, 'route-b']]);
  const again = await send('studio.recording.route', { recordingId: 'rec-1', operationId: 'op-route', routeRef: 'route-b' });
  assert.equal(again.payload.duplicate, true, 'mesma operação: sem efeito repetido');
});

test('S003-041 — finalizar com etapas pendentes espera as confirmações no limite e declara o que faltou', async () => {
  await startRecording();
  interact('FILL', 'n1');
  interact('CLICK', 'n3');
  await settle();
  await send('studio.recording.steps.ack', { recordingId: 'rec-1', upToSequence: 1 });
  const fin = await send('studio.recording.finish', { recordingId: 'rec-1', operationId: 'op-f' });
  assert.equal(fin.payload.recording.state, 'COMPLETED');
  assert.equal(fin.payload.summary.actionCapture, 'PARTIAL');
  assert.ok(fin.payload.summary.limitations.includes('STEPS_UNCONFIRMED'));
  assert.equal(fin.payload.summary.steps.unacknowledged, 1);
  assert.equal(fin.payload.summary.pageMapExported, false);
  const sync = await send('studio.recording.steps.sync', { recordingId: 'rec-1', afterSequence: 1 });
  assert.equal(sync.payload.resent, 1, 'a etapa não confirmada continua disponível depois do fim (retenção)');
});

test('S003-041 — finalizar com tudo confirmado: AVAILABLE', async () => {
  await startRecording();
  interact('CLICK', 'n3');
  await settle();
  await send('studio.recording.steps.ack', { recordingId: 'rec-1', upToSequence: 1 });
  const fin = await send('studio.recording.finish', { recordingId: 'rec-1', operationId: 'op-f' });
  assert.equal(fin.payload.summary.actionCapture, 'AVAILABLE');
  assert.deepEqual(fin.payload.summary.limitations, []);
});

test('S003-042 — fila cheia: DEGRADED (STEP_BACKLOG_FULL), sensores desligados e NADA descartado', async () => {
  await startRecording();
  for (let i = 0; i < 100; i++) {
    interact('CLICK', 'n3');
  }
  await settle();
  await settle();
  const r = recorder.recordings.get('rec-1');
  assert.equal(r.state, 'DEGRADED');
  assert.equal(r.reason, 'STEP_BACKLOG_FULL');
  assert.equal(r.steps.nextSeq, 100);
  assert.equal(steps().length, 100, 'todas emitidas e retidas');
  assert.equal(r.steps.outbox.length, 100);
  assert.ok(contentCalls.some((c) => c.cmd === 'cs.recorder.stop'), 'sensores desligados');
  assert.ok(posted.some((m) => m.event === 'studio.recording.degraded'));
  const late = interact('CLICK', 'n3');
  assert.equal(late.ok, false, 'nada é aceito em silêncio depois do limite');
  await send('studio.recording.steps.ack', { recordingId: 'rec-1', upToSequence: 100 });
  const resumed = await send('studio.recording.resume', { recordingId: 'rec-1', operationId: 'op-r' });
  assert.equal(resumed.payload.recording.state, 'RECORDING', 'drenada a fila, a gravação continua');
});

test('S003-038 — cancelar descarta só as etapas desta gravação e desliga os sensores descartando o aberto', async () => {
  await startRecording();
  interact('FILL', 'n1');
  await settle();
  const c = await send('studio.recording.cancel', { recordingId: 'rec-1', operationId: 'op-c' });
  assert.equal(c.payload.recording.state, 'CANCELLED');
  assert.ok(contentCalls.some((x) => x.cmd === 'cs.recorder.stop' && x.args.discard === true));
  assert.equal(recorder.recordings.get('rec-1').steps.outbox.length, 0);
  assert.equal(c.payload.summary.steps.produced, 1);
});

test('S003-027/ELEMENT_UNRESOLVED — motor sem o nó: etapa com descritor observado, sem identidade nem localizador', async () => {
  await startRecording();
  hostFails = true;
  interact('CLICK', 'n3');
  await settle();
  const e = steps()[0];
  assert.equal(e.element.resolution, 'UNRESOLVED');
  assert.deepEqual(e.element.locators, []);
  assert.equal(e.element.preferredLocator, -1);
  assert.ok(e.notes.includes('ELEMENT_UNRESOLVED'));
  assert.equal(e.element.correlation, undefined, 'nenhuma identidade inventada');
});

test('S003-021 — documento substituído antes da validação ao vivo: validação só no snapshot, declarada', async () => {
  await startRecording();
  liveFails = true;
  interact('CLICK', 'n3');
  await settle();
  const e = steps()[0];
  assert.equal(e.element.locators[0].validation, 'SNAPSHOT');
  assert.ok(e.notes.includes('LIVE_CHECK_UNAVAILABLE'));
});

test('S003-021 — preenchimento aberto quando o documento some: perda DECLARADA no resumo', async () => {
  await startRecording();
  recorder.onSensor(1, 0, 'interaction-open', { epoch: sensor.epoch, open: true });
  for (const fn of capture.hooks.onDocumentReplaced) {
    await fn({ tabId: 1 });
  }
  const r = recorder.recordings.get('rec-1');
  assert.equal(r.steps.lost, 1);
  const fin = await send('studio.recording.finish', { recordingId: 'rec-1', operationId: 'op-f' });
  assert.ok(fin.payload.summary.limitations.includes('INTERACTIONS_LOST'));
  assert.equal(fin.payload.summary.steps.lost, 1);
});

test('S003-029 — evidência posterior (submit) anexada à etapa ainda não emitida; comandos de etapas validam o payload', async () => {
  await startRecording();
  interact('CLICK', 'n3');
  recorder.onSensor(1, 0, 'interaction-evidence', { epoch: sensor.epoch, localSeq, evidence: 'FORM_SUBMITTED' });
  await settle();
  assert.ok(steps()[0].evidence.includes('FORM_SUBMITTED'));
  const bad = await send('studio.recording.steps.ack', { recordingId: 'rec-1', upToSequence: -1 });
  assert.equal(bad.error.code, 'BAD_REQUEST');
  const other = await send('studio.recording.steps.ack', { recordingId: 'rec-1', upToSequence: 1 }, 'studio-b');
  assert.equal(other.error.code, 'NOT_CONTROLLER', 'controlador antigo/outro não confirma etapas');
});

test('S003-020 — submit/navegação logo após o clique entram como contexto posterior (sem etapa NAVIGATE)', async () => {
  await startRecording();
  interact('CLICK', 'n3');
  recorder.onNavigation(1, 'SPA_ROUTE', 'http://127.0.0.1:18765/lab/studio003/consulta.html#/clientes');
  await settle();
  const e = steps()[0];
  assert.equal(steps().length, 1, 'navegação não vira etapa');
  assert.equal(e.context.after.navigation, 'SAME_DOCUMENT');
  assert.ok(e.evidence.includes('NAVIGATION_FOLLOWED'));
});
