/*
 * CHROME-SECURITY-001 — política de captura nekkora-privacy/2 (espelho exato: CapturePrivacyPolicy.java, verificado
 * pelos vetores contracts/privacy/privacy-v2-vectors.json). Usada no content script (1ª barreira) e no service worker
 * (2ª); o Native Host (3ª) e o Studio (4ª) reaplicam a mesma regra.
 *
 * Princípio: o que não pertence a uma lista FECHADA não sai. Não há detecção de dado pessoal.
 *  - Metadado de interface (nome de controle, rótulo, identificador do desenvolvedor, segmento de URL, título da aba) só
 *    atravessa se TODO termo pertencer ao vocabulário controlado (nekkora-ui-vocabulary) ou for número de até 2
 *    dígitos — validação do valor inteiro, sem supressão parcial, sem expansão automática.
 *  - Conteúdo de negócio, tabelas, mensagens e texto livre nunca atravessam, mesmo que compostos de palavras do
 *    vocabulário (a elegibilidade é decidida pela ESTRUTURA, antes do vocabulário).
 *  - Caracteres fora do conjunto seguro (controle, largura zero, marcas combinantes soltas, outros alfabetos,
 *    símbolos) recusam o valor inteiro.
 */
(function (root) {
  if (root.NekkoraCapturePolicy) {
    return;
  }
  const VERSION = 'nekkora-privacy/2';
  const WITHHELD = '[omitido]';
  const MAX_LEN = 80;
  const MAX_TERMS = 8;
  const HOLE = '{…}';
  const SPACES = /[\t\n\v\f\r \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]+/g;
  const SAFE = /^[A-Za-z0-9\u00C0-\u00D6\u00D8-\u00F6\u00F8-\u00FF \-_.:/()',!?]+$/;
  const IDENT = /^[A-Za-z0-9\-_:.]{1,80}$/;
  const CAMEL = /([a-z\u00DF-\u00F6\u00F8-\u00FF])([A-Z\u00C0-\u00D6\u00D8-\u00DE])/g;
  const LETTER_DIGIT = /([A-Za-z\u00C0-\u00D6\u00D8-\u00F6\u00F8-\u00FF])([0-9])/g;
  const DIGIT_LETTER = /([0-9])([A-Za-z\u00C0-\u00D6\u00D8-\u00F6\u00F8-\u00FF])/g;
  const SPLIT = /[^A-Za-z0-9\u00C0-\u00D6\u00D8-\u00F6\u00F8-\u00FF]+/;

  function vocab() {
    return root.NekkoraVocabulary ? root.NekkoraVocabulary.terms : new Set();
  }

  /** NFC + espaços colapsados (inclusive os Unicode). */
  function clean(v) {
    return String(v).normalize('NFC').replace(SPACES, ' ').trim();
  }

  function fold(t) {
    return t.normalize('NFD').replace(/\p{M}+/gu, '').toLowerCase();
  }

  function terms(s) {
    return s.replace(CAMEL, '$1 $2').replace(LETTER_DIGIT, '$1 $2').replace(DIGIT_LETTER, '$1 $2')
      .split(SPLIT).filter(Boolean).map(fold);
  }

  /** O valor inteiro é metadado de interface do vocabulário controlado? */
  /*
   * Memória de decisões SÓ no content script (contexto com document): os valores já estão na memória da própria página,
   * e a captura avalia os mesmos rótulos/ids/classes milhares de vezes. No service worker (sem document) não há cache:
   * nenhum texto da página fica retido lá. Limitado (esvaziado ao encher) e só para valores curtos.
   */
  const MEMO = typeof document !== 'undefined' ? new Map() : null;
  const MEMO_MAX = 8192;
  const MEMO_KEY_MAX = 256;

  function vocabularyOnly(v) {
    if (v == null) {
      return false;
    }
    const k = String(v);
    if (MEMO && k.length <= MEMO_KEY_MAX) {
      const hit = MEMO.get(k);
      if (hit !== undefined) {
        return hit;
      }
      const r = decideVocabulary(k);
      if (MEMO.size >= MEMO_MAX) {
        MEMO.clear();
      }
      MEMO.set(k, r);
      return r;
    }
    return decideVocabulary(k);
  }

  function decideVocabulary(v) {
    const s = clean(v);
    if (!s || s.length > MAX_LEN || !SAFE.test(s)) {
      return false;
    }
    const t = terms(s);
    if (!t.length || t.length > MAX_TERMS) {
      return false;
    }
    const words = vocab();
    return t.every((x) => /^\d{1,2}$/.test(x) || words.has(x));
  }

  /** Texto de interface transportável (forma limpa) ou null. */
  function interfaceText(v) {
    return vocabularyOnly(v) ? clean(v) : null;
  }

  /** Identificador do desenvolvedor (id, name, atributo de teste): ASCII técnico E termos do vocabulário; ou null. */
  function identifier(v) {
    if (v == null) {
      return null;
    }
    const s = String(v);
    return IDENT.test(s) && vocabularyOnly(s) ? s : null;
  }

  /** Classes CSS: cada classe é um identificador independente; ficam só as que passam. */
  function classes(v) {
    if (v == null) {
      return null;
    }
    const kept = String(v).split(/[\t\n\f\r ]+/).filter((c) => c && identifier(c));
    return kept.length ? kept.join(' ') : null;
  }

  function safeDecode(s) {
    try {
      return decodeURIComponent(s);
    } catch (e) {
      return null;
    }
  }

  function templatePath(p) {
    if (!p) {
      return '';
    }
    return p.split('/').map((seg) => {
      if (seg === '') {
        return '';
      }
      const d = safeDecode(seg);
      return d != null && !d.includes('/') && vocabularyOnly(d) ? seg : HOLE;
    }).join('/');
  }

  function templateQuery(q) {
    if (!q || q === '?') {
      return '';
    }
    const kept = [];
    for (const pair of q.slice(1).split('&')) {
      const k = safeDecode(pair.split('=')[0]);
      if (k && identifier(k) && !kept.includes(k)) {
        kept.push(k);
      }
    }
    return kept.length ? '?' + kept.map((k) => k + '=' + HOLE).join('&') : '';
  }

  const ABS = /^(https?):\/\/([^/?#]*)([^?#]*)(\?[^#]*)?(#.*)?$/i;
  const NET = /^\/\/([^/?#]*)([^?#]*)(\?[^#]*)?(#.*)?$/;
  const REL = /^([^?#]*)(\?[^#]*)?(#.*)?$/;
  const SCHEME = /^([a-zA-Z][a-zA-Z0-9+.-]*):/;

  function authority(a) {
    const at = a.lastIndexOf('@');
    return (at >= 0 ? a.slice(at + 1) : a).toLowerCase();
  }

  /**
   * URL mínima (decisão D3): origem + MODELO do caminho — segmento do vocabulário fica, os demais viram {…}; da consulta
   * só os NOMES de parâmetros do vocabulário (valor sempre {…}); fragmento nunca. Não é executável e não se passa por
   * URL real. Outros esquemas: só o esquema.
   */
  function urlTemplate(raw) {
    if (raw == null) {
      return null;
    }
    const s = String(raw).trim();
    if (!s) {
      return null;
    }
    const lower = s.toLowerCase();
    if (lower === 'about:blank' || lower === 'about:srcdoc') {
      return lower;
    }
    let m = ABS.exec(s);
    if (m) {
      return (m[1].toLowerCase() + '://' + authority(m[2]) + templatePath(m[3]) + templateQuery(m[4])).slice(0, 2000);
    }
    const sc = SCHEME.exec(s);
    if (sc) {
      return sc[1].toLowerCase() + ':' + HOLE;
    }
    m = NET.exec(s);
    if (m) {
      return ('//' + authority(m[1]) + templatePath(m[2]) + templateQuery(m[3])).slice(0, 2000);
    }
    m = REL.exec(s);
    const out = templatePath(m[1]) + templateQuery(m[2]);
    return out ? out.slice(0, 2000) : null;
  }

  const QUOTED = /'([^']*)'|"([^"]*)"/g;
  const CSS_TOKEN = /[#.](-?[A-Za-z_][A-Za-z0-9_-]*)/g;
  const PAIR = /\b[A-Za-z][A-Za-z0-9-]*=([^\s,}\]"'()/[][^\s,}\]"'()]*)/g;

  function literalOk(v) {
    return v === '' || /^\d+$/.test(v) || v === HOLE || vocabularyOnly(v);
  }

  /** Uma expressão de localizador (CSS, XPath ou descrição) só é transportável se TODO literal passar na política. */
  function expressionSafe(expr) {
    if (expr == null || expr === '') {
      return true;
    }
    const s = String(expr);
    const lits = [];
    let m;
    QUOTED.lastIndex = 0;
    while ((m = QUOTED.exec(s))) {
      lits.push(m[1] != null ? m[1] : m[2]);
    }
    const unquoted = s.replace(QUOTED, ' ');
    CSS_TOKEN.lastIndex = 0;
    while ((m = CSS_TOKEN.exec(unquoted))) {
      lits.push(m[1]);
    }
    PAIR.lastIndex = 0;
    while ((m = PAIR.exec(unquoted))) {
      lits.push(m[1]);
    }
    return lits.every(literalOk);
  }

  /** Atributos de vocabulário fechado, numéricos ou de estado (valor fica se tiver forma técnica). */
  const STRUCTURAL = new Set(['type', 'role', 'tabindex', 'disabled', 'checked', 'readonly', 'required', 'hidden', 'open', 'multiple',
    'selected', 'contenteditable', 'autocomplete', 'inputmode', 'method', 'target', 'scope', 'colspan', 'rowspan', 'draggable',
    'spellcheck', 'dir', 'lang', 'popover', 'maxlength', 'minlength', 'min', 'max', 'step', 'size', 'rows', 'cols',
    'aria-expanded', 'aria-pressed', 'aria-checked', 'aria-selected', 'aria-disabled', 'aria-hidden', 'aria-invalid', 'aria-required',
    'aria-readonly', 'aria-busy', 'aria-modal', 'aria-haspopup', 'aria-live', 'aria-current', 'aria-level', 'aria-multiselectable',
    'aria-orientation', 'aria-sort', 'aria-autocomplete', 'aria-atomic', 'aria-relevant', 'aria-valuenow', 'aria-valuemin',
    'aria-valuemax', 'aria-colcount', 'aria-rowcount', 'aria-colindex', 'aria-rowindex', 'aria-posinset', 'aria-setsize',
    'sandbox', 'loading', 'referrerpolicy', 'crossorigin', 'charset', 'rel', 'accept', 'enctype', 'novalidate', 'autofocus', 'wrap', 'width', 'height', 'translate', 'autocapitalize', 'enterkeyhint', 'decoding', 'fetchpriority', 'async', 'defer', 'nomodule', 'frameborder', 'scrolling', 'allowfullscreen', 'allow', 'controls', 'autoplay', 'loop', 'muted', 'playsinline', 'preload', 'reversed', 'start', 'span', 'shape', 'kind', 'hreflang', 'srclang']);
  const STRUCTURAL_VALUE = /^[A-Za-z0-9\-_. ]{0,80}$/;
  /** Identificadores escritos pelo desenvolvedor. */
  const IDENTIFIERS = new Set(['id', 'name', 'data-testid', 'data-test', 'data-test-id', 'data-qa', 'data-cy', 'slot', 'part', 'is']);
  /** Referências entre elementos: o valor (ids) não sai; a relação vai como id temporário de nó (refs). */
  const IDREFS = new Set(['for', 'aria-labelledby', 'aria-describedby', 'aria-controls', 'aria-owns', 'aria-activedescendant',
    'aria-errormessage', 'aria-details', 'aria-flowto', 'list', 'form', 'headers']);
  /** Atributos de NOME (metadado de interface). */
  const NAMING = new Set(['aria-label', 'title', 'placeholder', 'alt', 'aria-roledescription', 'aria-placeholder']);
  /** Texto descritivo ou valor: nunca. */
  const DESCRIPTIVE = new Set(['aria-description', 'aria-valuetext', 'label']);
  const URLS = new Set(['href', 'src', 'action', 'formaction', 'poster', 'data', 'cite', 'background', 'longdesc', 'srcset',
    'imagesrcset', 'ping', 'manifest', 'codebase', 'archive', 'usemap', 'xlink:href']);
  /** Só a PRESENÇA (o valor é conteúdo da página: HTML de srcdoc, nome de arquivo de download). */
  const PRESENCE = new Set(['srcdoc', 'download']);
  /** Elementos cujo src é a URL de um DOCUMENTO/recurso analisado (frame, script): vai como MODELO (D3), não só presença. */
  const URL_HOSTS = new Set(['iframe', 'frame', 'script']);
  const BUTTON_TYPES = new Set(['submit', 'button', 'reset']);

  /**
   * Valor transportável de um atributo: string (valor ou '' = só a presença) ou null (não sai).
   * ctx = { tag, type } do elemento.
   */
  function attribute(name, value, ctx) {
    const n = String(name).toLowerCase();
    const v = value == null ? '' : String(value);
    if (n === 'style') {
      return null;
    }
    if (n.startsWith('on')) {
      return '[manipulador declarado]';
    }
    if (STRUCTURAL.has(n)) {
      return STRUCTURAL_VALUE.test(v) ? v : '';
    }
    if (IDENTIFIERS.has(n)) {
      return identifier(v);
    }
    if (n === 'class') {
      return classes(v);
    }
    if (n === 'src' && URL_HOSTS.has(String((ctx && ctx.tag) || '').toLowerCase())) {
      return urlTemplate(v) || '';
    }
    if (IDREFS.has(n) || URLS.has(n) || PRESENCE.has(n)) {
      return '';
    }
    if (NAMING.has(n)) {
      // retido: a PRESENÇA da fonte de nome fica marcada ({…}) — sem ela o motor calcularia um nome por uma fonte de
      // prioridade menor (ex.: o conteúdo "Consultar" de um botão cujo aria-label é privado) e inventaria um nome.
      // Vazio continua vazio (alt="" de imagem decorativa é semântica legítima).
      const t = interfaceText(v);
      return t != null ? t : (String(v).trim() ? HOLE : '');
    }
    if (n === 'value') {
      const tag = String((ctx && ctx.tag) || '').toLowerCase();
      const type = String((ctx && ctx.type) || '').toLowerCase();
      if (tag !== 'input' || !BUTTON_TYPES.has(type)) {
        return null;
      }
      const text = interfaceText(v);
      return text != null ? text : (String(v).trim() ? HOLE : ''); // retido: presença marcada (senão o nome viraria "Submit")
    }
    if (DESCRIPTIVE.has(n)) {
      return null;
    }
    if (n === 'data-sitekey') {
      return '[redacted]';
    }
    return identifier(n) ? '' : null; // atributo personalizado/data-*: só a presença, e só se o NOME passar
  }

  /** Elementos que CARREGAM nome de interface (o texto dentro deles pode ser metadado elegível). */
  const CARRIER_TAGS = new Set(['button', 'summary', 'label', 'legend']);
  const CARRIER_ROLES = new Set(['button', 'link', 'tab', 'menuitem', 'menuitemcheckbox', 'menuitemradio', 'checkbox', 'radio',
    'switch', 'treeitem']);

  function isCarrier(tag, role, hasHref) {
    const t = String(tag || '').toLowerCase();
    if (CARRIER_TAGS.has(t) || (t === 'a' && hasHref)) {
      return true;
    }
    return CARRIER_ROLES.has(String(role || '').trim().split(/\s+/)[0].toLowerCase());
  }

  root.NekkoraCapturePolicy = Object.freeze({
    VERSION, WITHHELD, HOLE, MAX_LEN, STRUCTURAL, IDENTIFIERS, IDREFS, NAMING, DESCRIPTIVE, URLS,
    clean, terms, vocabularyOnly, interfaceText, identifier, classes, urlTemplate, expressionSafe, attribute, isCarrier,
    title: interfaceText,
  });
})(globalThis);
