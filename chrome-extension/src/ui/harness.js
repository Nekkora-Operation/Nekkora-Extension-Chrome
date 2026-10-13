/*
 * Harness de testes: expõe window.nekkora.call(cmd, args) para o WebDriver executar comandos do
 * service worker a partir de uma página interna da extensão (chrome-extension://<id>/...).
 * Eventos difundidos pelo service worker ficam em window.nekkoraEvents.
 */
window.nekkoraEvents = [];
chrome.runtime.onMessage.addListener((msg) => {
  if (msg && msg.source === 'background') {
    window.nekkoraEvents.push({ event: msg.event, data: msg.data, at: Date.now() });
    if (window.nekkoraEvents.length > 500) {
      window.nekkoraEvents.shift();
    }
  }
});

window.nekkora = {
  async call(cmd, args) {
    return chrome.runtime.sendMessage({ target: 'background', cmd, args: args || {} });
  },
  async tabIdFor(urlPrefix) {
    const all = await chrome.tabs.query({});
    const t = all.find((x) => x.url && x.url.startsWith(urlPrefix));
    return t ? t.id : null;
  },
  /** Aba mais recente (ids crescentes). Usado para origens sem permissão, cujo URL a extensão não enxerga. */
  async newestTabId() {
    const all = await chrome.tabs.query({});
    return all.length ? Math.max(...all.map((t) => t.id)) : null;
  },
  /** Contextos abertos da extensão (ex.: SIDE_PANEL) — comprova o painel lateral NATIVO. */
  async contexts(types) {
    return chrome.runtime.getContexts({ contextTypes: types || ['SIDE_PANEL'] });
  },
  async panel(cmd, extra) {
    return chrome.runtime.sendMessage({ target: 'panel', cmd, ...(extra || {}) });
  },
  events(name) {
    return window.nekkoraEvents.filter((e) => !name || e.event === name);
  },
  clearEvents() {
    window.nekkoraEvents.length = 0;
  },
};
const $ = (id) => document.getElementById(id);
async function recording(type) {
  const event = { type: 'recording.session.' + type, sessionId: $('rec-id').value, tabId: Number($('rec-tab').value), reason: type === 'finished' ? 'USER_FINISHED' : undefined };
  const r = await window.nekkora.call('recording.event', { event });
  $('out').textContent = JSON.stringify(r, null, 2);
  return r;
}
$('rec-start').onclick = () => recording('started');
$('rec-finish').onclick = () => recording('finished');
$('rec-cancel').onclick = () => recording('cancelled');
// chrome.sidePanel.open exige gesto do usuário: o clique (real ou do WebDriver) neste botão o fornece.
$('sp-open').onclick = async () => {
  try {
    await chrome.sidePanel.open({ tabId: Number($('sp-tab').value) });
    window.nekkoraSidePanel = { opened: true, at: Date.now() };
  } catch (e) {
    window.nekkoraSidePanel = { opened: false, error: String(e && e.message || e) };
  }
  $('out').textContent = JSON.stringify(window.nekkoraSidePanel);
};
$('log').textContent = 'pronto: ' + chrome.runtime.id;
