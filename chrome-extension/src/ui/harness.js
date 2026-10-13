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
  events(name) {
    return window.nekkoraEvents.filter((e) => !name || e.event === name);
  },
  clearEvents() {
    window.nekkoraEvents.length = 0;
  },
};
document.getElementById('log').textContent = 'pronto: ' + chrome.runtime.id;
