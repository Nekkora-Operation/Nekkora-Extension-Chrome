/*
 * InteractionRecorder (STUDIO-003) — sensor de interações do Recorder do Studio, por documento.
 *
 * - Só existe enquanto o service worker o liga (gravação do Studio ativa, autorizada e com a capacidade steps/1).
 * - CAPTURE_AND_INTERACT: ouvintes PASSIVOS na fase de captura; nunca preventDefault/stopPropagation, nunca altera
 *   valores nem dispara eventos — a página funciona normalmente.
 * - CAPTURE_ONLY: o clique (e Enter/Espaço no elemento focado) SELECIONA sem executar: os eventos de ponteiro, clique e
 *   envio são interceptados na captura, como no Inspector. Tab e atalhos do navegador continuam livres.
 * - Resolve o ALVO FUNCIONAL (o botão, não o ícone dentro dele; o controle de uma label) e entrega ao normalizador
 *   somente observações sanitizadas. Valores digitados NUNCA são lidos (nem .value de campos de texto); a opção de um
 *   select passa pela política de privacidade; campo sensível não tem a opção exportada.
 * - Enquanto liga sensores, suspende o Inspector deste documento (prioridade explícita e reversível, D-STUDIO-003-03).
 */
(function (root) {
  const NS = root.NekkoraCS;
  if (!NS || NS.interactionRecorder) {
    return;
  }
  const BLOCKED = ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click', 'dblclick', 'auxclick', 'submit', 'touchstart', 'touchend'];
  const OBSERVED = ['click', 'input', 'change', 'focusout', 'keydown', 'submit'];
  const TEXT_TYPES = new Set(['', 'text', 'email', 'password', 'number', 'search', 'tel', 'url', 'date', 'datetime-local', 'month', 'time', 'week']);
  const CLICK_TYPES = new Set(['submit', 'button', 'reset', 'image']);
  const CLICK_ROLES = new Set(['button', 'link', 'menuitem', 'menuitemcheckbox', 'menuitemradio', 'tab', 'switch', 'treeitem', 'checkbox', 'radio', 'combobox']);
  const MAX_DEPTH = 8;

  let state = null; // { epoch, mode, normalizer, attached: [], roots: WeakSet, open: bool }

  function send(event, data) {
    try {
      return chrome.runtime.sendMessage({ source: 'content', event, data }).catch(() => null);
    } catch (e) {
      return Promise.resolve(null); // contexto da extensão invalidado
    }
  }

  // ------------------------------------------------------------------------------------- alvo funcional

  function parentOf(el) {
    return el.parentElement || (el.parentNode instanceof ShadowRoot ? el.parentNode.host : null);
  }

  function inputType(el) {
    return (el.getAttribute('type') || '').toLowerCase();
  }

  function editingHost(el) {
    let cur = el;
    for (let i = 0; cur && i < MAX_DEPTH; i++, cur = parentOf(cur)) {
      const ce = cur.getAttribute && cur.getAttribute('contenteditable');
      if (ce === '' || ce === 'true' || ce === 'plaintext-only') {
        return cur;
      }
    }
    return null;
  }

  /** Controle de um elemento interativo NATIVO ou com papel ARIA; null quando ele não tem semântica de interação. */
  function controlOf(el) {
    const tag = el.tagName.toLowerCase();
    const role = (el.getAttribute('role') || '').toLowerCase();
    if (tag === 'input') {
      const type = inputType(el);
      if (type === 'hidden') {
        return null;
      }
      if (type === 'checkbox') {
        return 'CHECKBOX';
      }
      if (type === 'radio') {
        return 'RADIO';
      }
      if (CLICK_TYPES.has(type)) {
        return 'CLICKABLE';
      }
      if (TEXT_TYPES.has(type)) {
        return 'TEXT';
      }
      return 'NONE'; // file, range, color: fora do escopo (arquivos nunca são capturados)
    }
    if (tag === 'textarea') {
      return 'TEXT';
    }
    if (tag === 'select') {
      return 'SELECT';
    }
    if (tag === 'button' || tag === 'summary' || (tag === 'a' && el.hasAttribute('href'))) {
      return 'CLICKABLE';
    }
    if (role === 'option') {
      return 'COMBOBOX_OPTION';
    }
    if (role === 'textbox' && editingHost(el)) {
      return 'TEXT';
    }
    if (CLICK_ROLES.has(role)) {
      return 'CLICKABLE';
    }
    return null;
  }

  /** Sinais de "clicável" sem semântica: tornam o alvo AMBÍGUO (não se escolhe ancestral arbitrário). */
  function clickableSignal(el) {
    if (el.hasAttribute('onclick') || (el.hasAttribute('tabindex') && Number(el.getAttribute('tabindex')) >= 0)) {
      return true;
    }
    try {
      return root.getComputedStyle(el).cursor === 'pointer';
    } catch (e) {
      return false;
    }
  }

  /** Combobox que controla a listbox da opção (aria-controls/aria-owns): relação COMPROVADA, nunca suposta. */
  function comboboxOf(option) {
    let list = parentOf(option);
    for (let i = 0; list && i < 4 && (list.getAttribute('role') || '').toLowerCase() !== 'listbox'; i++) {
      list = parentOf(list);
    }
    if (!list || !list.id) {
      return null;
    }
    const scope = list.getRootNode();
    const sel = '[role="combobox"][aria-controls~="' + CSS.escape(list.id) + '"], [role="combobox"][aria-owns~="' + CSS.escape(list.id) + '"]';
    return (scope.querySelector && scope.querySelector(sel)) || null;
  }

  function maskText(t, max) {
    const s = String(t || '').replace(/\s+/g, ' ').trim().slice(0, max || 80);
    return root.NekkoraPrivacy ? root.NekkoraPrivacy.maskText(s) : s;
  }

  function optionOf(select) {
    if (NS.sensitivity.isSensitive(select)) {
      return null; // campo sensível: a opção escolhida não sai da página
    }
    const o = select.selectedOptions && select.selectedOptions[0];
    if (!o) {
      return null;
    }
    const label = maskText(o.label || o.textContent, 120);
    const raw = o.getAttribute('value');
    let value = raw == null ? '' : String(raw).slice(0, 120);
    if (root.NekkoraPrivacy) {
      value = root.NekkoraPrivacy.maskText(root.NekkoraPrivacy.sanitizeAttribute('value', value) || '');
    }
    return { label, value };
  }

  /**
   * Alvo funcional da interação a partir do caminho composto do evento: o primeiro elemento com semântica de
   * controle (até MAX_DEPTH níveis, atravessando Shadow DOM). Sem semântica: AMBÍGUO se houver sinal de clicável no
   * próprio elemento, senão NONE — nenhum ancestral é escolhido por palpite.
   */
  function resolve(event) {
    const path = event.composedPath ? event.composedPath() : [event.target];
    let leaf = null;
    let depth = 0;
    for (const n of path) {
      if (!n || n.nodeType !== 1) {
        continue;
      }
      if (NS.capture.isOverlay(n)) {
        return null;
      }
      if (!leaf) {
        leaf = n;
      }
      if (++depth > MAX_DEPTH) {
        break;
      }
      if (n.tagName.toLowerCase() === 'label') {
        const c = n.control;
        if (c) {
          const kind = controlOf(c);
          return kind ? { el: c, control: kind, viaLabel: true } : null;
        }
        continue;
      }
      const host = editingHost(n);
      if (host && host === n) {
        return { el: n, control: 'TEXT' };
      }
      const kind = controlOf(n);
      if (kind) {
        return { el: n, control: kind };
      }
      if (n.tagName.toLowerCase() === 'body' || n.tagName.toLowerCase() === 'html') {
        break;
      }
    }
    if (!leaf) {
      return null;
    }
    if (editingHost(leaf)) {
      return { el: editingHost(leaf), control: 'TEXT' };
    }
    return { el: leaf, control: clickableSignal(leaf) ? 'AMBIGUOUS' : 'NONE' };
  }

  function targetInfo(r, event) {
    const el = r.el;
    const info = { key: NS.registry.idFor(el), control: r.control, sensitive: NS.sensitivity.isSensitive(el), viaLabel: !!r.viaLabel };
    const form = el.form || (el.closest && el.closest('form'));
    if (form) {
      info.formKey = NS.registry.idFor(form);
    }
    if (r.control === 'CHECKBOX' || r.control === 'RADIO') {
      info.checked = !!el.checked;
    }
    if (r.control === 'SELECT' && event && event.type === 'change') {
      info.option = optionOf(el);
    }
    if (r.control === 'COMBOBOX_OPTION') {
      const combo = comboboxOf(el);
      if (combo) {
        info.comboboxKey = NS.registry.idFor(combo);
        info.option = { label: maskText(el.getAttribute('aria-label') || el.textContent, 120), value: '' };
      }
    }
    return info;
  }

  /** Descritor OBSERVADO do elemento (reserva quando o motor não conseguir correlacionar): nunca o valor. */
  function descriptor(el) {
    const role = el.getAttribute('role');
    const allowed = (v) => (v && !NS.sensitivity.isSensitive(el) && root.NekkoraPrivacy && !root.NekkoraPrivacy.looksSecret(v) ? maskText(v, 80) : null);
    let name = el.getAttribute('aria-label') || '';
    if (!name && el.labels && el.labels.length) {
      name = el.labels[0].textContent;
    }
    if (!name && (el.tagName === 'BUTTON' || el.tagName === 'A' || role === 'button' || role === 'link' || role === 'option' || role === 'menuitem' || role === 'tab')) {
      name = el.textContent;
    }
    if (!name && el.tagName === 'INPUT' && CLICK_TYPES.has(inputType(el))) {
      name = el.getAttribute('value') || '';
    }
    if (!name) {
      name = el.getAttribute('placeholder') || el.getAttribute('title') || '';
    }
    return {
      tag: el.tagName.toLowerCase(),
      type: el.tagName === 'INPUT' ? inputType(el) || 'text' : null,
      role: role ? role.slice(0, 40) : null,
      htmlId: allowed(el.id),
      htmlName: allowed(el.getAttribute('name')),
      name: maskText(name, 80) || null,
      sensitive: NS.sensitivity.isSensitive(el),
      inShadow: el.getRootNode() instanceof ShadowRoot,
    };
  }

  // -------------------------------------------------------------------------------------- observação

  function deliver(i) {
    state.lastSeq = i.localSeq;
    const el = NS.registry.elementFor(i.targetKey);
    send('interaction', { epoch: state.epoch, interaction: i, element: el ? descriptor(el) : null, url: NS.privacyUrl(),
      title: maskText(document.title, 120) || null });
  }

  function syncOpen() {
    const open = !!state.normalizer.openInteraction();
    if (open !== state.open) {
      state.open = open;
      send('interaction-open', { epoch: state.epoch, open });
    }
  }

  function observe(type, event, extra) {
    if (!state) {
      return;
    }
    const r = resolve(event);
    if (!r) {
      return;
    }
    if (type === 'focusout' || type === 'submit') {
      // focusout/submit: o alvo é o próprio elemento (campo que perdeu o foco; formulário enviado)
      const el = event.composedPath ? event.composedPath()[0] : event.target;
      const key = el && el.nodeType === 1 ? NS.registry.idFor(type === 'focusout' ? (editingHost(el) || el) : el) : null;
      state.normalizer.observe({ type, trusted: event.isTrusted, at: Date.now(), target: { key, control: 'NONE' },
        formKey: type === 'submit' ? key : null });
      syncOpen();
      return;
    }
    state.normalizer.observe({ type, trusted: event.isTrusted, at: Date.now(), target: targetInfo(r, event), detail: event.detail, key: event.key, ...extra });
    syncOpen();
  }

  function passive(event) {
    if (event.type === 'keydown' && event.key !== 'Enter') {
      return; // somente Enter é elegível (sem registrar o resto da digitação)
    }
    observe(event.type, event);
  }

  /** CAPTURE_ONLY: intercepta e transforma o gesto em seleção (proposta), sem executar a ação. */
  function block(event) {
    event.preventDefault();
    event.stopPropagation();
    event.stopImmediatePropagation();
    if (event.type === 'click') {
      observe('click', event);
    }
  }

  function keys(event) {
    if (event.key !== 'Enter' && event.key !== ' ') {
      return; // Tab, Escape e atalhos continuam com a página e o navegador
    }
    const r = resolve(event);
    if (!r || r.control === 'NONE') {
      return;
    }
    event.preventDefault();
    event.stopPropagation();
    event.stopImmediatePropagation();
    if (event.type === 'keydown' && !event.repeat) {
      state.normalizer.observe({ type: 'capture', trusted: event.isTrusted, at: Date.now(), target: targetInfo(r, event), viaKeyboard: true });
    }
  }

  function hover(event) {
    const r = resolve(event);
    if (!r || r.control === 'NONE') {
      NS.highlighter.hide('hover');
      return;
    }
    NS.highlighter.show('hover', r.el, 'Recorder · ' + r.el.tagName.toLowerCase(), NS.registry.idFor(r.el));
  }

  function listen(target, type, fn, options) {
    target.addEventListener(type, fn, options);
    state.attached.push({ target, type, fn, options });
  }

  /** change/submit não atravessam a fronteira do Shadow DOM: o shadow root recebe os próprios ouvintes. */
  function attachRoot(r) {
    if (!state || state.roots.has(r)) {
      return;
    }
    state.roots.add(r);
    if (state.mode === 'CAPTURE_ONLY') {
      listen(r, 'submit', block, { capture: true });
    } else {
      listen(r, 'change', passive, { capture: true, passive: true });
      listen(r, 'submit', passive, { capture: true, passive: true });
    }
  }

  function noticeRoot(event) {
    const first = event.composedPath ? event.composedPath()[0] : null;
    const r = first && first.getRootNode ? first.getRootNode() : null;
    if (r instanceof ShadowRoot) {
      attachRoot(r);
    }
  }

  function windows() {
    return [root, ...NS.capture.frameWindows()];
  }

  function attach() {
    for (const w of windows()) {
      listen(w, 'focusin', noticeRoot, { capture: true, passive: true });
      listen(w, 'pointerdown', noticeRoot, { capture: true, passive: true });
      if (state.mode === 'CAPTURE_ONLY') {
        for (const type of BLOCKED) {
          listen(w, type, block, { capture: true });
        }
        for (const type of ['keydown', 'keypress', 'keyup']) {
          listen(w, type, keys, { capture: true });
        }
        listen(w, 'pointermove', hover, { capture: true, passive: true });
      } else {
        for (const type of OBSERVED) {
          listen(w, type, passive, { capture: true, passive: true });
        }
      }
      listen(w, 'pagehide', onPageHide, { capture: true });
    }
    for (const r of NS.capture.observableRoots()) {
      if (r instanceof ShadowRoot) {
        attachRoot(r);
      }
    }
  }

  function detach() {
    for (const a of state.attached) {
      a.target.removeEventListener(a.type, a.fn, a.options);
    }
    state.attached = [];
    state.roots = new WeakSet();
    NS.highlighter.hide('hover');
  }

  /** Saída do documento: consolida o que estiver aberto (política explícita; a perda, se houver, é declarada pelo SW). */
  function onPageHide() {
    if (state) {
      state.normalizer.flush('NAVIGATION');
      syncOpen();
    }
  }

  function create(epoch, mode) {
    const s = { epoch, mode, attached: [], roots: new WeakSet(), open: false, normalizer: null, lastSeq: 0 };
    s.normalizer = NS.normalizer.createNormalizer({
      mode,
      onInteraction: (i) => deliver(i),
      onEvidence: (e) => send('interaction-evidence', { epoch: s.epoch, localSeq: e.localSeq, evidence: e.evidence }),
    });
    return s;
  }

  NS.interactionRecorder = {
    /** Liga os sensores para uma época (identificador opaco da ativação: eventos de épocas antigas são descartados). */
    start(a) {
      if (state && state.epoch === a.epoch && state.mode === a.mode) {
        return this.status();
      }
      if (state) {
        this.stop({ discard: false });
      }
      state = create(a.epoch, a.mode);
      NS.inspector.suspend('RECORDER');
      attach();
      return this.status();
    },
    /** Troca de modo: consolida, troca os ouvintes e muda as regras (sem sensores antigos ativos). */
    setMode(a) {
      if (!state) {
        return { active: false };
      }
      state.normalizer.setMode(a.mode);
      detach();
      state.mode = a.mode; // mesma época: a sequência local continua (fronteira = lastSeq devolvido)
      attach();
      syncOpen();
      return this.status();
    },
    flush(a) {
      if (state) {
        state.normalizer.flush((a && a.reason) || 'FLUSH');
        syncOpen();
      }
      return this.status();
    },
    stop(a) {
      if (!state) {
        return { active: false };
      }
      state.normalizer.stop(!!(a && a.discard));
      const stats = state.normalizer.stats();
      const lastSeq = state.lastSeq;
      const epoch = state.epoch;
      syncOpen();
      detach();
      state = null;
      NS.inspector.resume('RECORDER');
      return { active: false, epoch, lastSeq, stats };
    },
    navigation() {
      if (state) {
        state.normalizer.observe({ type: 'navigation', trusted: true, at: Date.now(), target: { key: '-', control: 'NONE' } });
        syncOpen();
      }
      return { ok: true };
    },
    status() {
      return state ? { active: true, epoch: state.epoch, mode: state.mode, lastSeq: state.lastSeq, listeners: state.attached.length, open: !!state.normalizer.openInteraction(),
        stats: state.normalizer.stats() } : { active: false, listeners: 0 };
    },
  };
})(globalThis);
