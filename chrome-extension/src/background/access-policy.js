/*
 * Política CENTRAL de acesso a páginas (NEKKORA-REAL-001). Todos os componentes do service worker (inspeção,
 * sessão de captura, frames, diagnóstico, eventos dos content scripts e navegação) decidem por aqui — não há
 * outra lista de origens no código.
 *
 *   LAB          http(s) em 127.0.0.1/localhost, qualquer porta (host_permissions do manifesto, laboratório local)
 *   AUTHORIZED   http(s) em origem que o USUÁRIO autorizou (optional_host_permissions concedidas pelo Chrome:
 *                por site ou ampla, sempre com consentimento explícito; revogações são respeitadas)
 *   recusado     BROWSER_INTERNAL (chrome:, edge:, devtools:, chrome-extension:, view-source:, about: …),
 *                BROWSER_RESTRICTED (Chrome Web Store: o Chrome não permite scripts de extensões),
 *                UNSUPPORTED_SCHEME (file:, data:, blob:, ftp:, javascript: …), PERMISSION_REQUIRED (http/https sem
 *                autorização), INVALID_URL.
 *
 * A decisão é síncrona sobre um espelho das permissões ATIVAS do Chrome (chrome.permissions.getAll), atualizado em
 * permissions.onAdded/onRemoved. Nenhuma limitação do navegador é contornada: a injeção de scripts continua
 * sujeita às permissões reais; esta política apenas recusa cedo e explica o motivo.
 */
import { HostError } from './native-client.js';

const LAB_HOSTS = new Set(['127.0.0.1', 'localhost']);
const INTERNAL_SCHEMES = new Set(['chrome', 'chrome-untrusted', 'edge', 'brave', 'opera', 'vivaldi', 'devtools', 'chrome-extension',
  'chrome-search', 'view-source', 'about', 'chrome-error', 'chrome-native', 'extension']);
const RESTRICTED_HOSTS = [/^chromewebstore\.google\.com$/i, /^chrome\.google\.com$/i]; // loja: scripts de extensões são bloqueados

let granted = []; // padrões de origem ativos (ex.: "https://en.wikipedia.org/*", "https://*/*")
let loaded = false;
const listeners = { added: [], removed: [] };

export const LAB_PATTERNS = ['http://127.0.0.1/*', 'http://localhost/*'];
/** Autorização ampla (concedida e revogada como par pelo painel). */
export const BROAD_PATTERNS = ['https://*/*', 'http://*/*'];

/** Carrega o espelho das permissões ativas (chamado na inicialização do service worker). */
export async function load() {
  try {
    const all = await chrome.permissions.getAll();
    granted = (all && all.origins) || [];
  } catch (e) {
    granted = [];
  }
  loaded = true;
  return granted.slice();
}

export function isLoaded() {
  return loaded;
}

/** Padrões concedidos ALÉM do laboratório (origens autorizadas pelo usuário). */
export function authorizedPatterns() {
  return granted.filter((p) => !LAB_PATTERNS.includes(p));
}

export function onAdded(fn) {
  listeners.added.push(fn);
}

export function onRemoved(fn) {
  listeners.removed.push(fn);
}

/** Correspondência de padrão de correspondência do Chrome (esquema, host com curinga e caminho). */
export function matchesPattern(url, pattern) {
  let u;
  try {
    u = new URL(url);
  } catch (e) {
    return false;
  }
  if (pattern === '<all_urls>') {
    return u.protocol === 'http:' || u.protocol === 'https:';
  }
  const m = /^(\*|https?):\/\/([^/]+)(\/.*)$/.exec(pattern);
  if (!m) {
    return false;
  }
  const [, scheme, hostPattern, pathPattern] = m;
  const proto = u.protocol.slice(0, -1);
  if (scheme === '*' ? !(proto === 'http' || proto === 'https') : scheme !== proto) {
    return false;
  }
  const host = u.hostname.toLowerCase();
  const hp = hostPattern.replace(/:\d+$/, '').toLowerCase(); // porta não faz parte do padrão de host
  if (hp !== '*') {
    if (hp.startsWith('*.')) {
      const base = hp.slice(2);
      if (host !== base && !host.endsWith('.' + base)) {
        return false;
      }
    } else if (host !== hp) {
      return false;
    }
  }
  const re = new RegExp('^' + pathPattern.split('*').map((s) => s.replace(/[.+?^${}()|[\]\\]/g, '\\$&')).join('.*') + '$');
  return re.test(u.pathname + u.search);
}

/**
 * Classifica uma URL.
 * @returns {{allowed: boolean, kind: 'LAB'|'AUTHORIZED'|null, reason: string|null, origin: string|null, pattern: string|null}}
 */
export function evaluate(url) {
  if (!url) {
    return { allowed: false, kind: null, reason: 'URL_UNAVAILABLE', origin: null, pattern: null };
  }
  let u;
  try {
    u = new URL(url);
  } catch (e) {
    return { allowed: false, kind: null, reason: 'INVALID_URL', origin: null, pattern: null };
  }
  const scheme = u.protocol.slice(0, -1).toLowerCase();
  if (INTERNAL_SCHEMES.has(scheme)) {
    return { allowed: false, kind: null, reason: 'BROWSER_INTERNAL', origin: null, pattern: null };
  }
  if (scheme !== 'http' && scheme !== 'https') {
    return { allowed: false, kind: null, reason: 'UNSUPPORTED_SCHEME', origin: null, pattern: null };
  }
  const origin = u.origin;
  const pattern = scheme + '://' + u.hostname + '/*';
  if (RESTRICTED_HOSTS.some((r) => r.test(u.hostname)) && (u.hostname !== 'chrome.google.com' || u.pathname.startsWith('/webstore'))) {
    return { allowed: false, kind: null, reason: 'BROWSER_RESTRICTED', origin, pattern: null };
  }
  if (LAB_HOSTS.has(u.hostname)) {
    return { allowed: true, kind: 'LAB', reason: null, origin, pattern };
  }
  const match = granted.find((p) => !LAB_PATTERNS.includes(p) && matchesPattern(url, p));
  if (match) {
    return { allowed: true, kind: 'AUTHORIZED', reason: null, origin, pattern: match };
  }
  return { allowed: false, kind: null, reason: 'PERMISSION_REQUIRED', origin, pattern };
}

export function isAllowedUrl(url) {
  return evaluate(url).allowed;
}

const MESSAGES = {
  PERMISSION_REQUIRED: 'Este site não está autorizado. Autorize-o no painel ("Autorizar este site") para inspecioná-lo.',
  BROWSER_INTERNAL: 'Páginas internas do navegador não podem ser inspecionadas.',
  BROWSER_RESTRICTED: 'O Chrome não permite que extensões atuem nesta página.',
  UNSUPPORTED_SCHEME: 'Somente páginas http:// e https:// podem ser inspecionadas.',
  INVALID_URL: 'Endereço da página inválido.',
  URL_UNAVAILABLE: 'Endereço da página indisponível (o Chrome não informou a página desta aba).',
};

/** Erro explícito (código mantido desde a CHROME-001; motivo detalhado em {@code reason}). */
export function deniedError(v) {
  const e = new HostError('ORIGIN_NOT_ALLOWED', MESSAGES[v.reason] || 'Origem não permitida');
  e.reason = v.reason;
  e.origin = v.origin;
  return e;
}

/**
 * URL do documento principal de uma aba. Sem a permissão "tabs", o Chrome só informa tab.url para origens com
 * acesso; para as demais usa-se o metadado do frame principal (webNavigation), apenas para decidir e explicar —
 * a URL não é armazenada.
 */
export async function tabUrl(tabId) {
  let tab;
  try {
    tab = await chrome.tabs.get(tabId);
  } catch (e) {
    throw new HostError('TAB_NOT_FOUND', 'Aba inexistente: ' + tabId);
  }
  if (tab && tab.url) {
    return tab.url;
  }
  try {
    const f = await chrome.webNavigation.getFrame({ tabId, frameId: 0 });
    return (f && f.url) || (tab && tab.pendingUrl) || null;
  } catch (e) {
    return (tab && tab.pendingUrl) || null;
  }
}

/** Avaliação da aba (sem URL completa no resultado: somente origem e padrão a solicitar). */
export async function describeTab(tabId) {
  const v = evaluate(await tabUrl(tabId));
  return { tabId, allowed: v.allowed, kind: v.kind, reason: v.reason, origin: v.origin, pattern: v.pattern,
    message: v.allowed ? null : MESSAGES[v.reason] || null, broad: granted.some((p) => p === 'https://*/*' || p === 'http://*/*' || p === '<all_urls>') };
}

export async function assertAllowed(tabId) {
  const url = await tabUrl(tabId);
  const v = evaluate(url);
  if (!v.allowed) {
    throw deniedError(v);
  }
  return { id: tabId, url, access: v };
}

// Registrados no carregamento do módulo (o service worker precisa dos ouvintes já na primeira execução).
if (globalThis.chrome && chrome.permissions && chrome.permissions.onAdded) {
  chrome.permissions.onAdded.addListener(async (p) => {
    await load();
    for (const fn of listeners.added) {
      try {
        await fn(p);
      } catch (e) {
        // ouvinte com falha não impede os demais
      }
    }
  });

  chrome.permissions.onRemoved.addListener(async (p) => {
    await load();
    for (const fn of listeners.removed) {
      try {
        await fn(p);
      } catch (e) {
        // ouvinte com falha não impede os demais
      }
    }
  });
}
