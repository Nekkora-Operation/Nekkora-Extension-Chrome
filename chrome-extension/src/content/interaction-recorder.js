/*
 * InteractionRecorder (STUDIO-003) — sensor de interações do Recorder do Studio, por documento.
 *
 * - Só existe enquanto o service worker o liga (gravação do Studio ativa, autorizada e com a capacidade steps/1).
 * - CAPTURE_AND_INTERACT: ouvintes PASSIVOS na fase de captura; nunca preventDefault/stopPropagation, nunca altera
 *   valores nem dispara eventos — a página funciona normalmente.
 * - CAPTURE_ONLY: o clique (e Enter/Espaço no elemento focado) SELECIONA sem executar: os eventos de ponteiro, clique e
 *   envio são interceptados na captura, como no Inspector. Tab e atalhos do navegador continuam livres.
 * - SELECT_DATA (NEKKORA-E2E-002, "Selecionar dado para extrair"): como CAPTURE_ONLY, o clique não executa nada; o alvo
 *   é o CONTÊINER DE DADO mais próximo do texto clicado (célula, termo/descrição, título, parágrafo, item de lista,
 *   legenda), realçado na página. Vira uma PROPOSTA de leitura (EXTRACT). O texto lido NUNCA é coletado aqui — só o
 *   elemento; quem lê é o robô, na execução.
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
  // contêineres de DADO (texto informativo, não interativo) para "Selecionar dado para extrair"
  const DATA_TAGS = new Set(['td', 'th', 'dd', 'dt', 'li', 'caption', 'figcaption', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'p', 'blockquote',
    'pre', 'time', 'address', 'output', 'label', 'legend']);
  const DATA_ROLES = new Set(['cell', 'gridcell', 'rowheader', 'columnheader', 'heading', 'status', 'note', 'term', 'definition', 'listitem',
    'caption']);

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


  /**
   * CHROME-SECURITY-001: a opção escolhida é VALOR do usuário / conteúdo de negócio — identificada aqui (há uma opção
   * selecionada), mas nem o rótulo nem o valor saem da página, mesmo que sejam palavras do vocabulário.
   */
  function optionChosen(select) {
    return !!(select.selectedOptions && select.selectedOptions[0]);
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

  // ------------------------------------------------------------------- alvo revelado pelo ponteiro (STUDIO-012)

  const REVEALING = /(^|[;{\s])(display|visibility|opacity|max-height|height|clip|clip-path|transform)\s*:/i;
  const hoverRuleCache = new WeakMap(); // Document -> { sheets, selectors }

  /** Divide uma lista de seletores no nível de topo (vírgulas fora de parênteses/colchetes). */
  function splitSelectors(text) {
    const out = [];
    let depth = 0;
    let start = 0;
    for (let i = 0; i < text.length; i++) {
      const c = text[i];
      if (c === '(' || c === '[') {
        depth++;
      } else if (c === ')' || c === ']') {
        depth--;
      } else if (c === ',' && depth === 0) {
        out.push(text.slice(start, i).trim());
        start = i + 1;
      }
    }
    out.push(text.slice(start).trim());
    return out;
  }

  /**
   * Seletores (sem o :hover) de regras da página que EXIBEM um elemento quando o ponteiro está sobre OUTRO — o :hover
   * fica num composto anterior a um combinador (li:hover > ul, .menu:hover .sub). Regras com :hover no próprio alvo
   * (a:hover { color }) não contam. Folhas de outra origem não são legíveis: ficam de fora (sem palpite).
   */
  function hoverSelectors(doc) {
    const sheets = doc.styleSheets;
    const cached = hoverRuleCache.get(doc);
    if (cached && cached.sheets === sheets.length) {
      return cached.selectors;
    }
    const selectors = [];
    const visit = (rules) => {
      for (const rule of rules) {
        if (rule.cssRules && !rule.selectorText) {
          visit(rule.cssRules); // @media, @supports
          continue;
        }
        if (!rule.selectorText || !rule.selectorText.includes(':hover') || !REVEALING.test(rule.style ? rule.style.cssText : '')) {
          continue;
        }
        for (const sel of splitSelectors(rule.selectorText)) {
          const at = sel.lastIndexOf(':hover');
          // depois do composto com :hover precisa vir um combinador e outro composto
          const after = at < 0 ? '' : sel.slice(at + 6).replace(/^[^\s>+~]*/, '');
          if (!/^[\s>+~]/.test(after) || !after.replace(/[\s>+~]/g, '')) {
            continue; // :hover no último composto = estado do próprio alvo
          }
          selectors.push(sel.replace(/:hover/g, ''));
        }
      }
    };
    for (let i = 0; i < sheets.length && selectors.length < 200; i++) {
      try {
        visit(sheets[i].cssRules);
      } catch (e) {
        // folha de outra origem: ilegível
      }
    }
    hoverRuleCache.set(doc, { sheets: sheets.length, selectors });
    return selectors;
  }

  /** O alvo está num contêiner exibido pelo ponteiro sobre outro elemento? Só com evidência da própria página (regra CSS). */
  function revealedByHover(el) {
    const doc = el.ownerDocument;
    const selectors = hoverSelectors(doc);
    let c = parentOf(el);
    for (let i = 0; c && i < MAX_DEPTH; i++, c = parentOf(c)) {
      const tag = c.tagName.toLowerCase();
      if (tag === 'body' || tag === 'html') {
        break;
      }
      for (const s of selectors) {
        try {
          if (c.matches(s)) {
            return true;
          }
        } catch (e) {
          // seletor que matches() não aceita
        }
      }
    }
    // Menu aberto por SCRIPT (mouseover) não é distinguível, pelo estado, de um aberto por clique (aria-expanded é igual
    // nos dois): sem evidência, nada é afirmado — limitação declarada na documentação.
    return false;
  }

  function targetInfo(r, event) {
    const el = r.el;
    const info = { key: NS.registry.idFor(el), control: r.control, sensitive: NS.sensitivity.isSensitive(el), viaLabel: !!r.viaLabel };
    if (r.control === 'CLICKABLE' || r.control === 'AMBIGUOUS') {
      try {
        info.revealedByHover = revealedByHover(el);
      } catch (e) {
        info.revealedByHover = false;
      }
    }
    const form = el.form || (el.closest && el.closest('form'));
    if (form) {
      info.formKey = NS.registry.idFor(form);
    }
    if (r.control === 'CHECKBOX' || r.control === 'RADIO') {
      info.checked = !!el.checked;
    }
    if (r.control === 'SELECT' && event && event.type === 'change') {
      info.option = null;
      info.optionWithheld = optionChosen(el); // identificada, retida pela política
    }
    if (r.control === 'COMBOBOX_OPTION') {
      const combo = comboboxOf(el);
      if (combo) {
        info.comboboxKey = NS.registry.idFor(combo);
        info.option = null;
        info.optionWithheld = true;
      }
    }
    return info;
  }

  /** Descritor OBSERVADO do elemento (reserva quando o motor não conseguir correlacionar): nunca o valor. */
  /**
   * Descritor observado (usado quando o motor não resolve o nó). CHROME-SECURITY-001: o nome só sai se for METADADO de
   * interface do vocabulário controlado (aria-label, rótulo, conteúdo de botão/link/aba/item de menu, value de botão,
   * placeholder/title) e o valor INTEIRO passar; opção de lista não é nome (é valor). id/name: só identificadores do
   * vocabulário. Nada de detecção heurística.
   */
  function descriptor(el) {
    const role = el.getAttribute('role');
    const CP = root.NekkoraCapturePolicy;
    const sensitive = NS.sensitivity.isSensitive(el);
    const ident = (v) => (v && !sensitive && CP ? CP.developerIdentifier(v) : null); // STUDIO-012: lista técnica de id/name
    let name = el.getAttribute('aria-label') || '';
    if (!name && el.labels && el.labels.length) {
      name = el.labels[0].textContent;
    }
    if (!name && (el.tagName === 'BUTTON' || el.tagName === 'A' || role === 'button' || role === 'link' || role === 'menuitem' || role === 'tab')) {
      name = el.textContent;
    }
    if (!name && el.tagName === 'INPUT' && CLICK_TYPES.has(inputType(el))) {
      name = el.getAttribute('value') || '';
    }
    if (!name) {
      name = el.getAttribute('placeholder') || el.getAttribute('title') || '';
    }
    return {
      tag: CP ? CP.tagName(el.tagName) || 'unknown' : 'unknown', // 2ª revisão dos PRs #7: nome de componente pela política
      type: el.tagName === 'INPUT' ? inputType(el) || 'text' : null,
      role: role && CP ? CP.attribute('role', role, { tag: el.tagName }) || null : null, // P1-01: regra fechada do role
      htmlId: ident(el.id),
      htmlName: ident(el.getAttribute('name')),
      idPresent: !!el.id, // STUDIO-012: só a PRESENÇA (estrutural), para explicar um id/name retido
      namePresent: !!el.getAttribute('name'),
      name: CP ? CP.interfaceText(name) : null,
      sensitive: NS.sensitivity.isSensitive(el),
      inShadow: el.getRootNode() instanceof ShadowRoot,
    };
  }

  // -------------------------------------------------------------------------------------- observação

  function deliver(i) {
    state.lastSeq = i.localSeq;
    const el = NS.registry.elementFor(i.targetKey);
    send('interaction', { epoch: state.epoch, mode: state.mode, interaction: i, element: el ? descriptor(el) : null, url: NS.privacyUrl(),
      title: root.NekkoraCapturePolicy ? root.NekkoraCapturePolicy.title(document.title) : null, discarded: state.normalizer.stats().discarded });
    if (state.evidence && NS.feedbackObserver) {
      NS.feedbackObserver.open(i, el); // STUDIO-005: janela de observação das consequências desta interação
    }
  }

  function feedbackSeq() {
    return state && state.evidence && NS.feedbackObserver ? NS.feedbackObserver.lastSeq() : 0;
  }

  /** Motivo do corte das janelas de observação abertas, pelo motivo da consolidação. */
  function cutReason(reason) {
    if (reason === 'PAUSE') {
      return 'WINDOW_CUT_BY_PAUSE';
    }
    if (reason === 'FINISH' || reason === 'STOP') {
      return 'WINDOW_CUT_BY_STOP';
    }
    if (reason === 'NAVIGATION') {
      return 'WINDOW_CUT_BY_NAVIGATION';
    }
    return null; // troca de rota/flush técnico: as janelas continuam
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

  const SENSOR_KEYS = new Set(['Enter', ' ', 'ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight']);

  function passive(event) {
    if (event.type === 'keydown' && !SENSOR_KEYS.has(event.key)) {
      return; // só Enter (operação) e Espaço/setas (ativação de checkbox/radio); o resto da digitação não é lido
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

  /**
   * Item de lista, parágrafo ou trecho DENTRO de uma célula de dados cuja linha tem cabeçalho próprio (th na mesma
   * linha): o dado é a célula inteira — a linha da ficha nomeia o dado e o robô lê todos os itens dela. Nunca sobe além
   * da tabela mais próxima.
   */
  function headedCell(el) {
    if (el.tagName.toLowerCase() === 'td') {
      return null;
    }
    for (let cur = parentOf(el), i = 0; cur && i < MAX_DEPTH; i++, cur = parentOf(cur)) {
      const tag = cur.tagName.toLowerCase();
      if (tag === 'table' || tag === 'body') {
        return null;
      }
      if (tag === 'th') {
        return null; // texto do próprio cabeçalho: é rótulo, não dado
      }
      if (tag === 'td') {
        const row = cur.parentElement;
        return row && row.tagName.toLowerCase() === 'tr' && [...row.children].some((c) => c.tagName.toLowerCase() === 'th') ? cur : null;
      }
    }
    return null;
  }

  /** Contêiner de dado mais próximo do ponto clicado (do texto clicado para cima); senão o próprio nó, se tem texto. */
  function dataTarget(event) {
    const path = event.composedPath ? event.composedPath() : [event.target];
    const leaf = path.find((n) => n && n.nodeType === 1);
    if (!leaf) {
      return null;
    }
    for (let cur = leaf, i = 0; cur && i < MAX_DEPTH; i++, cur = parentOf(cur)) {
      const tag = cur.tagName.toLowerCase();
      const role = (cur.getAttribute('role') || '').toLowerCase().trim().split(/\s+/)[0];
      if (DATA_TAGS.has(tag) || DATA_ROLES.has(role)) {
        return headedCell(cur) || cur;
      }
      if (tag === 'body' || tag === 'html') {
        break;
      }
    }
    const own = [...leaf.childNodes].some((n) => n.nodeType === Node.TEXT_NODE && n.nodeValue.trim());
    return own ? leaf : null;
  }

  /** SELECT_DATA: intercepta o gesto e propõe a LEITURA do contêiner de dado (nada é executado na página). */
  function selectData(event) {
    event.preventDefault();
    event.stopPropagation();
    event.stopImmediatePropagation();
    if (event.type !== 'click') {
      return;
    }
    const el = dataTarget(event);
    const key = el ? NS.registry.idFor(el) : null;
    if (el) {
      NS.highlighter.show('hover', el, 'Dado selecionado · ' + el.tagName.toLowerCase(), key);
    }
    state.normalizer.observe({ type: 'click', trusted: event.isTrusted, at: Date.now(),
      target: { key, control: el ? 'DATA' : 'NONE', sensitive: el ? NS.sensitivity.isSensitive(el) : false } });
    syncOpen();
  }

  function dataHover(event) {
    const el = dataTarget(event);
    if (!el) {
      NS.highlighter.hide('hover');
      return;
    }
    NS.highlighter.show('hover', el, 'Ler dado · ' + el.tagName.toLowerCase(), NS.registry.idFor(el));
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
    if (state.mode === 'SELECT_DATA') {
      listen(r, 'submit', selectData, { capture: true });
    } else if (state.mode === 'CAPTURE_ONLY') {
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

  function documentOf(w) {
    try {
      return w.document || null;
    } catch (e) {
      return null; // frame que deixou de ser acessível
    }
  }

  /**
   * Liga os sensores na janela de UM documento. NEKKORA-STUDIO-012: a chave é o DOCUMENTO, não a janela — o
   * contentWindow de um iframe é o mesmo objeto antes e depois de o frame navegar, mas os ouvintes ficam no documento
   * antigo; sem religar, as interações no documento novo do frame não eram observadas (o menu num iframe que recarrega,
   * a tela de conteúdo carregada no outro iframe).
   */
  function attachWindow(w) {
    const doc = documentOf(w);
    if (!doc || state.docs.has(doc)) {
      return;
    }
    state.docs.add(doc);
    state.framesAttached += w === root ? 0 : 1;
    listen(w, 'focusin', noticeRoot, { capture: true, passive: true });
    listen(w, 'pointerdown', noticeRoot, { capture: true, passive: true });
    if (state.mode === 'SELECT_DATA') {
      for (const type of BLOCKED) {
        listen(w, type, selectData, { capture: true });
      }
      listen(w, 'pointermove', dataHover, { capture: true, passive: true });
    } else if (state.mode === 'CAPTURE_ONLY') {
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
    // 'load' de iframe não propaga, mas passa pela fase de captura do documento que o contém
    listen(doc, 'load', onFrameLoad, { capture: true, passive: true });
  }

  /** Iframe que (re)carregou: religa já se a janela é de um frame que a captura aceitou (o documento novo vem depois). */
  function onFrameLoad(event) {
    const t = event.target;
    if (!state || !t || (t.tagName !== 'IFRAME' && t.tagName !== 'FRAME')) {
      return;
    }
    if (NS.capture.frameWindows().includes(t.contentWindow)) {
      attachWindow(t.contentWindow);
    }
  }

  /** Documento de frame acessível inventariado pela captura (frame novo ou que navegou). */
  function onFrameDocument() {
    if (!state) {
      return;
    }
    for (const w of NS.capture.frameWindows()) {
      attachWindow(w);
    }
    for (const r of NS.capture.observableRoots()) {
      if (r instanceof ShadowRoot) {
        attachRoot(r);
      }
    }
  }

  function attach() {
    for (const w of windows()) {
      attachWindow(w);
    }
    for (const r of NS.capture.observableRoots()) {
      if (r instanceof ShadowRoot) {
        attachRoot(r);
      }
    }
    if (!state.unwatch && NS.capture.onFrameDocument) {
      state.unwatch = NS.capture.onFrameDocument(onFrameDocument);
    }
  }

  function detach() {
    for (const a of state.attached) {
      a.target.removeEventListener(a.type, a.fn, a.options);
    }
    state.attached = [];
    state.roots = new WeakSet();
    state.docs = new WeakSet();
    if (state.unwatch) {
      state.unwatch();
      state.unwatch = null;
    }
    NS.highlighter.hide('hover');
  }

  /**
   * Saída do documento: consolida o que estiver aberto (política explícita; a perda, se houver, é declarada pelo SW).
   * Só um FRAME saindo (STUDIO-012): consolida o que estava aberto nele, mas as janelas de observação continuam — a
   * navegação do frame é justamente a consequência do clique que elas observam.
   */
  function onPageHide(event) {
    if (!state) {
      return;
    }
    const frame = event && event.currentTarget && event.currentTarget !== root;
    state.normalizer.flush(frame ? 'FLUSH' : 'NAVIGATION');
    syncOpen();
    if (!frame && state.evidence && NS.feedbackObserver) {
      NS.feedbackObserver.cut('WINDOW_CUT_BY_NAVIGATION');
    }
  }

  function startFeedback(s) {
    s.evidence = !!(s.wantsEvidence && NS.feedbackObserver && NS.feedbackObserver.start({ epoch: s.epoch, mode: s.mode, send }));
  }

  function create(epoch, mode, wantsEvidence) {
    const s = { epoch, mode, attached: [], roots: new WeakSet(), docs: new WeakSet(), framesAttached: 0, unwatch: null, open: false, normalizer: null, lastSeq: 0,
      wantsEvidence: !!wantsEvidence, evidence: false };
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
        this.stop({ discard: false, cut: 'PARTIAL_COVERAGE' }); // nova época (rota, documento): janelas abertas terminam declaradas
      }
      state = create(a.epoch, a.mode, a.evidence);
      NS.inspector.suspend('RECORDER');
      attach();
      startFeedback(state);
      return this.status();
    },
    /** Troca de modo: consolida, troca os ouvintes e muda as regras (sem sensores antigos ativos). */
    setMode(a) {
      if (!state) {
        return { active: false };
      }
      if (a.epoch && a.epoch !== state.epoch) {
        return this.status(); // pedido de outra ativação: nada muda (o service worker não recebe a confirmação)
      }
      state.normalizer.setMode(a.mode);
      detach();
      if (state.evidence) {
        NS.feedbackObserver.stop('PARTIAL_COVERAGE'); // troca de modo: as janelas abertas terminam declaradas
      }
      state.mode = a.mode; // mesma época: a sequência local continua (fronteira = lastSeq devolvido)
      attach();
      startFeedback(state); // só volta a observar em CAPTURE_AND_INTERACT
      syncOpen();
      return this.status();
    },
    flush(a) {
      if (state) {
        state.normalizer.flush((a && a.reason) || 'FLUSH');
        syncOpen();
        const cut = cutReason(a && a.reason);
        if (cut && state.evidence) {
          NS.feedbackObserver.cut(cut);
        }
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
      // cancelamento (discard): nada é enviado; senão as janelas abertas terminam INCOMPLETAS com o motivo
      const lastFeedback = state.evidence ? NS.feedbackObserver.stop(a && a.discard ? null : (a && a.cut) || 'WINDOW_CUT_BY_STOP') : 0;
      syncOpen();
      detach();
      state = null;
      NS.inspector.resume('RECORDER');
      return { active: false, epoch, lastSeq, stats, lastFeedback };
    },
    navigation() {
      if (state) {
        state.normalizer.observe({ type: 'navigation', trusted: true, at: Date.now(), target: { key: '-', control: 'NONE' } });
        syncOpen();
      }
      return { ok: true };
    },
    status() {
      return state ? { active: true, epoch: state.epoch, mode: state.mode, lastSeq: state.lastSeq, listeners: state.attached.length, framesAttached: state.framesAttached,
        open: !!state.normalizer.openInteraction(),
        stats: state.normalizer.stats(), evidence: state.evidence, lastFeedback: feedbackSeq(),
        feedback: state.evidence ? NS.feedbackObserver.status() : null } : { active: false, listeners: 0 };
    },
  };
})(globalThis);
