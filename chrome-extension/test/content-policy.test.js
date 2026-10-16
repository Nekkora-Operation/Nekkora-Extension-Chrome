import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

/** Carrega scripts clássicos do content script num sandbox (como o navegador faz). */
function load(...files) {
  const sandbox = { console };
  sandbox.globalThis = sandbox;
  vm.createContext(sandbox);
  for (const f of files) {
    vm.runInContext(readFileSync(new URL('../src/' + f, import.meta.url), 'utf8'), sandbox, { filename: f });
  }
  return sandbox;
}

function el(tag, attrs = {}) {
  return {
    tagName: tag.toUpperCase(),
    id: attrs.id || '',
    getAttribute: (n) => (n in attrs ? attrs[n] : null),
  };
}

test('sensitive fields are detected in the browser layer', () => {
  const { NekkoraCS } = load('content/ns.js', 'content/sensitivity.js');
  const p = NekkoraCS.sensitivity;
  assert.equal(p.isSensitive(el('input', { type: 'password' })), true);
  assert.equal(p.isSensitive(el('input', { name: 'cpf' })), true);
  assert.equal(p.isSensitive(el('input', { autocomplete: 'cc-number' })), true);
  assert.equal(p.isSensitive(el('input', { name: 'cidade' })), false);
});

test('SEC001-004: no field exposes its value (not even search fields); value attributes of text inputs are dropped', () => {
  const { NekkoraCS } = load('content/ns.js', 'content/sensitivity.js');
  const p = NekkoraCS.sensitivity;
  // CHROME-SECURITY-001: a antiga exceção dos campos de pesquisa (o termo pesquisado saía como exposedValue) foi removida
  assert.equal(p.isSearchField, undefined, 'nenhuma exceção de exposição para campos de pesquisa');
  const capture = readFileSync(new URL('../src/content/capture.js', import.meta.url), 'utf8');
  assert.match(capture, /const exposedValue = null;/, 'a captura nunca preenche exposedValue');
  assert.equal(p.valueAttributeAllowed(el('input', { type: 'text' })), false);
  assert.equal(p.valueAttributeAllowed(el('input', { type: 'submit' })), true);
  assert.equal(p.valueAttributeAllowed(el('option')), true);
});

test('content script only runs on http/https documents (origin authorization is decided by access-policy)', () => {
  // NEKKORA-REAL-001: a restrição exclusiva a loopback foi removida por requisito. A decisão de ORIGEM passou a ser
  // da política central (test/access-policy.test.js: example.com continua recusado sem autorização do usuário).
  const { NekkoraCS } = load('content/ns.js');
  assert.equal(NekkoraCS.isAllowedLocation({ protocol: 'http:', hostname: '127.0.0.1' }), true);
  assert.equal(NekkoraCS.isAllowedLocation({ protocol: 'http:', hostname: 'localhost' }), true);
  assert.equal(NekkoraCS.isAllowedLocation({ protocol: 'https:', hostname: 'example.com' }), true);
  assert.equal(NekkoraCS.isAllowedLocation({ protocol: 'file:', hostname: '' }), false);
  assert.equal(NekkoraCS.isAllowedLocation({ protocol: 'chrome:', hostname: 'settings' }), false);
  assert.equal(NekkoraCS.isAllowedLocation({ protocol: 'data:', hostname: '' }), false);
});

test('official Nekkora pink token', () => {
  const { NekkoraTokens } = load('shared/tokens.js');
  assert.equal(NekkoraTokens.primary, '#C2466D');
  assert.equal(NekkoraTokens.highlightWidthPx, 2);
  assert.equal(NekkoraTokens.highlightOffsetPx, 2);
});
