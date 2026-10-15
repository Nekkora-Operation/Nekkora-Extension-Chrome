/*
 * Infraestrutura compartilhada do service worker: cliente do motor Java, mensagens para content scripts
 * (por frame) e difusão de eventos para as páginas da extensão (política de origem: access-policy.js).
 */
import { NativeClient, HostError } from './native-client.js';

export { HostError };
export const client = new NativeClient();

const EVENT_LOG_LIMIT = 200;
export const eventLog = [];

/**
 * Log de diagnóstico (CHROME-SECURITY-001): RETÉM só campos de uma lista fechada — identificadores técnicos, códigos,
 * estados, números e booleanos; listas viram contagens. Nenhum payload bruto (nós, mutações, nomes, localizadores,
 * URLs, títulos, mensagens de erro) fica guardado, seja qual for o produtor. A difusão ao vivo para as páginas da
 * extensão não é retida aqui.
 */
const LOG_KEYS = new Set(['tabId', 'sessionId', 'captureId', 'recordingId', 'operationId', 'targetId', 'source', 'seq', 'sequence',
  'status', 'state', 'reason', 'where', 'code', 'kind', 'action', 'type', 'event', 'automatic', 'frameId', 'transitionType',
  'evidenceSequence', 'connected', 'enabled', 'mode', 'syncState', 'resolution', 'changedCount', 'nodes', 'frames', 'counts', 'removed',
  'added', 'authorized', 'lost']);
const LOG_TOKEN = /^[A-Za-z0-9:._-]{1,80}$/;

export function diagnosticEntry(event, data) {
  const out = {};
  if (data && typeof data === 'object') {
    for (const [k, v] of Object.entries(data)) {
      if (!LOG_KEYS.has(k)) {
        continue;
      }
      if (typeof v === 'number' || typeof v === 'boolean') {
        out[k] = v;
      } else if (typeof v === 'string' && LOG_TOKEN.test(v)) {
        out[k] = v;
      } else if (Array.isArray(v)) {
        out[k + 'Count'] = v.length;
      } else if (v && typeof v === 'object' && k === 'counts') {
        out.counts = Object.fromEntries(Object.entries(v).filter(([, n]) => typeof n === 'number'));
      }
    }
  }
  return { event: LOG_TOKEN.test(String(event)) ? event : 'unknown', data: out, at: Date.now() };
}

export function broadcast(event, data) {
  const entry = diagnosticEntry(event, data);
  eventLog.push(entry);
  if (eventLog.length > EVENT_LOG_LIMIT) {
    eventLog.shift();
  }
  chrome.runtime.sendMessage({ source: 'background', event, data }).catch(() => {});
}

/** Difusão com limite de frequência por chave (atualização de contadores sem inundar o painel). */
const throttled = new Map();
export function broadcastThrottled(key, event, dataFn, intervalMs = 250) {
  const t = throttled.get(key);
  if (t) {
    t.dirty = true;
    return;
  }
  broadcast(event, dataFn());
  const entry = { dirty: false };
  throttled.set(key, entry);
  setTimeout(() => {
    throttled.delete(key);
    if (entry.dirty) {
      broadcastThrottled(key, event, dataFn, intervalMs);
    }
  }, intervalMs);
}

export function tabKey(tabId) {
  return 'tab-' + tabId;
}

/** Política de origem: única, centralizada em access-policy.js (laboratório + origens autorizadas pelo usuário). */
export { isAllowedUrl, assertAllowed } from './access-policy.js';

/** Comando para o content script de um frame (0 = documento principal). */
export async function toContent(tabId, cmd, args, frameId = 0) {
  let res;
  try {
    res = await chrome.tabs.sendMessage(tabId, { target: 'content', cmd, args: args || {} }, { frameId });
  } catch (e) {
    throw new HostError('CONTENT_UNAVAILABLE', String(e && e.message || e));
  }
  if (res && res.__error) {
    throw new HostError('CONTENT_ERROR', res.__error);
  }
  if (res === undefined) {
    throw new HostError('CONTENT_UNAVAILABLE', 'content script não respondeu (' + cmd + ')');
  }
  return res;
}

export async function host(type, payload, options) {
  return (await client.request(type, payload, options)).payload;
}

/** Frame do Chrome dono de um id temporário de nó: {@code x<frameId>n…} pertence ao frame; {@code n…} ao principal. */
export function frameOfNode(nodeId) {
  const m = /^x(\d+)n/.exec(nodeId || '');
  return m ? Number(m[1]) : 0;
}

/** Exclusão mútua por chave (operações de ciclo de vida de uma aba nunca se sobrepõem). */
const locks = new Map();
export function withLock(key, fn) {
  const prev = locks.get(key) || Promise.resolve();
  const next = prev.catch(() => {}).then(fn);
  const tail = next.catch(() => {});
  locks.set(key, tail);
  tail.then(() => {
    if (locks.get(key) === tail) {
      locks.delete(key);
    }
  });
  return next;
}

export function errorInfo(e) {
  const info = { code: (e && e.code) || 'ERROR', message: String(e && e.message || e) };
  if (e && e.reason) {
    info.reason = e.reason; // ex.: ORIGIN_NOT_ALLOWED → PERMISSION_REQUIRED, BROWSER_INTERNAL, PERMISSION_REVOKED
  }
  return info;
}
