// Política central de acesso (NEKKORA-REAL-001): laboratório + origens autorizadas pelo usuário; o resto recusado.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { fileURLToPath, pathToFileURL } from 'node:url';
import path from 'node:path';

const granted = { origins: ['http://127.0.0.1/*', 'http://localhost/*'] };
const listeners = { added: [], removed: [] };
globalThis.chrome = {
  permissions: {
    getAll: async () => ({ origins: granted.origins.slice(), permissions: [] }),
    onAdded: { addListener: (fn) => listeners.added.push(fn) },
    onRemoved: { addListener: (fn) => listeners.removed.push(fn) },
  },
  tabs: { get: async (id) => ({ id, url: id === 1 ? 'https://en.wikipedia.org/wiki/Brasil' : undefined }) },
  webNavigation: { getFrame: async () => ({ url: 'https://pt.wikipedia.org/wiki/Brasil?x=1' }) },
  runtime: { getManifest: () => ({ content_scripts: [{ js: [] }] }) },
};

const here = path.dirname(fileURLToPath(import.meta.url));
const policy = await import(pathToFileURL(path.join(here, '../src/background/access-policy.js')).href);

async function grant(...origins) {
  granted.origins.push(...origins);
  for (const fn of listeners.added) {
    await fn({ origins });
  }
}

async function revoke(...origins) {
  granted.origins = granted.origins.filter((o) => !origins.includes(o));
  for (const fn of listeners.removed) {
    await fn({ origins });
  }
}

test('laboratório local permitido; sites externos exigem autorização explícita', async () => {
  await policy.load();
  assert.deepEqual(policy.evaluate('http://127.0.0.1:8765/lab/x.html').kind, 'LAB');
  assert.equal(policy.evaluate('http://localhost:8766/a').allowed, true);
  const ext = policy.evaluate('https://example.com/a?token=1');
  assert.equal(ext.allowed, false);
  assert.equal(ext.reason, 'PERMISSION_REQUIRED');
  assert.equal(ext.pattern, 'https://example.com/*', 'padrão por site a solicitar (sem caminho nem consulta)');
  assert.equal(ext.origin, 'https://example.com');
});

test('páginas internas, esquemas não suportados e a loja do Chrome são recusados com motivo', () => {
  assert.equal(policy.evaluate('chrome://settings').reason, 'BROWSER_INTERNAL');
  assert.equal(policy.evaluate('chrome-extension://abc/x.html').reason, 'BROWSER_INTERNAL');
  assert.equal(policy.evaluate('about:blank').reason, 'BROWSER_INTERNAL');
  assert.equal(policy.evaluate('file:///C:/x.html').reason, 'UNSUPPORTED_SCHEME');
  assert.equal(policy.evaluate('data:text/html,<p>x</p>').reason, 'UNSUPPORTED_SCHEME');
  assert.equal(policy.evaluate('https://chromewebstore.google.com/detail/x').reason, 'BROWSER_RESTRICTED');
  assert.equal(policy.evaluate('nada').reason, 'INVALID_URL');
  assert.equal(policy.evaluate(null).reason, 'URL_UNAVAILABLE');
});

test('autorização por site vale só para aquele host; revogação é respeitada imediatamente', async () => {
  await grant('https://en.wikipedia.org/*');
  assert.equal(policy.evaluate('https://en.wikipedia.org/wiki/Brasil').kind, 'AUTHORIZED');
  assert.equal(policy.evaluate('https://pt.wikipedia.org/wiki/Brasil').reason, 'PERMISSION_REQUIRED', 'outro subdomínio não é presumido');
  assert.equal(policy.evaluate('http://en.wikipedia.org/wiki/Brasil').reason, 'PERMISSION_REQUIRED', 'outro esquema não é presumido');
  assert.deepEqual(policy.authorizedPatterns(), ['https://en.wikipedia.org/*']);
  await revoke('https://en.wikipedia.org/*');
  assert.equal(policy.evaluate('https://en.wikipedia.org/wiki/Brasil').reason, 'PERMISSION_REQUIRED');
  assert.deepEqual(policy.authorizedPatterns(), []);
});

test('autorização ampla cobre http/https, nunca páginas internas', async () => {
  await grant('https://*/*', 'http://*/*');
  assert.equal(policy.evaluate('https://qualquer.test/a').kind, 'AUTHORIZED');
  assert.equal(policy.evaluate('http://intranet.local/a').kind, 'AUTHORIZED');
  assert.equal(policy.evaluate('chrome://extensions').allowed, false);
  assert.equal(policy.evaluate('http://127.0.0.1:8765/x').kind, 'LAB', 'laboratório continua LAB (sem injeção duplicada)');
  await revoke('https://*/*', 'http://*/*');
  assert.equal(policy.evaluate('https://qualquer.test/a').allowed, false);
});

test('padrões com curinga de subdomínio', () => {
  assert.equal(policy.matchesPattern('https://a.b.example.com/x', 'https://*.example.com/*'), true);
  assert.equal(policy.matchesPattern('https://example.com/x', 'https://*.example.com/*'), true);
  assert.equal(policy.matchesPattern('https://badexample.com/x', 'https://*.example.com/*'), false);
  assert.equal(policy.matchesPattern('http://site-a.nekkora.test:18765/x', 'http://site-a.nekkora.test/*'), true, 'porta não faz parte do padrão');
});

test('aba sem URL visível (sem permissão) é avaliada pelo frame principal, sem expor a URL completa', async () => {
  const d = await policy.describeTab(2);
  assert.equal(d.reason, 'PERMISSION_REQUIRED');
  assert.equal(d.origin, 'https://pt.wikipedia.org');
  assert.equal(d.pattern, 'https://pt.wikipedia.org/*');
  assert.equal(JSON.stringify(d).includes('x=1'), false);
  await assert.rejects(policy.assertAllowed(2), (e) => e.code === 'ORIGIN_NOT_ALLOWED' && e.reason === 'PERMISSION_REQUIRED');
});
