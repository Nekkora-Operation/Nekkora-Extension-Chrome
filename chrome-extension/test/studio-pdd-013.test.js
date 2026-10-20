// NEKKORA-STUDIO-013 — captura visual do PDD em sites autorizados: consentimento PDD por origem exata (independente da
// permissão do Chrome), perfil AUTHORIZED_SITE, política nekkora-visual/3 (descrição inteiramente sintética: a extensão
// não chama captureVisibleTab) e revogação com captura armada/em curso. Dados 100% sintéticos.
import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import { createPddConsent, canonicalOrigin, originOf } from '../src/background/pdd-consent.js';
import { visualProfile, denialReason, POLICIES_BY_PROFILE } from '../src/background/visual-capture-profile.js';
import { validateV3, V3_MAX } from '../src/background/visual-sanitizer.js';
import { createStudioPdd } from '../src/background/studio-pdd.js';

function textPolicy() {
  const sandbox = { console };
  sandbox.globalThis = sandbox;
  vm.createContext(sandbox);
  for (const f of ['shared/ui-vocabulary.js', 'shared/capture-policy.js']) {
    vm.runInContext(readFileSync(new URL('../src/' + f, import.meta.url), 'utf8'), sandbox, { filename: f });
  }
  return sandbox.NekkoraCapturePolicy;
}
const TP = textPolicy();

// ------------------------------------------------------------------------------------------------ armazenamentos falsos

function fakeChrome() {
  const session = {};
  return {
    session,
    storage: { session: {
      get: async (k) => (k in session ? { [k]: JSON.parse(JSON.stringify(session[k])) } : {}),
      set: async (o) => { for (const [k, v] of Object.entries(o)) session[k] = JSON.parse(JSON.stringify(v)); },
    } },
  };
}

function fakePersistent() {
  const rows = new Map();
  return {
    rows,
    getAll: async () => [...rows.values()].map((r) => ({ ...r })),
    put: async (r) => { rows.set(r.origin, { ...r }); },
    delete: async (o) => { rows.delete(o); },
  };
}

// ------------------------------------------------------------------------------------------------ origem e registro

test('origem de consentimento: só a forma canônica http(s) — sem caminho, query, fragmento, credencial, porta padrão ou laboratório', () => {
  for (const ok of ['https://app.exemplo.test', 'http://app.exemplo.test', 'https://app.exemplo.test:8443', 'http://10.0.0.5:8080']) {
    assert.equal(canonicalOrigin(ok), ok, ok);
  }
  for (const bad of ['https://app.exemplo.test/', 'https://app.exemplo.test/pacientes', 'https://app.exemplo.test?token=abc',
    'https://app.exemplo.test#x', 'https://user:senha@app.exemplo.test', 'https://APP.exemplo.test', 'https://app.exemplo.test:443',
    'ftp://app.exemplo.test', 'file:///c:/x', 'chrome://extensions', 'http://127.0.0.1:18765', 'http://localhost', '', null, 42,
    'https://' + 'a'.repeat(300) + '.test']) {
    assert.equal(canonicalOrigin(bad), null, String(bad));
  }
  assert.equal(originOf('https://app.exemplo.test:8443/pacientes/123?token=abc#ficha'), 'https://app.exemplo.test:8443');
});

test('consentimento SESSION (padrão) e REMEMBERED: registro só com origem/escopo/nível/quem/por onde/quando; revogar apaga os dois', async () => {
  const ch = fakeChrome();
  const store = fakePersistent();
  const c = createPddConsent({ chrome: ch, persistent: store, now: () => Date.UTC(2026, 9, 19) });
  await c.grant('https://app.exemplo.test', 'SESSION', 'DEVELOPER', 'STUDIO');
  assert.equal(c.get('https://app.exemplo.test').scope, 'SESSION');
  assert.equal(store.rows.size, 0, 'sessão não vai para o armazenamento persistente');
  assert.deepEqual(Object.keys(ch.session['nk.pdd.consent']['https://app.exemplo.test']).sort(), ['actor', 'grantedAt', 'level', 'origin', 'scope', 'via']);
  await c.grant('https://app.exemplo.test:8443', 'REMEMBERED', 'TEST_AGENT', 'EXTENSION');
  assert.equal(store.rows.get('https://app.exemplo.test:8443').actor, 'TEST_AGENT');
  // rebaixar para sessão apaga o lembrado
  await c.grant('https://app.exemplo.test:8443', 'SESSION', 'DEVELOPER', 'STUDIO');
  assert.equal(store.rows.size, 0);
  assert.equal(c.get('https://app.exemplo.test:8443').scope, 'SESSION');
  await c.grant('https://app.exemplo.test:8443', 'REMEMBERED', 'DEVELOPER', 'STUDIO');
  const events = [];
  c.onChange((e) => events.push(e));
  assert.deepEqual(await c.revoke('https://app.exemplo.test:8443'), { origin: 'https://app.exemplo.test:8443', revoked: true });
  assert.equal(c.get('https://app.exemplo.test:8443'), null);
  assert.equal(store.rows.size, 0);
  assert.deepEqual(events, [{ type: 'revoked', origin: 'https://app.exemplo.test:8443' }]);
  assert.equal((await c.revoke('https://app.exemplo.test:8443')).revoked, false);
  await assert.rejects(c.grant('https://app.exemplo.test/x', 'SESSION', 'DEVELOPER', 'STUDIO'), { code: 'BAD_REQUEST' });
  await assert.rejects(c.grant('https://app.exemplo.test', 'FOREVER', 'DEVELOPER', 'STUDIO'), { code: 'BAD_REQUEST' });
  await assert.rejects(c.grant('https://app.exemplo.test', 'SESSION', 'PAGE', 'STUDIO'), { code: 'BAD_REQUEST' });
  await assert.rejects(c.grant('https://app.exemplo.test', 'SESSION', 'DEVELOPER', 'CONTENT'), { code: 'BAD_REQUEST' });
});

test('consentimento sobrevive a reinício do service worker; registro adulterado no armazenamento é ignorado (fail-closed)', async () => {
  const ch = fakeChrome();
  const store = fakePersistent();
  const a = createPddConsent({ chrome: ch, persistent: store });
  await a.grant('https://app.exemplo.test', 'SESSION', 'DEVELOPER', 'STUDIO');
  await a.grant('https://outro.exemplo.test', 'REMEMBERED', 'DEVELOPER', 'EXTENSION');
  store.rows.set('https://x.test/caminho', { origin: 'https://x.test/caminho', scope: 'REMEMBERED', actor: 'DEVELOPER', via: 'STUDIO', grantedAt: 'x' });
  store.rows.set('https://y.test', { origin: 'https://y.test', scope: 'REMEMBERED', actor: 'PAGE', via: 'STUDIO', grantedAt: 'x' });
  ch.session['nk.pdd.consent']['https://z.test'] = { origin: 'https://z.test', scope: 'REMEMBERED', actor: 'DEVELOPER', via: 'STUDIO', grantedAt: 'x' };
  const b = createPddConsent({ chrome: ch, persistent: store });
  await b.load();
  assert.equal(b.get('https://app.exemplo.test').scope, 'SESSION');
  assert.equal(b.get('https://outro.exemplo.test').scope, 'REMEMBERED');
  for (const o of ['https://x.test/caminho', 'https://x.test', 'https://y.test', 'https://z.test']) assert.equal(b.get(o), null, o);
  assert.deepEqual((await b.list()).map((r) => r.origin), ['https://app.exemplo.test', 'https://outro.exemplo.test']);
});

test('remoção da permissão do Chrome apaga o consentimento PDD das origens que deixaram de ser permitidas', async () => {
  const c = createPddConsent({ chrome: fakeChrome(), persistent: fakePersistent() });
  await c.grant('https://a.exemplo.test', 'REMEMBERED', 'DEVELOPER', 'STUDIO');
  await c.grant('https://b.exemplo.test', 'SESSION', 'DEVELOPER', 'STUDIO');
  const gone = await c.prune((o) => o === 'https://b.exemplo.test');
  assert.deepEqual(gone, ['https://a.exemplo.test']);
  assert.equal(c.get('https://a.exemplo.test'), null);
  assert.ok(c.get('https://b.exemplo.test'));
});

test('perfil: origem EXATA — esquema, porta e subdomínio diferentes não herdam o consentimento; v3 obrigatória no site', () => {
  const consent = { origin: 'https://app.exemplo.test' };
  const ev = (origin, allowed = true) => ({ allowed, kind: allowed ? 'AUTHORIZED' : null, reason: allowed ? null : 'PERMISSION_REQUIRED', origin });
  assert.equal(visualProfile(ev('https://app.exemplo.test'), consent), 'AUTHORIZED_SITE');
  for (const o of ['http://app.exemplo.test', 'https://app.exemplo.test:8443', 'https://sub.app.exemplo.test', 'https://exemplo.test']) {
    assert.equal(visualProfile(ev(o), consent), null, o);
    assert.equal(denialReason(ev(o), consent), 'PDD_CONSENT_REQUIRED', o);
  }
  assert.equal(denialReason(ev('https://app.exemplo.test', false), consent), 'PERMISSION_REQUIRED');
  assert.equal(denialReason({ allowed: false, reason: 'BROWSER_RESTRICTED' }, null), 'BROWSER_RESTRICTED');
  assert.deepEqual([...POLICIES_BY_PROFILE.AUTHORIZED_SITE], ['nekkora-visual/3', 'nekkora-visual/4', 'nekkora-visual/5'], 'a v2 (rótulos reais sem máscara de texto) nunca; a v5 depende do nível FULL');
  assert.ok(POLICIES_BY_PROFILE.LAB.includes('nekkora-visual/2'));
});

// ------------------------------------------------------------------------------------------------ descrição v3 (pura)

const VP = { width: 400, height: 300, dpr: 1 };
const GOOD = [
  { kind: 'SURFACE', x: 0, y: 0, width: 400, height: 40, tone: 'DARK' },
  { kind: 'MEDIA', x: 4, y: 4, width: 32, height: 32 },
  { kind: 'FRAME', x: 300, y: 200, width: 90, height: 90 },
  { kind: 'CONTROL', x: 20, y: 200, width: 80, height: 28 },
  { kind: 'FIELD', x: 20, y: 100, width: 200, height: 24 },
  { kind: 'TEXT', x: 20, y: 60, width: 128, height: 8, ink: 'DARK' },
  { kind: 'LABEL', x: 20, y: 80, width: 60, height: 16, text: 'Senha', size: 14, weight: 'NORMAL', ink: 'DARK', align: 'START' },
  { kind: 'LABEL', x: 20, y: 200, width: 80, height: 28, text: 'Entrar', size: 14, weight: 'BOLD', ink: 'LIGHT', align: 'CENTER' },
];

test('validateV3: descrição conforme passa e sai normalizada (só os campos do contrato)', () => {
  const out = validateV3({ viewport: VP, regions: GOOD }, TP);
  assert.equal(out.regions.length, GOOD.length);
  assert.deepEqual(out.viewport, VP);
});

test('validateV3: texto de rótulo fora do vocabulário, campo a mais, barra não quantizada, ordem, geometria e tetos reprovam com código', () => {
  const v = (regions, vp = VP) => () => validateV3({ viewport: vp, regions }, TP);
  const label = (o) => ({ kind: 'LABEL', x: 1, y: 1, width: 50, height: 16, text: 'Salvar', size: 12, weight: 'NORMAL', ink: 'DARK', align: 'START', ...o });
  assert.throws(v([label({ text: 'Maria Souza' })]), { code: 'LABEL_NOT_INTERFACE_TEXT' }, 'nome de pessoa');
  assert.throws(v([label({ text: '123.456.789-09' })]), { code: 'LABEL_NOT_INTERFACE_TEXT' }, 'CPF sintético');
  assert.throws(v([label({ text: 'Entrar paciente' })]), { code: 'LABEL_NOT_INTERFACE_TEXT' }, 'termo fora do vocabulário');
  assert.throws(v([label({ text: '  Salvar ' })]), { code: 'LABEL_NOT_INTERFACE_TEXT' }, 'forma não limpa');
  assert.throws(v([label({ value: 'x' })]), { code: 'REGION_FIELD_UNKNOWN' });
  assert.throws(v([{ kind: 'TEXT', x: 0, y: 0, width: 32, height: 8, ink: 'DARK', text: 'Salvar' }]), { code: 'REGION_FIELD_UNKNOWN' }, 'barra nunca leva texto');
  assert.throws(v([{ kind: 'FIELD', x: 0, y: 0, width: 32, height: 8, placeholder: 'CPF' }]), { code: 'REGION_FIELD_UNKNOWN' });
  assert.throws(v([{ kind: 'TEXT', x: 0, y: 0, width: 33, height: 8, ink: 'DARK' }]), { code: 'TEXT_NOT_QUANTIZED' });
  assert.throws(v([GOOD[6], GOOD[0]]), { code: 'REGION_ORDER' });
  assert.throws(v([{ kind: 'CARRIER', x: 0, y: 0, width: 10, height: 10 }]), { code: 'REGION_KIND_UNKNOWN' }, 'portador de pixels não existe na v3');
  assert.throws(v([{ kind: 'FIELD', x: 0.5, y: 0, width: 10, height: 10 }]), { code: 'REGION_OUT_OF_VIEWPORT' }, 'só inteiros');
  assert.throws(v([{ kind: 'FIELD', x: 395, y: 0, width: 10, height: 10 }]), { code: 'REGION_OUT_OF_VIEWPORT' });
  assert.throws(v([{ kind: 'SURFACE', x: 0, y: 0, width: 10, height: 10, tone: 'RED' }]), { code: 'REGION_KIND_UNKNOWN' });
  assert.throws(v([label({ size: 99 })]), { code: 'REGION_KIND_UNKNOWN' });
  assert.throws(v([label({ ink: '#ff0000' })]), { code: 'REGION_KIND_UNKNOWN' }, 'nunca a cor real');
  assert.throws(v(Array.from({ length: V3_MAX.LABEL + 1 }, () => label())), { code: 'TOO_MANY_REGIONS' });
  assert.throws(v([], { width: 400, height: 300, dpr: 1, scrollY: 10 }), { code: 'GEOMETRY_MISMATCH' });
  assert.throws(v([], { width: 0, height: 300, dpr: 1 }), { code: 'GEOMETRY_MISMATCH' });
  assert.throws(() => validateV3({ viewport: VP, regions: [], png: 'QUFB' }, null), { code: 'INTERNAL' }, 'sem política de texto = nada sai');
});

// ------------------------------------------------------------------------------------------------ máquina de estados

let posted;
let urls;
let clock;
let content;
let activeTab;
let shots;
let consent;
let allowed; // origens que o Chrome permite (além do laboratório)
let contentCalls;

const STEPS = [{ stepRef: 'step-1', targetId: 'tgt-u', descriptor: null, actionType: 'FILL' }];
const SITE = 'https://app.exemplo.test:8443';

function deps(over = {}) {
  return {
    client: { post: async (m) => posted.push(JSON.parse(JSON.stringify(m))), status: () => ({ connected: true }) },
    policy: {
      tabUrl: async (id) => urls.get(id),
      evaluate: (u) => {
        if (!u) return { allowed: false, kind: null, reason: 'URL_UNAVAILABLE', origin: null };
        const o = new URL(u).origin;
        if (u.startsWith('chrome://')) return { allowed: false, kind: null, reason: 'BROWSER_INTERNAL', origin: null };
        if (o.startsWith('http://127.0.0.1')) return { allowed: true, kind: 'LAB', reason: null, origin: o };
        return allowed.has(o) ? { allowed: true, kind: 'AUTHORIZED', reason: null, origin: o } : { allowed: false, kind: null, reason: 'PERMISSION_REQUIRED', origin: o };
      },
    },
    tabs: { tabOf: (ref) => ({ 't-AbaLab0001': 1, 't-AbaSite002': 2, 't-AbaOutra03': 3 })[ref] ?? null, recordingOn: () => false },
    chrome: {
      tabs: {
        get: async (id) => ({ id, windowId: 9, active: id === activeTab }),
        query: async (q) => (q.active && q.windowId === 9 ? [{ id: activeTab, windowId: 9, active: true }] : []),
        captureVisibleTab: async () => { shots++; return 'data:image/png;base64,QlJVVEE='; },
      },
      action: { setBadgeText: async () => {} },
      commands: { getAll: async () => [{ name: 'nk-pdd-capturar', shortcut: 'Alt+Shift+K' }] },
    },
    host: async (cmd, p) => (p.targetId === 'tgt-u' ? { status: 'RESOLVED', resolvedNodeId: 'n1' } : { status: 'AMBIGUOUS', resolvedNodeId: null }),
    toContent: async (tabId, cmd, a) => { contentCalls.push(cmd); return content(cmd, a, tabId); },
    currentCapture: async () => ({ captureId: 'cap-1' }),
    frameOfNode: () => 0,
    imaging: { decode: async () => { throw new Error('a v3 nunca decodifica imagem'); }, encode: async () => { throw new Error('nunca codifica'); } },
    consent,
    textPolicy: TP,
    now: () => clock,
    ...over,
  };
}

function planV3(regions = GOOD) {
  return { ok: true, policy: 'nekkora-visual/3', viewport: { ...VP, scrollX: 0, scrollY: 0 }, token: 't1', regions, rejected: {}, hasFrames: true,
    limitations: ['TABLE_CONTENT_HIDDEN', 'INVENTADA'] };
}

beforeEach(() => {
  posted = [];
  urls = new Map([[1, 'http://127.0.0.1:18765/lab/studio013/login.html'], [2, SITE + '/pacientes/123?token=abc'], [3, 'https://outra.exemplo.test/']]);
  clock = 1_000_000;
  activeTab = 2;
  shots = 0;
  contentCalls = [];
  allowed = new Set([SITE, 'https://outra.exemplo.test']);
  consent = createPddConsent({ chrome: fakeChrome(), persistent: fakePersistent(), now: () => clock });
  content = (cmd) => {
    if (cmd === 'cs.pdd.plan') return planV3();
    if (cmd === 'cs.pdd.rects') return { ok: true, token: 't1', rects: [{ nodeId: 'n1', x: 20, y: 100, width: 200, height: 24 }] };
    if (cmd === 'cs.pdd.token') return { ok: true, token: 't1' };
    return { ok: true };
  };
});

function events() {
  return posted.filter((m) => m.type === 'studio.event' && m.event === 'studio.pdd.captured').map((m) => m.payload);
}

const ARM = (pdd, o = {}) => pdd.COMMANDS['studio.pdd.capture.arm']({ captureId: 'c1', tabRef: 't-AbaSite002', steps: STEPS, leaseMs: 30000,
  policy: 'nekkora-visual/3', origin: SITE, ...o }, 'studio-a');
const GRANT = (pdd, o = {}) => pdd.COMMANDS['studio.pdd.consent.grant']({ tabRef: 't-AbaSite002', origin: SITE, scope: 'SESSION', actor: 'DEVELOPER', ...o },
  'studio-a');

test('status do consentimento: origem exata sem caminho/query; sem consentimento → motivo PDD_CONSENT_REQUIRED', async () => {
  const pdd = createStudioPdd(deps());
  const st = await pdd.COMMANDS['studio.pdd.consent.status']({ tabRef: 't-AbaSite002' });
  assert.deepEqual(st, { tabRef: 't-AbaSite002', origin: SITE, access: 'AUTHORIZED', consent: 'NONE', visualCapture: null, reason: 'PDD_CONSENT_REQUIRED' });
  assert.ok(!JSON.stringify(st).includes('pacientes') && !JSON.stringify(st).includes('token'));
  const lab = await pdd.COMMANDS['studio.pdd.consent.status']({ tabRef: 't-AbaLab0001' });
  assert.equal(lab.consent, 'NOT_REQUIRED');
  assert.equal(lab.visualCapture, 'LAB');
});

test('conceder pelo Studio: exige acesso do Chrome e a MESMA origem mostrada; laboratório não aceita registro; quem decide fica registrado', async () => {
  const pdd = createStudioPdd(deps());
  allowed.delete(SITE);
  await assert.rejects(GRANT(pdd), { code: 'VISUAL_CAPTURE_NOT_ALLOWED', reason: 'PERMISSION_REQUIRED' });
  allowed.add(SITE);
  await assert.rejects(GRANT(pdd, { origin: 'https://outra.exemplo.test' }), { code: 'VISUAL_CAPTURE_NOT_ALLOWED', reason: 'ORIGIN_CHANGED' });
  await assert.rejects(GRANT(pdd, { tabRef: 't-AbaLab0001', origin: 'https://app.exemplo.test' }), { code: 'VISUAL_CAPTURE_NOT_ALLOWED' });
  await assert.rejects(GRANT(pdd, { origin: SITE + '/pacientes' }), { code: 'BAD_REQUEST' });
  await assert.rejects(GRANT(pdd, { extra: 1 }), { code: 'BAD_REQUEST' });
  const r = await GRANT(pdd, { actor: 'TEST_AGENT' });
  assert.deepEqual({ origin: r.origin, consent: r.consent, actor: r.actor, via: r.via }, { origin: SITE, consent: 'SESSION', actor: 'TEST_AGENT', via: 'STUDIO' });
  const list = await pdd.COMMANDS['studio.pdd.consent.list']({});
  assert.deepEqual(list.consents.map((c) => [c.origin, c.chromeAccess]), [[SITE, true]]);
});

test('site autorizado + consentimento: Studio antigo (v1/v2) recusado; v3 arma com perfil AUTHORIZED_SITE', async () => {
  const pdd = createStudioPdd(deps());
  await GRANT(pdd);
  for (const policy of [undefined, 'nekkora-visual/1', 'nekkora-visual/2']) {
    await assert.rejects(pdd.COMMANDS['studio.pdd.capture.arm']({ captureId: 'c0', tabRef: 't-AbaSite002', steps: STEPS, leaseMs: 30000,
      ...(policy ? { policy } : {}) }, 'studio-a'), { code: 'VISUAL_CAPTURE_NOT_ALLOWED', reason: 'POLICY_REQUIRES_V3' }, String(policy));
  }
  const r = await ARM(pdd);
  assert.deepEqual({ armed: r.armed, policy: r.policy, profile: r.profile }, { armed: true, policy: 'nekkora-visual/3', profile: 'AUTHORIZED_SITE' });
});

test('v3 ponta a ponta: nenhuma chamada a captureVisibleTab, nenhuma imagem no evento; descrição validada; limitações fora do contrato caem', async () => {
  const pdd = createStudioPdd(deps());
  await GRANT(pdd);
  await ARM(pdd);
  assert.deepEqual(await pdd.onShortcut('nk-pdd-capturar', { id: 2, windowId: 9 }), { captured: true });
  assert.equal(shots, 0, 'captureVisibleTab nunca é chamado na v3');
  const [ev] = events();
  assert.equal(ev.status, 'CAPTURED');
  assert.equal(ev.policy, 'nekkora-visual/3');
  assert.deepEqual(Object.keys(ev).sort(), ['annotations', 'captureId', 'limitations', 'observedAt', 'policy', 'regions', 'status', 'viewport']);
  assert.deepEqual(ev.viewport, VP, 'sem rolagem nem URL');
  assert.equal(ev.regions.length, GOOD.length);
  assert.deepEqual(ev.limitations.sort(), ['FRAMES_HIDDEN', 'TABLE_CONTENT_HIDDEN']);
  assert.deepEqual(ev.annotations[0].status, 'PLACED');
  const all = JSON.stringify(posted);
  for (const forbidden of ['png', 'sha256', 'pacientes', 'token=abc', 'data:image']) assert.ok(!all.includes(forbidden), forbidden);
  assert.deepEqual(contentCalls.filter((c) => c === 'cs.pdd.plan'), ['cs.pdd.plan']);
});

test('LAB continua sem consentimento (v1/v2/v3); v3 no LAB também não toca em pixels', async () => {
  activeTab = 1;
  const pdd = createStudioPdd(deps());
  const r = await pdd.COMMANDS['studio.pdd.capture.arm']({ captureId: 'c9', tabRef: 't-AbaLab0001', steps: STEPS, leaseMs: 30000, policy: 'nekkora-visual/3',
    origin: 'http://127.0.0.1:18765' }, 'studio-a');
  assert.equal(r.profile, 'LAB');
  assert.deepEqual(await pdd.onShortcut('nk-pdd-capturar', { id: 1, windowId: 9 }), { captured: true });
  assert.equal(shots, 0);
});

test('content script comprometido: rótulo com texto fora do vocabulário, campo extra ou barra com texto → falha sem nada publicado', async () => {
  const cases = [
    [{ kind: 'LABEL', x: 1, y: 1, width: 80, height: 16, text: 'Maria Souza', size: 12, weight: 'NORMAL', ink: 'DARK', align: 'START' }, 'LABEL_NOT_INTERFACE_TEXT'],
    [{ kind: 'FIELD', x: 1, y: 1, width: 80, height: 16, value: 'segredo-sintetico-123' }, 'REGION_FIELD_UNKNOWN'],
    [{ kind: 'TEXT', x: 0, y: 0, width: 32, height: 8, ink: 'DARK', text: 'Paciente Fulano' }, 'REGION_FIELD_UNKNOWN'],
    [{ kind: 'TEXT', x: 0, y: 0, width: 37, height: 8, ink: 'DARK' }, 'TEXT_NOT_QUANTIZED'],
  ];
  for (const [region, code] of cases) {
    posted = [];
    content = (cmd) => (cmd === 'cs.pdd.plan' ? planV3([region]) : cmd === 'cs.pdd.token' ? { ok: true, token: 't1' } : { ok: true, token: 't1', rects: [] });
    const pdd = createStudioPdd(deps());
    await GRANT(pdd);
    await ARM(pdd);
    assert.deepEqual(await pdd.onShortcut('nk-pdd-capturar', { id: 2, windowId: 9 }), { failed: code }, code);
    const [ev] = events();
    assert.deepEqual(Object.keys(ev).sort(), ['captureId', 'code', 'observedAt', 'status']);
    assert.ok(!JSON.stringify(posted).includes('segredo') && !JSON.stringify(posted).includes('Fulano') && !JSON.stringify(posted).includes('Maria'));
  }
});

test('revogar o consentimento com a captura ARMADA: a armação cai (PDD_CONSENT_REVOKED) e o atalho não captura', async () => {
  const pdd = createStudioPdd(deps());
  consent.onChange(() => pdd.onAccessChanged());
  await GRANT(pdd);
  await ARM(pdd);
  await pdd.COMMANDS['studio.pdd.consent.revoke']({ origin: SITE });
  await new Promise((r) => setImmediate(r));
  assert.equal(pdd.state().armed, false);
  assert.deepEqual(events().map((e) => e.code), ['PDD_CONSENT_REVOKED']);
  assert.deepEqual(await pdd.onShortcut('nk-pdd-capturar', { id: 2, windowId: 9 }), { ignored: true });
  assert.ok(!contentCalls.includes('cs.pdd.plan'));
});

test('revogar o consentimento DURANTE a captura: termina em PDD_CONSENT_REVOKED, nenhuma descrição publicada', async () => {
  const pdd = createStudioPdd(deps());
  await GRANT(pdd);
  await ARM(pdd);
  content = (cmd) => {
    if (cmd === 'cs.pdd.plan') {
      consent.revoke(SITE).then(() => pdd.onAccessChanged()); // revogação chega no meio do plano
      return planV3();
    }
    if (cmd === 'cs.pdd.rects') return { ok: true, token: 't1', rects: [] };
    return { ok: true, token: 't1' };
  };
  assert.deepEqual(await pdd.onShortcut('nk-pdd-capturar', { id: 2, windowId: 9 }), { failed: 'PDD_CONSENT_REVOKED' });
  assert.deepEqual(events().map((e) => [e.status, e.code]), [['FAILED', 'PDD_CONSENT_REVOKED']]);
});

test('permissão do Chrome revogada com consentimento PDD ainda presente: armação cai (PERMISSION_REVOKED); armar de novo é recusado', async () => {
  const pdd = createStudioPdd(deps());
  await GRANT(pdd);
  await ARM(pdd);
  allowed.delete(SITE);
  await pdd.onAccessChanged();
  assert.deepEqual(events().map((e) => e.code), ['PERMISSION_REVOKED']);
  await assert.rejects(ARM(pdd, { captureId: 'c2' }), { code: 'VISUAL_CAPTURE_NOT_ALLOWED', reason: 'PERMISSION_REQUIRED' });
});

test('mudança de origem: armar com a origem errada; navegar (SSO) durante a captura; mesma aba em outra origem também consentida', async () => {
  const pdd = createStudioPdd(deps());
  await GRANT(pdd);
  await assert.rejects(ARM(pdd, { origin: 'https://outra.exemplo.test' }), { code: 'VISUAL_CAPTURE_NOT_ALLOWED', reason: 'ORIGIN_CHANGED' });
  // redirecionamento de login para outra origem (também autorizada e consentida) no meio da captura
  urls.set(3, 'https://outra.exemplo.test/');
  await pdd.COMMANDS['studio.pdd.consent.grant']({ tabRef: 't-AbaOutra03', origin: 'https://outra.exemplo.test', scope: 'SESSION', actor: 'DEVELOPER' });
  await ARM(pdd);
  content = (cmd) => {
    if (cmd === 'cs.pdd.plan') {
      urls.set(2, 'https://outra.exemplo.test/sso/callback?code=xyz');
      return planV3();
    }
    if (cmd === 'cs.pdd.rects') return { ok: true, token: 't1', rects: [] };
    return { ok: true, token: 't1' };
  };
  assert.deepEqual(await pdd.onShortcut('nk-pdd-capturar', { id: 2, windowId: 9 }), { failed: 'ORIGIN_CHANGED' });
  assert.ok(!JSON.stringify(posted).includes('sso'));
});

test('navegação na aba armada derruba a armação; durante a captura vira PAGE_CHANGED', async () => {
  const pdd = createStudioPdd(deps());
  await GRANT(pdd);
  await ARM(pdd);
  pdd.onPageChanged(2);
  assert.deepEqual(events().map((e) => e.code), ['PAGE_CHANGED']);
  posted = [];
  await ARM(pdd, { captureId: 'c2' });
  content = (cmd) => {
    if (cmd === 'cs.pdd.plan') { pdd.onPageChanged(2); return planV3(); }
    return { ok: true, token: 't1', rects: [] };
  };
  assert.deepEqual(await pdd.onShortcut('nk-pdd-capturar', { id: 2, windowId: 9 }), { failed: 'PAGE_CHANGED' });
});

test('A→B→A, nova aba e cancelamento durante a captura: nada publicado', async () => {
  for (const [what, act, code] of [
    ['A→B→A', (pdd) => { pdd.onTabActivated({ tabId: 3, windowId: 9 }); pdd.onTabActivated({ tabId: 2, windowId: 9 }); }, 'ACTIVE_TAB_CHANGED'],
    ['nova aba ativa', () => { activeTab = 3; }, 'ACTIVE_TAB_CHANGED'],
    ['cancelar', (pdd) => pdd.COMMANDS['studio.pdd.capture.cancel']({ captureId: 'c1' }, 'studio-a'), 'CANCELLED'],
  ]) {
    posted = [];
    activeTab = 2;
    const pdd = createStudioPdd(deps());
    await GRANT(pdd);
    await ARM(pdd);
    content = (cmd) => {
      if (cmd === 'cs.pdd.plan') { act(pdd); return planV3(); }
      return { ok: true, token: 't1', rects: [] };
    };
    assert.deepEqual(await pdd.onShortcut('nk-pdd-capturar', { id: 2, windowId: 9 }), { failed: code }, what);
    assert.deepEqual(events().map((e) => e.status), ['FAILED'], what);
  }
});

test('páginas internas/restritas e sem permissão: consentimento impossível e captura recusada com o motivo do Chrome', async () => {
  urls.set(3, 'chrome://settings/');
  const pdd = createStudioPdd(deps());
  const st = await pdd.COMMANDS['studio.pdd.consent.status']({ tabRef: 't-AbaOutra03' });
  assert.deepEqual([st.origin, st.access, st.consent, st.reason], [null, 'BROWSER_INTERNAL', 'NONE', 'BROWSER_INTERNAL']);
  await assert.rejects(pdd.COMMANDS['studio.pdd.consent.grant']({ tabRef: 't-AbaOutra03', origin: 'https://x.test', scope: 'SESSION', actor: 'DEVELOPER' }),
    { code: 'VISUAL_CAPTURE_NOT_ALLOWED', reason: 'BROWSER_INTERNAL' });
  await assert.rejects(pdd.COMMANDS['studio.pdd.capture.arm']({ captureId: 'c1', tabRef: 't-AbaOutra03', steps: STEPS, leaseMs: 30000, policy: 'nekkora-visual/3' },
    'studio-a'), { code: 'VISUAL_CAPTURE_NOT_ALLOWED', reason: 'BROWSER_INTERNAL' });
});

test('sem módulo de consentimento (extensão montada sem ele): só o laboratório captura — fail-closed', async () => {
  const pdd = createStudioPdd(deps({ consent: undefined }));
  await assert.rejects(GRANT(pdd), { code: 'INVALID_STATE' });
  await assert.rejects(ARM(pdd), { code: 'VISUAL_CAPTURE_NOT_ALLOWED', reason: 'PDD_CONSENT_REQUIRED' });
});

// ------------------------------------------------------------------------------------------------ v4: print real com o texto mascarado

const S = await import('../src/background/visual-sanitizer.js');
const SENT = [230, 0, 126]; // cor-sentinela: o "dado" da página

function screen(w, h) {
  const px = new Uint8ClampedArray(w * h * 4);
  for (let i = 0; i < px.length; i += 4) {
    px[i] = SENT[0];
    px[i + 1] = SENT[1];
    px[i + 2] = SENT[2];
    px[i + 3] = 255;
  }
  return px;
}

function planV4(regions, dpr = 1, extra = {}) {
  return { ok: true, policy: 'nekkora-visual/4', viewport: { width: 200, height: 100, dpr, scrollX: 0, scrollY: 0 }, token: 't1', regions, rejected: {},
    hasFrames: false, incomplete: false, labels: 2, limitations: [], ...extra };
}

const MASKS = [{ kind: 'BLOCK', x: 100, y: 0, width: 100, height: 50 }, { kind: 'FIELD', x: 10, y: 60, width: 80, height: 20 },
  { kind: 'TEXT', x: 10, y: 10, width: 60.4, height: 12.2, ink: 'DARK' }];

test('v4: tela real fora das máscaras; DENTRO delas nenhum pixel da página (nem com dpr fracionário); adulterar 1 pixel é detectado', () => {
  for (const dpr of [1, 1.25, 1.5]) {
    const w = Math.round(200 * dpr), h = Math.round(100 * dpr);
    const masks = S.deviceMasksV4(planV4(MASKS, dpr), w, h);
    const out = S.composeV4(screen(w, h), w, h, masks);
    assert.equal(S.violationsV4(out, w, h, masks), 0);
    const inMask = (x, y) => masks.some((m) => x >= m.x && x < m.x2 && y >= m.y && y < m.y2);
    let sentInside = 0, sentOutside = 0;
    for (let y = 0; y < h; y++) {
      for (let x = 0; x < w; x++) {
        const k = (y * w + x) * 4;
        const s = out[k] === SENT[0] && out[k + 1] === SENT[1] && out[k + 2] === SENT[2];
        if (s && inMask(x, y)) sentInside++;
        if (s && !inMask(x, y)) sentOutside++;
      }
    }
    assert.equal(sentInside, 0, 'dpr ' + dpr);
    assert.ok(sentOutside > 0, 'o resto é a tela real');
    const t = masks[2];
    assert.ok(t.x <= Math.floor(10 * dpr) && t.x2 >= Math.ceil(70.4 * dpr), 'arredondado para fora');
    out[(masks[0].y + 3) * w * 4 + (masks[0].x + 3) * 4] = 1;
    assert.equal(S.violationsV4(out, w, h, masks), 1);
  }
  assert.throws(() => S.deviceMasksV4(planV4([MASKS[2], MASKS[0]]), 200, 100), { code: 'REGION_ORDER' });
  assert.throws(() => S.deviceMasksV4(planV4([{ kind: 'CARRIER', x: 0, y: 0, width: 5, height: 5 }]), 200, 100), { code: 'REGION_KIND_UNKNOWN' });
  assert.throws(() => S.deviceMasksV4(planV4([{ kind: 'TEXT', x: 0, y: 0, width: 5, height: 5, ink: '#000' }]), 200, 100), { code: 'REGION_KIND_UNKNOWN' });
  assert.throws(() => S.deviceMasksV4(planV4(MASKS), 210, 100), { code: 'GEOMETRY_MISMATCH' });
});

test('v4 no site autorizado: captura com o gesto, máscaras verificadas, PNG no evento; plano incompleto falha sem imagem', async () => {
  let encoded = null;
  const imaging = {
    decode: async () => ({ width: 200, height: 100, pixels: screen(200, 100), release() {} }),
    encode: async (px) => { encoded = new Uint8ClampedArray(px); return new Uint8Array([137, 80, 78, 71, 9]); },
  };
  for (const [extra, expect] of [[{}, 'CAPTURED'], [{ incomplete: true }, 'TOO_MANY_REGIONS']]) {
    posted = [];
    shots = 0;
    content = (cmd, a) => {
      if (cmd === 'cs.pdd.plan') return a.policy === 'nekkora-visual/4' ? planV4(MASKS, 1, extra) : { ok: false };
      if (cmd === 'cs.pdd.rects') return { ok: true, token: 't1', rects: [{ nodeId: 'n1', x: 10, y: 60, width: 80, height: 20 }] };
      return { ok: true, token: 't1' };
    };
    const pdd = createStudioPdd(deps({ imaging }));
    await GRANT(pdd);
    await ARM(pdd, { policy: 'nekkora-visual/4', captureId: 'c4' + expect.length });
    await pdd.onShortcut('nk-pdd-capturar', { id: 2, windowId: 9 });
    const [ev] = events();
    if (expect === 'CAPTURED') {
      assert.equal(ev.status, 'CAPTURED');
      assert.equal(ev.policy, 'nekkora-visual/4');
      assert.equal(shots, 1, 'a v4 é o print real');
      assert.ok(ev.png && ev.sha256);
      assert.deepEqual(ev.regions.map((r) => r.kind), ['BLOCK', 'FIELD', 'TEXT']);
      assert.equal(ev.regions[2].ink, 'DARK');
      assert.ok(!JSON.stringify(ev).includes('pacientes'));
      const masks = S.deviceMasksV4(planV4(MASKS), 200, 100);
      assert.equal(S.violationsV4(encoded, 200, 100, masks), 0);
    } else {
      assert.deepEqual([ev.status, ev.code], ['FAILED', expect]);
      assert.equal(shots, 0, 'nada é capturado com o plano incompleto');
    }
  }
});

// ------------------------------------------------------------------------------------------------ v5: print integral (revisão da usuária)

/** deps() com webNavigation: `frames` = [{ frameId, secrets | null (sem resposta) }] além do principal. */
function depsV5(frames, over = {}) {
  const d = deps(over);
  d.chrome = { ...d.chrome, webNavigation: { getAllFrames: async () => (frames === 'ERRO' ? null : [{ frameId: 0 }, ...frames.map((f) => ({ frameId: f.frameId }))]) } };
  const base = d.toContent;
  d.toContent = async (tabId, cmd, a, frameId = 0) => {
    if (cmd === 'cs.pdd.secrets') {
      const f = frames.find((x) => x.frameId === frameId);
      if (!f || f.secrets == null) throw new Error('CONTENT_UNAVAILABLE');
      contentCalls.push(cmd + '@' + frameId);
      return { ok: true, secrets: f.secrets };
    }
    return base(tabId, cmd, a);
  };
  return d;
}

const PASS = { kind: 'FIELD', x: 10, y: 60, width: 80, height: 20 };
const FRAME = { kind: 'BLOCK', x: 100, y: 0, width: 100, height: 50 };

function planV5(regions, extra = {}) {
  return { ...planV4(regions, 1, extra), policy: 'nekkora-visual/5', limitations: regions.some((r) => r.kind === 'BLOCK') ? ['FRAMES_HIDDEN'] : [], ...extra };
}

const imagingV5 = (sink) => ({
  decode: async () => ({ width: 200, height: 100, pixels: screen(200, 100), release() {} }),
  encode: async (px) => { sink.px = new Uint8ClampedArray(px); return new Uint8Array([137, 80, 78, 71, 5]); },
});

test('v5 consentimento: nível MASKED por padrão; FULL só pedido; registro da /1 (sem nível) vale MASKED; nível desconhecido é descartado', async () => {
  const ch = fakeChrome();
  const pers = fakePersistent();
  const c = createPddConsent({ chrome: ch, persistent: pers, now: () => clock });
  assert.equal((await c.grant(SITE, 'SESSION', 'DEVELOPER', 'STUDIO')).level, 'MASKED');
  assert.equal((await c.grant(SITE, 'SESSION', 'DEVELOPER', 'STUDIO', 'FULL')).level, 'FULL');
  assert.equal(c.get(SITE).level, 'FULL');
  assert.equal((await c.grant(SITE, 'SESSION', 'DEVELOPER', 'STUDIO', 'MASKED')).level, 'MASKED', 'conceder de novo rebaixa');
  await assert.rejects(c.grant(SITE, 'SESSION', 'DEVELOPER', 'STUDIO', 'TOTAL'), { code: 'BAD_REQUEST' });
  pers.rows.set('https://velho.exemplo.test', { origin: 'https://velho.exemplo.test', scope: 'REMEMBERED', actor: 'DEVELOPER', via: 'STUDIO', grantedAt: 'x' });
  pers.rows.set('https://ruim.exemplo.test', { origin: 'https://ruim.exemplo.test', scope: 'REMEMBERED', level: 'TUDO', actor: 'DEVELOPER', via: 'STUDIO', grantedAt: 'x' });
  const again = createPddConsent({ chrome: ch, persistent: pers, now: () => clock });
  await again.load();
  assert.equal(again.get('https://velho.exemplo.test').level, 'MASKED');
  assert.equal(again.get('https://ruim.exemplo.test'), null);
  assert.equal(again.get(SITE).level, 'MASKED');
});

test('v5 armação: site MASKED recusa (PDD_FULL_REQUIRED); FULL arma; v4 continua valendo no FULL; laboratório arma a v5 sem consentimento', async () => {
  const pdd = createStudioPdd(depsV5([]));
  await GRANT(pdd);
  await assert.rejects(ARM(pdd, { policy: 'nekkora-visual/5' }), { code: 'VISUAL_CAPTURE_NOT_ALLOWED', reason: 'PDD_FULL_REQUIRED' });
  await assert.rejects(GRANT(pdd, { level: 'TOTAL' }), { code: 'BAD_REQUEST' });
  const g = await GRANT(pdd, { level: 'FULL' });
  assert.equal(g.level, 'FULL');
  const st = await pdd.COMMANDS['studio.pdd.consent.status']({ tabRef: 't-AbaSite002' });
  assert.equal(st.level, 'FULL');
  assert.deepEqual((await pdd.COMMANDS['studio.pdd.consent.list']({})).consents.map((c) => c.level), ['FULL']);
  const r = await ARM(pdd, { policy: 'nekkora-visual/5' });
  assert.deepEqual([r.armed, r.policy, r.profile], [true, 'nekkora-visual/5', 'AUTHORIZED_SITE']);
  await pdd.COMMANDS['studio.pdd.capture.cancel']({ captureId: 'c1' }, 'studio-a');
  assert.equal((await ARM(pdd, { policy: 'nekkora-visual/4', captureId: 'c2' })).policy, 'nekkora-visual/4');
  await pdd.COMMANDS['studio.pdd.capture.cancel']({ captureId: 'c2' }, 'studio-a');
  activeTab = 1;
  const lab = await pdd.COMMANDS['studio.pdd.capture.arm']({ captureId: 'c3', tabRef: 't-AbaLab0001', steps: STEPS, leaseMs: 30000,
    policy: 'nekkora-visual/5', origin: 'http://127.0.0.1:18765' }, 'studio-a');
  assert.equal(lab.profile, 'LAB');
});

test('v5 ponta a ponta: a tela sai como está, só a senha coberta; frames sem senha confirmados ficam reais; PNG verificado', async () => {
  const sink = {};
  let asked = null;
  content = (cmd, a) => {
    if (cmd === 'cs.pdd.plan') { asked = a; return a.policy === 'nekkora-visual/5' ? planV5(a.blockFrames ? [FRAME, PASS] : [PASS]) : { ok: false }; }
    if (cmd === 'cs.pdd.rects') return { ok: true, token: 't1', rects: [{ nodeId: 'n1', x: 10, y: 60, width: 80, height: 20 }] };
    return { ok: true, token: 't1' };
  };
  const pdd = createStudioPdd(depsV5([{ frameId: 4, secrets: 0 }, { frameId: 7, secrets: 0 }], { imaging: imagingV5(sink) }));
  await GRANT(pdd, { level: 'FULL' });
  await ARM(pdd, { policy: 'nekkora-visual/5' });
  assert.deepEqual(await pdd.onShortcut('nk-pdd-capturar', { id: 2, windowId: 9 }), { captured: true });
  assert.deepEqual(asked, { policy: 'nekkora-visual/5', blockFrames: false });
  assert.ok(contentCalls.includes('cs.pdd.secrets@4') && contentCalls.includes('cs.pdd.secrets@7'));
  const [ev] = events();
  assert.deepEqual([ev.status, ev.policy, shots], ['CAPTURED', 'nekkora-visual/5', 1]);
  assert.deepEqual(ev.regions.map((r) => r.kind), ['FIELD']);
  assert.ok(!ev.limitations.includes('FRAMES_HIDDEN'));
  const masks = S.deviceMasksV5(planV5([PASS]), 200, 100);
  assert.equal(S.violationsV4(sink.px, 200, 100, masks), 0, 'a senha coberta, pixel a pixel');
  let real = 0;
  for (let k = 0; k < sink.px.length; k += 4) if (sink.px[k] === SENT[0] && sink.px[k + 1] === SENT[1] && sink.px[k + 2] === SENT[2]) real++;
  assert.equal(real, 200 * 100 - 80 * 20, 'todo o resto é a tela real (dados inclusive)');
});

test('v5 com frames: senha num frame, frame sem resposta ou falha ao listar os frames → TODOS os frames cobertos (FRAMES_HIDDEN)', async () => {
  for (const frames of [[{ frameId: 4, secrets: 1 }], [{ frameId: 4, secrets: 0 }, { frameId: 5, secrets: null }], 'ERRO']) {
    posted = [];
    shots = 0;
    let asked = null;
    content = (cmd, a) => {
      if (cmd === 'cs.pdd.plan') { asked = a; return planV5(a.blockFrames ? [FRAME, PASS] : [PASS]); }
      if (cmd === 'cs.pdd.rects') return { ok: true, token: 't1', rects: [] };
      return { ok: true, token: 't1' };
    };
    const pdd = createStudioPdd(depsV5(frames, { imaging: imagingV5({}) }));
    await GRANT(pdd, { level: 'FULL' });
    await ARM(pdd, { policy: 'nekkora-visual/5' });
    await pdd.onShortcut('nk-pdd-capturar', { id: 2, windowId: 9 });
    assert.equal(asked.blockFrames, true, JSON.stringify(frames));
    const [ev] = events();
    assert.deepEqual(ev.regions.map((r) => r.kind), ['BLOCK', 'FIELD']);
    assert.ok(ev.limitations.includes('FRAMES_HIDDEN'));
  }
});

test('v5 rebaixada para MASKED com a captura ARMADA ou EM CURSO: PDD_CONSENT_REVOKED, nenhuma imagem; plano v5 com TEXT é recusado', async () => {
  let pdd = createStudioPdd(depsV5([], { imaging: imagingV5({}) }));
  consent.onChange(() => pdd.onAccessChanged());
  await GRANT(pdd, { level: 'FULL' });
  await ARM(pdd, { policy: 'nekkora-visual/5' });
  await GRANT(pdd, { level: 'MASKED' });
  await new Promise((r) => setImmediate(r));
  assert.equal(pdd.state().armed, false);
  assert.deepEqual(events().map((e) => e.code), ['PDD_CONSENT_REVOKED']);

  posted = [];
  consent = createPddConsent({ chrome: fakeChrome(), persistent: fakePersistent(), now: () => clock });
  pdd = createStudioPdd(depsV5([], { imaging: imagingV5({}) }));
  await GRANT(pdd, { level: 'FULL' });
  await ARM(pdd, { policy: 'nekkora-visual/5' });
  content = (cmd) => {
    if (cmd === 'cs.pdd.plan') {
      consent.grant(SITE, 'SESSION', 'DEVELOPER', 'STUDIO', 'MASKED').then(() => pdd.onAccessChanged()); // rebaixado no meio do plano
      return planV5([PASS]);
    }
    if (cmd === 'cs.pdd.rects') return { ok: true, token: 't1', rects: [] };
    return { ok: true, token: 't1' };
  };
  assert.deepEqual(await pdd.onShortcut('nk-pdd-capturar', { id: 2, windowId: 9 }), { failed: 'PDD_CONSENT_REVOKED' });
  assert.equal(shots, 0);

  // sem o ouvinte de mudanças (evento perdido): a conferência antes da captura (sameAuthorization) segura sozinha
  posted = [];
  consent = createPddConsent({ chrome: fakeChrome(), persistent: fakePersistent(), now: () => clock });
  pdd = createStudioPdd(depsV5([], { imaging: imagingV5({}) }));
  await GRANT(pdd, { level: 'FULL' });
  await ARM(pdd, { policy: 'nekkora-visual/5' });
  content = (cmd) => {
    if (cmd === 'cs.pdd.plan') {
      consent.grant(SITE, 'SESSION', 'DEVELOPER', 'STUDIO', 'MASKED'); // rebaixado no meio do plano, SEM avisar o módulo
      return planV5([PASS]);
    }
    if (cmd === 'cs.pdd.rects') return { ok: true, token: 't1', rects: [] };
    return { ok: true, token: 't1' };
  };
  assert.deepEqual(await pdd.onShortcut('nk-pdd-capturar', { id: 2, windowId: 9 }), { failed: 'PDD_CONSENT_REVOKED' });
  assert.equal(shots, 0, 'nenhum print integral depois do rebaixamento');
  assert.throws(() => S.deviceMasksV5(planV5([PASS, { kind: 'TEXT', x: 0, y: 0, width: 5, height: 5, ink: 'DARK' }]), 200, 100), { code: 'REGION_KIND_UNKNOWN' });
});
