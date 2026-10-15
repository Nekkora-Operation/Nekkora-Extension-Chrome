/*
 * FeedbackObserver (STUDIO-005) — observa, por um período DELIMITADO depois de cada interação, o que a página mostrou:
 * mensagens (role=alert/alertdialog/status, aria-live), validação (aria-invalid, evento nativo invalid), carregamento
 * (aria-busy, role=progressbar), mudança de estado do elemento acionado e conteúdo atualizado ao fim de um carregamento.
 *
 * Regras (D-S005-01/02/03/09/10):
 *  - só existe com a capacidade evidence/1 e só em CAPTURE_AND_INTERACT (CAPTURE_ONLY não executa a ação: não há consequência);
 *  - não altera a página: nenhum evento disparado, nenhum reportValidity()/checkValidity(), nenhum atributo escrito;
 *  - MutationObserver só enquanto houver janela aberta (no máximo MAX_WINDOWS), desligado em seguida;
 *  - o texto lido (textContent) fica neste contexto e serve só para detectar a mudança (hash local): pela política
 *    ESTRITA (NekkoraFeedback.messagePolicy) o texto da mensagem nunca sai — só que houve mensagem. HTML nunca sai. Os
 *    valores dos campos NÃO são lidos;
 *  - correlação determinística (NekkoraFeedback.correlate): com mais de uma interação candidata no mesmo documento a
 *    evidência é AMBÍGUA; uma mensagem que já estava na tela antes da interação não vira evidência;
 *  - toda janela termina com OBSERVATION_COMPLETE (com a contagem de sinais) ou OBSERVATION_INCOMPLETE (motivo).
 */
(function (root) {
  const NS = root.NekkoraCS;
  if (!NS || NS.feedbackObserver) {
    return;
  }
  const FB = () => root.NekkoraFeedback;
  const WINDOW_MS = 3000;
  const MAX_WINDOW_MS = 8000;
  const MAX_WINDOWS = 8;
  const MAX_PER_WINDOW = 12;
  const MAX_REGIONS = 40;
  const SCAN_DEBOUNCE_MS = 100;
  const SELECTOR = '[role="alert"],[role="alertdialog"],[role="status"],[aria-live],[aria-invalid],[aria-busy],[role="progressbar"]';
  const STATE_ATTRS = ['aria-expanded', 'aria-pressed', 'aria-checked', 'aria-selected', 'aria-disabled', 'disabled'];
  const OBSERVED_ATTRS = ['role', 'aria-live', 'aria-invalid', 'aria-busy', 'hidden', 'class', 'style', 'open', ...STATE_ATTRS];

  let st = null; // { epoch, send, windows: [], tracked: Map, observers: [], timer, listeners: [] }
  // Sequência das mensagens 'feedback' deste documento: monotônica (nunca reinicia), e a última enviada na época vigente
  // (o service worker espera a chegada até ela nas fronteiras: pausa, parada, troca de modo).
  let fseq = 0;
  let epochOfLast = null;
  let lastInEpoch = 0;

  // ---------------------------------------------------------------------------------- leitura local

  function text(el) {
    const t = el.textContent || '';
    return t.length > 2000 ? t.slice(0, 2000) : t;
  }

  function hash(s) {
    let h = 0x811c9dc5;
    for (let i = 0; i < s.length; i++) {
      h ^= s.charCodeAt(i);
      h = Math.imul(h, 0x01000193);
    }
    return h >>> 0;
  }

  function visible(el) {
    if (!el.isConnected) {
      return false;
    }
    try {
      if (typeof el.checkVisibility === 'function') {
        return el.checkVisibility({ checkVisibilityCSS: true, visibilityProperty: true });
      }
    } catch (e) {
      // segue para o critério geométrico
    }
    return el.getClientRects().length > 0;
  }

  function attr(el, name) {
    if (name === 'disabled') {
      return el.hasAttribute('disabled') ? 'true' : null;
    }
    return el.getAttribute(name);
  }

  function isField(el) {
    const tag = el.tagName.toLowerCase();
    return tag === 'input' || tag === 'select' || tag === 'textarea' || ['textbox', 'combobox', 'searchbox', 'spinbutton'].includes((el.getAttribute('role') || '').toLowerCase());
  }

  function signature(el) {
    const t = FB().normalize(text(el));
    const role = (el.getAttribute('role') || '').toLowerCase();
    return {
      visible: visible(el),
      len: t.length,
      hash: hash(t),
      invalid: (el.getAttribute('aria-invalid') || '').toLowerCase() === 'true' || (el.getAttribute('aria-invalid') || '').toLowerCase() === 'grammar' || (el.getAttribute('aria-invalid') || '').toLowerCase() === 'spelling',
      busy: (el.getAttribute('aria-busy') || '').toLowerCase() === 'true',
      progress: role === 'progressbar',
      role,
      live: (el.getAttribute('aria-live') || '').toLowerCase(),
    };
  }

  function roots() {
    try {
      return NS.capture.observableRoots();
    } catch (e) {
      return [document];
    }
  }

  /** Política de mensagem no contexto da página (modo estrito: o texto nunca sai; nenhum valor de campo é lido). */
  function policy(raw) {
    return FB().messagePolicy(raw);
  }

  function byIds(el, attrName) {
    const ids = (el.getAttribute(attrName) || '').split(/\s+/).filter(Boolean).slice(0, 4);
    const scope = el.getRootNode();
    return ids.map((id) => (scope.getElementById ? scope.getElementById(id) : null)).filter(Boolean);
  }

  /**
   * Descrição controlada da região (revisão final do PR #6): só a palavra do vocabulário. aria-label, aria-labelledby e o
   * nome do formulário são TEXTO DA PÁGINA e não entram na evidência.
   */
  function regionLabel(word) {
    return FB().controlledRegion(word);
  }

  /** Descrição controlada do campo validado: o TIPO do controle, nunca o rótulo, placeholder ou nome acessível. */
  function fieldLabel(el) {
    return FB().controlledField(FB().fieldKind(el.tagName, el.getAttribute('type'), el.getAttribute('role')));
  }

  /** Elementos que apontam para esta região como mensagem de erro (aria-errormessage). */
  function errorMessageOf(el) {
    if (!el.id) {
      return false;
    }
    const scope = el.getRootNode();
    try {
      return !!(scope.querySelector && scope.querySelector('[aria-errormessage~="' + CSS.escape(el.id) + '"]'));
    } catch (e) {
      return false;
    }
  }

  function hintTokens(el) {
    const t = [el.id || '', ...(el.classList ? Array.from(el.classList) : [])];
    for (const a of el.getAttributeNames ? el.getAttributeNames() : []) {
      if (a.startsWith('data-')) {
        t.push(a, String(el.getAttribute(a) || '').slice(0, 40));
      }
    }
    return t.slice(0, 30);
  }

  function documentInfo(el) {
    const doc = el.ownerDocument || document;
    let url = null;
    try {
      url = root.NekkoraPrivacy ? root.NekkoraPrivacy.sanitizeUrl(doc.location.href) : null;
    } catch (e) {
      url = null;
    }
    // o título é texto da página: não entra (o documento é identificado por frame, Shadow DOM e URL sanitizada)
    return { childFrame: doc !== document, inShadow: el.getRootNode() instanceof ShadowRoot, title: null, url };
  }

  // ------------------------------------------------------------------------------------- envio

  function post(data) {
    fseq++;
    lastInEpoch = fseq;
    st.send('feedback', { epoch: st.epoch, fseq, ...data });
  }

  /** Evidência de uma (ou várias, se ambígua) janela(s). */
  function emit(cands, base, el, at) {
    if (!cands || !cands.length) {
      return;
    }
    const open = cands.filter((w) => w.count < MAX_PER_WINDOW);
    for (const w of cands) {
      if (w.count >= MAX_PER_WINDOW) {
        w.limited = true;
      }
    }
    if (!open.length) {
      return;
    }
    for (const w of open) {
      w.count++;
    }
    const ambiguous = open.length > 1;
    const doc = documentInfo(el);
    const limitations = [...(base.limitations || [])];
    if (base.message && base.message.availability === 'SUPPRESSED' && !limitations.includes('MESSAGE_SUPPRESSED')) {
      limitations.push('MESSAGE_SUPPRESSED');
    }
    if (doc.childFrame) {
      limitations.push('CROSS_DOCUMENT_NOT_ATTRIBUTED'); // documento filho: nunca atribuído a ação de outro documento
    }
    post({
      kind: 'evidence',
      localSeq: ambiguous ? null : open[0].localSeq,
      candidates: ambiguous ? Math.min(open.length, 20) : 0,
      at,
      type: ambiguous ? 'AMBIGUOUS_FEEDBACK' : base.type,
      classification: base.classification || null,
      confidence: ambiguous ? 'AMBIGUOUS' : base.confidence || 'TEMPORALLY_ASSOCIATED',
      document: doc,
      region: base.region || null,
      message: base.message || { availability: 'NONE', text: null },
      field: base.field || null,
      state: base.state || null,
      signals: (base.signals || []).slice(0, 8),
      limitations: Array.from(new Set(limitations)).slice(0, 8),
    });
  }

  function candidatesFor(doc, at) {
    return st.windows.filter((w) => w.doc === doc && FB().OPERATIONS.has(w.action) && w.openedAt <= at && at <= w.closesAt);
  }

  function once(w, key) {
    if (w.seen.has(key)) {
      return false;
    }
    w.seen.add(key);
    return true;
  }

  function onceAll(cands, key) {
    return cands.filter((w) => once(w, key));
  }

  // ------------------------------------------------------------------------------------ regras de diff

  let regionIds = new WeakMap();
  let nextRegion = 0;

  function regionId(el) {
    let id = regionIds.get(el);
    if (!id) {
      id = 'r' + ++nextRegion;
      regionIds.set(el, id);
    }
    return id;
  }

  function validation(el, at, signals, rawMessage) {
    const doc = el.ownerDocument;
    const own = st.windows.find((w) => w.doc === doc && w.action === 'FILL' && w.target === el && w.openedAt <= at && at <= w.closesAt);
    const cands = own ? [own] : candidatesFor(doc, at);
    const fresh = onceAll(cands, 'valid|' + regionId(el));
    if (!fresh.length) {
      return;
    }
    emit(fresh, {
      type: 'VALIDATION_FEEDBACK', classification: 'VALIDATION_INDICATOR', confidence: own ? 'DIRECTLY_OBSERVED' : 'TEMPORALLY_ASSOCIATED',
      region: regionLabel('região de validação'), field: fieldLabel(el), signals, message: policy(rawMessage),
    }, el, at);
  }

  function diff(el, prev, cur, at) {
    const doc = el.ownerDocument;
    // validação por aria-invalid (campo que passou a inválido)
    if (cur.invalid && !prev.invalid && isField(el)) {
      const msgEl = byIds(el, 'aria-errormessage')[0] || null;
      const raw = msgEl && visible(msgEl) ? text(msgEl) : (el.validationMessage || '');
      validation(el, at, msgEl ? ['ARIA_INVALID', 'ARIA_ERRORMESSAGE'] : ['ARIA_INVALID'], raw);
    }
    // carregamento
    const loadingNow = cur.busy || (cur.progress && cur.visible);
    const loadingBefore = prev.busy || (prev.progress && prev.visible);
    if (loadingNow && !loadingBefore) {
      const cands = candidatesFor(doc, at);
      const fresh = onceAll(cands, 'load|' + regionId(el) + '|' + at);
      for (const w of cands) {
        w.loading = (w.loading || 0) + 1;
        w.closesAt = Math.min(w.openedAt + MAX_WINDOW_MS, Math.max(w.closesAt, at + WINDOW_MS));
        reschedule(w);
      }
      st.busyStart.set(el, cur.hash);
      emit(fresh, { type: 'LOADING_INDICATOR', region: regionLabel('indicador de carregamento'), signals: [cur.busy ? 'ARIA_BUSY' : 'ROLE_PROGRESSBAR'] }, el, at);
      return;
    }
    if (!loadingNow && loadingBefore) {
      const cands = candidatesFor(doc, at);
      for (const w of cands) {
        w.loading = Math.max(0, (w.loading || 0) - 1);
        w.closesAt = Math.min(w.openedAt + MAX_WINDOW_MS, Math.max(w.closesAt, at + 1000)); // vê a mensagem que costuma seguir o carregamento
        reschedule(w);
      }
      emit(onceAll(cands, 'loaded|' + regionId(el) + '|' + at), { type: 'LOADING_FINISHED', region: regionLabel('indicador de carregamento'),
        signals: [prev.busy ? 'ARIA_BUSY' : 'ROLE_PROGRESSBAR'] }, el, at);
      const startHash = st.busyStart.get(el);
      st.busyStart.delete(el);
      if (prev.busy && startHash !== undefined && startHash !== cur.hash) {
        emit(onceAll(cands, 'content|' + regionId(el) + '|' + cur.hash), { type: 'CONTENT_CHANGED', region: regionLabel('região de conteúdo'), signals: ['ARIA_BUSY'] }, el, at);
      }
      return;
    }
    if (cur.busy) {
      return; // região ocupada: mudanças intermediárias não são avisos (aria-busy)
    }
    // mensagens
    const cls = FB().classifyRegion({ role: cur.role, live: cur.live, errorMessageOf: errorMessageOf(el), errorHint: FB().hasErrorHint(hintTokens(el)) });
    if (!cls || !cur.visible || cur.len === 0) {
      return;
    }
    if (prev.visible && prev.len > 0 && prev.hash === cur.hash) {
      return; // já estava na tela, inalterada: não é evidência desta interação
    }
    const fresh = onceAll(candidatesFor(doc, at), 'msg|' + regionId(el) + '|' + cur.hash);
    if (!fresh.length) {
      return;
    }
    const word = cls.type === 'VISIBLE_ERROR_MESSAGE' ? 'mensagem de erro' : cls.type === 'VISIBLE_STATUS_MESSAGE' ? 'mensagem de status' : 'alerta';
    emit(fresh, { type: cls.type, classification: cls.classification, region: regionLabel(word), signals: cls.signals,
      message: policy(text(el)) }, el, at);
  }

  function stateChanges(w, at) {
    if (!FB().OPERATIONS.has(w.action) || !w.target || !w.target.isConnected) {
      return;
    }
    for (const a of STATE_ATTRS) {
      const now = FB().stateValue(attr(w.target, a));
      const before = w.state[a];
      if (now !== before) {
        w.state[a] = now;
        if (w.count < MAX_PER_WINDOW) {
          w.count++;
          post({ kind: 'evidence', localSeq: w.localSeq, candidates: 0, at, type: 'ELEMENT_STATE_CHANGED', classification: null, confidence: 'DIRECTLY_OBSERVED',
            document: documentInfo(w.target), region: regionLabel('elemento acionado'), message: { availability: 'NONE', text: null }, field: null,
            state: { attribute: a, from: before, to: now }, signals: ['TARGET_ATTRIBUTE'], limitations: [] });
        } else {
          w.limited = true;
        }
      }
    }
  }

  // --------------------------------------------------------------------------------------- varredura

  const EMPTY = { visible: false, len: 0, hash: 0, invalid: false, busy: false, progress: false, role: '', live: '' };

  function scan(emitChanges) {
    if (!st) {
      return;
    }
    const at = Date.now();
    const els = new Set(st.tracked.keys());
    for (const r of roots()) {
      try {
        for (const el of r.querySelectorAll(SELECTOR)) {
          if (els.size >= MAX_REGIONS + st.tracked.size) {
            break;
          }
          els.add(el);
        }
      } catch (e) {
        // raiz inacessível: ignorada (cobertura parcial já declarada pela captura)
      }
    }
    for (const el of els) {
      const prev = st.tracked.get(el);
      const cur = signature(el);
      if (!el.isConnected) {
        st.tracked.delete(el);
      } else if (st.tracked.size < MAX_REGIONS || st.tracked.has(el)) {
        st.tracked.set(el, cur);
      }
      if (emitChanges && st.windows.length) {
        diff(el, prev || EMPTY, cur, at);
      }
    }
    if (emitChanges) {
      for (const w of st.windows) {
        stateChanges(w, at);
      }
    }
  }

  function scheduleScan() {
    if (st && !st.timer) {
      st.timer = setTimeout(() => {
        if (st) {
          st.timer = null;
          scan(true);
        }
      }, SCAN_DEBOUNCE_MS);
    }
  }

  function observeRoots() {
    if (!st || st.observers.length) {
      return;
    }
    for (const r of roots()) {
      try {
        const mo = new MutationObserver(scheduleScan);
        mo.observe(r, { subtree: true, childList: true, characterData: true, attributes: true, attributeFilter: OBSERVED_ATTRS });
        st.observers.push(mo);
      } catch (e) {
        // raiz que não pode ser observada
      }
    }
  }

  function disconnect() {
    for (const mo of st.observers) {
      mo.disconnect();
    }
    st.observers = [];
    if (st.timer) {
      clearTimeout(st.timer);
      st.timer = null;
    }
  }

  // ----------------------------------------------------------------------------------------- janelas

  function reschedule(w) {
    clearTimeout(w.timer);
    w.timer = setTimeout(() => close(w, null), Math.max(0, w.closesAt - Date.now()));
  }

  /** Fecha a janela. reason null = fim natural (COMPLETE); senão INCOMPLETE com a limitação. */
  function close(w, reason) {
    if (!st || !st.windows.includes(w)) {
      return;
    }
    if (!reason) {
      scan(true); // varredura final antes de concluir
    }
    clearTimeout(w.timer);
    st.windows = st.windows.filter((x) => x !== w);
    const limitations = [];
    if (w.limited) {
      limitations.push('EVIDENCE_LIMIT_REACHED');
    }
    let kind = reason ? 'incomplete' : 'complete';
    if (!reason && w.loading > 0) {
      kind = 'incomplete'; // o carregamento não terminou dentro do limite: observação parcial
      limitations.push('PARTIAL_COVERAGE');
    }
    if (reason) {
      limitations.push(reason);
    }
    post({ kind, localSeq: w.localSeq, candidates: 0, at: Date.now(), signalsObserved: Math.min(50, w.count), document: documentInfo(w.target || document.documentElement),
      limitations: Array.from(new Set(limitations)).slice(0, 8) });
    if (!st.windows.length) {
      disconnect();
    }
  }

  function cutAll(reason) {
    for (const w of st.windows.slice()) {
      close(w, reason);
    }
  }

  function onInvalid(event) {
    if (!st || !st.windows.length) {
      return;
    }
    const el = event.composedPath ? event.composedPath()[0] : event.target;
    if (el && el.nodeType === 1) {
      validation(el, Date.now(), ['NATIVE_INVALID'], el.validationMessage || ''); // nada é disparado: só o evento que a página já gerou
    }
  }

  function windowsOf() {
    try {
      return [root, ...NS.capture.frameWindows()];
    } catch (e) {
      return [root];
    }
  }

  NS.feedbackObserver = {
    MAX_WINDOWS,
    WINDOW_MS,
    MAX_WINDOW_MS,
    /** Liga para uma época (somente CAPTURE_AND_INTERACT). */
    start(a) {
      this.stop(null);
      if (!root.NekkoraFeedback || a.mode !== 'CAPTURE_AND_INTERACT') {
        return false;
      }
      if (epochOfLast !== a.epoch) {
        epochOfLast = a.epoch;
        lastInEpoch = 0;
      }
      st = { epoch: a.epoch, send: a.send, windows: [], tracked: new Map(), busyStart: new Map(), observers: [], timer: null, listeners: [] };
      regionIds = new WeakMap();
      for (const w of windowsOf()) {
        w.addEventListener('invalid', onInvalid, { capture: true, passive: true });
        st.listeners.push(w);
      }
      return true;
    },
    active() {
      return !!st;
    },
    /** Interação entregue: abre a janela de observação (snapshot do estado anterior já registrado). */
    open(i, el) {
      if (!st || !FB().opensWindow(i.action, 'CAPTURE_AND_INTERACT') || i.observation === 'PROPOSED') {
        return;
      }
      scan(st.windows.length > 0); // registra a linha de base (e entrega o que mudou para as janelas já abertas)
      if (st.windows.length >= MAX_WINDOWS) {
        close(st.windows[0], 'EVIDENCE_LIMIT_REACHED');
      }
      const at = Date.now();
      const target = el && el.nodeType === 1 ? el : null;
      const state = {};
      if (target) {
        for (const a2 of STATE_ATTRS) {
          state[a2] = FB().stateValue(attr(target, a2));
        }
      }
      const w = { localSeq: i.localSeq, action: i.action, target, doc: target ? target.ownerDocument : document, openedAt: at, closesAt: at + WINDOW_MS,
        count: 0, loading: 0, limited: false, seen: new Set(), state, timer: null };
      st.windows.push(w);
      observeRoots();
      reschedule(w);
    },
    /** Troca de modo, pausa, parada, saída do documento: as janelas abertas terminam INCOMPLETAS (motivo declarado). */
    cut(reason) {
      if (st) {
        cutAll(reason);
      }
    },
    lastSeq() {
      return lastInEpoch;
    },
    stop(reason) {
      if (!st) {
        return 0;
      }
      if (reason) {
        cutAll(reason);
      }
      for (const w of st.windows) {
        clearTimeout(w.timer);
      }
      disconnect();
      for (const w of st.listeners) {
        w.removeEventListener('invalid', onInvalid, { capture: true, passive: true });
      }
      st = null;
      return lastInEpoch;
    },
    status() {
      return st ? { active: true, windows: st.windows.length, observers: st.observers.length, tracked: st.tracked.size, sent: lastInEpoch } : { active: false, windows: 0, observers: 0 };
    },
  };
})(globalThis);
