/*
 * FeedbackRules (STUDIO-005) — regras PURAS e determinísticas das evidências observadas depois de uma interação. Sem DOM:
 * o observador da página (content/feedback-observer.js) descreve as regiões e os sinais; estas regras decidem o que é
 * evidência, como classificá-la, a quem associá-la e se o texto de uma mensagem pode sair. Cada regra tem teste em
 * test/feedback-rules.test.js. Carregado no content script e no service worker (segunda barreira).
 *
 * Princípios (D-S005-01/04/05/10):
 *  - evidência é ACONTECIMENTO observado, nunca resultado presumido (mensagem de erro ≠ falha de negócio);
 *  - classificação reflete o SINAL realmente presente (role, aria-live, aria-invalid, aria-busy, invalid nativo…);
 *    uma classe CSS "error" sozinha não faz um erro (ERROR_HINT só reforça um alerta);
 *  - correlação explícita: com mais de uma interação candidata a evidência é AMBÍGUA — nunca se escolhe uma;
 *  - texto de mensagem: NUNCA sai (modo estrito). A evidência diz que houve mensagem, não o que ela dizia.
 */
(function (root) {
  const MESSAGE_MAX = 160;
  /** Ações que operam a página (podem provocar avisos, carregamento, navegação). FILL só concorre pela validação do próprio campo. */
  const OPERATIONS = new Set(['CLICK', 'PRESS_KEY', 'SELECT_OPTION', 'CHECK', 'UNCHECK', 'UNKNOWN']);
  const ERROR_HINT = /(^|[^a-z])(error|erro|errors|erros|danger|invalid|invalido|inválido|falha|failure|fail)([^a-z]|$)/i;


  function normalize(text) {
    return String(text == null ? '' : text).replace(/\s+/g, ' ').trim();
  }

  /**
   * Política do TEXTO de uma mensagem — MODO ESTRITO (decisão da revisão final do PR #6): o texto livre que a página
   * apresentou NUNCA sai. Uma mensagem com texto vira {availability: SUPPRESSED, text: null}; sem texto, NONE. O que
   * aconteceu (tipo, classificação, sinais, região segura, documento, horário) continua sendo evidência; o conteúdo, não.
   * Nenhuma heurística decide o que é dado pessoal: "o cliente joão já existe" é omitido como qualquer outra frase.
   * Aplicada no content script (origem, antes de sair da página) e de novo no service worker; o host e o Studio repetem a
   * regra. Os argumentos extras de versões anteriores (valores dos campos) não são mais lidos nem usados.
   */
  function messagePolicy(raw) {
    return normalize(raw) ? { availability: 'SUPPRESSED', text: null } : { availability: 'NONE', text: null };
  }

  /**
   * Classificação de uma região de aviso pelos sinais presentes:
   *  role=alert/alertdialog ou aria-live=assertive  → VISIBLE_ALERT (UNKNOWN_FEEDBACK);
   *    + indício de erro (ERROR_HINT em classe/id/data-*) ou referenciada por aria-errormessage → VISIBLE_ERROR_MESSAGE (ERROR_INDICATOR);
   *  role=status ou aria-live=polite                → VISIBLE_STATUS_MESSAGE (STATUS_INDICATOR);
   *  referenciada por aria-errormessage (sem role)   → VISIBLE_ERROR_MESSAGE (ERROR_INDICATOR).
   * Região sem nenhum desses sinais não é região de aviso (null).
   */
  function classifyRegion(r) {
    const role = r.role || '';
    const live = r.live || '';
    const signals = [];
    let assertive = false;
    let status = false;
    if (role === 'alert') {
      signals.push('ROLE_ALERT');
      assertive = true;
    } else if (role === 'alertdialog') {
      signals.push('ROLE_ALERTDIALOG');
      assertive = true;
    } else if (role === 'status') {
      signals.push('ROLE_STATUS');
      status = true;
    }
    if (live === 'assertive') {
      signals.push('ARIA_LIVE_ASSERTIVE');
      assertive = assertive || !status;
    } else if (live === 'polite') {
      signals.push('ARIA_LIVE_POLITE');
      status = status || !assertive;
    }
    if (r.errorMessageOf) {
      signals.push('ARIA_ERRORMESSAGE');
    }
    const hint = !!r.errorHint;
    if (assertive) {
      if (hint || r.errorMessageOf) {
        if (hint) {
          signals.push('ERROR_HINT');
        }
        return { type: 'VISIBLE_ERROR_MESSAGE', classification: 'ERROR_INDICATOR', signals };
      }
      return { type: 'VISIBLE_ALERT', classification: 'UNKNOWN_FEEDBACK', signals };
    }
    if (status) {
      return { type: 'VISIBLE_STATUS_MESSAGE', classification: 'STATUS_INDICATOR', signals };
    }
    if (r.errorMessageOf) {
      return { type: 'VISIBLE_ERROR_MESSAGE', classification: 'ERROR_INDICATOR', signals };
    }
    return null;
  }

  /** Indício textual de erro em classe, id ou data-* (reforço, nunca prova sozinho). */
  function hasErrorHint(tokens) {
    return (tokens || []).some((t) => ERROR_HINT.test(String(t)));
  }

  /**
   * Correlação de uma evidência NÃO direta (aviso, carregamento, conteúdo) com as janelas de observação abertas NO MESMO
   * documento no instante em que foi vista. Só ações que operam a página concorrem. {localSeq, confidence} com uma
   * candidata; {ambiguous: true, candidates} com várias; null sem nenhuma (fora de qualquer janela: não é evidência).
   */
  function correlate(windows, at, doc) {
    const open = (windows || []).filter((w) => w.doc === doc && OPERATIONS.has(w.action) && w.openedAt <= at && at <= w.closesAt);
    if (open.length === 0) {
      return null;
    }
    if (open.length > 1) {
      return { ambiguous: true, candidates: Math.min(open.length, 20) };
    }
    return { localSeq: open[0].localSeq, confidence: 'TEMPORALLY_ASSOCIATED' };
  }

  /** A ação pode provocar consequências observáveis na página (janela de observação). FILL observa só a validação do campo. */
  function opensWindow(action, mode) {
    return mode === 'CAPTURE_AND_INTERACT' && (OPERATIONS.has(action) || action === 'FILL');
  }

  /** Valores de estado de um atributo para o contrato ("absent" quando não existe). */
  function stateValue(v) {
    if (v == null) {
      return 'absent';
    }
    const s = String(v).toLowerCase();
    return s === 'true' || s === 'false' || s === 'mixed' ? s : s === '' ? 'true' : 'absent';
  }

  root.NekkoraFeedback = { MESSAGE_MAX, OPERATIONS, messagePolicy, classifyRegion, hasErrorHint, correlate, opensWindow, stateValue, normalize };
})(typeof globalThis !== 'undefined' ? globalThis : this);
