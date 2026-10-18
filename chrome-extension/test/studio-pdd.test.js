// STUDIO-010 (pdd-capture/1, política nekkora-visual/1): sanitização de pixels por lista de permissão e máquina de
// estados da captura (armar → gesto → resolver → plano → capturar → ficha → sanitizar → verificar → evento). A imagem
// "bruta" destes testes é preenchida com uma cor-sentinela: qualquer vazamento aparece na verificação.
import { visualProfile, BENCHMARK_ORIGINS } from '../src/background/visual-capture-profile.js';
import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { fileURLToPath, pathToFileURL } from 'node:url';
import path from 'node:path';
import { schema, errors } from './helpers/mini-schema.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const V = await import(pathToFileURL(path.join(here, '../src/background/visual-sanitizer.js')).href);
const { createStudioPdd } = await import(pathToFileURL(path.join(here, '../src/background/studio-pdd.js')).href);

const SENTINEL = [10, 200, 30]; // "texto da página" fictício: nunca pode sobrar fora dos portadores

function rawImage(w, h) {
  const px = new Uint8ClampedArray(w * h * 4);
  for (let i = 0; i < px.length; i += 4) {
    px[i] = SENTINEL[0];
    px[i + 1] = SENTINEL[1];
    px[i + 2] = SENTINEL[2];
    px[i + 3] = 255;
  }
  return px;
}

function plan(w, h, dpr, regions, token = 't1') {
  return { ok: true, viewport: { width: w, height: h, dpr, scrollX: 0, scrollY: 0 }, token, regions, rejected: {}, hasFrames: false };
}

// ------------------------------------------------------------------------------------------- sanitizador puro

test('deviceRegions: imagem que não é o viewport, portador grande, portador sobre campo e região fora → recusa (código)', () => {
  const ok = plan(200, 100, 1, [{ kind: 'CARRIER', x: 10, y: 10, width: 40, height: 20 }, { kind: 'FIELD', x: 60, y: 10, width: 80, height: 20 }]);
  assert.equal(V.deviceRegions(ok, 200, 100).carriers.length, 1);
  assert.throws(() => V.deviceRegions(ok, 201, 120), { code: 'GEOMETRY_MISMATCH' });
  assert.throws(() => V.deviceRegions(plan(200, 100, 1, [{ kind: 'CARRIER', x: 0, y: 0, width: 50, height: 70 }]), 200, 100), { code: 'CARRIER_TOO_LARGE' });
  assert.throws(() => V.deviceRegions(plan(200, 100, 1, [{ kind: 'CARRIER', x: 0, y: 0, width: 150, height: 20 }]), 200, 100), { code: 'CARRIER_TOO_LARGE' });
  assert.throws(() => V.deviceRegions(plan(200, 100, 1, [{ kind: 'CARRIER', x: 50, y: 10, width: 40, height: 20 },
    { kind: 'FIELD', x: 60, y: 10, width: 80, height: 20 }]), 200, 100), { code: 'CARRIER_OVERLAPS_FIELD' });
  assert.throws(() => V.deviceRegions(plan(200, 100, 1, [{ kind: 'FIELD', x: 190, y: 10, width: 80, height: 20 }]), 200, 100), { code: 'REGION_OUT_OF_VIEWPORT' });
  assert.throws(() => V.deviceRegions(plan(200, 100, 1, [{ kind: 'TEXT', x: 1, y: 1, width: 5, height: 5 }]), 200, 100), { code: 'REGION_KIND_UNKNOWN' });
});

test('deviceRegions em 150%: portador arredondado PARA DENTRO (copia menos), campo para fora (cobre mais)', () => {
  const r = V.deviceRegions(plan(200, 100, 1.5, [{ kind: 'CARRIER', x: 10.3, y: 10.3, width: 20.2, height: 10.2 },
    { kind: 'FIELD', x: 50.3, y: 10.3, width: 20.2, height: 10.2 }]), 300, 150);
  assert.deepEqual(r.carriers[0], { x: 16, y: 16, x2: 45, y2: 30 });
  assert.deepEqual(r.fields[0], { x: 75, y: 15, x2: 106, y2: 31 });
});

test('compose + violations: fora dos portadores só as 3 cores sintéticas; campo nunca recebe pixel bruto; adulterar 1 pixel é detectado', () => {
  const w = 200, h = 100;
  const regions = V.deviceRegions(plan(w, h, 1, [{ kind: 'CARRIER', x: 10, y: 10, width: 40, height: 20 },
    { kind: 'FIELD', x: 60, y: 10, width: 80, height: 20 }]), w, h);
  const out = V.compose(rawImage(w, h), w, h, regions);
  assert.equal(V.violations(out, w, h, regions), 0);
  let sentinelOutside = 0;
  let sentinelInside = 0;
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const k = (y * w + x) * 4;
      const s = out[k] === SENTINEL[0] && out[k + 1] === SENTINEL[1] && out[k + 2] === SENTINEL[2];
      const inCarrier = x >= 10 && x < 50 && y >= 10 && y < 30;
      if (s && !inCarrier) sentinelOutside++;
      if (s && inCarrier) sentinelInside++;
    }
  }
  assert.equal(sentinelOutside, 0, 'nada da imagem bruta fora dos portadores (inclusive dentro do campo)');
  assert.equal(sentinelInside, 40 * 20, 'portador mostra a imagem real');
  out[(50 * w + 150) * 4] = 7; // um pixel fora da regra
  assert.equal(V.violations(out, w, h, regions), 1);
  assert.equal(V.violations(new Uint8ClampedArray(10), w, h, regions), Number.MAX_SAFE_INTEGER, 'tamanho errado = não conforme');
});

// ------------------------------------------------------------------------------------------- máquina de estados

let posted;
let urls;
let clock;
let content;
let captured;
let decodeCalls;
let lastPixels;
let activeTab; // aba ativa da janela 9 (o que captureVisibleTab pegaria)
let shots;

function deps(over = {}) {
  return {
    client: { post: async (m) => posted.push(JSON.parse(JSON.stringify(m))), status: () => ({ connected: true }) },
    policy: { tabUrl: async (id) => urls.get(id), evaluate: (u) => ({ allowed: !!u, origin: u ? new URL(u).origin : null,
      kind: u && u.startsWith('http://127.0.0.1') ? 'LAB' : 'AUTHORIZED' }) },
    tabs: { tabOf: (ref) => ({ 't-AbaLab0001': 1, 't-AbaSite002': 2, 't-AbaBench03': 3 })[ref] ?? null, recordingOn: () => false },
    chrome: {
      tabs: {
        get: async (id) => ({ id, windowId: 9, active: id === activeTab }),
        query: async (q) => (q.active && q.windowId === 9 ? [{ id: activeTab, windowId: 9, active: true }] : []),
        captureVisibleTab: async () => { shots++; return captured(); },
      },
      action: { setBadgeText: async () => {} },
      commands: { getAll: async () => [{ name: 'nk-pdd-capturar', shortcut: 'Alt+Shift+K' }] },
    },
    host: async (cmd, p) => {
      assert.equal(cmd, 'debug.resolve');
      return p.targetId === 'tgt-u' ? { status: 'RESOLVED', resolvedNodeId: 'n1' } : { status: 'AMBIGUOUS', resolvedNodeId: null };
    },
    toContent: async (tabId, cmd, a) => content(cmd, a),
    currentCapture: async () => ({ captureId: 'cap-1' }),
    frameOfNode: () => 0,
    imaging: {
      async decode() {
        decodeCalls++;
        return { width: 200, height: 100, pixels: rawImage(200, 100), release() {} };
      },
      async encode(pixels) {
        lastPixels = pixels;
        return new Uint8Array([137, 80, 78, 71, 1, 2, 3]);
      },
    },
    now: () => clock,
    ...over,
  };
}

beforeEach(() => {
  posted = [];
  urls = new Map([[1, 'http://127.0.0.1:18765/lab/studio010/login.html'], [2, 'https://site-a.example/x']]);
  clock = 1_000_000;
  decodeCalls = 0;
  lastPixels = null;
  activeTab = 1;
  shots = 0;
  captured = async () => 'data:image/png;base64,QlJVVEE=';
  content = (cmd) => {
    if (cmd === 'cs.pdd.plan') {
      return plan(200, 100, 1, [{ kind: 'CARRIER', x: 10, y: 10, width: 40, height: 20 }, { kind: 'FIELD', x: 60, y: 10, width: 80, height: 20 }]);
    }
    if (cmd === 'cs.pdd.rects') return { ok: true, token: 't1', rects: [{ nodeId: 'n1', x: 60, y: 10, width: 80, height: 20 }] };
    if (cmd === 'cs.pdd.token') return { ok: true, token: 't1' };
    return { ok: true };
  };
});

const STEPS = [{ stepRef: 'step-1', targetId: 'tgt-u', descriptor: null, actionType: 'FILL' },
  { stepRef: 'step-2', targetId: 'tgt-x', descriptor: null, actionType: 'CLICK' }];

function events() {
  return posted.filter((m) => m.type === 'studio.event' && m.event === 'studio.pdd.captured').map((m) => m.payload);
}

function conform(payload) {
  assert.deepEqual(errors(schema.$defs.PddCapturedPayload, payload), [], JSON.stringify(payload).slice(0, 400));
}

test('armar: só aba LAB (site autorizado → VISUAL_CAPTURE_NOT_ALLOWED); resposta pelo contrato; ocupada → INVALID_STATE', async () => {
  const pdd = createStudioPdd(deps());
  await assert.rejects(pdd.COMMANDS['studio.pdd.capture.arm']({ captureId: 'c1', tabRef: 't-AbaSite002', steps: STEPS, leaseMs: 30000 }, 'studio-a'),
    { code: 'VISUAL_CAPTURE_NOT_ALLOWED' });
  const r = await pdd.COMMANDS['studio.pdd.capture.arm']({ captureId: 'c1', tabRef: 't-AbaLab0001', steps: STEPS, leaseMs: 30000 }, 'studio-a');
  assert.deepEqual(errors(schema.$defs.PddArmResult, r), []);
  assert.equal(r.shortcut, 'Alt+Shift+K');
  await assert.rejects(pdd.COMMANDS['studio.pdd.capture.arm']({ captureId: 'c2', tabRef: 't-AbaLab0001', steps: STEPS, leaseMs: 30000 }, 'studio-a'),
    { code: 'INVALID_STATE' });
  await assert.rejects(pdd.COMMANDS['studio.pdd.capture.arm']({ captureId: 'c3', tabRef: 't-AbaLab0001', steps: STEPS, leaseMs: 30000, url: 'x' }, 'a'),
    { code: 'BAD_REQUEST' }, 'campo fora do contrato');
});

test('gesto sem armação: nada é capturado; armação para outra aba → TAB_MISMATCH; vencida → ARM_EXPIRED', async () => {
  let shots = 0;
  captured = async () => { shots++; return 'data:x'; };
  const pdd = createStudioPdd(deps());
  assert.deepEqual(await pdd.onShortcut('nk-pdd-capturar', { id: 1, windowId: 9 }), { ignored: true });
  await pdd.COMMANDS['studio.pdd.capture.arm']({ captureId: 'c1', tabRef: 't-AbaLab0001', steps: STEPS, leaseMs: 30000 }, 'studio-a');
  assert.deepEqual(await pdd.onShortcut('nk-pdd-capturar', { id: 7, windowId: 9 }), { failed: 'TAB_MISMATCH' });
  await pdd.COMMANDS['studio.pdd.capture.arm']({ captureId: 'c2', tabRef: 't-AbaLab0001', steps: STEPS, leaseMs: 5000 }, 'studio-a');
  clock += 6000;
  assert.deepEqual(await pdd.onShortcut('nk-pdd-capturar', { id: 1, windowId: 9 }), { failed: 'ARM_EXPIRED' });
  assert.equal(shots, 0, 'nenhuma captura sem armação válida');
  assert.deepEqual(events().map((e) => e.code), ['TAB_MISMATCH', 'ARM_EXPIRED']);
  events().forEach(conform);
});

test('captura: PNG sanitizado conforme ao contrato; anotação PLACED só do alvo comprovado; nenhuma imagem bruta no evento; uso único', async () => {
  const pdd = createStudioPdd(deps());
  await pdd.COMMANDS['studio.pdd.capture.arm']({ captureId: 'c1', tabRef: 't-AbaLab0001', steps: STEPS, leaseMs: 30000 }, 'studio-a');
  assert.deepEqual(await pdd.onShortcut('nk-pdd-capturar', { id: 1, windowId: 9 }), { captured: true });
  const [e] = events();
  conform(e);
  assert.equal(e.status, 'CAPTURED');
  assert.equal(e.policy, 'nekkora-visual/1');
  assert.deepEqual(e.annotations.map((a) => [a.stepRef, a.status, a.reason]), [['step-1', 'PLACED', ''], ['step-2', 'OMITTED', 'AMBIGUOUS']]);
  assert.deepEqual([e.annotations[0].x, e.annotations[0].y, e.annotations[0].width, e.annotations[0].height], [0.3, 0.1, 0.4, 0.2], 'normalizado');
  assert.ok(!JSON.stringify(posted).includes('QlJVVEE='), 'a imagem bruta nunca sai');
  assert.equal(V.violations(lastPixels, 200, 100, V.deviceRegions(plan(200, 100, 1, [{ kind: 'CARRIER', x: 10, y: 10, width: 40, height: 20 },
    { kind: 'FIELD', x: 60, y: 10, width: 80, height: 20 }]), 200, 100)), 0, 'o que foi codificado é o sanitizado');
  assert.deepEqual(await pdd.onShortcut('nk-pdd-capturar', { id: 1, windowId: 9 }), { ignored: true }, 'gesto consumido');
});

test('página mudou entre o plano e a captura → PAGE_CHANGED_DURING_CAPTURE, nada decodificado; captura negada → CAPTURE_DENIED', async () => {
  const pdd = createStudioPdd(deps());
  const base = content;
  content = (cmd, a) => (cmd === 'cs.pdd.token' ? { ok: true, token: 't2' } : base(cmd, a));
  await pdd.COMMANDS['studio.pdd.capture.arm']({ captureId: 'c1', tabRef: 't-AbaLab0001', steps: STEPS, leaseMs: 30000 }, 'studio-a');
  assert.deepEqual(await pdd.onShortcut('nk-pdd-capturar', { id: 1, windowId: 9 }), { failed: 'PAGE_CHANGED_DURING_CAPTURE' });
  assert.equal(decodeCalls, 0, 'a imagem bruta não chega a ser decodificada');
  content = base;
  captured = async () => { throw new Error('Either the <all_urls> or activeTab permission is required.'); };
  await pdd.COMMANDS['studio.pdd.capture.arm']({ captureId: 'c2', tabRef: 't-AbaLab0001', steps: STEPS, leaseMs: 30000 }, 'studio-a');
  assert.deepEqual(await pdd.onShortcut('nk-pdd-capturar', { id: 1, windowId: 9 }), { failed: 'CAPTURE_DENIED' });
  events().forEach(conform);
  assert.ok(!JSON.stringify(posted).includes('permission'), 'mensagem de erro nunca vai ao Studio');
});

test('região incoerente (portador sobre campo) → falha sem imagem; navegação e aba fechada desarmam com o motivo; cancelar', async () => {
  const pdd = createStudioPdd(deps());
  const base = content;
  content = (cmd, a) => (cmd === 'cs.pdd.plan' ? plan(200, 100, 1, [{ kind: 'CARRIER', x: 60, y: 10, width: 40, height: 20 },
    { kind: 'FIELD', x: 60, y: 10, width: 80, height: 20 }]) : base(cmd, a));
  await pdd.COMMANDS['studio.pdd.capture.arm']({ captureId: 'c1', tabRef: 't-AbaLab0001', steps: STEPS, leaseMs: 30000 }, 'studio-a');
  assert.deepEqual(await pdd.onShortcut('nk-pdd-capturar', { id: 1, windowId: 9 }), { failed: 'CARRIER_OVERLAPS_FIELD' });
  await pdd.COMMANDS['studio.pdd.capture.arm']({ captureId: 'c2', tabRef: 't-AbaLab0001', steps: STEPS, leaseMs: 30000 }, 'studio-a');
  pdd.onPageChanged(1);
  await pdd.COMMANDS['studio.pdd.capture.arm']({ captureId: 'c3', tabRef: 't-AbaLab0001', steps: STEPS, leaseMs: 30000 }, 'studio-a');
  pdd.onTabRemoved(1);
  await pdd.COMMANDS['studio.pdd.capture.arm']({ captureId: 'c4', tabRef: 't-AbaLab0001', steps: STEPS, leaseMs: 30000 }, 'studio-a');
  assert.deepEqual(await pdd.COMMANDS['studio.pdd.capture.cancel']({ captureId: 'c4' }, 'studio-a'), { captureId: 'c4', cancelled: true });
  assert.deepEqual(events().map((e) => e.code), ['CARRIER_OVERLAPS_FIELD', 'PAGE_CHANGED', 'TAB_CLOSED', 'CANCELLED']);
  events().forEach(conform);
  assert.equal(pdd.state().armed, false);
});

// ------------------------------------------------------------------- R1 da revisão: identidade da aba na captura

const ARM = (pdd, captureId = 'c1') => pdd.COMMANDS['studio.pdd.capture.arm']({ captureId, tabRef: 't-AbaLab0001', steps: STEPS, leaseMs: 30000 }, 'studio-a');

function noImagePublished() {
  assert.ok(events().every((e) => e.status === 'FAILED' && !('png' in e)), 'nenhuma imagem publicada');
  assert.ok(!JSON.stringify(posted).includes('QlJVVEE='), 'a imagem bruta nunca sai');
  events().forEach(conform);
}

/** Troca de aba simulada como o Chrome faz: a aba ativa muda e chrome.tabs.onActivated dispara. */
function switchTo(pdd, tabId) {
  activeTab = tabId;
  pdd.onTabActivated({ tabId, windowId: 9 });
}

test('R1 troca de aba entre o gesto e a captura → ACTIVE_TAB_CHANGED; captureVisibleTab nem é chamado', async () => {
  const pdd = createStudioPdd(deps());
  const base = content;
  content = (cmd, a) => {
    if (cmd === 'cs.pdd.plan') switchTo(pdd, 2);
    return base(cmd, a);
  };
  await ARM(pdd);
  assert.deepEqual(await pdd.onShortcut('nk-pdd-capturar', { id: 1, windowId: 9 }), { failed: 'ACTIVE_TAB_CHANGED' });
  assert.equal(shots, 0);
  assert.deepEqual(events().map((e) => e.code), ['ACTIVE_TAB_CHANGED']);
  noImagePublished();
});

test('R1 troca de aba durante uma preparação demorada (resolução das etapas) → ACTIVE_TAB_CHANGED, nada capturado', async () => {
  const pdd = createStudioPdd(deps({
    host: async (cmd, p) => {
      await new Promise((r) => setTimeout(r, 30)); // preparação lenta
      if (p.targetId === 'tgt-x') switchTo(pdd, 3); // outra aba do LABORATÓRIO
      return { status: 'RESOLVED', resolvedNodeId: 'n1' };
    },
  }));
  urls.set(3, 'http://127.0.0.1:18765/lab/studio010/consulta.html');
  await ARM(pdd);
  assert.deepEqual(await pdd.onShortcut('nk-pdd-capturar', { id: 1, windowId: 9 }), { failed: 'ACTIVE_TAB_CHANGED' });
  assert.equal(shots, 0);
  noImagePublished();
});

test('R1 troca DURANTE captureVisibleTab (a imagem pode ser da outra aba) → descartada sem decodificar', async () => {
  const pdd = createStudioPdd(deps());
  captured = async () => {
    switchTo(pdd, 3);
    return 'data:image/png;base64,QlJVVEE=';
  };
  await ARM(pdd);
  assert.deepEqual(await pdd.onShortcut('nk-pdd-capturar', { id: 1, windowId: 9 }), { failed: 'ACTIVE_TAB_CHANGED' });
  assert.equal(shots, 1);
  assert.equal(decodeCalls, 0, 'a imagem bruta é descartada sem ser decodificada');
  noImagePublished();
});

test('R1 segunda aba do laboratório: gesto nela → TAB_MISMATCH; aba ativa diferente sem evento → ACTIVE_TAB_CHANGED', async () => {
  urls.set(3, 'http://127.0.0.1:18765/lab/studio010/consulta.html');
  const pdd = createStudioPdd(deps());
  await ARM(pdd, 'c1');
  activeTab = 3;
  assert.deepEqual(await pdd.onShortcut('nk-pdd-capturar', { id: 3, windowId: 9 }), { failed: 'TAB_MISMATCH' });
  // o Chrome entrega o gesto com a aba armada, mas a ativa já é outra (evento ainda não chegou): conferência direta
  await ARM(pdd, 'c2');
  assert.deepEqual(await pdd.onShortcut('nk-pdd-capturar', { id: 1, windowId: 9 }), { failed: 'ACTIVE_TAB_CHANGED' });
  assert.equal(shots, 0);
  noImagePublished();
});

test('R1 ida e volta A→B→A antes da captura → ACTIVE_TAB_CHANGED (não se sabe o que estava na tela)', async () => {
  const pdd = createStudioPdd(deps());
  const base = content;
  content = (cmd, a) => {
    if (cmd === 'cs.pdd.rects') {
      switchTo(pdd, 2);
      switchTo(pdd, 1);
    }
    return base(cmd, a);
  };
  await ARM(pdd);
  assert.equal(activeTab, 1);
  assert.deepEqual(await pdd.onShortcut('nk-pdd-capturar', { id: 1, windowId: 9 }), { failed: 'ACTIVE_TAB_CHANGED' });
  assert.equal(shots, 0);
  noImagePublished();
  // depois disso, uma captura nova e limpa na aba original funciona
  content = base;
  await ARM(pdd, 'c2');
  assert.deepEqual(await pdd.onShortcut('nk-pdd-capturar', { id: 1, windowId: 9 }), { captured: true });
  assert.equal(events().at(-1).status, 'CAPTURED');
});

test('R1 aba armada movida para outra janela ou troca em OUTRA janela', async () => {
  const pdd = createStudioPdd(deps());
  const base = content;
  content = (cmd, a) => {
    if (cmd === 'cs.pdd.plan') pdd.onTabMoved(1);
    return base(cmd, a);
  };
  await ARM(pdd, 'c1');
  assert.deepEqual(await pdd.onShortcut('nk-pdd-capturar', { id: 1, windowId: 9 }), { failed: 'ACTIVE_TAB_CHANGED' });
  content = (cmd, a) => {
    if (cmd === 'cs.pdd.plan') pdd.onTabActivated({ tabId: 40, windowId: 12 }); // outra janela: não afeta esta captura
    return base(cmd, a);
  };
  await ARM(pdd, 'c2');
  assert.deepEqual(await pdd.onShortcut('nk-pdd-capturar', { id: 1, windowId: 9 }), { captured: true });
});

test('R1 cancelar durante a captura (preparação ou sanitização) → CANCELLED, nenhuma imagem publicada', async () => {
  const pdd = createStudioPdd(deps());
  const base = content;
  let cancelReply = null;
  content = (cmd, a) => {
    if (cmd === 'cs.pdd.plan' && !cancelReply) {
      cancelReply = pdd.COMMANDS['studio.pdd.capture.cancel']({ captureId: 'c1' }, 'studio-a');
    }
    return base(cmd, a);
  };
  await ARM(pdd, 'c1');
  assert.deepEqual(await pdd.onShortcut('nk-pdd-capturar', { id: 1, windowId: 9 }), { failed: 'CANCELLED' });
  assert.deepEqual(await cancelReply, { captureId: 'c1', cancelled: true });
  assert.equal(shots, 0);
  // cancelamento depois da captura bruta, durante a codificação: ainda nada é publicado
  content = base;
  const d = deps();
  const enc = d.imaging.encode;
  let pdd2;
  d.imaging.encode = async (px, w, h) => {
    await pdd2.COMMANDS['studio.pdd.capture.cancel']({ captureId: 'c2' }, 'studio-a');
    return enc(px, w, h);
  };
  pdd2 = createStudioPdd(d);
  await ARM(pdd2, 'c2');
  assert.deepEqual(await pdd2.onShortcut('nk-pdd-capturar', { id: 1, windowId: 9 }), { failed: 'CANCELLED' });
  assert.deepEqual(events().map((e) => e.code), ['CANCELLED', 'CANCELLED']);
  noImagePublished();
  assert.equal(pdd2.state().running, false);
});


// ---------------------------------------------------------------------------------------- NEKKORA-E2E-001: BENCHMARK
test('perfil visual: LAB continua; BENCHMARK só origem exata autorizada; resto recusado', () => {
  assert.equal(visualProfile({ allowed: true, kind: 'LAB', origin: 'http://127.0.0.1:18765' }), 'LAB');
  assert.equal(visualProfile({ allowed: true, kind: 'AUTHORIZED', origin: 'https://rpachallenge.com' }), 'BENCHMARK');
  for (const origin of ['https://www.rpachallenge.com', 'http://rpachallenge.com', 'https://rpachallenge.com:8443',
    'https://rpachallenge.com.evil.test', 'https://en.wikipedia.org']) {
    assert.equal(visualProfile({ allowed: true, kind: 'AUTHORIZED', origin }), null, origin);
  }
  assert.equal(visualProfile({ allowed: false, kind: null, reason: 'PERMISSION_REQUIRED', origin: 'https://rpachallenge.com' }), null);
  assert.equal(visualProfile(null), null);
  assert.ok(Object.isFrozen(BENCHMARK_ORIGINS));
});

test('armar: aba AUTORIZADA de benchmark aceita; autorizada fora do perfil recusada (VISUAL_CAPTURE_NOT_ALLOWED)', async () => {
  urls.set(3, 'https://rpachallenge.com/');
  const pdd = createStudioPdd(deps());
  await assert.rejects(pdd.COMMANDS['studio.pdd.capture.arm']({ captureId: 'c1', tabRef: 't-AbaSite002', steps: STEPS, leaseMs: 30000 }, 'studio-a'),
    { code: 'VISUAL_CAPTURE_NOT_ALLOWED' });
  const r = await pdd.COMMANDS['studio.pdd.capture.arm']({ captureId: 'c2', tabRef: 't-AbaBench03', steps: STEPS, leaseMs: 30000 }, 'studio-a');
  assert.equal(r.armed, true);
});
