/*
 * Injeção dos content scripts nas origens AUTORIZADAS pelo usuário (NEKKORA-REAL-001).
 *
 * - Laboratório local: content_scripts estáticos do manifesto (inalterado).
 * - Origens autorizadas: um registro dinâmico (chrome.scripting.registerContentScripts) com os MESMOS arquivos, na
 *   MESMA ordem, em todos os frames, sincronizado com as permissões ativas a cada concessão/revogação. O laboratório
 *   é excluído do registro (excludeMatches) para que uma autorização ampla nunca injete duas vezes.
 * - Abas já abertas antes da autorização: injeção programática somente nos frames que ainda não têm o content
 *   script (verificação prévia por frame), na ordem do manifesto. Frames de origens sem permissão são ignorados pelo
 *   próprio Chrome (nada é contornado).
 * A presença do content script NÃO inicia captura: a captura só começa quando um consumidor ativa a sessão.
 */
import * as policy from './access-policy.js';
import { HostError } from './native-client.js';

export const REGISTRATION_ID = 'nekkora-authorized-origins';

/** Mesma lista e ordem de content_scripts[0].js do manifesto. */
export function contentFiles() {
  return chrome.runtime.getManifest().content_scripts[0].js.slice();
}

let syncing = Promise.resolve();

/** Mantém o registro dinâmico igual às origens autorizadas atuais (idempotente, serializado). */
export function syncRegistration() {
  syncing = syncing.catch(() => {}).then(async () => {
    const patterns = policy.authorizedPatterns();
    const existing = await chrome.scripting.getRegisteredContentScripts({ ids: [REGISTRATION_ID] }).catch(() => []);
    if (!patterns.length) {
      if (existing.length) {
        await chrome.scripting.unregisterContentScripts({ ids: [REGISTRATION_ID] });
      }
      return { registered: false, matches: [] };
    }
    const script = { id: REGISTRATION_ID, matches: patterns, excludeMatches: policy.LAB_PATTERNS, js: contentFiles(),
      runAt: 'document_idle', allFrames: true, persistAcrossSessions: true };
    if (existing.length) {
      await chrome.scripting.updateContentScripts([script]);
    } else {
      await chrome.scripting.registerContentScripts([script]);
    }
    return { registered: true, matches: patterns };
  });
  return syncing;
}

export async function registration() {
  const [r] = await chrome.scripting.getRegisteredContentScripts({ ids: [REGISTRATION_ID] }).catch(() => []);
  return r ? { id: r.id, matches: r.matches, excludeMatches: r.excludeMatches, js: r.js, allFrames: r.allFrames, runAt: r.runAt } : null;
}

/** Frames da aba que já executam o content script completo (NekkoraCS.main). */
async function probe(tabId) {
  try {
    const res = await chrome.scripting.executeScript({ target: { tabId, allFrames: true },
      func: () => !!(globalThis.NekkoraCS && globalThis.NekkoraCS.main) });
    return res || [];
  } catch (e) {
    return null; // sem acesso ao documento principal
  }
}

/**
 * Garante o content script nos frames permitidos da aba (abas abertas antes da autorização). Nunca injeta duas
 * vezes no mesmo documento. Devolve os frames injetados agora.
 */
export async function ensureInjected(tabId) {
  const results = await probe(tabId);
  if (!results) {
    throw new HostError('CONTENT_UNAVAILABLE', 'o Chrome não permitiu acesso ao documento desta aba');
  }
  const missing = [];
  for (const r of results) {
    if (r.result === true) {
      continue;
    }
    let url = null;
    try {
      const f = await chrome.webNavigation.getFrame({ tabId, frameId: r.frameId });
      url = f && f.url;
    } catch (e) {
      url = null;
    }
    if (policy.isAllowedUrl(url)) {
      missing.push(r.frameId);
    }
  }
  if (missing.length) {
    await chrome.scripting.executeScript({ target: { tabId, frameIds: missing }, files: contentFiles() });
  }
  return { injected: missing };
}
