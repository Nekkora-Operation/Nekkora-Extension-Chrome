/*
 * Namespace do content script (mundo isolado da extensão). Os scripts de conteúdo são clássicos e
 * compartilham este objeto; não há acesso às variáveis da página.
 */
(function (root) {
  if (root.NekkoraCS) {
    return; // injeção repetida (ex.: recarga da extensão): mantém a instância existente
  }
  const NekkoraCS = {
    version: '0.4.0',
    /**
     * Somente documentos http/https. O content script só existe onde o Chrome concedeu acesso (laboratório local por
     * host_permissions; demais origens por autorização explícita do usuário, injeção pelo service worker), e o
     * service worker revalida a origem de toda mensagem com a política central (background/access-policy.js).
     */
    isAllowedLocation(loc) {
      return loc.protocol === 'http:' || loc.protocol === 'https:';
    },
    /**
     * URL do documento como MODELO (CHROME-SECURITY-001, nekkora-privacy/2: origem + segmentos do vocabulário, demais
     * {…}; nunca location.href bruto). Sem a política carregada, nada sai.
     */
    privacyUrl() {
      return root.NekkoraCapturePolicy ? root.NekkoraCapturePolicy.urlTemplate(root.location.href) : null;
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
