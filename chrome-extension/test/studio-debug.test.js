// STUDIO-009 (guided-debug/1): debug guiado no service worker — sessão, inspeção somente leitura, destaque, candidatos,
// seleção no navegador, épocas da página, concessão, reinício e privacidade. Toda resposta/evento é conferido contra o
// contrato (contracts/studio-bridge.schema.json).
import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath, pathToFileURL } from 'node:url';
import path from 'node:path';
import { schema, errors } from './helpers/mini-schema.js';

const here = path.dirname(fileURLToPath(import.meta.url));
// eslint-disable-next-line no-new-func
new Function(readFileSync(path.join(here, '../src/shared/privacy.js'), 'utf8'))();
new Function(readFileSync(path.join(here, '../src/shared/ui-vocabulary.js'), 'utf8'))();
new Function(readFileSync(path.join(here, '../src/shared/capture-policy.js'), 'utf8'))();

let store;
let tabs;
let clock;
globalThis.chrome = {
  storage: { session: {
    get: async () => ({ ...store }),
    set: async (o) => Object.assign(store, JSON.parse(JSON.stringify(o))),
    remove: async (k) => { delete store[k]; },
  } },
  tabs: {
    get: async (id) => { if (!tabs.has(id)) throw new Error('No tab'); return tabs.get(id); },
    query: async () => Array.from(tabs.values()),
    onRemoved: { addListener: () => {} },
  },
  webNavigation: { getAllFrames: async () => [{ frameId: 0, url: 'x' }] },
  alarms: { create: async () => {}, clear: async () => {}, onAlarm: { addListener: () => {} } },
};

const { createStudioRecorder } = await import(pathToFileURL(path.join(here, '../src/background/studio-recording.js')).href);

function fakeCapture() {
  const sessions = new Map();
  return {
    ACTIVE: new Set(['STARTING', 'ACTIVE', 'PAUSED', 'RECOVERING', 'FINALIZING']),
    sessions, acquired: [], released: [],
    get: (tabId) => sessions.get(tabId) || null,
    on: () => {},
    async acquire(tabId, consumer) {
      this.acquired.push(consumer);
      let rec = sessions.get(tabId);
      if (!rec) {
        rec = { tabId, sessionId: 'cs-1', status: 'ACTIVE', consumers: [], frames: {} };
        sessions.set(tabId, rec);
      }
      rec.consumers.push(consumer);
      return rec;
    },
    async release(tabId, consumer, reason) {
      this.released.push({ consumer, reason });
      const rec = sessions.get(tabId);
      if (rec) {
        rec.consumers = rec.consumers.filter((c) => c !== consumer);
      }
      return rec;
    },
  };
}

function fakePolicy() {
  const urls = new Map([[1, 'http://127.0.0.1:18765/studio009/consulta.html'], [2, 'https://portal.exemplo.com/x'], [3, 'https://consulta.nekkora.test/x']]);
  const evaluate = (url) => {
    if (!url) return { allowed: false, kind: null, reason: 'URL_UNAVAILABLE' };
    const h = new URL(url).hostname;
    if (h === '127.0.0.1') return { allowed: true, kind: 'LAB', reason: null };
    if (h === 'consulta.nekkora.test') return { allowed: true, kind: 'AUTHORIZED', reason: null };
    return { allowed: false, kind: null, reason: 'PERMISSION_REQUIRED' };
  };
  return { urls, evaluate, isAllowedUrl: (u) => evaluate(u).allowed, tabUrl: async (tabId) => urls.get(tabId), onRemoved: () => {},
    async assertAllowed() { return {}; } };
}

function withLock() {
  const locks = new Map();
  return (key, fn) => {
    const prev = locks.get(key) || Promise.resolve();
    const next = prev.catch(() => {}).then(fn);
    locks.set(key, next.catch(() => {}));
    return next;
  };
}

/** Motor falso: devolve o que o teste programar para debug.resolve e descreve nós como o host. */
let resolveReply;
let hostCalls;
let contentCalls;
let connected;
function fakeHost(type, payload) {
  hostCalls.push({ type, payload });
  if (type === 'debug.resolve') {
    return typeof resolveReply === 'function' ? resolveReply(payload) : resolveReply;
  }
  if (type === 'recorder.element.describe') {
    return { captureId: 'cap-2', nodeId: payload.nodeId, targetId: 'tgt-novo', documentId: 'doc:f0', kind: 'BUTTON', role: 'button', name: 'Consultar',
      tag: 'button', htmlId: 'consultar', htmlName: null, sensitive: false,
      locators: [{ strategy: 'TEST_ATTRIBUTE', description: '', css: '[data-testid="consultar"]', xpath: null, matchCount: 1, matchesTarget: true,
        stability: 'HEURISTIC_STABLE' }], preferredLocator: 0, accessPath: [{ type: 'MAIN_DOCUMENT', label: '', locator: null }], context: [],
      lookalikes: 0, notes: [], verification: { primary: null, fallbacks: [] } };
  }
  if (type === 'recorder.element.verify') {
    return { checks: [{ index: 0, checked: true, liveUnique: true }] };
  }
  if (type === 'debug.node.state') {
    return { checks: { identity: 'NOT_APPLICABLE', unique: 'NOT_APPLICABLE', visible: 'PASS', enabled: 'PASS', editable: 'NOT_APPLICABLE',
      kindMatchesAction: 'NOT_APPLICABLE', frameReachable: 'NOT_APPLICABLE', shadowReachable: 'NOT_APPLICABLE' }, reasons: [], kind: 'BUTTON' };
  }
  throw new Error('comando de host inesperado: ' + type);
}

function fakeContent(tabId, cmd, args) {
  contentCalls.push({ cmd, args });
  if (cmd === 'cs.debug.mark') {
    return { ok: args.nodeId !== 'n-sumiu', box: { x: 100, y: 200, width: 300, height: 30, viewportWidth: 1280, viewportHeight: 720 } };
  }
  if (cmd === 'cs.debug.viewport') {
    return { screenX: 0, screenY: 0, chromeLeft: 8, chromeTop: 100, innerWidth: 1280, innerHeight: 720, devicePixelRatio: 1 };
  }
  if (cmd === 'cs.debug.probe') {
    return { ok: true, domChanged: true };
  }
  if (cmd === 'cs.verifyLocators') {
    return { checks: [] };
  }
  return { ok: true };
}

let capture;
let posted;
let recorder;
const CTRL = 'studio-a';

function build() {
  posted = [];
  hostCalls = [];
  contentCalls = [];
  connected = true;
  const client = { post: async (m) => { posted.push(JSON.parse(JSON.stringify(m))); }, onHostMessage: () => {}, status: () => ({ connected }) };
  recorder = createStudioRecorder({
    client, broadcast: () => {}, withLock: withLock(), capture, policy: fakePolicy(), now: () => clock,
    host: async (t, p) => fakeHost(t, p), toContent: async (tabId, cmd, args) => fakeContent(tabId, cmd, args), tabKey: (id) => 'tab-' + id,
    frameOfNode: () => 0, currentCapture: async () => ({ captureId: 'cap-1' }),
    errorInfo: (e) => ({ code: (e && e.code) || 'ERROR', message: String(e && e.message || e), reason: e && e.reason }),
  });
  recorder.init(Promise.resolve());
}

const RESOLVED = {
  identitySource: 'TARGET_ID', status: 'RESOLVED', score: 0.93, resolvedNodeId: 'n41', subjectNodeId: 'n41', subjectTag: 'input', subjectSensitive: false,
  nodeReplaced: false, reasons: [],
  checks: { identity: 'PASS', unique: 'PASS', visible: 'PASS', enabled: 'PASS', editable: 'PASS', kindMatchesAction: 'PASS', frameReachable: 'NOT_APPLICABLE',
    shadowReachable: 'NOT_APPLICABLE' },
  candidates: [{ nodeId: 'n41', verdict: 'CONFIRMED', score: 0.93, kind: 'SEARCH_INPUT', name: 'Pesquisar', chosen: true }], continuity: 'NOT_PROVEN',
};
const AMBIGUOUS = {
  identitySource: 'DESCRIPTOR', status: 'AMBIGUOUS', score: 0.7, resolvedNodeId: null, subjectNodeId: null, nodeReplaced: false,
  reasons: ['DESCRIPTOR_ONLY', 'TARGET_AMBIGUOUS', 'INVENTADO'],
  checks: { identity: 'FAIL', unique: 'FAIL', visible: 'UNKNOWN', enabled: 'UNKNOWN', editable: 'NOT_APPLICABLE', kindMatchesAction: 'NOT_APPLICABLE',
    frameReachable: 'NOT_APPLICABLE', shadowReachable: 'NOT_APPLICABLE' },
  candidates: [{ nodeId: 'n9', verdict: 'CONFIRMED', score: 0.7, kind: 'BUTTON', name: 'Excluir', chosen: false },
    { nodeId: 'n16', verdict: 'CONFIRMED', score: 0.7, kind: 'BUTTON', name: 'Pedido de Ana Souza', chosen: false }],
  continuity: null,
};
const DESCRIPTOR = { kind: 'SEARCH_INPUT', tag: 'input', name: 'Pesquisar', htmlId: 'q', htmlName: null,
  locators: [{ strategy: 'ID', css: '#q', xpath: null, description: '' }], accessPath: [{ type: 'MAIN_DOCUMENT', locator: null }] };

beforeEach(() => {
  store = {};
  clock = 1_000_000;
  tabs = new Map([[1, { id: 1, title: 'Consulta', active: true }], [2, { id: 2, title: 'Portal', active: false }],
    [3, { id: 3, title: 'Consulta', active: false }]]);
  capture = fakeCapture();
  resolveReply = RESOLVED;
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

/** O que o host faria: o envelope do Bridge com o payload, conferido contra o contrato. */
function assertContract(command, reply) {
  const env = reply.ok
    ? { protocol: 'nekkora-bridge/1', kind: 'response', type: command, id: 'c-1', seq: 1, ok: true, payload: reply.payload, error: null }
    : { protocol: 'nekkora-bridge/1', kind: 'response', type: command, id: 'c-1', seq: 1, ok: false, payload: null, error: reply.error };
  const def = reply.ok ? {
    'studio.debug.start': 'DebugResult', 'studio.debug.finish': 'DebugResult', 'studio.debug.status': 'DebugResult', 'studio.debug.renew': 'DebugResult',
    'studio.debug.inspect': 'DebugInspectResult', 'studio.debug.highlight': 'DebugHighlightResult', 'studio.debug.pick': 'DebugPickResult',
  }[command] : 'Error';
  const e = errors(schema.$defs[def], reply.ok ? env.payload : env.error);
  assert.deepEqual(e, [], command + ': ' + JSON.stringify(reply));
}

async function startDebug() {
  const ref = (await send('browser.tabs.list', {})).payload.tabs.find((t) => t.access === 'LAB').tabRef;
  const r = await send('studio.debug.start', { debugId: 'dbg-1', operationId: 'op-1', tabRef: ref, leaseMs: 30000, capabilities: ['guided-debug/1'] });
  assertContract('studio.debug.start', r);
  return { ref, r };
}

function inspectPayload(over = {}) {
  return { debugId: 'dbg-1', requestId: 'rq-1', stepRef: 'step-1', actionType: 'FILL', targetId: 'tgt-1', descriptor: DESCRIPTOR, highlight: true, ...over };
}

const settle = () => new Promise((r) => setTimeout(r, 20));

test('início: só em aba autorizada, com a capacidade, consumidor próprio; idempotente; sem gravação na aba', async () => {
  const { ref, r } = await startDebug();
  assert.equal(r.ok, true);
  assert.equal(r.payload.debug.state, 'ACTIVE');
  assert.equal(r.payload.debug.protocol, 'guided-debug/1');
  assert.deepEqual(capture.acquired, ['studio-debug:dbg-1']);
  const again = await send('studio.debug.start', { debugId: 'dbg-1', operationId: 'op-1', tabRef: ref, leaseMs: 30000, capabilities: ['guided-debug/1'] });
  assert.equal(again.payload.duplicate, true);
  const other = (await send('browser.tabs.list', {})).payload.tabs.find((t) => t.access === 'PERMISSION_REQUIRED').tabRef;
  const denied = await send('studio.debug.start', { debugId: 'dbg-2', operationId: 'op-2', tabRef: other, leaseMs: 30000, capabilities: ['guided-debug/1'] });
  assert.equal(denied.error.code, 'ORIGIN_NOT_ALLOWED');
  const second = await send('studio.debug.start', { debugId: 'dbg-3', operationId: 'op-3', tabRef: ref, leaseMs: 30000, capabilities: ['guided-debug/1'] });
  assert.equal(second.error.code, 'INVALID_STATE', 'uma sessão por aba');
  const noCap = await send('studio.debug.start', { debugId: 'dbg-4', operationId: 'op-4', tabRef: ref, leaseMs: 30000, capabilities: [] });
  assert.equal(noCap.error.code, 'BAD_REQUEST');
  const intruder = await send('studio.debug.status', { debugId: 'dbg-1' }, 'studio-b');
  assert.equal(intruder.error.code, 'NOT_CONTROLLER');
});

test('inspeção RESOLVED: destaque sólido, caixa com posição aproximada na tela, verificações e candidato; idempotente por requestId', async () => {
  await startDebug();
  const r = await send('studio.debug.inspect', inspectPayload());
  assertContract('studio.debug.inspect', r);
  assert.equal(r.payload.status, 'RESOLVED');
  assert.equal(r.payload.highlighted, true);
  assert.equal(r.payload.box.screenX, 108);
  assert.equal(r.payload.box.screenY, 300);
  assert.equal(r.payload.element.kind, 'SEARCH_INPUT');
  assert.equal(r.payload.candidates[0].chosen, true);
  const mark = contentCalls.find((c) => c.cmd === 'cs.debug.mark');
  assert.equal(mark.args.style, 'confirmed');
  assert.ok(!/click|focus|value/i.test(JSON.stringify(contentCalls.map((c) => c.cmd))), 'nenhum comando de interação foi enviado à página');
  const resolves = hostCalls.filter((c) => c.type === 'debug.resolve').length;
  const again = await send('studio.debug.inspect', inspectPayload());
  assert.deepEqual(again.payload, r.payload, 'mesmo requestId → mesma resposta');
  assert.equal(hostCalls.filter((c) => c.type === 'debug.resolve').length, resolves, 'sem repetir a análise');
  assert.equal(hostCalls.find((c) => c.type === 'debug.resolve').payload.targetId, 'tgt-1');
});

test('inspeção AMBÍGUA: nenhum destaque, nenhum elemento afirmado; códigos desconhecidos e nomes fora do vocabulário filtrados', async () => {
  resolveReply = AMBIGUOUS;
  await startDebug();
  const r = await send('studio.debug.inspect', inspectPayload({ requestId: 'rq-2', targetId: null, actionType: 'CLICK' }));
  assertContract('studio.debug.inspect', r);
  assert.equal(r.payload.status, 'AMBIGUOUS');
  assert.equal(r.payload.highlighted, false);
  assert.equal(r.payload.element, null);
  assert.ok(!r.payload.reasons.includes('INVENTADO'));
  assert.equal(r.payload.candidates.length, 2);
  assert.equal(r.payload.candidates[1].name, '', 'nome com dado de pessoa não vai ao Studio');
  assert.ok(!contentCalls.some((c) => c.cmd === 'cs.debug.mark'), 'vários candidatos: nada destacado como se fosse o alvo');
  // examinar um candidato: destaque pontilhado, sem promover nada
  const h = await send('studio.debug.highlight', { debugId: 'dbg-1', requestId: 'rq-3', candidateRef: r.payload.candidates[1].candidateRef });
  assertContract('studio.debug.highlight', h);
  assert.equal(h.payload.highlighted, true);
  assert.equal(contentCalls.filter((c) => c.cmd === 'cs.debug.mark').pop().args.style, 'candidate');
});

test('candidato único plausível (não comprovado): destaque tracejado de revisão, nunca o sólido', async () => {
  resolveReply = { ...AMBIGUOUS, status: 'INSUFFICIENT_EVIDENCE', subjectNodeId: 'n9', subjectTag: 'button', candidates: [AMBIGUOUS.candidates[0]],
    reasons: ['DESCRIPTOR_ONLY'] };
  await startDebug();
  const r = await send('studio.debug.inspect', inspectPayload({ requestId: 'rq-9', targetId: null, actionType: 'CLICK' }));
  assertContract('studio.debug.inspect', r);
  assert.equal(r.payload.status, 'INSUFFICIENT_EVIDENCE');
  assert.equal(contentCalls.find((c) => c.cmd === 'cs.debug.mark').args.style, 'review');
});

test('página mudou durante a verificação: resultado não vale (CONTEXT_UNAVAILABLE), candidatos expiram', async () => {
  await startDebug();
  resolveReply = (p) => {
    recorder.debug.onPageChanged(1, 'NAVIGATED'); // a página navegou enquanto o motor analisava
    return RESOLVED;
  };
  const r = await send('studio.debug.inspect', inspectPayload({ requestId: 'rq-4' }));
  assertContract('studio.debug.inspect', r);
  assert.equal(r.payload.status, 'CONTEXT_UNAVAILABLE');
  assert.ok(r.payload.reasons.includes('PAGE_CHANGED_DURING_CHECK'));
  assert.equal(r.payload.highlighted, false);
  const changed = posted.filter((m) => m.event === 'studio.debug.changed').pop();
  assert.equal(changed.payload.debug.reason, 'NAVIGATED');
  assert.deepEqual(errors(schema.$defs.DebugChangedPayload, changed.payload), []);
  resolveReply = RESOLVED;
  const old = await send('studio.debug.inspect', inspectPayload({ requestId: 'rq-5' }));
  recorder.debug.onPageChanged(1, 'DOCUMENT_REPLACED');
  const expired = await send('studio.debug.highlight', { debugId: 'dbg-1', requestId: 'rq-6', candidateRef: old.payload.candidates[0].candidateRef });
  assert.equal(expired.error.code, 'CANDIDATE_EXPIRED');
});

test('falha de captura: CONTEXT_UNAVAILABLE com CAPTURE_UNAVAILABLE, nada promovido', async () => {
  await startDebug();
  resolveReply = () => { throw new Error('host caiu'); };
  const r = await send('studio.debug.inspect', inspectPayload({ requestId: 'rq-7' }));
  assertContract('studio.debug.inspect', r);
  assert.equal(r.payload.status, 'CONTEXT_UNAVAILABLE');
  assert.equal(r.payload.checks.identity, 'UNKNOWN');
});

test('seleção no navegador: arma o inspetor, o clique interceptado vira studio.debug.picked pelas barreiras de etapa; ação não executada', async () => {
  await startDebug();
  const r = await send('studio.debug.pick', { debugId: 'dbg-1', requestId: 'rq-8', action: 'START', candidateRef: null, actionType: 'CLICK' });
  assertContract('studio.debug.pick', r);
  assert.equal(r.payload.picking, true);
  assert.ok(contentCalls.some((c) => c.cmd === 'cs.inspect.start'));
  assert.equal(recorder.debug.onElementSelected(2, 'n31'), false, 'outra aba: não é do debug');
  assert.equal(recorder.debug.onElementSelected(1, 'n31'), true);
  await settle();
  const picked = posted.filter((m) => m.event === 'studio.debug.picked').pop();
  assert.ok(picked, 'evento de seleção');
  assert.deepEqual(errors(schema.$defs.DebugPickedPayload, picked.payload), [], JSON.stringify(picked.payload));
  assert.equal(picked.payload.origin, 'BROWSER_SELECTION');
  assert.equal(picked.payload.element.correlation.targetId, 'tgt-novo', 'identidade lógica nova para a referência escolhida');
  assert.equal(picked.payload.requestId, 'rq-8');
  assert.ok(contentCalls.some((c) => c.cmd === 'cs.inspect.stop'), 'a seleção é única: o inspetor é desarmado');
  assert.equal(recorder.debug.onElementSelected(1, 'n32'), false, 'um segundo clique não é capturado');
});

test('fim: limpa destaque, desarma e libera o consumidor; depois, comandos recusados; concessão vencida encerra', async () => {
  await startDebug();
  const f = await send('studio.debug.finish', { debugId: 'dbg-1', operationId: 'op-f' });
  assertContract('studio.debug.finish', f);
  assert.equal(f.payload.debug.state, 'ENDED');
  assert.deepEqual(capture.released.map((x) => x.consumer), ['studio-debug:dbg-1']);
  assert.ok(contentCalls.some((c) => c.cmd === 'cs.debug.unmark'));
  const after = await send('studio.debug.inspect', inspectPayload({ requestId: 'rq-10' }));
  assert.equal(after.error.code, 'DEBUG_ENDED');
  build(); // novo controlador: sessão nova que vence
  await startDebug();
  clock += 31_000;
  recorder.checkLeases();
  await settle();
  const expired = posted.filter((m) => m.event === 'studio.debug.changed').pop();
  assert.equal(expired.payload.debug.reason, 'LEASE_EXPIRED');
});

test('gravação iniciada na aba encerra o debug; aba fechada encerra sem tocar na página; reinício volta DEGRADED', async () => {
  const { ref } = await startDebug();
  await send('studio.recording.start', { recordingId: 'rec-1', operationId: 'op-r', tabRef: ref, mode: 'CAPTURE_ONLY', leaseMs: 30000 });
  await settle();
  assert.equal(posted.filter((m) => m.event === 'studio.debug.changed').pop().payload.debug.reason, 'RECORDING_STARTED');
  build();
  await startDebug();
  await recorder.debug.onTabRemoved(1);
  assert.equal(posted.filter((m) => m.event === 'studio.debug.changed').pop().payload.debug.reason, 'TAB_CLOSED');
  build();
  await startDebug();
  const saved = { ...store };
  build();
  store = saved;
  await recorder.restore();
  const restored = posted.filter((m) => m.event === 'studio.debug.changed').pop();
  assert.equal(restored.payload.debug.state, 'DEGRADED');
  assert.equal(restored.payload.debug.reason, 'EXTENSION_RESTARTED');
  assert.ok(!JSON.stringify(saved).includes('Pesquisar'), 'estado salvo sem nada da página');
});

test('payloads fora do contrato recusados antes de qualquer efeito: script, valor, campo extra', async () => {
  await startDebug();
  for (const bad of [{ ...inspectPayload({ requestId: 'rq-x1' }), script: 'x' }, inspectPayload({ requestId: 'rq-x2', descriptor: { ...DESCRIPTOR, value: '12345' } }),
    inspectPayload({ requestId: 'rq-x3', actionType: 'EXECUTE' })]) {
    const r = await send('studio.debug.inspect', bad);
    assert.equal(r.error.code, 'BAD_REQUEST', JSON.stringify(bad));
  }
  assert.equal(hostCalls.filter((c) => c.type === 'debug.resolve').length, 0);
});

function probePayload(over = {}) {
  return { debugId: 'dbg-1', requestId: 'rq-p1', stepRef: 'step-1', operation: 'FILL_FIXTURE', targetId: 'tgt-1', descriptor: DESCRIPTOR,
    valueKey: 'lab:registro-ativo', authorization: 'LAB_PROBE', ...over };
}

test('G6 sonda LAB: preenche com o valor do catálogo (nunca transportado), idempotente, fatos separados', async () => {
  await startDebug();
  const r = await send('studio.debug.probe', probePayload());
  assert.equal(r.ok, true, JSON.stringify(r));
  assert.deepEqual(errors(schema.$defs.DebugProbeResult, r.payload), [], JSON.stringify(r.payload));
  assert.equal(r.payload.performed, true);
  assert.equal(r.payload.status, 'RESOLVED');
  const call = contentCalls.find((c) => c.cmd === 'cs.debug.probe');
  assert.equal(call.args.value, 'REG-0001', 'o valor sai do catálogo da extensão');
  assert.ok(!JSON.stringify(posted).includes('REG-0001'), 'o valor nunca vai ao Studio');
  assert.ok(!JSON.stringify(hostCalls).includes('REG-0001'), 'nem ao host');
  const again = await send('studio.debug.probe', probePayload());
  assert.equal(again.payload.duplicate, true);
  assert.equal(contentCalls.filter((c) => c.cmd === 'cs.debug.probe').length, 1, 'o mesmo pedido NUNCA repete a ação');
});

test('G6 sonda: alvo não comprovado, campo não editável e chave desconhecida — nada é feito', async () => {
  resolveReply = AMBIGUOUS;
  await startDebug();
  const amb = await send('studio.debug.probe', probePayload({ requestId: 'rq-p2' }));
  assert.equal(amb.payload.performed, false);
  assert.deepEqual(amb.payload.reasons, ['TARGET_NOT_RESOLVED']);
  resolveReply = { ...RESOLVED, checks: { ...RESOLVED.checks, editable: 'FAIL' } };
  const ro = await send('studio.debug.probe', probePayload({ requestId: 'rq-p3' }));
  assert.equal(ro.payload.performed, false);
  assert.ok(ro.payload.reasons.includes('NOT_EDITABLE'));
  const unknown = await send('studio.debug.probe', probePayload({ requestId: 'rq-p4', valueKey: 'lab:nao-existe' }));
  assert.equal(unknown.payload.performed, false);
  assert.deepEqual(unknown.payload.reasons, ['UNKNOWN_FIXTURE']);
  assert.equal(contentCalls.filter((c) => c.cmd === 'cs.debug.probe').length, 0);
  const bad = await send('studio.debug.probe', probePayload({ requestId: 'rq-p5', valueKey: 'Ana Souza' }));
  assert.equal(bad.error.code, 'BAD_REQUEST');
});

test('G6 sonda fora do laboratório (site autorizado) é recusada: PROBE_NOT_ALLOWED', async () => {
  const ref = (await send('browser.tabs.list', {})).payload.tabs.find((t) => t.access === 'AUTHORIZED').tabRef;
  const st = await send('studio.debug.start', { debugId: 'dbg-1', operationId: 'op-1', tabRef: ref, leaseMs: 30000, capabilities: ['guided-debug/1'] });
  assert.equal(st.ok, true, JSON.stringify(st));
  const r = await send('studio.debug.probe', probePayload());
  assert.equal(r.error.code, 'PROBE_NOT_ALLOWED');
  assert.equal(contentCalls.filter((c) => c.cmd === 'cs.debug.probe').length, 0);
});
