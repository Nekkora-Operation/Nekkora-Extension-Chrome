/*
 * Canal com o Nekkora Studio (STUDIO-002) — lado da extensão.
 *
 * O Native Host é iniciado pelo Chrome, não pelo Studio. Para o Studio conseguir falar com o navegador, o usuário
 * LIGA "Conectar ao Nekkora Studio" no painel (opt-in persistido em chrome.storage.local). Com o canal ligado:
 *   - a extensão mantém a porta nativa aberta (o host roda; a porta aberta também mantém o service worker ativo) e
 *     reabre-a por alarme se o host terminar;
 *   - a cada NOVA porta (novo processo do host) envia studio.link.configure — o host então procura o Studio local
 *     (arquivo de descoberta) e faz o handshake autenticado;
 *   - o host informa o estado do canal por mensagens studio.link, exibidas no painel.
 * Com o canal desligado (padrão), nada muda no funcionamento da extensão.
 */
import { client, host, broadcast } from './runtime.js';
import * as config from './config.js';

const KEY = 'nekkora.studioLink';
const ALARM = 'nekkora.studioLink';
let enabled = false;
let loaded = false;
let link = { state: 'DISABLED', detail: null, controller: null, at: 0 };
const listeners = [];

export function onLinkState(fn) {
  listeners.push(fn);
}

export async function load() {
  if (loaded) {
    return;
  }
  try {
    const v = await chrome.storage.local.get(KEY);
    enabled = v[KEY] === true;
  } catch (e) {
    enabled = false;
  }
  loaded = true;
}

export function isEnabled() {
  return enabled;
}

export function describe() {
  return { enabled, ...link, hostConnected: client.status().connected };
}

/** Controlador (instância do Studio) atualmente conectado, ou null. */
export function controller() {
  return link.state === 'CONNECTED' && link.controller ? link.controller.instanceId : null;
}

function extensionInfo() {
  return { version: chrome.runtime.getManifest().version, buildMode: config.describe().buildMode === 'PRD' ? 'PRD' : 'DEV' };
}

/** Garante a porta nativa e (re)configura o host. Seguro chamar repetidamente. */
export async function ensure() {
  if (!enabled) {
    return describe();
  }
  try {
    await host('studio.link.configure', { enabled: true, extension: extensionInfo() }, { timeoutMs: 10000 });
  } catch (e) {
    setLink({ state: 'HOST_UNAVAILABLE', detail: e.code || 'HOST_ERROR', controller: null });
  }
  return describe();
}

export async function setEnabled(value) {
  enabled = !!value;
  await chrome.storage.local.set({ [KEY]: enabled });
  if (enabled) {
    chrome.alarms.create(ALARM, { periodInMinutes: 0.5 }).catch(() => {});
    await ensure();
  } else {
    chrome.alarms.clear(ALARM).catch(() => {});
    if (client.status().connected) {
      await host('studio.link.configure', { enabled: false, extension: extensionInfo() }, { timeoutMs: 10000 }).catch(() => {});
    }
    setLink({ state: 'DISABLED', detail: null, controller: null });
  }
  return describe();
}

function setLink(next) {
  link = { ...next, at: Date.now() };
  broadcast('studio-link', describe());
  for (const fn of listeners) {
    try {
      fn(link);
    } catch (e) {
      // ouvinte com falha não afeta os demais
    }
  }
}

/** Mensagem studio.link do host (estado do canal com o Studio). */
export function onHostLink(msg) {
  setLink({ state: String(msg.state), detail: msg.detail || null, controller: msg.controller || null });
}

/** Inicialização: ouvintes e, se ligado, conexão imediata. */
export function init(ready) {
  client.onConnected(() => {
    if (enabled) {
      // Nova porta = novo processo do host: ele começa com o canal desligado e precisa ser configurado.
      Promise.resolve().then(() => ready).then(ensure).catch(() => {});
    }
  });
  const lost = client.onConnectionLost;
  client.onConnectionLost = (info) => {
    if (typeof lost === 'function') {
      lost(info);
    }
    if (enabled) {
      setLink({ state: 'HOST_DISCONNECTED', detail: null, controller: null });
      setTimeout(() => ensure().catch(() => {}), 1500); // novo processo do host
    }
  };
  chrome.alarms.onAlarm.addListener((a) => {
    if (a.name === ALARM && enabled) {
      ensure().catch(() => {});
    }
  });
  return ready.then(async () => {
    await load();
    if (enabled) {
      chrome.alarms.create(ALARM, { periodInMinutes: 0.5 }).catch(() => {});
      await ensure();
    }
  });
}
