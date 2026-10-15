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
