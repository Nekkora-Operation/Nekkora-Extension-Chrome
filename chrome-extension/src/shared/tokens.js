/*
 * Nekkora — tokens visuais da extensão (fonte única).
 *
 * Valores confirmados em Nekkora-Operation/Nekkora-lib, arquivo src/tokens/themes.css:
 *   --nk-color-accent (tema light)      = #c2466d  → primary
 *   --nk-color-accent-hover (light)     = #ad3a5f
 *   --nk-color-accent-active (light)    = #983252
 *   --nk-solid-accent (tema dark)       = #c2466d
 *   --nk-color-accent (tema dark)       = #f29bb2
 *   --nk-color-bg (dark)                = #180c1a
 *   --nk-color-card (dark)              = #48213b
 *   --nk-color-text (light)             = #2a1a28
 * Tipografia/raios de src/tokens/foundation.css (--nk-font-family, --nk-radius-md).
 * Somente VALORES foram reutilizados; nenhum código ou ativo gráfico foi copiado.
 */
(function (root) {
  const tokens = Object.freeze({
    primary: '#C2466D',
    primaryHover: '#AD3A5F',
    primaryActive: '#983252',
    accentOnDark: '#F29BB2',
    bgDark: '#180C1A',
    cardDark: '#48213B',
    bgLight: '#F8F3F5',
    surface: '#FFFFFF',
    text: '#2A1A28',
    textSecondary: '#64566A',
    success: '#2E7D4F',
    warning: '#9A6A00',
    danger: '#B3261E',
    fontFamily: "'Inter', 'Segoe UI', system-ui, -apple-system, sans-serif",
    fontMono: "'JetBrains Mono', 'Fira Code', ui-monospace, monospace",
    radius: '8px',
    highlightWidthPx: 2,
    highlightOffsetPx: 2,
    zIndexOverlay: 2147483647,
  });

  function applyCssVariables(element) {
    const s = element.style;
    s.setProperty('--nekkora-primary', tokens.primary);
    s.setProperty('--nekkora-primary-hover', tokens.primaryHover);
    s.setProperty('--nekkora-primary-active', tokens.primaryActive);
    s.setProperty('--nekkora-accent-dark', tokens.accentOnDark);
    s.setProperty('--nekkora-bg-dark', tokens.bgDark);
    s.setProperty('--nekkora-card-dark', tokens.cardDark);
    s.setProperty('--nekkora-bg-light', tokens.bgLight);
    s.setProperty('--nekkora-surface', tokens.surface);
    s.setProperty('--nekkora-text', tokens.text);
    s.setProperty('--nekkora-text-secondary', tokens.textSecondary);
    s.setProperty('--nekkora-success', tokens.success);
    s.setProperty('--nekkora-warning', tokens.warning);
    s.setProperty('--nekkora-danger', tokens.danger);
    s.setProperty('--nekkora-font', tokens.fontFamily);
    s.setProperty('--nekkora-mono', tokens.fontMono);
    s.setProperty('--nekkora-radius', tokens.radius);
  }

  root.NekkoraTokens = Object.freeze({ ...tokens, applyCssVariables });
  if (typeof module !== 'undefined' && module.exports) {
    module.exports = root.NekkoraTokens;
  }
})(typeof globalThis !== 'undefined' ? globalThis : this);
