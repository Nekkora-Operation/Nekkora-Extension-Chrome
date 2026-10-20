import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

/** CHROME-SECURITY-001 — política nekkora-privacy/2 (1ª e 2ª barreiras) contra os vetores compartilhados com o Java. */
function load() {
  const sandbox = { console };
  sandbox.globalThis = sandbox;
  vm.createContext(sandbox);
  for (const f of ['shared/ui-vocabulary.js', 'shared/capture-policy.js']) {
    vm.runInContext(readFileSync(new URL('../src/' + f, import.meta.url), 'utf8'), sandbox, { filename: f });
  }
  return sandbox;
}

const vectors = JSON.parse(readFileSync(new URL('../../contracts/privacy/privacy-v2-vectors.json', import.meta.url), 'utf8'));
const source = JSON.parse(readFileSync(new URL('../../contracts/privacy/ui-vocabulary.json', import.meta.url), 'utf8'));

test('SEC001-065/067 — vocabulário gerado idêntico à fonte única (contracts/privacy/ui-vocabulary.json)', () => {
  const { NekkoraVocabulary } = load();
  assert.equal(NekkoraVocabulary.version, source.version);
  assert.deepEqual([...NekkoraVocabulary.terms].sort(), [...source.terms].sort(), 'rode node scripts/gen-ui-vocabulary.mjs');
  for (const name of source.excludedOnPurpose) {
    assert.equal(NekkoraVocabulary.terms.has(name), false, 'palavra que também é sobrenome comum fica fora: ' + name);
  }
});

test('SEC001-065/067 — vetores compartilhados: texto de interface, identificadores, classes, URLs, expressões, atributos, portadores', () => {
  const { NekkoraCapturePolicy: P } = load();
  assert.equal(vectors.policy, P.VERSION);
  for (const v of vectors.interfaceText) {
    assert.equal(P.interfaceText(v.in), v.out, 'interfaceText ' + JSON.stringify(v.in));
  }
  for (const v of vectors.identifiers) {
    assert.equal(P.identifier(v.in), v.out, 'identifier ' + JSON.stringify(v.in));
  }
  assert.ok(vectors.developerIdentifiers.length > 10, 'vetores da lista técnica (STUDIO-012)');
  for (const v of vectors.developerIdentifiers) {
    assert.equal(P.developerIdentifier(v.in), v.out, 'developerIdentifier ' + JSON.stringify(v.in));
  }
  for (const v of vectors.classes) {
    assert.equal(P.classes(v.in), v.out, 'classes ' + JSON.stringify(v.in));
  }
  for (const v of vectors.urls) {
    assert.equal(P.urlTemplate(v.in), v.out, 'urlTemplate ' + JSON.stringify(v.in));
  }
  for (const v of vectors.expressions) {
    assert.equal(P.expressionSafe(v.in), v.out, 'expressionSafe ' + JSON.stringify(v.in));
  }
  for (const v of vectors.attributes) {
    assert.equal(P.attribute(v.name, v.value, { tag: v.tag, type: v.type }), v.out, 'attribute ' + v.name + '=' + JSON.stringify(v.value));
  }
  for (const v of vectors.carriers) {
    assert.equal(P.isCarrier(v.tag, v.role, v.hasHref), v.out, 'isCarrier ' + v.tag + '/' + v.role);
  }
  for (const v of vectors.rowHeaders) { // NEKKORA-E2E-002: cabeçalho de linha / termo como portador
    assert.equal(P.isRowHeader(v.tag, v.role, v.scope, v.rowHasDataCell), v.out, 'isRowHeader ' + JSON.stringify(v));
  }
  for (const v of vectors.tags) {
    assert.equal(P.tagName(v.in), v.out, 'tagName ' + JSON.stringify(v.in));
  }
});

test('SEC001-001/002/018/019 — os marcadores do laboratório nunca passam, nem em forma transformada', () => {
  const { NekkoraCapturePolicy: P } = load();
  for (const s of ['o cliente joão já existe', 'Consultar cliente Bartolomeu Quixadá', 'telefone do cliente Godofredo Ubirajara',
    'Pedido de Ximena Tavolaro aprovado', 'Excluir Doralice Mondego', 'joao oliveira', 'JOAO-OLIVEIRA', 'joao_oliveira', 'Joa%C3%A3o']) {
    assert.equal(P.interfaceText(s), null, s);
    assert.equal(P.identifier(s), null, s);
  }
  for (const s of ['cliente-anacleto-prudencio', 'usuario_hermengarda', 'clienteAnacletoPrudencio', 'pedido_483192']) {
    assert.equal(P.identifier(s), null, s);
  }
  assert.equal(P.urlTemplate('https://x.exemplo/clientes/joao-oliveira').includes('joao'), false);
});

test('SEC001-065 — conteúdo de negócio não passa por ser feito de palavras do vocabulário: a elegibilidade é estrutural', () => {
  const { NekkoraCapturePolicy: P } = load();
  // "Ativo" é palavra do vocabulário, mas uma célula de tabela, um parágrafo ou um aviso não são portadores de nome
  for (const tag of ['td', 'th', 'p', 'h2', 'caption', 'li', 'option', 'div']) {
    assert.equal(P.isCarrier(tag, null, false), false, tag);
  }
  assert.equal(P.isCarrier('div', 'alert', false), false);
  assert.equal(P.isCarrier('div', 'status', false), false);
  assert.equal(P.isCarrier('div', 'option', false), false);
});

test('SEC001-065 — sem expansão automática: consultar a política não altera o vocabulário', () => {
  const { NekkoraCapturePolicy: P, NekkoraVocabulary: V } = load();
  const before = V.terms.size;
  P.interfaceText('Quixadá Quixadá Quixadá');
  P.identifier('novoTermoQualquer');
  assert.equal(V.terms.size, before);
  assert.equal(V.terms.has('quixada'), false);
});

const structuralSource = JSON.parse(readFileSync(new URL('../../contracts/privacy/structural-attributes.json', import.meta.url), 'utf8'));

test('SEC001-088 — regras dos atributos estruturais geradas idênticas à fonte única (structural-attributes.json)', () => {
  const { NekkoraVocabulary } = load();
  assert.equal(NekkoraVocabulary.structural.version, structuralSource.version);
  assert.deepEqual({ ...NekkoraVocabulary.structural.rules }, structuralSource.rules, 'rode node scripts/gen-ui-vocabulary.mjs');
});

test('SEC001-088 (P1-01) — atributo estrutural só leva valor da regra FECHADA do atributo; forma técnica não basta', () => {
  const CP = load().NekkoraCapturePolicy;
  const a = (n, v, tag = 'div', type = null) => CP.attribute(n, v, { tag, type });
  // reprodução da revisão: passavam pela expressão genérica /^[A-Za-z0-9\-_. ]{0,80}$/
  assert.equal(a('role', 'cliente-joao'), '');
  assert.equal(a('autocomplete', 'joao oliveira', 'input', 'text'), '');
  assert.equal(a('type', '48291736455', 'input'), '');
  assert.equal(a('tabindex', '48291736455'), '');
  assert.equal(a('target', 'gumercindo_tavares', 'a'), '');
  assert.equal(a('rel', 'gumercindo tavares', 'a'), '');
  assert.equal(a('lang', 'gumercindo-tavares', 'p'), '');
  assert.equal(a('aria-current', 'gumercindo'), '');
  assert.equal(a('scope', 'gumercindo', 'th'), '');
  // valores legítimos continuam
  assert.equal(a('role', 'button'), 'button');
  assert.equal(a('role', 'switch checkbox'), 'switch checkbox');
  assert.equal(a('autocomplete', 'shipping street-address', 'input', 'text'), 'shipping street-address');
  assert.equal(a('type', 'password', 'input'), 'password');
  assert.equal(a('type', 'application/x-nekkora-inert', 'script'), 'application/x-nekkora-inert');
  assert.equal(a('tabindex', '-1'), '-1');
  assert.equal(a('aria-expanded', 'true'), 'true');
  assert.equal(a('lang', 'pt-BR', 'p'), 'pt-BR');
  assert.equal(a('disabled', 'disabled', 'button'), 'disabled');
  assert.equal(a('disabled', 'gumercindo', 'button'), '', 'booleano com valor arbitrário: só presença');
  assert.equal(a('width', '120px', 'img'), '120px');
  assert.equal(a('sandbox', 'allow-scripts allow-forms', 'iframe'), 'allow-scripts allow-forms');
  assert.equal(a('allow', 'camera https://gumercindo.exemplo', 'iframe'), '', 'allow: só presença');
});

test('SEC001-089 (P1-02) — número em localizador: posição estrutural passa; VALOR numérico longo não', () => {
  const CP = load().NekkoraCapturePolicy;
  // reprodução da revisão: literal só de dígitos era aceito em qualquer comprimento
  assert.equal(CP.expressionSafe("//button[@data-customer='12345678900']"), false);
  assert.equal(CP.expressionSafe('input[type="48291736455"]'), false);
  assert.equal(CP.expressionSafe('[data-customer=12345678900]'), false);
  assert.equal(CP.expressionSafe('//button[@data-customer=12345678900]'), false);
  assert.equal(CP.expressionSafe('.c12345678900'), false);
  // posições estruturais (não são literais) continuam
  assert.equal(CP.expressionSafe('button:nth-child(12)'), true);
  assert.equal(CP.expressionSafe('html > body > div:nth-of-type(123) > button:nth-of-type(1)'), true);
  assert.equal(CP.expressionSafe('//html/body/div[123]/button[1]'), true);
  assert.equal(CP.expressionSafe('html >> body >> div {nth-of-type 123} >> button {nth-of-type 1}'), true);
  assert.equal(CP.expressionSafe("input[maxlength='12']"), true, 'número curto de atributo estrutural');
  assert.equal(CP.expressionSafe("//li[normalize-space()='Item 12']"), true);
});

const elementsSource = JSON.parse(readFileSync(new URL('../../contracts/privacy/html-elements.json', import.meta.url), 'utf8'));

test('SEC001-091 — catálogo de elementos gerado idêntico à fonte única (html-elements.json)', () => {
  const { NekkoraVocabulary } = load();
  assert.equal(NekkoraVocabulary.elementsVersion, elementsSource.version);
  assert.deepEqual([...NekkoraVocabulary.elements].sort(), [...elementsSource.elements].sort(), 'rode node scripts/gen-ui-vocabulary.mjs');
  assert.equal(NekkoraVocabulary.elements.has(elementsSource.neutral), false, 'o marcador neutro não é elemento do catálogo');
});

test('SEC001-090 (2ª revisão) — operadores CSS e expressões não analisáveis: recusadas inteiras; posições continuam', () => {
  const CP = load().NekkoraCapturePolicy;
  // reprodução da revisão: estes eram aceitos (a lista de literais ficava vazia => aprovado)
  for (const e of ['button[data-client^=joao-oliveira]', 'button[data-client~=joao-oliveira]', 'button[data-client|=joao-oliveira]',
    'button[data-client$=joao-oliveira]', 'input[aria-label*=gumercindo-tavares]', 'input[aria-label *= gumercindo-tavares]',
    '#\\6a oao-oliveira', 'button /* joao oliveira */', 'button {aria-label=Consultar Joao Oliveira}', 'button[data-joao-oliveira]',
    'ficha-cliente-joao > button', '//ficha-cliente-joao/button', 'ficha-cliente-joao >> button', 'button:not(.btn)', 'button, a', '[data-client']) {
    assert.equal(CP.expressionSafe(e), false, e);
  }
  // operadores reconhecidos com literal da política e posições estruturais continuam
  for (const e of ["input[aria-label*='Consultar']", 'input[aria-label^="Consultar"]', 'button[data-client="Consultar" i]',
    'html > body > main:nth-of-type(1) > button', 'button:nth-child(2n+1)', '//html/body/div[123]/button[1]',
    'html >> body >> div {nth-of-type 123} >> button {nth-of-type 1}', 'painel-cliente > button']) {
    assert.equal(CP.expressionSafe(e), true, e);
  }
});

test('SEC001-091 (2ª revisão) — nome de componente fora do catálogo e do vocabulário vira o marcador neutro', () => {
  const CP = load().NekkoraCapturePolicy;
  assert.equal(CP.tagName('ficha-cliente-teodolinda'), CP.NEUTRAL_TAG);
  assert.equal(CP.tagName('eustaquio'), CP.NEUTRAL_TAG, 'elemento desconhecido sem hífen');
  assert.equal(CP.tagName('nk-open-card'), CP.NEUTRAL_TAG, 'prefixo fora do vocabulário');
  assert.equal(CP.tagName('painel-cliente'), 'painel-cliente', 'nome inteiro do vocabulário');
  assert.equal(CP.tagName('BUTTON'), 'button');
  assert.equal(CP.tagName('foreignObject'), 'foreignobject');
  assert.equal(CP.tagName('\u212Abd'), CP.NEUTRAL_TAG, 'sem dobra Unicode para um nome do catálogo');
  assert.equal(CP.expressionSafe('custom-element > button'), false, 'o marcador neutro nunca é localizador');
});
