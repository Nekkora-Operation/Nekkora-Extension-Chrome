// Studio Recording Controller (STUDIO-002): comandos, consumidor próprio, idempotência, concessão e privacidade.
import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath, pathToFileURL } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
// Política de privacidade real (mascaramento de títulos).
// eslint-disable-next-line no-new-func
new Function(readFileSync(path.join(here, '../src/shared/privacy.js'), 'utf8'))();

let store;
let tabs;
let tabRemoved;
let clock;
globalThis.chrome = {
  storage: {
    session: {
      get: async () => ({ ...store }),
      set: async (o) => Object.assign(store, JSON.parse(JSON.stringify(o))),
      remove: async (k) => { delete store[k]; },
    },
  },
  tabs: {
    get: async (id) => { if (!tabs.has(id)) throw new Error('No tab'); return tabs.get(id); },
    query: async () => Array.from(tabs.values()),
    onRemoved: { addListener: (fn) => { tabRemoved = fn; } },
  },
  webNavigation: { getAllFrames: async () => [{ frameId: 0, url: 'x' }] },
  alarms: { create: async () => {}, clear: async () => {}, onAlarm: { addListener: () => {} } },
};

const { createStudioRecorder } = await import(pathToFileURL(path.join(here, '../src/background/studio-recording.js')).href);
const { HostError } = await import(pathToFileURL(path.join(here, '../src/background/native-client.js')).href);

/** CaptureSession falsa: consumidores por aba, encerramento quando o último sai, hook onStatus. */
function fakeCapture() {
  const sessions = new Map();
  const hooks = [];
  let seq = 0;
  const emit = (rec) => hooks.forEach((fn) => fn(rec));
  return {
    ACTIVE: new Set(['STARTING', 'ACTIVE', 'PAUSED', 'RECOVERING', 'FINALIZING']),
    sessions,
    acquired: [],
    released: [],
    get: (tabId) => sessions.get(tabId) || null,
    on: (name, fn) => { if (name === 'onStatus') hooks.push(fn); },
    async acquire(tabId, consumer) {
      this.acquired.push(consumer);
      let rec = sessions.get(tabId);
      if (!rec) {
        rec = { tabId, sessionId: 'cs-' + (++seq), status: 'ACTIVE', consumers: [], frames: {}, fullCaptures: 1, deltasApplied: 0, resyncs: 0 };
        sessions.set(tabId, rec);
      }
      if (!rec.consumers.includes(consumer)) rec.consumers.push(consumer);
      emit(rec);
      return rec;
    },
    async release(tabId, consumer, reason, options) {
      this.released.push({ consumer, reason, options });
      const rec = sessions.get(tabId);
      if (!rec) return null;
      rec.consumers = rec.consumers.filter((c) => c !== consumer);
      if (!rec.consumers.length) {
        sessions.delete(tabId);
        rec.status = 'COMPLETED';
        rec.reason = reason;
      }
      emit(rec);
      return rec;
    },
    end(tabId, reason) {
      const rec = sessions.get(tabId);
      sessions.delete(tabId);
      rec.status = 'COMPLETED';
      rec.reason = reason;
      rec.consumers = [];
      emit(rec);
    },
  };
}

function fakePolicy(allowed) {
  const urls = new Map([[1, 'http://127.0.0.1:18765/simple/form-basics.html?token=abc#x'], [2, 'https://portal.exemplo.com/clientes?id=99'],
    [3, 'chrome://settings/'], [4, 'https://pt.wikipedia.org/wiki/Brasil']]);
  const evaluate = (url) => {
    if (!url) return { allowed: false, kind: null, reason: 'URL_UNAVAILABLE' };
    if (url.startsWith('chrome:')) return { allowed: false, kind: null, reason: 'BROWSER_INTERNAL' };
    const host = new URL(url).hostname;
    if (host === '127.0.0.1') return { allowed: true, kind: 'LAB', reason: null };
    if (allowed.has(host)) return { allowed: true, kind: 'AUTHORIZED', reason: null };
    return { allowed: false, kind: null, reason: 'PERMISSION_REQUIRED' };
  };
  return {
    urls,
    evaluate,
    isAllowedUrl: (u) => evaluate(u).allowed,
    tabUrl: async (tabId) => urls.get(tabId),
    async assertAllowed(tabId) {
      const url = urls.get(tabId);
      const v = evaluate(url);
      if (!v.allowed) {
        const e = new HostError('ORIGIN_NOT_ALLOWED', 'não autorizado');
        e.reason = v.reason;
        throw e;
      }
      return { id: tabId, url, access: v };
    },
    onRemoved: () => {},
  };
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

let capture;
let policy;
let posted;
let recorder;
let handler;
const CTRL = 'studio-a';

function build() {
  posted = [];
  const client = { post: async (m) => { posted.push(JSON.parse(JSON.stringify(m))); }, onHostMessage: (t, fn) => { handler = fn; } };
  recorder = createStudioRecorder({
    client, broadcast: () => {}, withLock: withLock(), capture, policy, now: () => clock,
    errorInfo: (e) => ({ code: (e && e.code) || 'ERROR', message: String(e && e.message || e), reason: e && e.reason }),
  });
  recorder.init(Promise.resolve());
}

beforeEach(() => {
  store = {};
  clock = 1_000_000;
  tabs = new Map([
    [1, { id: 1, title: 'Sistema de testes — pedido de ana@exemplo.com', active: true }],
    [2, { id: 2, title: undefined, active: false }],
    [3, { id: 3, title: 'Configurações', active: false }],
    [4, { id: 4, title: 'Brasil – Wikipédia', active: false }],
  ]);
  capture = fakeCapture();
  policy = fakePolicy(new Set(['pt.wikipedia.org']));
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

async function refOf(predicate) {
  const r = await send('browser.tabs.list', {});
  return r.payload.tabs.find(predicate).tabRef;
}

const settle = () => new Promise((r) => setTimeout(r, 10));

test('lista de abas: referências opacas, host sem URL, título mascarado, acesso classificado', async () => {
  const r = await send('browser.tabs.list', {});
  assert.equal(r.ok, true);
  const list = r.payload.tabs;
  assert.equal(list.length, 4);
  for (const t of list) {
    assert.match(t.tabRef, /^t-[A-Za-z0-9]{8,40}$/);
    assert.deepEqual(Object.keys(t).sort(), ['access', 'active', 'host', 'recording', 'tabRef', 'title']);
  }
  const lab = list.find((t) => t.access === 'LAB');
  assert.equal(lab.host, '127.0.0.1');
  assert.ok(!lab.title.includes('ana@exemplo.com'), 'e-mail do título mascarado');
  assert.ok(lab.title.includes('[email]'));
  const denied = list.find((t) => t.access === 'PERMISSION_REQUIRED');
  assert.equal(denied.host, 'portal.exemplo.com');
  assert.equal(denied.title, null);
  assert.equal(list.find((t) => t.access === 'BROWSER_INTERNAL').host, null);
  assert.equal(list.find((t) => t.access === 'AUTHORIZED').host, 'pt.wikipedia.org');
  const text = JSON.stringify(r);
  assert.ok(!/token=|clientes\?id|"tabId"|"url"/.test(text), 'nenhuma URL completa ou id interno do Chrome');
});

test('página não autorizada: início recusado com a orientação de autorizar pela extensão', async () => {
  const ref = await refOf((t) => t.access === 'PERMISSION_REQUIRED');
  const r = await send('studio.recording.start', { recordingId: 'rec-1', operationId: 'op-1', tabRef: ref, mode: 'CAPTURE_ONLY', leaseMs: 30000 });
  assert.equal(r.ok, false);
  assert.equal(r.error.code, 'ORIGIN_NOT_ALLOWED');
  assert.equal(r.error.reason, 'PERMISSION_REQUIRED');
  assert.match(r.error.message, /Abra a Nekkora Extension no Chrome e conceda acesso ao site/);
  assert.equal(capture.acquired.length, 0, 'nenhuma captura iniciada');
});

test('início real: consumidor próprio, estado confirmado, evento e idempotência', async () => {
  const ref = await refOf((t) => t.access === 'LAB');
  const start = { recordingId: 'rec-1', operationId: 'op-1', tabRef: ref, mode: 'CAPTURE_AND_INTERACT', leaseMs: 30000 };
  const r = await send('studio.recording.start', start);
  assert.equal(r.ok, true, JSON.stringify(r.error));
  assert.equal(r.payload.duplicate, false);
  const rec = r.payload.recording;
  assert.equal(rec.state, 'RECORDING');
  assert.equal(rec.capture.recorderAttached, true);
  assert.equal(rec.capture.captureStatus, 'ACTIVE');
  assert.deepEqual(capture.get(1).consumers, ['studio-recorder:rec-1']);
  assert.equal(rec.counters.technicalCaptures, 1, 'a captura-base criada pelo Recorder conta');
  await settle();
  assert.ok(posted.some((m) => m.type === 'studio.event' && m.event === 'studio.recording.started' && m.payload.operationId === 'op-1'));
  // repetição do mesmo comando
  const again = await send('studio.recording.start', start);
  assert.equal(again.payload.duplicate, true);
  // mesma gravação, outra operação: nunca inicia duas vezes
  const other = await send('studio.recording.start', { ...start, operationId: 'op-2' });
  assert.equal(other.payload.duplicate, true);
  assert.equal(capture.acquired.length, 1, 'uma única aquisição');
  // uma gravação do Studio por aba
  const second = await send('studio.recording.start', { ...start, recordingId: 'rec-2', operationId: 'op-3' });
  assert.equal(second.error.code, 'RECORDING_ALREADY_ACTIVE');
});

test('coexistência com a inspeção: pausar e finalizar liberam SÓ o consumidor do Recorder, sem exportar', async () => {
  await capture.acquire(1, 'inspection');
  const ref = await refOf((t) => t.access === 'LAB');
  await send('studio.recording.start', { recordingId: 'rec-1', operationId: 'op-1', tabRef: ref, mode: 'CAPTURE_ONLY', leaseMs: 30000 });
  assert.deepEqual(capture.get(1).consumers, ['inspection', 'studio-recorder:rec-1']);
  const p = await send('studio.recording.pause', { recordingId: 'rec-1', operationId: 'op-p' });
  assert.equal(p.payload.recording.state, 'PAUSED');
  assert.equal(p.payload.recording.capture.recorderAttached, false);
  assert.equal(p.payload.recording.capture.otherConsumers, 1, 'a observação da página continua para a inspeção');
  assert.deepEqual(capture.get(1).consumers, ['inspection']);
  assert.equal(capture.released.at(-1).options.detachHost, true, 'liberação não espera o motor');
  const again = await send('studio.recording.pause', { recordingId: 'rec-1', operationId: 'op-p2' });
  assert.equal(again.payload.duplicate, true, 'pausar duas vezes não tem efeito adicional');
  const r = await send('studio.recording.resume', { recordingId: 'rec-1', operationId: 'op-r' });
  assert.equal(r.payload.recording.state, 'RECORDING');
  assert.equal(r.payload.recording.counters.segments, 2);
  const f = await send('studio.recording.finish', { recordingId: 'rec-1', operationId: 'op-f' });
  assert.equal(f.payload.recording.state, 'COMPLETED');
  assert.equal(f.payload.summary.pageMapExported, false);
  assert.equal(f.payload.summary.actionCapture, 'NOT_AVAILABLE');
  assert.deepEqual(capture.get(1).consumers, ['inspection'], 'inspeção manual continua ativa');
  assert.ok(!posted.some((m) => JSON.stringify(m).includes('export')), 'nada de exportação');
});

test('modo: configuração confirmada, inválido recusado, repetido sem efeito', async () => {
  const ref = await refOf((t) => t.access === 'LAB');
  await send('studio.recording.start', { recordingId: 'rec-1', operationId: 'op-1', tabRef: ref, mode: 'CAPTURE_ONLY', leaseMs: 30000 });
  const bad = await send('studio.recording.mode', { recordingId: 'rec-1', operationId: 'op-m0', mode: 'AUTOPILOT' });
  assert.equal(bad.error.code, 'INVALID_MODE');
  const m = await send('studio.recording.mode', { recordingId: 'rec-1', operationId: 'op-m1', mode: 'CAPTURE_AND_INTERACT' });
  assert.equal(m.payload.recording.mode, 'CAPTURE_AND_INTERACT');
  const same = await send('studio.recording.mode', { recordingId: 'rec-1', operationId: 'op-m2', mode: 'CAPTURE_AND_INTERACT' });
  assert.equal(same.payload.duplicate, true);
  await settle();
  assert.equal(posted.filter((x) => x.event === 'studio.recording.modeChanged').length, 1);
});

test('finalizar e cancelar simultâneos: um único desfecho válido', async () => {
  const ref = await refOf((t) => t.access === 'LAB');
  await send('studio.recording.start', { recordingId: 'rec-1', operationId: 'op-1', tabRef: ref, mode: 'CAPTURE_ONLY', leaseMs: 30000 });
  const [f, c] = await Promise.all([
    send('studio.recording.finish', { recordingId: 'rec-1', operationId: 'op-f' }),
    send('studio.recording.cancel', { recordingId: 'rec-1', operationId: 'op-c' }),
  ]);
  const oks = [f, c].filter((x) => x.ok);
  assert.equal(oks.length, 1, 'exatamente um vence');
  const loser = [f, c].find((x) => !x.ok);
  assert.equal(loser.error.code, 'RECORDING_ENDED');
  const fin = await send('studio.recording.finish', { recordingId: 'rec-1', operationId: 'op-f' });
  assert.equal(fin.ok, f.ok, 'repetição do mesmo operationId devolve o mesmo resultado');
  assert.equal(capture.released.filter((x) => x.consumer === 'studio-recorder:rec-1').length, 1, 'consumidor liberado uma única vez');
});

test('controlador: outra instância do Studio não controla nem enxerga a gravação', async () => {
  const ref = await refOf((t) => t.access === 'LAB');
  await send('studio.recording.start', { recordingId: 'rec-1', operationId: 'op-1', tabRef: ref, mode: 'CAPTURE_ONLY', leaseMs: 30000 });
  const p = await send('studio.recording.pause', { recordingId: 'rec-1', operationId: 'op-x' }, 'studio-b');
  assert.equal(p.error.code, 'NOT_CONTROLLER');
  const s = await send('studio.recording.status', {}, 'studio-b');
  assert.deepEqual(s.payload.recordings, []);
  assert.equal((await send('studio.recording.status', {})).payload.recordings.length, 1);
});

test('lista explícita e validação: comando desconhecido, campo extra e tabRef cru recusados', async () => {
  assert.equal((await send('page.capture', {})).error.code, 'UNKNOWN_COMMAND');
  assert.equal((await send('host.ping', {})).error.code, 'UNKNOWN_COMMAND');
  const ref = await refOf((t) => t.access === 'LAB');
  const extra = await send('studio.recording.start', { recordingId: 'rec-1', operationId: 'op-1', tabRef: ref, mode: 'CAPTURE_ONLY', leaseMs: 30000, script: 'document.cookie' });
  assert.equal(extra.error.code, 'BAD_REQUEST');
  const raw = await send('studio.recording.start', { recordingId: 'rec-1', operationId: 'op-1', tabRef: 1, mode: 'CAPTURE_ONLY', leaseMs: 30000 });
  assert.equal(raw.error.code, 'BAD_REQUEST');
  const unknownTab = await send('studio.recording.start', { recordingId: 'rec-1', operationId: 'op-1', tabRef: 't-AAAAAAAAAAAA', mode: 'CAPTURE_ONLY', leaseMs: 30000 });
  assert.equal(unknownTab.error.code, 'TAB_NOT_FOUND');
  assert.equal(capture.acquired.length, 0);
});

test('concessão: sem renovação a gravação expira e o consumidor é liberado; com renovação continua', async () => {
  const ref = await refOf((t) => t.access === 'LAB');
  await send('studio.recording.start', { recordingId: 'rec-1', operationId: 'op-1', tabRef: ref, mode: 'CAPTURE_ONLY', leaseMs: 10000 });
  clock += 8000;
  const renew = await send('studio.recording.renew', { recordingId: 'rec-1' });
  assert.equal(renew.payload.expiresInMs, 10000);
  clock += 8000;
  recorder.checkLeases();
  await settle();
  assert.equal(recorder.recordings.get('rec-1').state, 'RECORDING', 'renovada: continua');
  clock += 2001;
  recorder.checkLeases();
  await settle();
  const r = recorder.recordings.get('rec-1');
  assert.equal(r.state, 'EXPIRED');
  assert.equal(r.reason, 'LEASE_EXPIRED');
  assert.equal(capture.get(1), null, 'consumidor liberado: nenhuma captura órfã');
  assert.ok(posted.some((m) => m.event === 'studio.recording.expired' && m.payload.operationId === null));
});

test('aba fechada: DEGRADED sem operação do Studio; finalizar registra a interrupção', async () => {
  const ref = await refOf((t) => t.access === 'LAB');
  await send('studio.recording.start', { recordingId: 'rec-1', operationId: 'op-1', tabRef: ref, mode: 'CAPTURE_ONLY', leaseMs: 30000 });
  tabs.delete(1);
  capture.end(1, 'TAB_CLOSED');
  await tabRemoved(1);
  await settle();
  const r = recorder.recordings.get('rec-1');
  assert.equal(r.state, 'DEGRADED');
  assert.equal(r.reason, 'TAB_CLOSED');
  const ev = posted.filter((m) => m.event === 'studio.recording.degraded');
  assert.equal(ev.length, 1, 'um único evento de degradação');
  assert.equal(ev[0].payload.operationId, null);
  const res = await send('studio.recording.resume', { recordingId: 'rec-1', operationId: 'op-r' });
  assert.equal(res.error.code, 'TAB_NOT_FOUND');
  const f = await send('studio.recording.finish', { recordingId: 'rec-1', operationId: 'op-f' });
  assert.ok(f.payload.summary.limitations.includes('INTERRUPTED'));
});

test('permissão revogada durante a gravação: DEGRADED; retomada recusada até nova autorização', async () => {
  const ref = await refOf((t) => t.access === 'AUTHORIZED');
  await send('studio.recording.start', { recordingId: 'rec-1', operationId: 'op-1', tabRef: ref, mode: 'CAPTURE_ONLY', leaseMs: 30000 });
  const tabId = 4;
  policy.urls.set(tabId, 'https://pt.wikipedia.org/wiki/Brasil'); // mesma página, autorização removida:
  const revoked = fakePolicy(new Set());
  Object.assign(policy, { evaluate: revoked.evaluate, isAllowedUrl: revoked.isAllowedUrl, assertAllowed: revoked.assertAllowed });
  capture.end(tabId, 'PERMISSION_REVOKED');
  await settle();
  assert.equal(recorder.recordings.get('rec-1').state, 'DEGRADED');
  assert.equal(recorder.recordings.get('rec-1').reason, 'PERMISSION_REVOKED');
  const res = await send('studio.recording.resume', { recordingId: 'rec-1', operationId: 'op-r' });
  assert.equal(res.error.code, 'ORIGIN_NOT_ALLOWED');
  assert.equal(capture.get(tabId), null, 'nenhuma observação de origem revogada');
});

test('reinício do service worker: estado restaurado do armazenamento mínimo e reconciliado', async () => {
  const ref = await refOf((t) => t.access === 'LAB');
  await send('studio.recording.start', { recordingId: 'rec-1', operationId: 'op-1', tabRef: ref, mode: 'CAPTURE_ONLY', leaseMs: 30000 });
  const saved = JSON.stringify(store);
  assert.ok(!/ana@exemplo|token=abc/.test(saved), 'armazenamento mínimo sem conteúdo sensível');
  // novo processo do service worker: mesma storage.session, mesma captura (restaurada com os consumidores)
  build();
  await recorder.restore();
  await recorder.reconcile();
  const s = await send('studio.recording.status', { recordingId: 'rec-1' });
  assert.equal(s.payload.recordings[0].state, 'RECORDING');
  assert.equal(s.payload.recordings[0].tab.tabRef, ref, 'referência opaca preservada');
  // captura perdida no reinício: a gravação fica DEGRADED (nunca "gravando" sem consumidor)
  build();
  capture.sessions.delete(1);
  await recorder.restore();
  await recorder.reconcile();
  assert.equal(recorder.recordings.get('rec-1').state, 'DEGRADED');
});
