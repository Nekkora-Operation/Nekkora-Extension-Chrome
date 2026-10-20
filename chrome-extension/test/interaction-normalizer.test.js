// InteractionNormalizer (STUDIO-003): cada regra de normalização com a sequência real de eventos do navegador.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const sandbox = { console };
sandbox.globalThis = sandbox;
vm.createContext(sandbox);
vm.runInContext(readFileSync(new URL('../src/shared/interaction-normalizer.js', import.meta.url), 'utf8'), sandbox, { filename: 'interaction-normalizer.js' });
const { createNormalizer } = sandbox.NekkoraInteractionNormalizer;

function rig(mode) {
  const out = [];
  const evidence = [];
  const n = createNormalizer({ mode, onInteraction: (i) => out.push(JSON.parse(JSON.stringify(i))), onEvidence: (e) => evidence.push(JSON.parse(JSON.stringify(e))) });
  let t = 1000;
  const at = () => (t += 10);
  const ev = (type, target, extra = {}) => n.observe({ type, trusted: true, at: at(), target, ...extra });
  return { n, out, evidence, ev, actions: () => out.map((i) => i.action + ':' + i.targetKey) };
}

const USER = { key: 'n1', control: 'TEXT', sensitive: false };
const PASS = { key: 'n2', control: 'TEXT', sensitive: true };
const ENTRAR = { key: 'n3', control: 'CLICKABLE', formKey: 'f1' };
const LINK = { key: 'n4', control: 'CLICKABLE' };
const ACEITO = { key: 'n5', control: 'CHECKBOX' };
const ESTADO = { key: 'n6', control: 'SELECT' };
const PF = { key: 'n7', control: 'RADIO' };
const PJ = { key: 'n8', control: 'RADIO' };

/** Sequência que o Chrome produz ao clicar num campo, digitar e sair (pointer/mouse já filtrados pelo sensor). */
function typeInto(r, field, chars) {
  r.ev('click', field);
  for (let i = 0; i < chars; i++) {
    r.ev('input', field);
  }
}

test('S003-006/R1 — clique em botão vira uma etapa CLICK', () => {
  const r = rig();
  r.ev('click', ENTRAR, { detail: 1 });
  assert.deepEqual(r.actions(), ['CLICK:n3']);
  assert.equal(r.out[0].observation, 'INTERACTION_OBSERVED');
});

test('S003-007/R1 — clique em link e clique duplo fundido no mesmo alvo', () => {
  const r = rig();
  r.ev('click', LINK, { detail: 1 });
  r.ev('click', LINK, { detail: 2 });
  assert.deepEqual(r.actions(), ['CLICK:n4'], 'o segundo clique do clique duplo não é outra etapa');
  assert.deepEqual(r.evidence.map((e) => e.evidence), ['DOUBLE_CLICK']);
  assert.equal(r.n.stats().consolidated, 1);
});

test('S003-010/014/015/R2/R3 — clicar no campo, digitar 8 caracteres e sair: UMA etapa FILL (foco absorvido)', () => {
  const r = rig();
  typeInto(r, USER, 8);
  r.ev('focusout', USER);
  assert.deepEqual(r.actions(), ['FILL:n1']);
  const f = r.out[0];
  assert.equal(f.trigger, 'FOCUSOUT');
  assert.equal(f.events, 9, '8 inputs + clique de foco consolidados');
  assert.ok(f.evidence.includes('FOCUS_CLICK_ABSORBED'));
  assert.ok(!('value' in f), 'nenhum valor digitado');
});

test('R2 — clique só de foco, sem edição: nenhuma etapa', () => {
  const r = rig();
  r.ev('click', USER);
  r.ev('focusout', USER);
  assert.deepEqual(r.actions(), []);
  assert.equal(r.n.stats().discarded.FOCUS_ONLY, 1);
});

test('S003-013/R3 — senha: FILL marcado sensível, sem valor', () => {
  const r = rig();
  typeInto(r, PASS, 6);
  r.ev('change', PASS);
  assert.deepEqual(r.actions(), ['FILL:n2']);
  assert.equal(r.out[0].sensitive, true);
  assert.equal(r.out[0].trigger, 'CHANGE');
});

test('R3 — consolidação por mudança de alvo: login completo na ordem', () => {
  const r = rig();
  typeInto(r, USER, 5);
  typeInto(r, PASS, 6); // o clique na senha consolida o usuário (sem focusout entregue)
  r.ev('click', ENTRAR, { detail: 1 }); // e o clique no botão consolida a senha
  assert.deepEqual(r.actions(), ['FILL:n1', 'FILL:n2', 'CLICK:n3']);
  assert.deepEqual(r.out.map((i) => i.localSeq), [1, 2, 3]);
});

test('S003-020/R7 — submit depois do clique é evidência, não etapa', () => {
  const r = rig();
  r.ev('click', ENTRAR, { detail: 1 });
  r.ev('submit', { key: 'f1', control: 'NONE' }, { formKey: 'f1' });
  assert.deepEqual(r.actions(), ['CLICK:n3']);
  assert.deepEqual(r.evidence, [{ localSeq: 1, evidence: 'FORM_SUBMITTED' }]);
});

test('R4 — Enter no campo: consolida o FILL e registra PRESS_KEY; o submit é evidência dele (sem clique inventado)', () => {
  const r = rig();
  typeInto(r, USER, 3);
  r.ev('keydown', { ...USER, formKey: 'f1' }, { key: 'Enter' });
  r.ev('submit', { key: 'f1', control: 'NONE' }, { formKey: 'f1' });
  assert.deepEqual(r.actions(), ['FILL:n1', 'PRESS_KEY:n1']);
  assert.equal(r.out[0].trigger, 'ENTER');
  assert.equal(r.out[1].key, 'Enter');
  assert.deepEqual(r.evidence, [{ localSeq: 2, evidence: 'FORM_SUBMITTED' }]);
});

test('R4 — envio implícito por Enter: o clique sintético no botão padrão não vira etapa; o submit é evidência do Enter', () => {
  const r = rig();
  typeInto(r, { ...USER, formKey: 'f1' }, 3);
  r.ev('keydown', { ...USER, formKey: 'f1' }, { key: 'Enter' });
  r.ev('click', ENTRAR, { detail: 0 }); // disparado pelo navegador (envio implícito)
  r.ev('submit', { key: 'f1', control: 'NONE' }, { formKey: 'f1' });
  assert.deepEqual(r.actions(), ['FILL:n1', 'PRESS_KEY:n1'], 'nenhum clique inventado');
  assert.deepEqual(r.evidence, [{ localSeq: 2, evidence: 'FORM_SUBMITTED' }]);
});

test('R1 — Enter/Espaço num botão focado (detail 0, sem Enter em campo antes) é CLICK pelo teclado', () => {
  const r = rig();
  r.ev('click', ENTRAR, { detail: 0 });
  assert.deepEqual(r.actions(), ['CLICK:n3']);
  assert.deepEqual(r.out[0].evidence, ['VIA_KEYBOARD']);
});

test('R4 — outras teclas não viram etapas', () => {
  const r = rig();
  typeInto(r, USER, 2);
  r.ev('keydown', USER, { key: 'Tab' });
  r.ev('keydown', USER, { key: 'a' });
  r.ev('focusout', USER);
  assert.deepEqual(r.actions(), ['FILL:n1']);
});

test('S003-017/018/R5 — checkbox marcado e desmarcado (clique absorvido; label não duplica)', () => {
  const r = rig();
  r.ev('click', ACEITO);
  r.ev('change', { ...ACEITO, checked: true });
  r.ev('click', { ...ACEITO, viaLabel: true }); // clique na label (o alvo funcional é o controle)
  r.ev('change', { ...ACEITO, checked: false, viaLabel: true });
  r.ev('click', ACEITO); // clique técnico disparado pela label depois do change
  assert.deepEqual(r.actions(), ['CHECK:n5', 'UNCHECK:n5']);
  assert.deepEqual(r.out[1].evidence, ['VIA_LABEL']);
});

test('S003-043/R5 — change de checkbox/radio sem ativação do usuário (.click() da página) NÃO vira etapa', () => {
  const r = rig();
  // O Chrome entrega o change de um .click() programático como confiável; o clique em si não é.
  r.n.observe({ type: 'click', trusted: false, at: 5, target: ACEITO });
  r.n.observe({ type: 'change', trusted: true, at: 6, target: { ...ACEITO, checked: true } });
  r.n.observe({ type: 'change', trusted: true, at: 7, target: { ...PF, checked: true } });
  assert.deepEqual(r.actions(), []);
  assert.equal(r.n.stats().discarded.PROGRAMMATIC_CHANGE, 2);
  assert.equal(r.n.stats().discarded.UNTRUSTED, 1);
});

test('R5 — marcação pelo teclado (Espaço no checkbox, setas no grupo de radio) é ativação real', () => {
  const r = rig();
  r.ev('keydown', ACEITO, { key: ' ' });
  r.ev('change', { ...ACEITO, checked: true });
  r.ev('keydown', PF, { key: 'ArrowDown' });
  r.ev('change', { ...PJ, checked: true });
  assert.deepEqual(r.actions(), ['CHECK:n5', 'CHECK:n8']);
});

test('S003-019/R5 — radio: só a opção marcada vira etapa CHECK', () => {
  const r = rig();
  r.ev('click', PF);
  r.ev('change', { ...PF, checked: true });
  r.ev('click', PJ);
  r.ev('change', { ...PJ, checked: true });
  assert.deepEqual(r.actions(), ['CHECK:n7', 'CHECK:n8']);
});

test('S003-016/R6 — select e option formam UMA etapa com a opção como parâmetro', () => {
  const r = rig();
  r.ev('click', ESTADO);
  r.ev('input', ESTADO);
  r.ev('change', { ...ESTADO, option: { label: 'São Paulo', value: 'SP' } });
  assert.deepEqual(r.actions(), ['SELECT_OPTION:n6']);
  assert.deepEqual(r.out[0].option, { label: 'São Paulo', value: 'SP' });
});

test('S003-043/R7/R8 — eventos não confiáveis e submit programático são descartados e contados', () => {
  const r = rig();
  r.n.observe({ type: 'click', trusted: false, at: 1, target: ENTRAR });
  r.n.observe({ type: 'input', trusted: false, at: 2, target: USER });
  r.n.observe({ type: 'change', trusted: false, at: 3, target: { ...ACEITO, checked: true } });
  r.ev('submit', { key: 'f9', control: 'NONE' }, { formKey: 'f9' });
  assert.deepEqual(r.actions(), []);
  assert.equal(r.n.stats().discarded.UNTRUSTED, 3);
  assert.equal(r.n.stats().discarded.PROGRAMMATIC_SUBMIT, 1);
});

test('R9 — opção de combobox com relação comprovada vira SELECT_OPTION no combobox; sem relação, CLICK', () => {
  const r = rig();
  r.ev('click', { key: 'n20', control: 'COMBOBOX_OPTION', comboboxKey: 'n19', option: { label: 'Rio de Janeiro', value: '' } }, { detail: 1 });
  r.ev('click', { key: 'n21', control: 'COMBOBOX_OPTION' }, { detail: 1 });
  assert.deepEqual(r.actions(), ['SELECT_OPTION:n19', 'CLICK:n21']);
  assert.deepEqual(r.out[1].notes, ['OPTION_WITHOUT_PROVEN_COMBOBOX'], 'não inventa SELECT_OPTION');
});

test('S003-027/R10 — alvo ambíguo vira UNKNOWN; elemento sem sinal de interação é ignorado', () => {
  const r = rig();
  r.ev('click', { key: 'n30', control: 'AMBIGUOUS' }, { detail: 1 });
  r.ev('click', { key: 'n31', control: 'NONE' }, { detail: 1 });
  assert.deepEqual(r.actions(), ['UNKNOWN:n30']);
  assert.deepEqual(r.out[0].notes, ['AMBIGUOUS_TARGET']);
  assert.equal(r.n.stats().discarded.NON_INTERACTIVE, 1);
});

test('S003-003/R11 — CAPTURE_ONLY: propostas pelo tipo de controle, sem input/change', () => {
  const r = rig('CAPTURE_ONLY');
  r.ev('click', ENTRAR);
  r.ev('capture', USER, { viaKeyboard: true });
  r.ev('click', { ...ACEITO, checked: false });
  r.ev('click', { ...ACEITO, checked: true });
  r.ev('click', ESTADO);
  r.ev('click', PF);
  r.ev('input', USER); // não deveria acontecer (ação bloqueada), e se acontecer é ignorado
  assert.deepEqual(r.actions(), ['CLICK:n3', 'FILL:n1', 'CHECK:n5', 'UNCHECK:n5', 'SELECT_OPTION:n6', 'CHECK:n7']);
  assert.ok(r.out.every((i) => i.observation === 'PROPOSED'));
  assert.deepEqual(r.out[1].evidence, ['VIA_KEYBOARD']);
  assert.deepEqual(r.out[4].notes, ['OPTION_NOT_CHOSEN']);
});

test('S003-005 — troca de modo consolida o preenchimento aberto ANTES de aplicar o novo modo', () => {
  const r = rig();
  typeInto(r, USER, 4);
  r.n.setMode('CAPTURE_ONLY');
  r.ev('click', ENTRAR);
  assert.deepEqual(r.actions(), ['FILL:n1', 'CLICK:n3']);
  assert.equal(r.out[0].trigger, 'MODE_CHANGE');
  assert.equal(r.out[0].observation, 'INTERACTION_OBSERVED');
  assert.equal(r.out[1].observation, 'PROPOSED');
});

test('S003-031/041 — flush consolida; stop(discard) descarta o aberto; depois de parar nada é aceito', () => {
  const r = rig();
  typeInto(r, USER, 2);
  r.n.flush('PAUSE');
  assert.deepEqual(r.actions(), ['FILL:n1']);
  typeInto(r, PASS, 2);
  r.n.stop(true);
  r.ev('click', ENTRAR);
  assert.deepEqual(r.actions(), ['FILL:n1'], 'cancelamento descarta o aberto e ignora o resto');
  assert.ok(r.n.stats().discarded.AFTER_STOP >= 2);
});

test('S003-021 — navegação consolida o preenchimento aberto; openInteraction indica o que ainda não foi consolidado', () => {
  const r = rig();
  typeInto(r, USER, 2);
  assert.equal(r.n.openInteraction().action, 'FILL');
  r.ev('navigation', { key: '-', control: 'NONE' });
  assert.deepEqual(r.actions(), ['FILL:n1']);
  assert.equal(r.out[0].trigger, 'NAVIGATION');
  assert.equal(r.n.openInteraction(), null);
});

test('determinismo — a mesma sequência produz exatamente o mesmo resultado', () => {
  const run = () => {
    const r = rig();
    typeInto(r, USER, 3);
    typeInto(r, PASS, 3);
    r.ev('click', ACEITO);
    r.ev('change', { ...ACEITO, checked: true });
    r.ev('click', ESTADO);
    r.ev('change', { ...ESTADO, option: { label: 'São Paulo', value: 'SP' } });
    r.ev('click', ENTRAR, { detail: 1 });
    r.ev('submit', { key: 'f1', control: 'NONE' }, { formKey: 'f1' });
    return JSON.stringify([r.out, r.evidence, r.n.stats()]);
  };
  assert.equal(run(), run());
});

test('NEKKORA-STUDIO-012 — alvo revelado por :hover: o clique leva a limitação REVEALED_BY_HOVER; nenhuma etapa de "passar o mouse" é inventada', () => {
  const r = rig();
  r.ev('click', { key: 'n9', control: 'CLICKABLE', revealedByHover: true }, { detail: 1 });
  r.ev('click', { key: 'n10', control: 'AMBIGUOUS', revealedByHover: true }, { detail: 1 });
  r.ev('click', LINK, { detail: 1 });
  assert.deepEqual(r.actions(), ['CLICK:n9', 'UNKNOWN:n10', 'CLICK:n4']);
  assert.deepEqual(r.out[0].notes, ['REVEALED_BY_HOVER']);
  assert.deepEqual(r.out[1].notes, ['AMBIGUOUS_TARGET', 'REVEALED_BY_HOVER']);
  assert.deepEqual(r.out[2].notes, [], 'sem evidência, nada é afirmado');
  const c = rig('CAPTURE_ONLY');
  c.ev('capture', { key: 'n9', control: 'CLICKABLE', revealedByHover: true });
  assert.deepEqual(c.out[0].notes, ['REVEALED_BY_HOVER']);
  assert.equal(c.out[0].observation, 'PROPOSED');
});

// ------------------------------------------------------------------------------------------- NEKKORA-E2E-002 (R12)
const CELULA = { key: 'n20', control: 'DATA', sensitive: false };
const TITULO = { key: 'n21', control: 'DATA', sensitive: false };

test('E2E002/R12 — no modo SELECT_DATA o clique num contêiner de dado vira PROPOSTA de leitura (EXTRACT)', () => {
  const r = rig('SELECT_DATA');
  r.ev('click', TITULO);
  r.ev('click', CELULA);
  assert.deepEqual(r.actions(), ['EXTRACT:n21', 'EXTRACT:n20']);
  assert.ok(r.out.every((i) => i.observation === 'PROPOSED' && i.option === null && i.key === null));
  assert.ok(r.out.every((i) => !('text' in i) && !('value' in i)), 'nenhum texto lido acompanha a etapa');
});

test('E2E002/R12 — sem contêiner de dado (ou alvo de controle) nada é proposto: descarte contado', () => {
  const r = rig('SELECT_DATA');
  r.ev('click', { key: null, control: 'NONE' });
  r.ev('click', ENTRAR); // um botão não é DADO: no modo de seleção de dado ele não vira clique
  assert.deepEqual(r.actions(), []);
  assert.equal(r.n.stats().discarded.NON_INTERACTIVE, 2);
});

test('E2E002/R12 — no modo SELECT_DATA preenchimento, troca e teclas não viram etapa', () => {
  const r = rig('SELECT_DATA');
  typeInto(r, USER, 3);
  r.ev('change', ESTADO);
  r.ev('keydown', USER, { key: 'Enter' });
  r.n.flush();
  assert.deepEqual(r.actions(), []);
});

test('E2E002/R12 — trocar de SELECT_DATA para CAPTURE_AND_INTERACT volta às regras de interação', () => {
  const r = rig('SELECT_DATA');
  r.ev('click', CELULA);
  r.n.setMode('CAPTURE_AND_INTERACT');
  r.ev('click', ENTRAR);
  assert.deepEqual(r.actions(), ['EXTRACT:n20', 'CLICK:n3']);
  assert.equal(r.out[1].observation, 'INTERACTION_OBSERVED');
});
