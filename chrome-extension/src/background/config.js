/*
 * Modo de operação DEV/PRD (controlado pelo projeto, nunca pela página).
 *   DEV: recursos de diagnóstico (forçar captura completa, comparar, testar reencontro, exportação manual).
 *   PRD: somente o fluxo de produto; exportação ocorre na finalização da gravação (evento da plataforma).
 * O modo não altera a política de origem (access-policy.js): nos dois modos só o laboratório local e as origens
 * autorizadas explicitamente pelo usuário podem ser inspecionados.
 */
import { BUILD_MODE } from '../shared/build-mode.js';
import { HostError } from './runtime.js';

const KEY = 'nekkora.modeOverride';
let override = null;
let loaded = false;

/** Comandos expostos somente em DEV. */
export const DEV_ONLY = new Set(['page.capture', 'session.forceFull', 'page.compare', 'target.resolve', 'pagemap.export', 'page.rawSnapshot', 'session.debug', 'page.highlightFrame',
  'studio.tabRef', 'frames.list', 'testHooks.read']);
// frames.list: URLs cruas do navegador; testHooks.read: valores que a PRÓPRIA página expõe em window.nekkoraTestHooks
// (qualquer página pode declarar o marcador) — diagnósticos de teste, nunca em PRD (CHROME-SECURITY-001)

export async function loadConfig() {
  if (loaded) {
    return;
  }
  try {
    const v = await chrome.storage.local.get(KEY);
    override = v[KEY] === 'PRD' ? 'PRD' : null;
  } catch (e) {
    override = null;
  }
  loaded = true;
}

export function mode() {
  return BUILD_MODE === 'PRD' ? 'PRD' : (override || 'DEV');
}

export function describe() {
  return { mode: mode(), buildMode: BUILD_MODE, simulated: BUILD_MODE === 'DEV' && override === 'PRD' };
}

export async function setMode(m) {
  if (BUILD_MODE === 'PRD') {
    throw new HostError('MODE_LOCKED', 'Construção PRD: o modo não pode ser alterado');
  }
  if (m !== 'DEV' && m !== 'PRD') {
    throw new HostError('INVALID_MODE', String(m));
  }
  override = m === 'PRD' ? 'PRD' : null;
  await chrome.storage.local.set({ [KEY]: override });
  return describe();
}

export function assertCommandAllowed(cmd) {
  if (mode() === 'PRD' && DEV_ONLY.has(cmd)) {
    throw new HostError('MODE_RESTRICTED', cmd + ' é um recurso de desenvolvimento (indisponível em PRD)');
  }
}
