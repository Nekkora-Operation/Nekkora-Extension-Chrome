// NEKKORA-STUDIO-013 — a política de acesso REAL (access-policy.js) com a permissão AMPLA do Chrome (https://*/* e
// http://*/*): inspeção/gravação liberadas em qualquer site, captura do PDD em NENHUM sem o consentimento da origem exata.
// (No Chrome real, conceder permissão opcional nova exige o diálogo nativo, que esta homologação não automatiza.)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { fileURLToPath, pathToFileURL } from 'node:url';
import path from 'node:path';

const granted = { origins: ['http://127.0.0.1/*', 'http://localhost/*', 'https://*/*', 'http://*/*'] };
const removed = [];
globalThis.chrome = {
  permissions: {
    getAll: async () => ({ origins: granted.origins.slice(), permissions: [] }),
    onAdded: { addListener: () => {} },
    onRemoved: { addListener: (fn) => removed.push(fn) },
  },
};

const here = path.dirname(fileURLToPath(import.meta.url));
const policy = await import(pathToFileURL(path.join(here, '../src/background/access-policy.js')).href);
const { visualProfile, denialReason } = await import(pathToFileURL(path.join(here, '../src/background/visual-capture-profile.js')).href);
const { createPddConsent } = await import(pathToFileURL(path.join(here, '../src/background/pdd-consent.js')).href);

function memory() {
  const session = {};
  const rows = new Map();
  return {
    chrome: { storage: { session: { get: async (k) => (k in session ? { [k]: session[k] } : {}), set: async (o) => Object.assign(session, o) } } },
    persistent: { getAll: async () => [...rows.values()], put: async (r) => { rows.set(r.origin, r); }, delete: async (o) => { rows.delete(o); } },
  };
}

test('permissão ampla: qualquer site http/https vira AUTHORIZED para inspeção, mas a captura do PDD exige consentimento por origem', async () => {
  await policy.load();
  const consent = createPddConsent(memory());
  for (const url of ['https://app.exemplo.test/pacientes?id=1', 'http://intranet.exemplo.test:8080/', 'https://outro.exemplo.test/']) {
    const ev = policy.evaluate(url);
    assert.equal(ev.kind, 'AUTHORIZED', url);
    assert.equal(visualProfile(ev, consent.get(ev.origin)), null, url);
    assert.equal(denialReason(ev, consent.get(ev.origin)), 'PDD_CONSENT_REQUIRED', url);
  }
  await consent.grant('https://app.exemplo.test', 'SESSION', 'DEVELOPER', 'EXTENSION');
  const ok = policy.evaluate('https://app.exemplo.test/outra/tela');
  assert.equal(visualProfile(ok, consent.get(ok.origin)), 'AUTHORIZED_SITE');
  const sub = policy.evaluate('https://sub.app.exemplo.test/');
  assert.equal(visualProfile(sub, consent.get(sub.origin)), null, 'subdomínio não herda');
  const http = policy.evaluate('http://app.exemplo.test/');
  assert.equal(visualProfile(http, consent.get(http.origin)), null, 'outro esquema não herda');
  // continuam bloqueados pelo navegador, com ou sem permissão ampla
  for (const url of ['chrome://settings/', 'https://chromewebstore.google.com/detail/x', 'file:///c:/x.html']) {
    assert.equal(policy.evaluate(url).allowed, false, url);
  }
});

test('revogar a permissão ampla no Chrome: o consentimento das origens que deixaram de ser permitidas é apagado', async () => {
  await policy.load();
  const consent = createPddConsent(memory());
  await consent.grant('https://app.exemplo.test', 'REMEMBERED', 'DEVELOPER', 'STUDIO');
  granted.origins = ['http://127.0.0.1/*', 'http://localhost/*'];
  await policy.load();
  const gone = await consent.prune((o) => policy.evaluate(o + '/').kind === 'AUTHORIZED');
  assert.deepEqual(gone, ['https://app.exemplo.test']);
  assert.equal(consent.get('https://app.exemplo.test'), null);
});
