// Regras puras das evidências observadas (STUDIO-005): política de mensagem, classificação por sinais e correlação.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
// eslint-disable-next-line no-new-func
new Function(readFileSync(path.join(here, '../src/shared/privacy.js'), 'utf8'))();
// eslint-disable-next-line no-new-func
new Function(readFileSync(path.join(here, '../src/shared/feedback-rules.js'), 'utf8'))();
const F = globalThis.NekkoraFeedback;

test('S005-047 — mensagem curta e sem dado sensível sai; vazia é NONE', () => {
  assert.deepEqual(F.messagePolicy('  Usuário ou   senha inválidos. ', []), { availability: 'AVAILABLE', text: 'Usuário ou senha inválidos.' });
  assert.deepEqual(F.messagePolicy('   ', []), { availability: 'NONE', text: null });
  assert.deepEqual(F.messagePolicy(null, []), { availability: 'NONE', text: null });
});

test('S005-047/048/049 — em caso de dúvida o texto é omitido (SUPPRESSED): CPF, e-mail, token, URL, números longos, segredo, tamanho', () => {
  for (const unsafe of [
    'CPF 123.456.789-09 já cadastrado',
    'Enviamos o código para ana.souza@exemplo.com',
    'Sessão eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U expirada',
    'Authorization: Bearer abcdef1234567890ghij',
    'Conta 98765432 bloqueada',
    'Seu código é 482913',
    'Acesse https://exemplo.com/reset?t=abc',
    'Chave a1b2c3d4e5f6a7b8c9d0e1f2 inválida',
    'x'.repeat(161),
  ]) {
    assert.deepEqual(F.messagePolicy(unsafe, []), { availability: 'SUPPRESSED', text: null }, unsafe);
  }
});

test('S005-047/048 — mensagem que repete um valor digitado na página é suprimida (comparação local, valor nunca sai)', () => {
  assert.equal(F.messagePolicy('Usuário anateste não encontrado', ['anateste']).availability, 'SUPPRESSED');
  assert.equal(F.messagePolicy('Usuário ANATESTE não encontrado', ['anateste']).availability, 'SUPPRESSED', 'sem diferenciar maiúsculas');
  assert.equal(F.messagePolicy('Usuário não encontrado', ['ab']).availability, 'AVAILABLE', 'valores curtos (<3) não suprimem tudo');
});

test('S005-011/013/014 — classificação pelos SINAIS: alert, alertdialog, status, aria-live; classe "error" só reforça', () => {
  assert.deepEqual(F.classifyRegion({ role: 'alert' }), { type: 'VISIBLE_ALERT', classification: 'UNKNOWN_FEEDBACK', signals: ['ROLE_ALERT'] });
  assert.deepEqual(F.classifyRegion({ role: 'alert', errorHint: true }),
    { type: 'VISIBLE_ERROR_MESSAGE', classification: 'ERROR_INDICATOR', signals: ['ROLE_ALERT', 'ERROR_HINT'] });
  assert.deepEqual(F.classifyRegion({ role: 'alertdialog' }).type, 'VISIBLE_ALERT');
  assert.deepEqual(F.classifyRegion({ role: 'status' }), { type: 'VISIBLE_STATUS_MESSAGE', classification: 'STATUS_INDICATOR', signals: ['ROLE_STATUS'] });
  assert.deepEqual(F.classifyRegion({ live: 'polite' }).type, 'VISIBLE_STATUS_MESSAGE');
  assert.deepEqual(F.classifyRegion({ live: 'assertive' }).type, 'VISIBLE_ALERT');
  assert.deepEqual(F.classifyRegion({ role: 'status', errorHint: true }).type, 'VISIBLE_STATUS_MESSAGE', 'status com classe de erro continua status');
  assert.equal(F.classifyRegion({ errorHint: true }), null, 'classe CSS "error" sozinha NÃO é evidência de erro');
  assert.equal(F.classifyRegion({}), null);
  const viaRef = F.classifyRegion({ errorMessageOf: true });
  assert.equal(viaRef.type, 'VISIBLE_ERROR_MESSAGE');
  assert.deepEqual(viaRef.signals, ['ARIA_ERRORMESSAGE']);
});

test('S005-011 — indício de erro em classe/id/data-*: palavras inteiras, não substrings', () => {
  assert.equal(F.hasErrorHint(['msg', 'alert-danger']), true);
  assert.equal(F.hasErrorHint(['erro-login']), true);
  assert.equal(F.hasErrorHint(['form-errors']), true);
  assert.equal(F.hasErrorHint(['terror', 'mirror']), false);
  assert.equal(F.hasErrorHint([]), false);
});

test('S005-021/042 — correlação: uma candidata = associada; várias = AMBÍGUA; outro documento ou FILL não concorrem', () => {
  const main = {};
  const frame = {};
  const w = (localSeq, action, doc, openedAt, closesAt) => ({ localSeq, action, doc, openedAt, closesAt });
  assert.deepEqual(F.correlate([w(3, 'CLICK', main, 100, 3100)], 1100, main), { localSeq: 3, confidence: 'TEMPORALLY_ASSOCIATED' });
  assert.deepEqual(F.correlate([w(3, 'CLICK', main, 100, 3100), w(4, 'CLICK', main, 200, 3200)], 1100, main), { ambiguous: true, candidates: 2 });
  assert.equal(F.correlate([w(3, 'CLICK', main, 100, 3100)], 1100, frame), null, 'mensagem do iframe não é da ação do documento principal');
  assert.equal(F.correlate([w(3, 'CLICK', main, 100, 3100)], 3101, main), null, 'fora da janela');
  assert.deepEqual(F.correlate([w(2, 'FILL', main, 50, 3050), w(3, 'CLICK', main, 100, 3100)], 1100, main), { localSeq: 3, confidence: 'TEMPORALLY_ASSOCIATED' },
    'FILL não disputa avisos gerais');
  assert.equal(F.correlate([], 1, main), null);
});

test('S005-035/036 — janela de observação só em CAPTURE_AND_INTERACT', () => {
  assert.equal(F.opensWindow('CLICK', 'CAPTURE_AND_INTERACT'), true);
  assert.equal(F.opensWindow('FILL', 'CAPTURE_AND_INTERACT'), true);
  assert.equal(F.opensWindow('CLICK', 'CAPTURE_ONLY'), false);
  assert.equal(F.opensWindow('NAVIGATE', 'CAPTURE_AND_INTERACT'), false);
});

test('S005-018 — valores de estado normalizados para o contrato', () => {
  assert.equal(F.stateValue(null), 'absent');
  assert.equal(F.stateValue('TRUE'), 'true');
  assert.equal(F.stateValue('false'), 'false');
  assert.equal(F.stateValue('mixed'), 'mixed');
  assert.equal(F.stateValue(''), 'true', 'atributo booleano presente');
  assert.equal(F.stateValue('qualquer'), 'absent');
});
