// Gera contracts/privacy/privacy-v2-vectors.json pela implementação JS (CHROME-SECURITY-001). As saídas são REVISADAS à mão
// contra a regra aprovada antes de serem commitadas; o Java (CapturePolicyVectorsTest) precisa produzir exatamente o mesmo.
import { readFileSync, writeFileSync } from 'node:fs';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';

const repo = fileURLToPath(new URL('../', import.meta.url)).split(String.fromCharCode(92)).join('/');
const sandbox = { console };
sandbox.globalThis = sandbox;
vm.createContext(sandbox);
for (const f of ['chrome-extension/src/shared/ui-vocabulary.js', 'chrome-extension/src/shared/capture-policy.js']) {
  vm.runInContext(readFileSync(repo + f, 'utf8'), sandbox, { filename: f });
}
const P = sandbox.NekkoraCapturePolicy;

const interfaceTexts = [
  'Entrar', 'Salvar', 'Pesquisar', 'Usuário', 'Senha', 'E-mail', 'Nome completo', 'Data de nascimento', 'Carregar mais',
  'Item 12', 'Item 123', 'Consultar', 'Consultar cliente', 'Consultar cliente Bartolomeu Quixadá', 'o cliente joão já existe',
  'telefone do cliente joão', 'Pedido de Maria Souza aprovado', 'Contrato 4831', 'Ximena', 'Costa', 'Campos obrigatórios',
  'Últimos 30 dias', 'SALVAR', '  Salvar '+String.fromCharCode(160)+' alteração  ', 'Sal\u200Bvar', 'Salvar\u0000', 'Ｓａｌｖａｒ', 'Salva\u0301r',
  'Salvár', 'joão', 'João', 'Joa\u0303o', '<b>Salvar</b>', 'Salvar & sair', 'Salvar; sair', 'salvar@sair', 'Привет', '',
  '   ', 'a'.repeat(81), 'Salvar sair voltar fechar abrir editar excluir novo', 'Salvar sair voltar fechar abrir editar excluir novo item',
  'Ver detalhes (2)', 'Próxima página', 'próximo →', 'Sim', 'Não', 'OK', 'Cancelar', 'Rua', 'Endereço de entrega', 'Ativo',
];
const identifiers = [
  'username', 'password', 'btn-pesquisar', 'btnPesquisar', 'searchInput', 'cliente-anacleto-prudencio', 'usuario_hermengarda',
  'pedido_483192', 'pedido_12', 'estado', 'cidade', 'cep', 'q', 'nk:menu.item-2', 'menu item', 'user@host', 'Nome', 'nome',
  'consultar', 'r-consultar', 's-campo', 'rot-fone', 'fone', 'a'.repeat(81), '', 'ação', 'login-form', 'loginForm', 'submitBtn',
];
const classLists = ['btn btn-primary cliente-rosamunda-teixeira', 'cartao', 'ver', 'hidden  active', 'xyz123 abc', ''];
const urls = [
  'https://sistema.exemplo/clientes/joao', 'https://sistema.exemplo/clientes/joao-oliveira#aba-bonifacio',
  'http://127.0.0.1:18765/lab/chrome-security-001/clientes/ataulfo-marcondes.html?cliente=Ataulfo#ficha-ataulfo',
  'http://127.0.0.1:18765/lab/chrome-security-001/urls.html', 'https://app.exemplo/clientes/consulta?status=ativo&nome=Ana',
  'https://user:pass@App.Exemplo:8443/pedidos/index.html?page=2', 'https://app.exemplo/relat%C3%B3rio/2024',
  'https://app.exemplo/api/v2/clientes/483192', 'https://app.exemplo/', 'https://app.exemplo',
  '/api/clientes/sinfronio-lacerda?nome=Sinfronio', '/clientes?nome=ana&status=ativo', '#/clientes/benvinda-guaraciaba',
  'about:blank', 'about:srcdoc', 'mailto:ana@exemplo.com', 'javascript:void(0)', 'data:text/html,oi', 'blob:https://x/1',
  '//cdn.exemplo/js/app.js', 'https://app.exemplo/a%2Fb/pedidos', 'https://app.exemplo/%E0%A4%A/x', '', null,
  'https://app.exemplo/login?redirect=https%3A%2F%2Fx&token=abc&lang=pt',
];
const expressions = [
  "button[aria-label='Consultar']", "button[aria-label='Consultar cliente Bartolomeu Quixadá']", '#pesquisar',
  '#cliente-anacleto-prudencio', 'input[name="usuario_hermengarda"]', "//button[normalize-space()='Salvar']",
  "//button[text()='Consultar João Oliveira']", "[data-customer='joao-oliveira']", 'form > p:nth-of-type(3) > input',
  '/html/body/main/form[1]/p[12]/input', 'button {name="Consultar"}', 'section {has child <h2> "ficha de luciola assumpcao"} >> input {id=rua}',
  'input {id=rua}', 'tr {row: "Wenceslau Barbacena"} >> button', 'input[type=submit]', '.btn.btn-primary', '.cliente-rosamunda-teixeira',
  "a[href='/clientes/{…}']", '//input[@id=//label[normalize-space(.)="Nome"]/@for]',
  '//input[@id=//label[normalize-space(.)="Nome do cliente João"]/@for]', '', null,
];
const attrs = [
  ['id', 'pesquisar', 'input', 'search'], ['id', 'cliente-anacleto-prudencio', 'button', null], ['name', 'q', 'input', 'search'],
  ['name', 'usuario_hermengarda', 'input', 'text'], ['class', 'btn cliente-rosamunda-teixeira', 'button', null],
  ['type', 'password', 'input', 'password'], ['role', 'button', 'div', null], ['aria-expanded', 'true', 'button', null],
  ['aria-valuenow', '3', 'span', null], ['tabindex', '0', 'span', null], ['autocomplete', 'current-password', 'input', 'password'],
  ['type', 'texto livre do cliente joão', 'input', null], ['aria-label', 'Pesquisar', 'button', null],
  ['aria-label', 'Consultar cliente Bartolomeu Quixadá', 'button', null], ['title', 'Observação sobre Teodolinda Vasconi', 'input', 'text'],
  ['placeholder', 'ex.: Filomena Zanatta', 'input', 'text'], ['placeholder', 'Pesquisar', 'input', 'search'], ['alt', 'Foto de Cremilda Bastião', 'img', null],
  ['aria-description', 'Conta', 'button', null], ['aria-valuetext', 'Limite de Hortência Malaquias', 'span', null],
  ['value', 'Entrar', 'input', 'submit'], ['value', 'Leocádia', 'input', 'text'], ['value', 't1', 'option', null],
  ['aria-labelledby', 'rot-fone', 'input', 'tel'], ['for', 'nome', 'label', null], ['aria-controls', 'lista', 'input', null],
  ['href', '/lab/chrome-security-001/clientes/ataulfo-marcondes.html?cliente=Ataulfo', 'a', null], ['src', 'frames/ficha-gervasio-barros.html', 'iframe', null],
  ['action', '/api/clientes/sinfronio-lacerda', 'form', null], ['onclick', 'abrir("joão")', 'button', null], ['style', 'color:red', 'div', null],
  ['data-cliente', 'Eustáquio Marangoni', 'div', null], ['x-titular', 'Clodomiro Ventania', 'span', null], ['data-testid', 'btn-salvar', 'button', null],
  ['data-testid', 'linha-55821', 'tr', null], ['data-sitekey', '6Lc_abc', 'div', null], ['data-joao', 'x', 'div', null],
  ['sandbox', 'allow-scripts allow-same-origin', 'iframe', null], ['srcdoc', '<p>Cliente Zéfiro</p>', 'iframe', null],
  ['src', 'http://127.0.0.1:18765/lab/frames/ficha-gervasio.html?cliente=Ana', 'iframe', null], ['src', '/img/foto-de-ana.png', 'img', null],
  ['slot', 'cabecalho', 'div', null], ['slot', 'cliente-anacleto', 'div', null], ['download', 'contrato-ana.pdf', 'a', null],
  ['loading', 'lazy', 'iframe', null], ['rel', 'noopener noreferrer', 'a', null],
];
const carriers = [
  ['button', null, false], ['a', null, true], ['a', null, false], ['label', null, false], ['legend', null, false], ['summary', null, false],
  ['div', 'button', false], ['span', 'link', false], ['li', 'menuitem', false], ['div', 'tab', false], ['td', null, false],
  ['th', null, false], ['p', null, false], ['h2', null, false], ['option', null, false], ['div', 'option', false], ['div', 'alert', false],
  ['li', 'option', false], ['div', 'BUTTON extra', false],
];

const out = {
  policy: P.VERSION,
  vocabulary: sandbox.NekkoraVocabulary.version,
  note: 'Vetores compartilhados (Node e Java; o Studio usa o subconjunto de texto, identificador, URL e expressão): as implementações da política nekkora-privacy/2 devem produzir exatamente estes resultados. Gerados pela implementação JS e revisados à mão contra a regra aprovada (D1+D2+D3+D4).',
  interfaceText: interfaceTexts.map((x) => ({ in: x, out: P.interfaceText(x) })),
  identifiers: identifiers.map((x) => ({ in: x, out: P.identifier(x) })),
  classes: classLists.map((x) => ({ in: x, out: P.classes(x) })),
  urls: urls.map((x) => ({ in: x, out: P.urlTemplate(x) })),
  expressions: expressions.map((x) => ({ in: x, out: P.expressionSafe(x) })),
  attributes: attrs.map(([n, v, tag, type]) => ({ name: n, value: v, tag, type, out: P.attribute(n, v, { tag, type }) })),
  carriers: carriers.map(([tag, role, href]) => ({ tag, role, hasHref: href, out: P.isCarrier(tag, role, href) })),
};
writeFileSync(repo + 'contracts/privacy/privacy-v2-vectors.json', JSON.stringify(out, null, 1) + '\n');
for (const k of ['interfaceText', 'identifiers', 'classes', 'urls', 'expressions', 'attributes', 'carriers']) {
  console.log('== ' + k);
  for (const r of out[k]) {
    console.log(JSON.stringify(r));
  }
}
