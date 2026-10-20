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

  // NEKKORA-E2E-002 (D-E2E2-12, aprovado pela usuária): marcador de gênero/plural COLADO a uma palavra não é termo —
  // "Campo(s)", "Filho(a)(s)" contam como "Campo", "Filho". Nenhum termo novo entra; o marcador sozinho não é aceito.
  const INFLECTION = /(\p{L})\((?:s|es|a|as|o|os)\)/giu;

  function uninflect(s) {
    let prev;
    let cur = s;
    do {
      prev = cur;
      cur = cur.replace(INFLECTION, '$1');
    } while (cur !== prev);
    return cur;
  }

  function terms(s) {
    return uninflect(s).replace(CAMEL, '$1 $2').replace(LETTER_DIGIT, '$1 $2').replace(DIGIT_LETTER, '$1 $2')
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

  /** NEKKORA-STUDIO-012: atributos com identificador ESCRITO PELO DESENVOLVEDOR (lista técnica aplicável). */
  const DEV_IDENTIFIERS = new Set(['id', 'name', 'data-testid', 'data-test', 'data-test-id', 'data-qa', 'data-cy']);

  function identifierTerms() {
    return root.NekkoraVocabulary && root.NekkoraVocabulary.identifierTerms ? root.NekkoraVocabulary.identifierTerms : new Set();
  }

  /**
   * NEKKORA-STUDIO-012 (nekkora-identifier-terms/1, aprovado pela usuária): id/name/data-testid do desenvolvedor. Passa
   * como em {@link identifier} ou, além disso, quando cada termo é do vocabulário, número de até 2 dígitos ou termo
   * técnico da lista fechada (ds, txt, hdn, temp…), com PELO MENOS UM termo do vocabulário. Sem supressão parcial.
   */
  function developerIdentifier(v) {
    const plain = identifier(v);
    if (plain != null || v == null) {
      return plain;
    }
    const s = String(v);
    if (!IDENT.test(s)) {
      return null;
    }
    const t = terms(s);
    if (!t.length || t.length > MAX_TERMS) {
      return null;
    }
    const words = vocab();
    const tech = identifierTerms();
    let word = false;
    for (const x of t) {
      if (words.has(x)) {
        word = true;
      } else if (!/^\d{1,2}$/.test(x) && !tech.has(x)) {
        return null;
      }
    }
    return word ? s : null;
  }

  /** Valor na posição de identificador do desenvolvedor num localizador (#id, [name=…], @id, {id=…}). */
  function developerLiteralOk(v) {
    return literalOk(v) || developerIdentifier(v) != null;
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

  /*
   * Literal de localizador (texto entre aspas, valor de atributo, id/classe CSS). P1-02 (revisão dos PRs #7): número
   * longo NÃO é estrutural quando é VALOR (//button[@data-customer='12345678900']) — só até 2 dígitos, como no texto.
   * Posições (nth-of-type(12), [12] do XPath, {nth-of-type 12}) não são literais: ficam fora desta verificação.
   */
  function literalOk(v) {
    return v === '' || /^\d{1,2}$/.test(v) || v === HOLE || vocabularyOnly(v);
  }

  // ------------------------------------------------------------------------- nomes de elemento (2ª revisão dos PRs #7)

  /** Marcador neutro de um nome de elemento retido (nunca é o nome real e nunca entra em localizador). */
  const NEUTRAL_TAG = 'custom-element';
  /** Nome de elemento personalizado (ASCII; o caractere "-" é o que o distingue de um elemento HTML). */
  const CUSTOM_TAG = /^[a-z][a-z0-9._-]*-[a-z0-9._-]*$/;

  function asciiLower(v) {
    return String(v).replace(/[A-Z]/g, (c) => c.toLowerCase());
  }

  function elementSet() {
    const v = root.NekkoraVocabulary;
    return v && v.elements ? v.elements : new Set();
  }

  /**
   * Nome de elemento transportável. O nome de um componente é escolhido pelo desenvolvedor e pode carregar dado de
   * negócio (<ficha-cliente-joao>): só atravessa se for do catálogo fechado HTML/SVG/MathML (contrato
   * nekkora-html-elements/1) ou se o nome personalizado INTEIRO for identificador do vocabulário (como id e classe).
   * Senão vai o marcador neutro "custom-element": o motor sabe que há um elemento, sem o nome.
   */
  function tagName(raw) {
    if (raw == null) {
      return null;
    }
    const s = asciiLower(raw);
    if (!s) {
      return null;
    }
    if (s !== NEUTRAL_TAG && (elementSet().has(s) || (CUSTOM_TAG.test(s) && identifier(s) != null))) {
      return s;
    }
    return NEUTRAL_TAG;
  }

  /** Nome de elemento aceito DENTRO de um localizador: "*", ou um nome que a política mantém (nunca o marcador neutro). */
  function tagOk(t) {
    const s = asciiLower(t);
    return t === '*' || (s !== NEUTRAL_TAG && tagName(s) === s);
  }

  const ATTR_NAME = /^[a-z_:][a-z0-9_:.-]*$/;
  const KNOWN_ATTRIBUTES = ['class', 'value', 'style', 'data-sitekey'];

  /** Nome de atributo aceito num localizador: nome que a política conhece, ou personalizado inteiro do vocabulário. */
  function attributeNameOk(name) {
    const s = asciiLower(name);
    if (s.length > MAX_LEN || !ATTR_NAME.test(s)) {
      return false;
    }
    return STRUCTURAL.has(s) || IDENTIFIERS.has(s) || IDREFS.has(s) || NAMING.has(s) || DESCRIPTIVE.has(s) || URLS.has(s)
      || PRESENCE.has(s) || KNOWN_ATTRIBUTES.includes(s) || identifier(s) != null;
  }

  // ------------------------------------------------------------------------- expressões (2ª revisão dos PRs #7)
  /*
   * Análise ESTRITA da expressão pronta (CSS, XPath ou notação semântica). As barreiras que só recebem a expressão
   * serializada (service worker, host, Studio) não podem depender de o produtor ter gerado o localizador certo: a
   * expressão inteira precisa ser reconhecida pela gramática, e todo nome de elemento, nome de atributo e literal
   * (depois de decodificar os escapes) precisa passar na política. O que não for analisável é recusado inteiro.
   */
  const MAX_EXPR = 4000;
  const CSS_WS = ' \t\n\r\f';
  const CSS_PSEUDO = ['first-child', 'last-child', 'only-child', 'first-of-type', 'last-of-type', 'only-of-type', 'checked',
    'disabled', 'enabled', 'required', 'optional', 'read-only', 'read-write', 'empty', 'root'];
  const CSS_NTH = ['nth-child', 'nth-last-child', 'nth-of-type', 'nth-last-of-type'];
  const NTH_ARG = /^ *(odd|even|[+-]?\d+|[+-]?\d*n( *[+-] *\d+)?) *$/;
  /** Controle, separador de linha e DEL: nenhum produtor os emite; a expressão inteira é recusada. */
  const EXPR_CONTROL = /[\u0000-\u001f\u007f\u0085\u2028\u2029]/;

  /** Seletor CSS (lista de compostos com combinadores; sem vírgula, comentário, namespace, ::, nem :not/:is/:has). */
  function cssSafe(s) {
    const n = s.length;
    let i = 0;
    const ch = (k) => (k < n ? s[k] : '');
    const isHex = (c) => /^[0-9a-fA-F]$/.test(c);
    const ws = () => {
      const st = i;
      while (i < n && CSS_WS.includes(s[i])) {
        i++;
      }
      return i > st;
    };
    function escape() { // em "\"
      i++;
      const c = ch(i);
      if (c === '' || c === '\n' || c === '\r' || c === '\f') {
        return null;
      }
      if (isHex(c)) {
        let h = '';
        while (i < n && h.length < 6 && isHex(s[i])) {
          h += s[i++];
        }
        if (ch(i) === '\r' && ch(i + 1) === '\n') {
          i += 2;
        } else if (CSS_WS.includes(ch(i)) && ch(i) !== '') {
          i++;
        }
        const cp = parseInt(h, 16);
        return cp === 0 || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF) ? '�' : String.fromCodePoint(cp);
      }
      const r = String.fromCodePoint(s.codePointAt(i));
      i += r.length;
      return r;
    }
    function identStart(k) {
      const c = ch(k);
      return c === '\\' || /^[A-Za-z_]$/.test(c) || (c !== '' && c.charCodeAt(0) >= 0x80)
        || (c === '-' && (ch(k + 1) === '-' || ch(k + 1) === '\\' || /^[A-Za-z_]$/.test(ch(k + 1)) || (ch(k + 1) !== '' && ch(k + 1).charCodeAt(0) >= 0x80)));
    }
    function ident() { // valor DECODIFICADO ou null
      if (!identStart(i)) {
        return null;
      }
      let out = '';
      while (i < n) {
        const c = s[i];
        if (c === '\\') {
          const e = escape();
          if (e == null) {
            return null;
          }
          out += e;
        } else if (/^[A-Za-z0-9_-]$/.test(c) || c.charCodeAt(0) >= 0x80) {
          out += c;
          i++;
        } else {
          break;
        }
      }
      return out;
    }
    function string() { // valor DECODIFICADO ou null
      const q = s[i++];
      let out = '';
      while (i < n) {
        const c = s[i];
        if (c === q) {
          i++;
          return out;
        }
        if (c === '\n' || c === '\r' || c === '\f') {
          return null;
        }
        if (c === '\\') {
          if (ch(i + 1) === '\n') {
            i += 2;
            continue;
          }
          const e = escape();
          if (e == null) {
            return null;
          }
          out += e;
          continue;
        }
        out += c;
        i++;
      }
      return null;
    }
    function attributeSelector() { // em "["
      i++;
      ws();
      const name = ident();
      if (name == null || !attributeNameOk(name)) {
        return false;
      }
      ws();
      if (ch(i) === ']') {
        i++;
        return true;
      }
      if (ch(i) === '=') {
        i++;
      } else if ('~|^$*'.includes(ch(i)) && ch(i) !== '' && ch(i + 1) === '=') {
        i += 2;
      } else {
        return false;
      }
      ws();
      const v = ch(i) === '"' || ch(i) === "'" ? string() : ident();
      if (v == null || !(DEV_IDENTIFIERS.has(asciiLower(name)) ? developerLiteralOk(v) : literalOk(v))) {
        return false;
      }
      ws();
      if ('iIsS'.includes(ch(i)) && ch(i) !== '') {
        i++;
        ws();
      }
      if (ch(i) !== ']') {
        return false;
      }
      i++;
      return true;
    }
    function pseudo() { // em ":"
      i++;
      if (ch(i) === ':') {
        return false;
      }
      const p = ident();
      if (p == null) {
        return false;
      }
      const name = asciiLower(p);
      if (ch(i) === '(') {
        if (!CSS_NTH.includes(name)) {
          return false;
        }
        const close = s.indexOf(')', i);
        if (close < 0 || !NTH_ARG.test(asciiLower(s.slice(i + 1, close)))) {
          return false;
        }
        i = close + 1;
        return true;
      }
      return CSS_PSEUDO.includes(name);
    }
    function compound() {
      let any = false;
      if (ch(i) === '*') {
        i++;
        any = true;
      } else if (identStart(i)) {
        const t = ident();
        if (t == null || !tagOk(t)) {
          return false;
        }
        any = true;
      }
      for (;;) {
        const c = ch(i);
        if (c === '#' || c === '.') {
          i++;
          const v = ident();
          if (v == null || !(c === '#' ? developerLiteralOk(v) : literalOk(v))) {
            return false;
          }
        } else if (c === '[') {
          if (!attributeSelector()) {
            return false;
          }
        } else if (c === ':') {
          if (!pseudo()) {
            return false;
          }
        } else {
          break;
        }
        any = true;
      }
      return any;
    }
    ws();
    if (!compound()) {
      return false;
    }
    for (;;) {
      const hadWs = ws();
      if (i >= n) {
        return true;
      }
      const c = s[i];
      if (c === '>' || c === '+' || c === '~') {
        i++;
        ws();
        if (!compound()) {
          return false;
        }
      } else if (hadWs) {
        if (!compound()) {
          return false;
        }
      } else {
        return false;
      }
    }
  }

  const XPATH_NAME = /^[A-Za-z_][A-Za-z0-9._-]*/;
  const XPATH_TEXT = ['normalize-space(.)', 'normalize-space()', 'text()'];

  /** Subconjunto de XPath 1.0 que o motor produz (caminhos, posição, @atributo, texto normalizado, concat, ancestor::, preceding-sibling::). */
  function xpathSafe(s) {
    const n = s.length;
    let i = 0;
    const ch = (k) => (k < n ? s[k] : '');
    const skip = () => {
      while (ch(i) === ' ') {
        i++;
      }
    };
    function name() {
      const m = XPATH_NAME.exec(s.slice(i));
      if (!m) {
        return null;
      }
      i += m[0].length;
      return m[0];
    }
    function nodeTest() {
      if (ch(i) === '*') {
        i++;
        return true;
      }
      const t = name();
      return t != null && tagOk(t);
    }
    function quoted() {
      const q = ch(i);
      const e = s.indexOf(q, i + 1);
      if (e < 0) {
        return null;
      }
      const v = s.slice(i + 1, e);
      i = e + 1;
      return v;
    }
    function literal() { // valor (concat: a concatenação, julgada inteira) ou null
      if (ch(i) === '"' || ch(i) === "'") {
        return quoted();
      }
      i += 'concat('.length;
      let out = '';
      for (;;) {
        skip();
        if (ch(i) !== '"' && ch(i) !== "'") {
          return null;
        }
        const v = quoted();
        if (v == null) {
          return null;
        }
        out += v;
        skip();
        if (ch(i) === ',') {
          i++;
        } else if (ch(i) === ')') {
          i++;
          return out;
        } else {
          return null;
        }
      }
    }
    function step(axis) {
      if (axis && s.startsWith('ancestor::', i)) {
        i += 'ancestor::'.length;
      } else if (axis && s.startsWith('preceding-sibling::', i)) { // NEKKORA-E2E-002: termo (dt) de uma descrição (dd)
        i += 'preceding-sibling::'.length;
      }
      if (ch(i) === '@') {
        i++;
        const a = name();
        return a != null && attributeNameOk(a);
      }
      if (!nodeTest()) {
        return false;
      }
      while (ch(i) === '[') {
        if (!predicate()) {
          return false;
        }
      }
      return true;
    }
    function steps(first) { // passos separados por / ou //
      if (first && !step(true)) {
        return false;
      }
      while (ch(i) === '/') {
        i += ch(i + 1) === '/' ? 2 : 1;
        if (!step(false)) {
          return false;
        }
      }
      return true;
    }
    // 'ok', 'lit:<valor>' (julgado no predicado), 'attr:<nome>' (só "@nome"), 'num:<dígitos>' ou null
    function operand() {
      skip();
      const c = ch(i);
      if (c === '"' || c === "'" || s.startsWith('concat(', i)) {
        const v = literal();
        return v != null && developerLiteralOk(v) ? 'lit:' + v : null;
      }
      if (c === '@') {
        const m = XPATH_NAME.exec(s.slice(i + 1));
        const after = m ? ch(i + 1 + m[0].length) : '';
        if (m && after !== '/' && after !== '[' && attributeNameOk(m[0])) {
          i += 1 + m[0].length;
          return 'attr:' + asciiLower(m[0]);
        }
      }
      if (/^[0-9]$/.test(c)) {
        const m = /^\d+/.exec(s.slice(i));
        i += m[0].length;
        return 'num:' + m[0];
      }
      for (const fn of XPATH_TEXT) {
        if (s.startsWith(fn, i)) {
          i += fn.length;
          return 'ok';
        }
      }
      if (c === '/') {
        return steps(false) ? 'ok' : null;
      }
      return steps(true) ? 'ok' : null;
    }
    function predicate() { // em "["
      i++;
      const a = operand();
      if (a == null) {
        return false;
      }
      skip();
      if (ch(i) === '=') {
        i++;
        const b = operand();
        if (b == null) {
          return false;
        }
        for (const [x, other] of [[a, b], [b, a]]) {
          if (x.startsWith('num:') && !literalOk(x.slice(4))) {
            return false; // número comparado é VALOR (P1-02)
          }
          // literal: vocabulário; a lista técnica só quando comparado a @id/@name/@data-testid (STUDIO-012)
          if (x.startsWith('lit:') && !literalOk(x.slice(4)) && !(DEV_IDENTIFIERS.has(other.slice(5)) && other.startsWith('attr:'))) {
            return false;
          }
        }
      } else if (a.startsWith('lit:')) {
        return false;
      }
      skip();
      if (ch(i) !== ']') {
        return false;
      }
      i++;
      return true;
    }
    if (ch(0) !== '/') {
      return false;
    }
    while (i < n) {
      if (ch(i) !== '/') {
        return false;
      }
      i += ch(i + 1) === '/' ? 2 : 1;
      if (!nodeTest()) {
        return false;
      }
      while (ch(i) === '[') {
        if (!predicate()) {
          return false;
        }
      }
    }
    return true;
  }

  const SEM_TAG = /^[A-Za-z][A-Za-z0-9._-]*/;
  const SEM_KEYS = ['text', 'label', 'name', 'role'];

  function semanticPredicate(p) {
    if (p.includes('{')) {
      return false;
    }
    if (/^nth-(of-type|child) \d+$/.test(p)) {
      return true;
    }
    let m = /^has child <([^>]*)> "(.*)"$/.exec(p);
    if (m) {
      return tagOk(m[1]) && literalOk(m[2]);
    }
    m = /^has (\S+)$/.exec(p);
    if (m) {
      return attributeNameOk(m[1]);
    }
    m = /^([A-Za-z_:][A-Za-z0-9_:.-]*)=(.*)$/.exec(p);
    if (m) {
      let v = m[2];
      const quoted = v.length >= 2 && v.startsWith('"') && v.endsWith('"');
      if (quoted) {
        v = v.slice(1, -1);
      }
      // sem aspas = valor de ATRIBUTO ({id=…}, {name=…}); name="…" entre aspas é o NOME ACESSÍVEL (texto de interface)
      const developer = !quoted && DEV_IDENTIFIERS.has(m[1]);
      return (SEM_KEYS.includes(m[1]) || attributeNameOk(m[1])) && (developer ? developerLiteralOk(v) : literalOk(v));
    }
    return false;
  }

  /** Notação semântica do motor: "tag {predicado} … >> tag {…}". Valor sem aspas é julgado INTEIRO, até o "}". */
  function semanticSafe(s) {
    const n = s.length;
    let i = 0;
    function sstep() {
      if (s[i] === '*') {
        i++;
      } else {
        const m = SEM_TAG.exec(s.slice(i));
        if (!m || !tagOk(m[0])) {
          return false;
        }
        i += m[0].length;
      }
      while (s.startsWith(' {', i)) {
        const e = s.indexOf('}', i + 2);
        if (e < 0 || !semanticPredicate(s.slice(i + 2, e))) {
          return false;
        }
        i = e + 1;
      }
      return true;
    }
    if (!sstep()) {
      return false;
    }
    while (i < n) {
      if (!s.startsWith(' >> ', i)) {
        return false;
      }
      i += 4;
      if (!sstep()) {
        return false;
      }
    }
    return true;
  }

  /** Uma expressão de localizador só é transportável se for INTEIRAMENTE analisada e tudo nela passar na política. */
  function expressionSafe(expr) {
    if (expr == null || expr === '') {
      return true;
    }
    const s = String(expr);
    if (s.length > MAX_EXPR || EXPR_CONTROL.test(s)) {
      return false;
    }
    return cssSafe(s) || xpathSafe(s) || semanticSafe(s);
  }

  /*
   * Atributos estruturais (CHROME-SECURITY-001, revisão dos PRs #7, P1-01): cada um tem uma regra FECHADA no contrato
   * contracts/privacy/structural-attributes.json (embutido em ui-vocabulary.js). Valor fora da regra => só a PRESENÇA.
   * A forma técnica do valor NÃO basta: role="cliente-joao" ou autocomplete="joao oliveira" não atravessam.
   */
  function structuralSpec() {
    const v = root.NekkoraVocabulary;
    return v && v.structural ? v.structural : { rules: {}, languages: [], scripts: [] };
  }
  const STRUCTURAL = { has: (n) => Object.prototype.hasOwnProperty.call(structuralSpec().rules, n) };

  function inList(list, value) {
    const words = list.split(' ');
    return words.includes(value) || words.some((w) => w.toLowerCase() === value.toLowerCase());
  }

  function langOk(s, spec) {
    const parts = s.split('-');
    if (parts.length > 3 || !spec.languages.includes(parts[0].toLowerCase())) {
      return false;
    }
    let k = 1;
    if (k < parts.length && spec.scripts.some((x) => x.toLowerCase() === parts[k].toLowerCase())) {
      k++;
    }
    if (k < parts.length && /^([A-Za-z]{2}|\d{3})$/.test(parts[k])) {
      k++;
    }
    return k === parts.length;
  }

  function ruleOk(kind, arg, s, name, spec) {
    switch (kind) {
      case 'boolean':
        return s === '' || ['true', 'false', name].includes(s.toLowerCase());
      case 'enum':
        return s !== '' && inList(arg, s);
      case 'tokens': {
        const t = s.split(/[\s,]+/).filter(Boolean);
        return t.length > 0 && t.every((x) => inList(arg, x));
      }
      case 'integer':
        return new RegExp('^-?\\d{1,' + Number(arg) + '}$').test(s);
      case 'decimal':
        return new RegExp('^-?\\d{1,' + Number(arg) + '}(\\.\\d{1,4})?$').test(s);
      case 'dimension':
        return new RegExp('^\\d{1,' + Number(arg) + '}(px|%)?$').test(s);
      case 'lang':
        return langOk(s, spec);
      default:
        return false; // presence e regras desconhecidas: nunca o valor
    }
  }

  /** Valor de atributo estrutural pela regra do atributo; fora dela, só a presença (""). */
  function structuralValue(name, v) {
    const spec = structuralSpec();
    const rule = spec.rules[name];
    const s = String(v).trim();
    if (!rule || s.length > MAX_LEN * 4) {
      return '';
    }
    for (const alt of rule.split(';')) {
      const i = alt.indexOf(':');
      if (ruleOk(i < 0 ? alt : alt.slice(0, i), i < 0 ? '' : alt.slice(i + 1), s, name, spec)) {
        return s;
      }
    }
    return '';
  }
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
      return structuralValue(n, v);
    }
    if (IDENTIFIERS.has(n)) {
      return DEV_IDENTIFIERS.has(n) ? developerIdentifier(v) : identifier(v);
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

  /**
   * NEKKORA-E2E-002 (D-E2E2-01, aprovado pela usuária): o CABEÇALHO DE LINHA (th[scope=row], role=rowheader ou th numa
   * linha que também tem td) e o TERMO de uma lista de definição (dt) também carregam rótulo de interface — é o que
   * nomeia o dado da linha ("Nascimento"). Mesma regra dos demais portadores: o texto inteiro precisa passar no
   * vocabulário fechado; cabeçalho de COLUNA continua fora.
   */
  function isRowHeader(tag, role, scope, rowHasDataCell) {
    const t = String(tag || '').toLowerCase();
    const r = String(role || '').trim().split(/\s+/)[0].toLowerCase();
    if (t === 'dt' || r === 'rowheader') {
      return true;
    }
    return t === 'th' && (String(scope || '').trim().toLowerCase() === 'row' || rowHasDataCell === true);
  }

  root.NekkoraCapturePolicy = Object.freeze({
    VERSION, WITHHELD, HOLE, MAX_LEN, STRUCTURAL, IDENTIFIERS, IDREFS, NAMING, DESCRIPTIVE, URLS,
    NEUTRAL_TAG, clean, terms, vocabularyOnly, interfaceText, identifier, classes, urlTemplate, expressionSafe, attribute, isCarrier, isRowHeader,
    tagName, title: interfaceText, DEV_IDENTIFIERS, developerIdentifier,
  });
})(globalThis);
