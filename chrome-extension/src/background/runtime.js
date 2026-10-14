/*
 * Infraestrutura compartilhada do service worker: cliente do motor Java, mensagens para content scripts
 * (por frame) e difusão de eventos para as páginas da extensão (política de origem: access-policy.js).
 */
import { NativeClient, HostError } from './native-client.js';

export { HostError };
export const client = new NativeClient();

const EVENT_LOG_LIMIT = 200;
export const eventLog = [];

export function broadcast(event, data) {
  const entry = { event, data, at: Date.now() };
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
