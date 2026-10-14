/*
 * Política de privacidade de URLs e textos (NEKKORA-REAL-001), aplicada NO NAVEGADOR antes de qualquer dado sair da
 * página ou do service worker. O motor Java reaplica a mesma política (com.nekkora.extension.core.privacy) como
 * segunda barreira. A política é idempotente: aplicar duas vezes produz o mesmo resultado.
 *
 * URLs (http/https):
 *   - credenciais do endereço (usuário:senha@) são removidas;
 *   - segmentos de caminho com aparência de identificador/segredo (UUID, hexadecimal longo, JWT, opaco longo,
 *     sequência longa de dígitos, e-mail) viram ':redacted'; parâmetros de matriz (;jsessionid=…) têm o valor redigido;
 *   - parâmetros de consulta: o NOME é mantido; o VALOR só é mantido quando o nome não é sensível e o valor é curto,
 *     sem dados pessoais e sem aparência de segredo — senão 'REDACTED';
 *   - fragmento: rotas (#/…) seguem as regras de caminho/consulta; pares chave=valor seguem as regras de consulta;
 *     âncoras curtas são mantidas.
 * Outros esquemas: about:blank/about:srcdoc mantidos; mailto:/tel:/sms: e data:/blob: têm o conteúdo omitido.
 * A forma original (absoluta, relativa à raiz ou relativa) é preservada para que localizadores continuem válidos
 * quando nada precisa ser redigido.
 *
 * Textos: e-mails, CPF, CNPJ, cartões (Luhn), telefones em formato explícito e tokens JWT/Bearer são mascarados.
 *
 * Atributos (revisão do PR #3, P1): além dos atributos de URL e de texto, TODO atributo não estrutural (data-*,
 * personalizados) é tratado — nome sensível ⇒ valor redigido; URL inteira ou embutida ⇒ sanitizada; pares chave=valor
 * ou JSON com chave sensível ⇒ valor redigido; segredo longo ⇒ redigido; dados pessoais ⇒ mascarados.
 */
(function (root) {
  if (root.NekkoraPrivacy) {
    return;
  }
  const REDACTED = 'REDACTED';
  const SEGMENT = ':redacted';
  const MAX_URL = 2000;
  const MAX_VALUE = 64;

  const SENSITIVE_WORDS = new Set([
    'token', 'auth', 'authorization', 'bearer', 'jwt', 'session', 'sess', 'sessid', 'sid', 'jsessionid', 'phpsessid',
    'aspsessionid', 'cookie', 'key', 'apikey', 'secret', 'password', 'passwd', 'pwd', 'pass', 'senha', 'signature',
    'sig', 'hmac', 'hash', 'code', 'state', 'nonce', 'ticket', 'otp', 'pin', 'credential', 'credentials', 'cred',
    'saml', 'samlrequest', 'samlresponse', 'relaystate', 'assertion', 'email', 'mail', 'cpf', 'cnpj', 'rg', 'ssn',
    'phone', 'telefone', 'fone', 'celular', 'mobile', 'tel', 'user', 'username', 'usuario', 'uid', 'login', 'account',
    'conta', 'card', 'cartao', 'cvv', 'cvc', 'iban', 'redirect', 'return', 'returnurl', 'next', 'callback', 'continue',
    'goto', 'url', 'uri', 'ref', 'expires', 'policy', 'access', 'refresh', 'verifier', 'challenge', 'oauth',
    'credential', 'x', 'amz', 'goog',
  ]);
  const SENSITIVE_PARTS = ['token', 'secret', 'passw', 'senha', 'session', 'sessid', 'auth', 'apikey', 'signature',
    'jwt', 'bearer', 'cookie', 'credential', 'sitekey'];

  /** Nome de parâmetro sensível: alguma palavra (separadores ou camelCase) ou trecho conhecido. */
  function sensitiveKey(name) {
    const k = String(name || '');
    const lower = k.toLowerCase();
    if (SENSITIVE_PARTS.some((p) => lower.includes(p))) {
      return true;
    }
    const words = k.replace(/([a-z0-9])([A-Z])/g, '$1 $2').toLowerCase().split(/[^a-z0-9]+/).filter(Boolean);
    return words.some((w) => SENSITIVE_WORDS.has(w));
  }

  const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
  const HEX = /^[0-9a-f]{16,}$/i;
  const JWT = /^[A-Za-z0-9_-]{6,}\.[A-Za-z0-9_-]{6,}\.[A-Za-z0-9_-]{6,}$/;
  const OPAQUE = /^[A-Za-z0-9_\-+/=~.%]{24,}$/;
  const DIGITS = /\d{6,}/;

  /** Valor com aparência de identificador ou segredo. */
  function looksSecret(s) {
    if (!s) {
      return false;
    }
    if (s.includes('@') || UUID.test(s) || HEX.test(s) || JWT.test(s) || DIGITS.test(s)) {
      return true;
    }
    if (OPAQUE.test(s)) {
      const digits = (s.match(/\d/g) || []).length;
      const letters = (s.match(/[A-Za-z]/g) || []).length;
      return digits >= 3 && letters >= 3;
    }
    return false;
  }

  function decode(s) {
    try {
      return decodeURIComponent(String(s).replace(/\+/g, ' '));
    } catch (e) {
      return String(s);
    }
  }

  function sanitizeSegment(seg) {
    if (!seg || seg === SEGMENT) {
      return seg;
    }
    let base = seg;
    let matrix = '';
    const semi = seg.indexOf(';');
    if (semi >= 0) {
      base = seg.slice(0, semi);
      matrix = ';' + seg.slice(semi + 1).split(';').map((p) => {
        const eq = p.indexOf('=');
        return eq < 0 ? p : p.slice(0, eq) + '=' + REDACTED;
      }).join(';');
    }
    const d = decode(base);
    if (looksSecret(d) || maskText(d) !== d) {
      base = SEGMENT;
    }
    return base + matrix;
  }

  function sanitizePath(path) {
    if (!path) {
      return path;
    }
    return path.split('/').map(sanitizeSegment).join('/');
  }

  function keepValue(key, value) {
    if (value === REDACTED || value === '') {
      return true;
    }
    if (sensitiveKey(decode(key))) {
      return false;
    }
    const d = decode(value);
    return d.length <= MAX_VALUE && !looksSecret(d) && maskText(d) === d;
  }

  function sanitizeQuery(q) {
    if (!q) {
      return q;
    }
    return q.split('&').map((pair) => {
      if (!pair) {
        return pair;
      }
      const eq = pair.indexOf('=');
      const k = eq < 0 ? pair : pair.slice(0, eq);
      const key = looksSecret(decode(k)) ? SEGMENT : k.slice(0, 100);
      if (eq < 0) {
        return key;
      }
      const v = pair.slice(eq + 1);
      return key + '=' + (key !== SEGMENT && keepValue(k, v) ? v : REDACTED);
    }).join('&');
  }

  /** Pares chave=valor do fragmento mantidos sem redação (tipo de frame de widgets, ex.: hCaptcha #frame=challenge). */
  const FRAGMENT_KEPT = new Set(['frame']);

  function sanitizeFragment(f) {
    if (!f) {
      return f;
    }
    if (f.startsWith('/') || f.startsWith('!/')) {
      const q = f.indexOf('?');
      return q < 0 ? sanitizePath(f) : sanitizePath(f.slice(0, q)) + '?' + sanitizeQuery(f.slice(q + 1));
    }
    if (f.includes('=')) {
      return f.split('&').map((pair) => {
        const eq = pair.indexOf('=');
        if (eq >= 0 && FRAGMENT_KEPT.has(pair.slice(0, eq)) && pair.length <= 40) {
          return pair;
        }
        return sanitizeQuery(pair);
      }).join('&');
    }
    const d = decode(f);
    return d.length > 120 || looksSecret(d) || maskText(d) !== d ? SEGMENT : f;
  }

  const SCHEME = /^([a-zA-Z][a-zA-Z0-9+.-]*):/;
  const AUTHORITY = /^((?:[a-zA-Z][a-zA-Z0-9+.-]*:)?\/\/)([^/?#]*)/;

  function special(scheme, s) {
    switch (scheme) {
      case 'about': {
        const m = /^about:([^?#]*)/i.exec(s);
        return 'about:' + (m ? m[1] : '');
      }
      case 'javascript':
        return /^javascript:\s*(void\s*\(\s*0\s*\)\s*;?|;)?\s*$/i.test(s) ? s : 'javascript:[omitido]';
      case 'mailto':
      case 'tel':
      case 'sms':
      case 'callto':
        return scheme + ':[redacted]';
      case 'data': {
        const m = /^data:([^,;]*)/i.exec(s);
        return 'data:' + (m ? m[1] : '') + ',[omitido]';
      }
      default:
        return scheme + ':[omitido]';
    }
  }

  /**
   * URL sanitizada preservando a forma original. Nulo/vazio devolvidos como estão.
   * @param {string} raw
   */
  function sanitizeUrl(raw) {
    if (raw == null) {
      return raw;
    }
    const s = String(raw).trim();
    if (!s) {
      return s;
    }
    const sm = SCHEME.exec(s);
    if (sm) {
      const scheme = sm[1].toLowerCase();
      if (scheme !== 'http' && scheme !== 'https') {
        return special(scheme, s);
      }
    }
    let prefix = '';
    let rest = s;
    const am = AUTHORITY.exec(s);
    if (am) {
      prefix = am[1] + am[2].replace(/^[^@]*@/, ''); // sem usuário:senha
      rest = s.slice(am[0].length);
    }
    let frag = null;
    const h = rest.indexOf('#');
    if (h >= 0) {
      frag = rest.slice(h + 1);
      rest = rest.slice(0, h);
    }
    let query = null;
    const q = rest.indexOf('?');
    if (q >= 0) {
      query = rest.slice(q + 1);
      rest = rest.slice(0, q);
    }
    let out = prefix + sanitizePath(rest);
    if (query !== null) {
      out += '?' + sanitizeQuery(query);
    }
    if (frag !== null) {
      out += '#' + sanitizeFragment(frag);
    }
    return out.length > MAX_URL ? out.slice(0, MAX_URL) : out;
  }

  /** Origem + caminho sanitizado (sem consulta nem fragmento) — usado em recursos e metadados de frames. */
  function originAndPath(raw, base) {
    try {
      const u = new URL(raw, base);
      if (u.protocol === 'about:') {
        return 'about:' + u.pathname;
      }
      if (u.protocol !== 'http:' && u.protocol !== 'https:') {
        return special(u.protocol.slice(0, -1), u.href);
      }
      return (u.origin + sanitizePath(u.pathname)).slice(0, 300);
    } catch (e) {
      return null;
    }
  }

  // ------------------------------------------------------------------------------------------- textos

  const EMAIL = /[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}/g;
  const CPF = /\b\d{3}\.\d{3}\.\d{3}-\d{2}\b/g;
  const CNPJ = /\b\d{2}\.\d{3}\.\d{3}\/\d{4}-\d{2}\b/g;
  const CARD = /\b\d(?:[ -]?\d){12,18}\b/g;
  const PHONE = /(?:\+\d{1,3}[\s.-]?)?\(\d{2,3}\)\s?\d{4,5}[\s.-]?\d{4}\b|\+\d{1,3}[\s.-]?\d{2,3}[\s.-]?\d{4,5}[\s.-]?\d{4}\b/g;
  const JWT_TEXT = /\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{5,}/g;
  const BEARER = /\b(Bearer|Basic)\s+[A-Za-z0-9._~+/=-]{16,}/g;

  function luhn(digits) {
    let sum = 0;
    let alt = false;
    for (let i = digits.length - 1; i >= 0; i--) {
      let n = digits.charCodeAt(i) - 48;
      if (alt) {
        n *= 2;
        if (n > 9) {
          n -= 9;
        }
      }
      sum += n;
      alt = !alt;
    }
    return sum % 10 === 0;
  }

  /** Mascara dados pessoais e segredos com formato reconhecível. Texto sem '@', dígitos ou 'eyJ' volta intacto. */
  function maskText(s) {
    if (!s || typeof s !== 'string' || !/[@\d]|eyJ|Bearer|Basic/.test(s)) {
      return s;
    }
    let out = s;
    if (out.includes('@')) {
      out = out.replace(EMAIL, '[email]');
    }
    out = out.replace(JWT_TEXT, '[token]').replace(BEARER, '$1 [token]');
    out = out.replace(CNPJ, '[cnpj]').replace(CPF, '[cpf]');
    out = out.replace(CARD, (m) => {
      const d = m.replace(/\D/g, '');
      return d.length >= 13 && d.length <= 19 && luhn(d) ? '[cartão]' : m;
    });
    out = out.replace(PHONE, '[telefone]');
    return out;
  }

  /** Valor produzido por esta política (não deve ser usado como localizador: não corresponde à página). */
  function isMasked(v) {
    return typeof v === 'string' && (v.includes(REDACTED) || v.includes(SEGMENT) || v.includes('[omitido]') || v.includes('[redacted]')
      || /\[(email|cpf|cnpj|cartão|telefone|token)\]/.test(v));
  }

  // ---------------------------------------------------------------------------------------- atributos

  /** Atributos que carregam URL (forma original preservada). */
  const URL_ATTRIBUTES = new Set(['href', 'src', 'action', 'formaction', 'poster', 'data', 'cite', 'background', 'longdesc',
    'xlink:href', 'manifest', 'codebase', 'data-nekkora-simulated-src']);
  const SRCSET_ATTRIBUTES = new Set(['srcset', 'imagesrcset']);
  /** Atributos de TEXTO livre (dados pessoais mascarados). */
  const TEXT_ATTRIBUTES = new Set(['title', 'alt', 'aria-label', 'aria-description', 'aria-valuetext', 'aria-roledescription',
    'aria-placeholder', 'label', 'content', 'value', 'summary', 'abbr', 'download']);
  /** Atributos ESTRUTURAIS do HTML/SVG: vocabulário fechado, ids ou números — mantidos. */
  const STRUCTURAL_ATTRIBUTES = new Set(['id', 'class', 'name', 'type', 'role', 'for', 'tabindex', 'lang', 'dir', 'hidden',
    'disabled', 'checked', 'selected', 'readonly', 'required', 'multiple', 'rel', 'target', 'method', 'enctype',
    'autocomplete', 'inputmode', 'enterkeyhint', 'maxlength', 'minlength', 'min', 'max', 'step', 'pattern', 'size', 'rows',
    'cols', 'colspan', 'rowspan', 'scope', 'headers', 'width', 'height', 'slot', 'part', 'is', 'draggable',
    'contenteditable', 'spellcheck', 'translate', 'accesskey', 'sandbox', 'allow', 'loading', 'decoding',
    'referrerpolicy', 'crossorigin', 'charset', 'xmlns', 'open', 'wrap', 'form', 'list', 'accept', 'autofocus',
    'novalidate', 'start', 'reversed', 'span', 'shape', 'coords', 'media', 'sizes', 'srclang', 'kind', 'default',
    'async', 'defer', 'nomodule', 'popover', 'popovertarget', 'popovertargetaction', 'inert', 'datetime',
    'viewbox', 'd', 'points', 'transform', 'fill', 'stroke', 'stroke-width', 'cx', 'cy', 'r', 'rx', 'ry', 'x', 'y',
    'x1', 'x2', 'y1', 'y2', 'focusable', 'preserveaspectratio', 'version']);
  /**
   * Palavras que tornam o NOME de um atributo sensível (ex.: data-password, data-api-key, data-reset-token). Mais
   * estreita que a lista de parâmetros de URL: palavras genéricas de interface (state, code, next, url…) ficam de fora
   * para não apagar atributos comuns de componentes (ex.: data-state="open").
   */
  const ATTRIBUTE_SENSITIVE_WORDS = new Set(['token', 'auth', 'authorization', 'bearer', 'jwt', 'session', 'sess', 'sessid',
    'sid', 'jsessionid', 'phpsessid', 'cookie', 'key', 'apikey', 'secret', 'password', 'passwd', 'pwd', 'pass', 'senha',
    'signature', 'sig', 'hmac', 'otp', 'pin', 'credential', 'credentials', 'cred', 'nonce', 'cpf', 'cnpj', 'rg', 'ssn',
    'email', 'mail', 'phone', 'telefone', 'fone', 'celular', 'mobile', 'tel', 'cvv', 'cvc', 'card', 'cartao', 'iban',
    'user', 'username', 'usuario', 'uid', 'login', 'account', 'conta', 'ticket', 'saml', 'private', 'privatekey']);
  const URL_VALUE = /^\s*(?:(?:https?|mailto|tel|sms|javascript|data|blob|ftp|file|wss?|intent):|\/\/|\/|\.\.?\/|\?)\S*$/i;
  const EMBEDDED_URL = /(?:\bhttps?:\/\/|(?<![\w:])\/\/)[^\s"'<>()]+/gi;
  const QUOTED_PAIR = /(["'])([A-Za-z_][\w.-]*)\1(\s*[:=]\s*)(["'])((?:(?!\4).)*)\4/g;
  const UNQUOTED_PAIR = /(["'])([A-Za-z_][\w.-]*)\1(\s*:\s*)(-?[\w.+-]+)/g;
  const QUERY_VALUE = /^[^\s=&?#]+=[^\s&]*(?:&[^\s=&]+=[^\s&]*)*$/;

  /** Nome de atributo sensível (prefixos data-/aria- ignorados; palavras por separadores e camelCase). */
  function sensitiveAttributeName(name) {
    const k = String(name || '').replace(/^(data|aria)-/i, '');
    const lower = k.toLowerCase();
    if (SENSITIVE_PARTS.some((p) => lower.includes(p))) {
      return true;
    }
    const words = k.replace(/([a-z0-9])([A-Z])/g, '$1 $2').toLowerCase().split(/[^a-z0-9]+/).filter(Boolean);
    return words.some((w) => ATTRIBUTE_SENSITIVE_WORDS.has(w));
  }

  /** Atributo livre (data-*, personalizados, placeholder): nome, URLs no valor, pares chave=valor, segredos, PII. */
  function sanitizeFree(name, v, mask) {
    if (name !== 'placeholder' && sensitiveAttributeName(name)) {
      return '[redacted]';
    }
    if (v.length >= 16 && looksSecret(v.trim())) {
      return '[redacted]';
    }
    if (URL_VALUE.test(v)) {
      return sanitizeUrl(v);
    }
    let out = v.replace(EMBEDDED_URL, (u) => sanitizeUrl(u));
    if (QUERY_VALUE.test(out)) {
      out = sanitizeQuery(out);
    }
    out = out.replace(QUOTED_PAIR, (m, q, key, sep, q2, val) => (val && val !== REDACTED && sensitiveKey(key) ? q + key + q + sep + q2 + REDACTED + q2 : m));
    out = out.replace(UNQUOTED_PAIR, (m, q, key, sep, val) => (val !== REDACTED && sensitiveKey(key) && !/^(true|false|null)$/.test(val) ? q + key + q + sep + REDACTED : m));
    return mask ? maskText(out) : out;
  }

  /**
   * Valor de atributo sob a política (espelho exato de AttributePrivacy no motor Java). Casos tratados ANTES pelo
   * content script: style e data-nekkora-* omitidos, on* substituídos, data-sitekey redigido, value não permitido
   * omitido, src de iframe/script com todos os valores redigidos.
   */
  function sanitizeAttribute(name, value) {
    if (value == null || value === '') {
      return value;
    }
    const n = String(name).toLowerCase();
    const v = String(value);
    if (URL_ATTRIBUTES.has(n)) {
      return sanitizeUrl(v);
    }
    if (n === 'ping') {
      return v.split(/\s+/).map(sanitizeUrl).join(' ');
    }
    if (SRCSET_ATTRIBUTES.has(n)) {
      return v.split(',').map((part) => {
        const t = part.trim();
        const sp = t.search(/\s/);
        return sp < 0 ? sanitizeUrl(t) : sanitizeUrl(t.slice(0, sp)) + t.slice(sp);
      }).join(', ');
    }
    if (TEXT_ATTRIBUTES.has(n)) {
      return maskText(v);
    }
    if (STRUCTURAL_ATTRIBUTES.has(n) || (n.startsWith('aria-') && !sensitiveAttributeName(n))) {
      return v;
    }
    return sanitizeFree(n, v, n !== 'placeholder');
  }

  const NekkoraPrivacy = { sanitizeUrl, originAndPath, maskText, isMasked, sensitiveKey, looksSecret, sanitizeAttribute,
    sensitiveAttributeName, REDACTED, SEGMENT, version: 'nekkora-privacy/1' };
  root.NekkoraPrivacy = NekkoraPrivacy;
  if (typeof module !== 'undefined' && module.exports) {
    module.exports = NekkoraPrivacy;
  }
})(globalThis);
