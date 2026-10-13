/*
 * Namespace do content script (mundo isolado da extensão). Os scripts de conteúdo são clássicos e
 * compartilham este objeto; não há acesso às variáveis da página.
 */
(function (root) {
  if (root.NekkoraCS) {
    return; // injeção repetida (ex.: recarga da extensão): mantém a instância existente
  }
  const NekkoraCS = {
    version: '0.1.0',
    /** Somente origens de laboratório local. Defesa em profundidade além de host_permissions. */
    isAllowedLocation(loc) {
      return (loc.protocol === 'http:' || loc.protocol === 'https:')
        && (loc.hostname === '127.0.0.1' || loc.hostname === 'localhost');
    },
    log(...args) {
      if (root.__NEKKORA_DEBUG__) {
        console.debug('[nekkora]', ...args);
      }
    },
  };
  root.NekkoraCS = NekkoraCS;
  if (typeof module !== 'undefined' && module.exports) {
    module.exports = NekkoraCS;
  }
})(typeof globalThis !== 'undefined' ? globalThis : this);
