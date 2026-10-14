/*
 * Estado MÍNIMO persistido em chrome.storage.session (sobrevive ao reinício do service worker, não ao do
 * navegador). Nada de conteúdo da página: somente identificadores, estados e contadores.
 */
const PREFIX = 'nk.tab.';

export async function save(tabId, record) {
  try {
    await chrome.storage.session.set({ [PREFIX + tabId]: record });
  } catch (e) {
    // armazenamento indisponível: o estado em memória continua valendo
  }
}

export async function remove(tabId) {
  try {
    await chrome.storage.session.remove(PREFIX + tabId);
  } catch (e) {
    // ignore
  }
}

export async function loadAll() {
  try {
    const all = await chrome.storage.session.get(null);
    const out = [];
    for (const [k, v] of Object.entries(all)) {
      if (k.startsWith(PREFIX) && v) {
        out.push({ tabId: Number(k.slice(PREFIX.length)), ...v });
      }
    }
    return out;
  } catch (e) {
    return [];
  }
}
