// Política de privacidade de URLs e textos (NEKKORA-REAL-001).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

/** Script clássico carregado como no navegador (content script) e no service worker (importação de efeito). */
const sandbox = { console, URL };
sandbox.globalThis = sandbox;
vm.createContext(sandbox);
vm.runInContext(readFileSync(new URL('../src/shared/privacy.js', import.meta.url), 'utf8'), sandbox, { filename: 'privacy.js' });
const P = sandbox.NekkoraPrivacy;

test('URLs sem dados sensíveis permanecem idênticas (localizadores continuam válidos)', () => {
  for (const u of ['/clientes?nome=ana&status=ativo', 'https://en.wikipedia.org/wiki/Albert_Einstein', '/lab/spa/pedidos?status=aberto',
    '#Early_life', 'pagina2.html', '/generated/large?n=10000', 'https://en.wikipedia.org/w/index.php?title=Special:Search&go=Go',
    '/generated/frames?count=10&depth=3', 'about:blank', 'about:srcdoc', 'javascript:void(0)']) {
    assert.strictEqual(P.sanitizeUrl(u), u, u);
  }
});

test('valores sensíveis de consulta são redigidos e os nomes mantidos', () => {
  assert.strictEqual(P.sanitizeUrl('https://app.example/cb?code=abc123&state=xyz&lang=pt'), 'https://app.example/cb?code=REDACTED&state=REDACTED&lang=pt');
  assert.strictEqual(P.sanitizeUrl('/api?access_token=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.abcdefgh&page=2'), '/api?access_token=REDACTED&page=2');
  assert.strictEqual(P.sanitizeUrl('/r?email=ana%40x.com&q=sapato'), '/r?email=REDACTED&q=sapato');
  assert.strictEqual(P.sanitizeUrl('/r?q=ana@x.com'), '/r?q=REDACTED', 'dado pessoal em valor de nome não sensível');
  assert.strictEqual(P.sanitizeUrl('/w/index.php?title=X&oldid=1234567890'), '/w/index.php?title=X&oldid=REDACTED');
  assert.strictEqual(P.sanitizeUrl('/p?sessionId=abc&sitekey=LAB'), '/p?sessionId=REDACTED&sitekey=REDACTED');
});

test('segmentos de caminho com identificadores ou segredos são mascarados', () => {
  assert.strictEqual(P.sanitizeUrl('https://x.test/reset/550e8400-e29b-41d4-a716-446655440000/confirm'), 'https://x.test/reset/:redacted/confirm');
  assert.strictEqual(P.sanitizeUrl('/u/ana@x.com/perfil'), '/u/:redacted/perfil');
  assert.strictEqual(P.sanitizeUrl('/pedidos/12345678'), '/pedidos/:redacted');
  assert.strictEqual(P.sanitizeUrl('/s/aB3dE5fG7hJ9kL1mN3pQ5rS7tU9'), '/s/:redacted');
  assert.strictEqual(P.sanitizeUrl('/app;jsessionid=ABCDEF0123/home'), '/app;jsessionid=REDACTED/home');
  assert.strictEqual(P.sanitizeUrl('/wiki/Albert_Einstein'), '/wiki/Albert_Einstein');
});

test('credenciais, fragmentos e esquemas especiais', () => {
  assert.strictEqual(P.sanitizeUrl('https://user:secret@host.test/a'), 'https://host.test/a');
  assert.strictEqual(P.sanitizeUrl('https://x.test/#access_token=abc&token_type=bearer'), 'https://x.test/#access_token=REDACTED&token_type=REDACTED');
  assert.strictEqual(P.sanitizeUrl('/spa#/conta/98765432?aba=dados'), '/spa#/conta/:redacted?aba=dados');
  assert.strictEqual(P.sanitizeUrl('https://w.test/#frame=challenge&id=0x1'), 'https://w.test/#frame=challenge&id=0x1');
  assert.strictEqual(P.sanitizeUrl('mailto:ana@x.com'), 'mailto:[redacted]');
  assert.strictEqual(P.sanitizeUrl('tel:+5511999999999'), 'tel:[redacted]');
  assert.strictEqual(P.sanitizeUrl('data:image/png;base64,AAAA'), 'data:image/png,[omitido]');
  assert.strictEqual(P.sanitizeUrl('blob:https://x.test/550e8400'), 'blob:[omitido]');
  assert.strictEqual(P.sanitizeUrl('javascript:alert(document.cookie)'), 'javascript:[omitido]');
});

test('a política é idempotente', () => {
  for (const u of ['https://a:b@x.test/reset/550e8400-e29b-41d4-a716-446655440000?code=1&q=ok#access_token=z', '/u/ana@x.com?email=a@b.co',
    'mailto:a@b.co', '/spa#/conta/98765432?aba=dados']) {
    const once = P.sanitizeUrl(u);
    assert.strictEqual(P.sanitizeUrl(once), once, u);
  }
  const t = 'Contato: ana@x.com, CPF 123.456.789-09, cartão 4111 1111 1111 1111, tel (11) 98765-4321';
  assert.strictEqual(P.maskText(P.maskText(t)), P.maskText(t));
});

test('textos: dados pessoais com formato reconhecível são mascarados', () => {
  assert.strictEqual(P.maskText('Contato: ana@x.com'), 'Contato: [email]');
  assert.strictEqual(P.maskText('CPF 123.456.789-09 / CNPJ 12.345.678/0001-95'), 'CPF [cpf] / CNPJ [cnpj]');
  assert.strictEqual(P.maskText('cartão 4111 1111 1111 1111'), 'cartão [cartão]');
  assert.strictEqual(P.maskText('pedido 1234 5678 9012 3456'), 'pedido 1234 5678 9012 3456', 'número sem Luhn válido não é cartão');
  assert.strictEqual(P.maskText('Ligue (11) 98765-4321 ou +55 11 98765-4321'), 'Ligue [telefone] ou [telefone]');
  assert.strictEqual(P.maskText('Authorization: Bearer abcdefghijklmnopqrstuvwxyz012345'), 'Authorization: Bearer [token]');
  assert.strictEqual(P.maskText('Total: 1.234,56 em 2024-10-14'), 'Total: 1.234,56 em 2024-10-14');
  assert.ok(P.isMasked('Enviar para [email]'));
  assert.ok(!P.isMasked('Enviar'));
});

test('paridade com o motor Java: vetores compartilhados (contracts/privacy/privacy-vectors.json)', () => {
  const v = JSON.parse(readFileSync(new URL('../../contracts/privacy/privacy-vectors.json', import.meta.url), 'utf8'));
  assert.equal(v.policy, P.version);
  for (const c of v.urls) {
    assert.equal(P.sanitizeUrl(c.in), c.out, c.in);
  }
  for (const c of v.texts) {
    assert.equal(P.maskText(c.in), c.out, c.in);
  }
});
