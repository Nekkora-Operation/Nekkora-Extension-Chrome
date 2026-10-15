/*
 * DOM Capture Engine — captura estrutural SOMENTE LEITURA.
 *
 * Percorre a árvore composta (documento → shadow roots abertos → iframes acessíveis) em pré-ordem e
 * emite descritores de elementos no formato RawSnapshot (contrato nekkora-nm/1). Não abre
 * componentes, não dispara eventos e não altera a página.
 *
 * Limitações declaradas no próprio snapshot: shadow roots fechados (detectados, não inventariados),
 * iframes inacessíveis (cross-origin/sandbox) e truncamento por limite de nós.
 */
(function (root) {
  const NS = root.NekkoraCS;
  if (NS.capture) {
    return;
  }
  const P = root.NekkoraPrivacy; // política de privacidade (src/shared/privacy.js), aplicada antes de qualquer envio
  /**
   * CHROME-SECURITY-001 (nekkora-privacy/2, 1ª barreira): só sai da página o METADADO DE INTERFACE elegível — texto
   * dentro de um PORTADOR de nome (botão, link, rótulo, legenda, aba, item de menu…) cujo texto INTEIRO passe no
   * vocabulário controlado, e atributos pela política de cada categoria. Conteúdo de negócio, tabelas, mensagens e
   * texto livre ficam como presença ("há texto"), nunca como conteúdo. Relações entre elementos vão como ids
   * temporários de nó (refs), sem o valor dos atributos. URLs como modelo (origem + caminho do vocabulário).
   */
  const CP = root.NekkoraCapturePolicy;
  /** Conteúdo que é VALOR (não nome): não entra no texto do portador. */
  const VALUE_SUBTREES = new Set(['select', 'option', 'optgroup', 'datalist', 'textarea', 'script', 'style', 'template', 'noscript']);
  /** Caches de UMA passada de captura (portador → texto aprovado?; alvos de aria-labelledby). */
  let pass = null;

  function newPass() {
    pass = { carrierOk: new Map(), labelTargets: null };
  }

  function composedUp(el) {
    if (el.parentElement) {
      return el.parentElement;
    }
    const p = el.parentNode;
    return p instanceof ShadowRoot ? p.host : null;
  }

  function isEditable(el) {
    const ce = el.getAttribute && el.getAttribute('contenteditable');
    return ce === '' || ce === 'true' || ce === 'plaintext-only';
  }

  /** Elementos referenciados por aria-labelledby (nome de interface por referência), da passada corrente. */
  function labelTargets() {
    if (pass.labelTargets) {
      return pass.labelTargets;
    }
    const out = new Set();
    for (const r of NS.capture.observableRoots()) {
      for (const ref of r.querySelectorAll('[aria-labelledby]')) {
        const scope = ref.getRootNode();
        for (const id of (ref.getAttribute('aria-labelledby') || '').split(/\s+/).filter(Boolean).slice(0, 8)) {
          const t = scope.getElementById ? scope.getElementById(id) : null;
          if (t) {
            out.add(t);
          }
        }
      }
    }
    pass.labelTargets = out;
    return out;
  }

  /** Portador de nome do elemento (ele mesmo ou ancestral próximo), ou null: fora de portador, texto é conteúdo. */
  function carrierOf(el) {
    let e = el;
    for (let i = 0; e && i < 8; i++) {
      const tag = e.localName;
      if (VALUE_SUBTREES.has(tag) || isEditable(e)) {
        return null; // valor do usuário ou conteúdo de lista: nunca nome
      }
      if (CP.isCarrier(tag, e.getAttribute('role'), e.hasAttribute('href')) || labelTargets().has(e)) {
        return e;
      }
      e = composedUp(e);
    }
    return null;
  }

  /** Texto do portador, sem subárvores de valor; aprovado só se o valor INTEIRO passar no vocabulário. */
  function carrierOk(c) {
    if (pass.carrierOk.has(c)) {
      return pass.carrierOk.get(c);
    }
    let text = '';
    const walk = (n, depth) => {
      if (depth > 12 || text.length > 400) {
        return;
      }
      for (const k of n.childNodes) {
        if (k.nodeType === Node.TEXT_NODE) {
          text += ' ' + k.nodeValue;
        } else if (k.nodeType === 1 && !VALUE_SUBTREES.has(k.localName) && !isEditable(k)) {
          walk(k, depth + 1);
        }
      }
    };
    walk(c, 0);
    const t = CP.clean(text);
    const ok = t === '' || CP.vocabularyOnly(t);
    pass.carrierOk.set(c, ok);
    return ok;
  }

  /** O texto deste elemento é metadado de interface elegível e aprovado? */
  function textEligible(el) {
    const c = carrierOf(el);
    return !!c && carrierOk(c);
  }

  const REF_ATTRIBUTES = ['for', 'aria-labelledby', 'aria-describedby', 'aria-controls', 'aria-owns', 'aria-activedescendant',
    'aria-errormessage', 'aria-details', 'aria-flowto', 'list', 'form', 'headers'];

  /** Relações declaradas por id, resolvidas NA PÁGINA para ids temporários de nó (o valor do atributo não sai). */
  function refsOf(el) {
    let out = null;
    const scope = el.getRootNode();
    for (const attr of REF_ATTRIBUTES) {
      if (!el.hasAttribute(attr)) {
        continue;
      }
      const ids = [];
      if (attr === 'for' && el.localName === 'label') {
        const t = el.control;
        if (t) {
          ids.push(NS.registry.idFor(t));
        }
      } else {
        for (const id of (el.getAttribute(attr) || '').split(/\s+/).filter(Boolean).slice(0, 16)) {
          const t = scope.getElementById ? scope.getElementById(id) : (el.ownerDocument && el.ownerDocument.getElementById(id));
          if (t && !isOverlay(t)) {
            ids.push(NS.registry.idFor(t));
          }
        }
      }
      if (ids.length) {
        (out || (out = {}))[attr] = ids;
      }
    }
    return out;
  }
  const SKIP = new Set(['script', 'style', 'noscript', 'template', 'head', 'meta', 'link', 'title', 'base']);
  const LEAF = new Set(['svg', 'math', 'canvas', 'video', 'audio', 'object', 'embed']);
  const SHADOW_CAPABLE = new Set(['article', 'aside', 'blockquote', 'body', 'div', 'footer', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
    'header', 'main', 'nav', 'p', 'section', 'span']);
  const LABELABLE = new Set(['input', 'select', 'textarea', 'button', 'meter', 'output', 'progress']);
  const FORM_CONTROLS = new Set(['input', 'select', 'textarea', 'button', 'option', 'optgroup', 'fieldset']);
  const TEXT_ENTRY_EXCLUDED = new Set(['checkbox', 'radio', 'submit', 'button', 'reset', 'image', 'file', 'hidden', 'range', 'color']);
  const MAX_ATTR_LEN = 300;
  const MAX_TEXT_LEN = 500;

  const frameIds = new WeakMap();      // Document -> frameId
  const frameHosts = new WeakMap();    // Document -> elemento iframe hospedeiro
  const frameWindows = new Map();      // frameId -> Window (somente frames acessíveis)
  const openShadowRoots = new Set();   // shadow roots abertos já vistos (observáveis pelo tracker)
  const knownShadows = new Map();      // host -> ShadowRootInfo (lista atual enviada nos deltas)
  const knownFrames = new Map();       // iframe host -> FrameInfo (lista atual enviada nos deltas)
  const geometry = new WeakMap();      // Element -> chave de geometria (rect + inViewport) do último envio
  const visibility = new WeakMap();    // Element -> visível no último envio (varredura de subárvores grandes)
  let frameCounter = 0;
  let captureSeq = 0;
  /**
   * Fonte desta instância do content script. Documento principal: frames f0, f1… e marcadores x<host>.
   * Content script de um iframe de outra origem permitida (configurado pelo service worker):
   * frames c<id>, c<id>f1… e marcadores c<id>x<host>; ids de nó prefixados por x<id> (node-registry).
   */
  const src = { name: 'top', rootFrame: 'f0', nestedPrefix: 'f', placeholderPrefix: 'x', configured: false };

  function frameIdOf(doc) {
    if (doc === document) {
      return src.rootFrame;
    }
    return frameIds.get(doc) || null;
  }

  function isOverlay(el) {
    return el.hasAttribute && el.hasAttribute('data-nekkora-overlay');
  }

  function scopeOf(el) {
    const r = el.getRootNode();
    if (r instanceof ShadowRoot) {
      return { scopeId: 'shadow:' + NS.registry.idFor(r.host), frameId: frameIdOf(r.host.ownerDocument) || src.rootFrame };
    }
    const fid = frameIdOf(el.ownerDocument) || src.rootFrame;
    return { scopeId: 'doc:' + fid, frameId: fid };
  }

  function composedParent(el) {
    if (el.parentElement) {
      return el.parentElement;
    }
    const p = el.parentNode;
    if (p instanceof ShadowRoot) {
      return p.host;
    }
    if (p && p.nodeType === Node.DOCUMENT_NODE) {
      return frameHosts.get(p) || null;
    }
    return null;
  }

  /** Texto próprio CRU: só decide presença/elegibilidade; o que sai é decidido em describe(). */
  function rawOwnText(el) {
    let s = '';
    for (const n of el.childNodes) {
      if (n.nodeType === Node.TEXT_NODE) {
        s += ' ' + n.nodeValue;
      }
    }
    return CP.clean(s);
  }

  /**
   * Conteúdo MISTO: trechos de texto antes de cada filho emitido e após o último (ordem texto/elemento preservada
   * para o nome acessível por conteúdo). Somente quando há texto e filhos e os filhos são os de luz, em ordem.
   */
  function textSegments(el, childIds, own) {
    if (!own || !childIds || !childIds.length || el.shadowRoot || el.localName === 'slot') {
      return undefined;
    }
    const segs = [];
    let cur = '';
    let matched = 0;
    for (const n of el.childNodes) {
      if (n.nodeType === Node.TEXT_NODE) {
        cur += ' ' + n.nodeValue;
      } else if (n.nodeType === 1 && matched < childIds.length && NS.registry.peek(n) === childIds[matched]) {
        segs.push(CP.clean(cur).slice(0, MAX_TEXT_LEN));
        cur = '';
        matched++;
      }
    }
    if (matched !== childIds.length) {
      return undefined;
    }
    segs.push(CP.clean(cur).slice(0, MAX_TEXT_LEN));
    return segs;
  }

  /** O SVG é capturado como folha; seu <title> compõe o nome acessível (botões de ícone). */
  function svgTitle(el) {
    const t = el.querySelector(':scope > title');
    return t ? CP.clean(t.textContent).slice(0, MAX_TEXT_LEN) : '';
  }

  const URL_ATTRIBUTE_HOSTS = new Set(['IFRAME', 'FRAME', 'SCRIPT']);
  const KEPT_FRAGMENT_KEYS = new Set(['frame']); // tipo de frame de widgets (ex.: hCaptcha #frame=challenge)
  /**
   * URL de recurso com os VALORES de parâmetros redigidos (chaves de site, tokens e identificadores nunca são
   * coletados); nomes dos parâmetros e o caminho são mantidos para a análise determinística.
   */
  function redactUrlValues(raw, base) {
    let u;
    try {
      u = new URL(raw, base);
    } catch (e) {
      return raw;
    }
    if (u.protocol !== 'http:' && u.protocol !== 'https:') {
      return raw;
    }
    const redact = (params, kept) => [...params.keys()].map((k) => k + '=' + (kept && kept.has(k) ? params.get(k) : 'REDACTED')).join('&');
    let out = u.origin + u.pathname;
    if (u.search) {
      out += '?' + redact(u.searchParams);
    }
    if (u.hash) {
      const h = u.hash.slice(1);
      out += '#' + (h.includes('=') ? redact(new URLSearchParams(h), KEPT_FRAGMENT_KEYS) : h.slice(0, 40));
    }
    return out;
  }

  function attributes(el) {
    const out = {};
    const ctx = { tag: el.localName, type: el.getAttribute('type') };
    for (const a of el.attributes) {
      const name = a.name;
      if (name === 'style' || (name.startsWith('data-nekkora') && name !== 'data-nekkora-simulated-src')) {
        continue; // data-nekkora-simulated-src: metadado de SIMULAÇÃO do laboratório, sempre rotulado como tal
      }
      let v;
      if (name === 'data-nekkora-simulated-src') {
        v = CP.urlTemplate(new URL(a.value, el.ownerDocument.baseURI).href);
      } else if (name === 'value' && !NS.sensitivity.valueAttributeAllowed(el)) {
        continue;
      } else {
        v = CP.attribute(name, a.value, ctx); // nekkora-privacy/2: categoria do atributo decide (espelho no Java)
      }
      if (v == null) {
        continue;
      }
      out[name] = v.length > MAX_ATTR_LEN ? v.slice(0, MAX_ATTR_LEN) : v;
    }
    return out;
  }

  function visibilityOf(el, tag, parentVisible) {
    if (tag === 'option' || tag === 'optgroup') {
      return { visible: parentVisible, reason: parentVisible ? null : 'ancestor' }; // opções nativas não têm caixa própria
    }
    let visible;
    if (typeof el.checkVisibility === 'function') {
      visible = el.checkVisibility({ checkOpacity: true, checkVisibilityCSS: true, contentVisibilityAuto: true });
    } else {
      visible = el.getClientRects().length > 0;
    }
    if (visible) {
      return { visible: true, reason: null };
    }
    if (el.hidden) {
      return { visible: false, reason: 'hidden-attribute' };
    }
    if (!parentVisible) {
      return { visible: false, reason: 'ancestor' };
    }
    const cs = el.ownerDocument.defaultView.getComputedStyle(el);
    if (cs.display === 'none') {
      return { visible: false, reason: 'display:none' };
    }
    if (cs.visibility === 'hidden' || cs.visibility === 'collapse') {
      return { visible: false, reason: 'visibility:' + cs.visibility };
    }
    if (cs.opacity === '0') {
      return { visible: false, reason: 'opacity:0' };
    }
    return { visible: false, reason: 'not-rendered' };
  }

  function round(v) {
    return Math.round(v * 10) / 10;
  }

  /**
   * Descreve um elemento. ctx: { frameId, scopeId, parentId, childIds, parentVisible, modal }.
   */
  function describe(el, ctx) {
    const tag = el.tagName.toLowerCase();
    const win = el.ownerDocument.defaultView;
    const vis = visibilityOf(el, tag, ctx.parentVisible);
    visibility.set(el, vis.visible);
    const rectObj = el.getBoundingClientRect();
    const rect = { x: round(rectObj.x), y: round(rectObj.y), width: round(rectObj.width), height: round(rectObj.height) };
    const vw = win.innerWidth;
    const vh = win.innerHeight;
    const inViewport = rect.width > 0 && rect.height > 0 && rect.x < vw && rect.y < vh && rect.x + rect.width > 0 && rect.y + rect.height > 0;
    geometry.set(el, rect.x + ',' + rect.y + ',' + rect.width + ',' + rect.height + ',' + inViewport);
    const isFormControl = FORM_CONTROLS.has(tag);
    const type = (el.getAttribute('type') || 'text').toLowerCase();
    const textEntry = tag === 'textarea' || (tag === 'input' && !TEXT_ENTRY_EXCLUDED.has(type));
    const sensitive = (tag === 'input' || tag === 'textarea' || tag === 'select') && NS.sensitivity.isSensitive(el);

    let cursorPointer = null;
    let boxed = null;
    let scroll = null;
    if (!isFormControl && vis.visible) {
      const cs = win.getComputedStyle(el);
      cursorPointer = cs.cursor === 'pointer';
      const border = cs.borderTopStyle !== 'none' && parseFloat(cs.borderTopWidth) > 0;
      const bg = cs.backgroundColor && cs.backgroundColor !== 'rgba(0, 0, 0, 0)' && cs.backgroundColor !== 'transparent';
      boxed = border || bg || (cs.boxShadow && cs.boxShadow !== 'none');
      if ((cs.overflowY === 'auto' || cs.overflowY === 'scroll') && el.scrollHeight > el.clientHeight + 1) {
        scroll = { scrollHeight: el.scrollHeight, clientHeight: el.clientHeight, scrollTop: Math.round(el.scrollTop) };
      }
    }

    let hasValue = null;
    let valueLength = null;
    // CHROME-SECURITY-001 (SEC001-004): nenhum valor de campo sai da página — nem o de pesquisa. Ser um campo de pesquisa
    // não torna o termo pesquisado público ("João Oliveira", um CPF). Só a presença e o tamanho.
    const exposedValue = null;
    if (textEntry || tag === 'select') {
      const v = el.value == null ? '' : String(el.value);
      hasValue = v.length > 0;
      valueLength = sensitive ? null : v.length;
    }
    // STUDIO-003: o conteúdo de um editor contenteditable é VALOR DE CAMPO (digitado pelo usuário) — como o de um input,
    // não sai da página; o hospedeiro informa só se há conteúdo e o tamanho.
    const editableContent = !!el.isContentEditable;
    const ce = el.getAttribute('contenteditable');
    if (editableContent && (ce === '' || ce === 'true' || ce === 'plaintext-only')) {
      const v = el.textContent || '';
      hasValue = v.trim().length > 0;
      valueLength = sensitive ? null : v.length;
    }

    let inert = false;
    if (el.closest('[inert]')) {
      inert = true;
    } else if (ctx.modal && !ctx.modal.contains(el) && !el.contains(ctx.modal)) {
      inert = true; // bloqueado por diálogo modal aberto
    }

    const labels = LABELABLE.has(tag) && el.labels ? Array.from(el.labels).map((l) => NS.registry.idFor(l)) : [];
    let disabled = null;
    if (isFormControl) {
      try {
        disabled = el.matches(':disabled');
      } catch (e) {
        disabled = el.disabled === true;
      }
    }
    let modal = null;
    if (tag === 'dialog') {
      try {
        modal = el.matches(':modal');
      } catch (e) {
        modal = null;
      }
    }

    const rawOwn = tag === 'svg' ? svgTitle(el) : editableContent ? '' : rawOwnText(el);
    const eligible = rawOwn !== '' && textEligible(el);
    const own = eligible ? rawOwn.slice(0, MAX_TEXT_LEN) : '';
    const refs = refsOf(el);
    const out = {
      id: NS.registry.idFor(el),
      parentId: ctx.parentId,
      childIds: ctx.childIds,
      tag,
      frameId: ctx.frameId,
      scopeId: ctx.scopeId,
      attributes: attributes(el),
      ownText: own,
      state: {
        visible: vis.visible,
        hiddenReason: vis.reason,
        inViewport,
        zeroSize: rect.width === 0 || rect.height === 0,
        disabled,
        readOnly: textEntry ? !!el.readOnly : null,
        required: isFormControl && 'required' in el ? !!el.required : null,
        checked: tag === 'input' && (type === 'checkbox' || type === 'radio') ? !!el.checked : null,
        selected: tag === 'option' ? !!el.selected : null,
        editable: !!el.isContentEditable,
        focusable: el.tabIndex >= 0 && disabled !== true,
        cursorPointer,
        boxed,
        hasValue,
        valueLength,
        open: tag === 'dialog' || tag === 'details' ? !!el.open : null,
        modal,
        inert,
      },
      rect,
      labelIds: labels,
      scroll,
      exposedValue,
    };
    if (rawOwn !== '') {
      out.textPresent = true; // houve texto (estrutura útil ao motor); o conteúdo só sai se elegível
      if (!eligible) {
        out.textSuppressed = true;
      }
    }
    if (refs) {
      out.refs = refs;
    }
    const segs = tag === 'svg' || editableContent || !eligible ? undefined : textSegments(el, ctx.childIds, own);
    if (segs) {
      out.textSegments = segs;
    }
    return out;
  }

  /** Filhos na árvore composta + registro de shadow roots e frames encontrados. */
  function composedChildren(el, info) {
    const tag = el.tagName.toLowerCase();
    const out = [];
    if (LEAF.has(tag)) {
      return out;
    }
    for (const c of el.children) {
      const ct = c.tagName.toLowerCase();
      if (!SKIP.has(ct) && !isOverlay(c)) {
        out.push({ el: c });
      }
    }
    const hostId = NS.registry.idFor(el);
    if (el.shadowRoot) {
      openShadowRoots.add(el.shadowRoot);
      const sri = { hostNodeId: hostId, mode: el.shadowRoot.mode || 'open', inspected: true, detection: 'ELEMENT_SHADOW_ROOT', reason: null };
      knownShadows.set(el, sri);
      info.shadowRoots.push(sri);
      for (const c of el.shadowRoot.children) {
        const ct = c.tagName.toLowerCase();
        if (!SKIP.has(ct)) {
          out.push({ el: c, scopeId: 'shadow:' + hostId });
        }
      }
    } else if (tag.includes('-') || SHADOW_CAPABLE.has(tag)) {
      let closed = null;
      if (root.chrome && chrome.dom && typeof chrome.dom.openOrClosedShadowRoot === 'function') {
        try {
          closed = chrome.dom.openOrClosedShadowRoot(el);
        } catch (e) {
          closed = null;
        }
        if (closed) {
          const sri = { hostNodeId: hostId, mode: 'closed', inspected: false, detection: 'EXTENSION_API',
            reason: 'shadow root fechado: encapsulamento intencional, conteúdo não inventariado' };
          knownShadows.set(el, sri);
          info.shadowRoots.push(sri);
        }
      } else if (tag.includes('-') && el.children.length === 0 && el.getClientRects().length > 0) {
        const sri = { hostNodeId: hostId, mode: 'closed', inspected: false, detection: 'HEURISTIC',
          reason: 'elemento personalizado renderizado sem filhos acessíveis (suspeita de shadow root fechado)' };
        knownShadows.set(el, sri);
        info.shadowRoots.push(sri);
      }
    }
    if (tag === 'iframe' || tag === 'frame') {
      const parentFrame = info.currentFrame;
      let doc = null;
      let reason = null;
      try {
        doc = el.contentDocument;
      } catch (e) {
        doc = null;
      }
      if (!doc || !doc.documentElement) {
        const sandbox = el.getAttribute('sandbox');
        if (sandbox !== null && !/\ballow-same-origin\b/.test(sandbox)) {
          reason = 'SANDBOXED';
        } else {
          try {
            const src = new URL(el.getAttribute('src') || 'about:blank', el.ownerDocument.baseURI);
            reason = src.origin !== el.ownerDocument.defaultView.location.origin && src.protocol.startsWith('http') ? 'CROSS_ORIGIN' : 'NOT_LOADED';
          } catch (e) {
            reason = 'NOT_LOADED';
          }
        }
      }
      let fid = frameIds.get(doc);
      if (doc && doc.documentElement && !fid) {
        fid = src.nestedPrefix + (++frameCounter);
        frameIds.set(doc, fid);
      }
      let fi;
      if (doc && doc.documentElement) {
        frameHosts.set(doc, el);
        frameWindows.set(fid, el.contentWindow);
        fi = { frameId: fid, parentFrameId: parentFrame, hostNodeId: hostId, url: CP.urlTemplate(doc.location.href), accessible: true, reason: null,
          title: CP.title(doc.title || ''), readyState: doc.readyState };
        out.push({ el: doc.documentElement, scopeId: 'doc:' + fid, frameId: fid });
      } else {
        fi = { frameId: src.placeholderPrefix + hostId, parentFrameId: parentFrame, hostNodeId: hostId,
          url: el.getAttribute('src') ? CP.urlTemplate(new URL(el.getAttribute('src'), el.ownerDocument.baseURI).href) : null, accessible: false, reason };
      }
      knownFrames.set(el, fi);
      info.frames.push(fi);
    }
    return out;
  }

  function modalOf(doc) {
    try {
      return doc.querySelector('dialog:modal');
    } catch (e) {
      return null;
    }
  }

  /**
   * Serializa a subárvore de {@code start} em pré-ordem.
   * @returns {{nodes: object[], truncated: boolean, visited: number}}
   */
  function serializeSubtree(start, startCtx, info, maxNodes) {
    const t = createTraversal(start, startCtx, info, maxNodes);
    t.step(Infinity);
    return { nodes: t.nodes, truncated: t.truncated, visited: t.visited };
  }

  /**
   * Travessia em pré-ordem executável em passos: {@code step(deadline)} processa elementos até o prazo e devolve
   * {@code true} ao terminar. Os nós produzidos ficam em {@code nodes} (podem ser retirados em partes).
   */
  function createTraversal(start, startCtx, info, maxNodes) {
    const t = { nodes: [], emitted: 0, truncated: false, visited: 0 };
    const stack = [{ el: start, parentId: startCtx.parentId, frameId: startCtx.frameId, scopeId: startCtx.scopeId, parentVisible: startCtx.parentVisible }];
    const modals = new Map();
    t.step = (deadline) => {
    let checks = 0;
    while (stack.length) {
      if ((++checks & 31) === 0 && performance.now() >= deadline) {
        return false;
      }
      const item = stack.pop();
      if (t.emitted >= maxNodes) {
        t.truncated = true;
        stack.length = 0;
        break;
      }
      if (!item.el.isConnected) {
        continue; // removido durante uma captura cooperativa: o delta posterior registra a remoção
      }
      t.visited++;
      const el = item.el;
      info.currentFrame = item.frameId;
      const children = composedChildren(el, info);
      const childIds = children.map((c) => NS.registry.idFor(c.el));
      if (!modals.has(el.ownerDocument)) {
        modals.set(el.ownerDocument, modalOf(el.ownerDocument));
      }
      const d = describe(el, {
        parentId: item.parentId, childIds, frameId: item.frameId, scopeId: item.scopeId,
        parentVisible: item.parentVisible, modal: modals.get(el.ownerDocument),
      });
      t.nodes.push(d);
      t.emitted++;
      for (let i = children.length - 1; i >= 0; i--) {
        const c = children[i];
        stack.push({
          el: c.el, parentId: d.id, frameId: c.frameId || item.frameId, scopeId: c.scopeId || item.scopeId,
          parentVisible: d.state.visible,
        });
      }
    }
    return true;
    };
    return t;
  }

  /** Cede a thread da página (scheduler.yield quando disponível; senão MessageChannel). */
  function yieldToPage() {
    if (root.scheduler && typeof root.scheduler.yield === 'function') {
      return root.scheduler.yield();
    }
    return new Promise((resolve) => {
      const ch = new MessageChannel();
      ch.port1.onmessage = () => resolve();
      ch.port2.postMessage(0);
    });
  }

  function connectedList(map) {
    const out = [];
    for (const [el, info] of map) {
      if (el.isConnected) {
        out.push(info);
      } else {
        map.delete(el);
      }
    }
    return out;
  }

  function rootFrameInfo() {
    return { frameId: src.rootFrame, parentFrameId: null, hostNodeId: null, url: CP.urlTemplate(location.href), accessible: true, reason: null,
      title: CP.title(document.title || ''), readyState: document.readyState };
  }

  /** URL de recurso como MODELO (origem + caminho do vocabulário) + NOMES de parâmetros do vocabulário. */
  function sanitizeUrl(raw, base) {
    try {
      const u = new URL(raw, base || document.baseURI);
      if (u.protocol === 'about:') {
        return { url: 'about:' + u.pathname, queryKeys: [] };
      }
      const keys = [];
      for (const k of u.searchParams.keys()) {
        if (!keys.includes(k) && keys.length < 20 && CP.identifier(k)) {
          keys.push(k);
        }
      }
      return { url: CP.urlTemplate(u.origin + u.pathname), queryKeys: keys };
    } catch (e) {
      return null;
    }
  }

  const MAX_RESOURCES = 400;
  const RESOURCE_KINDS = { script: 'SCRIPT', iframe: 'IFRAME', link: 'STYLESHEET', css: 'STYLESHEET' };

  /**
   * Recursos observáveis (CHROME-003): scripts/folhas referenciados no DOM (documento, frames acessíveis e shadow
   * roots abertos) e recursos efetivamente carregados (Resource Timing). Nenhum corpo, cabeçalho ou cookie.
   */
  function resourceList() {
    const out = [];
    const seen = new Set();
    const push = (r) => {
      const key = r.kind + '|' + r.url + '|' + r.observedBy + '|' + r.frameId;
      if (out.length < MAX_RESOURCES && !seen.has(key)) {
        seen.add(key);
        out.push(r);
      }
    };
    const docFrame = (doc) => (doc === document ? src.rootFrame : (frameIds.get(doc) || src.rootFrame));
    for (const root of NS.capture.observableRoots()) {
      const doc = root.nodeType === 9 ? root : root.ownerDocument;
      for (const s of root.querySelectorAll('script[src]')) {
        const u = sanitizeUrl(s.getAttribute('src'), doc.baseURI);
        if (u) {
          push({ kind: 'SCRIPT', url: u.url, observedBy: 'DOM', frameId: docFrame(doc), type: s.getAttribute('type'), nodeId: null, queryKeys: u.queryKeys });
        }
      }
      for (const l of root.querySelectorAll('link[rel~="stylesheet"][href]')) {
        const u = sanitizeUrl(l.getAttribute('href'), doc.baseURI);
        if (u) {
          push({ kind: 'STYLESHEET', url: u.url, observedBy: 'DOM', frameId: docFrame(doc), type: null, nodeId: null, queryKeys: u.queryKeys });
        }
      }
    }
    const perf = [[window, document]];
    for (const w of NS.capture.frameWindows()) {
      try {
        perf.push([w, w.document]);
      } catch (e) {
        // inacessível
      }
    }
    for (const [w, doc] of perf) {
      let entries = [];
      try {
        entries = w.performance.getEntriesByType('resource');
      } catch (e) {
        entries = [];
      }
      for (const e of entries.slice(-300)) {
        const kind = RESOURCE_KINDS[e.initiatorType];
        if (!kind) {
          continue; // fetch/xhr continuam em "requests"
        }
        const u = sanitizeUrl(e.name, doc.baseURI);
        if (u) {
          push({ kind, url: u.url, observedBy: 'RESOURCE_TIMING', frameId: docFrame(doc), type: null, nodeId: null, queryKeys: u.queryKeys });
        }
      }
    }
    return out;
  }

  function observedRequests() {
    try {
      return performance.getEntriesByType('resource')
        .filter((e) => e.initiatorType === 'fetch' || e.initiatorType === 'xmlhttprequest')
        .slice(-200)
        .map((e) => ({ url: CP.urlTemplate(e.name), initiatorType: e.initiatorType, startTime: Math.round(e.startTime * 10) / 10, duration: Math.round(e.duration * 10) / 10 }));
    } catch (e) {
      return [];
    }
  }

  function documentInfo(navigationKind) {
    return {
      url: CP.urlTemplate(location.href),
      title: CP.title(document.title || ''),
      readyState: document.readyState,
      viewport: { width: innerWidth, height: innerHeight, scrollX: Math.round(scrollX), scrollY: Math.round(scrollY), devicePixelRatio },
      navigationKind: navigationKind || 'MANUAL',
      pageSessionId: NS.registry.pageSessionId,
    };
  }

  function nextCaptureId() {
    return 'cap-' + NS.registry.pageSessionId + '-' + (++captureSeq);
  }

  NS.capture = {
    /** Captura completa. */
    full(options) {
      const opts = options || {};
      const maxNodes = opts.maxNodes || 20000;
      const t0 = performance.now();
      knownShadows.clear();
      knownFrames.clear();
      newPass();
      const info = { frames: [rootFrameInfo()], shadowRoots: [], currentFrame: src.rootFrame };
      const r = serializeSubtree(document.documentElement, { parentId: null, frameId: src.rootFrame, scopeId: 'doc:' + src.rootFrame, parentVisible: true }, info, maxNodes);
      const durationMs = Math.round((performance.now() - t0) * 100) / 100;
      return {
        privacy: CP.VERSION,
        captureId: nextCaptureId(),
        capturedAt: new Date().toISOString(),
        document: documentInfo(opts.navigationKind),
        frames: info.frames,
        shadowRoots: info.shadowRoots,
        nodes: r.nodes,
        requests: observedRequests(),
        resources: resourceList(),
        stats: { elementsVisited: r.visited, nodesEmitted: r.nodes.length, durationMs, truncated: r.truncated, maxNodes, mode: 'SYNC', slices: 1, maxSliceMs: durationMs, consistency: 'INSTANT' },
      };
    },

    /**
     * Captura completa COOPERATIVA: fatias de no máximo {@code sliceMs} na thread da página, cedendo entre elas;
     * nós entregues em partes por {@code onPart(nodes)} (awaited). Não é um instante único: mutações que ocorram
     * durante a travessia devem ser registradas pelo tracker (já ativo em modo de buffer) e entregues no 1º delta.
     */
    async fullAsync(options, onPart) {
      const opts = options || {};
      const maxNodes = opts.maxNodes || 20000;
      const sliceMs = opts.sliceMs || 8;
      const partSize = opts.partSize || 1000;
      const t0 = performance.now();
      knownShadows.clear();
      knownFrames.clear();
      newPass();
      const captureId = nextCaptureId();
      const info = { frames: [rootFrameInfo()], shadowRoots: [], currentFrame: src.rootFrame };
      const t = createTraversal(document.documentElement, { parentId: null, frameId: src.rootFrame, scopeId: 'doc:' + src.rootFrame, parentVisible: true }, info, maxNodes);
      let slices = 0;
      let maxSlice = 0;
      let parts = 0;
      for (;;) {
        const s0 = performance.now();
        const done = t.step(s0 + sliceMs);
        const sliceDur = performance.now() - s0;
        slices++;
        maxSlice = Math.max(maxSlice, sliceDur);
        while (t.nodes.length >= partSize || (done && t.nodes.length)) {
          const part = t.nodes.splice(0, partSize);
          parts++;
          await onPart(part, captureId);
        }
        if (done) {
          break;
        }
        await yieldToPage();
      }
      const durationMs = Math.round((performance.now() - t0) * 100) / 100;
      return {
        privacy: CP.VERSION,
        captureId,
        capturedAt: new Date().toISOString(),
        document: documentInfo(opts.navigationKind),
        frames: info.frames,
        shadowRoots: info.shadowRoots,
        requests: observedRequests(),
        resources: resourceList(),
        parts,
        stats: { elementsVisited: t.visited, nodesEmitted: t.emitted, durationMs, truncated: t.truncated, maxNodes,
          mode: 'COOPERATIVE', slices, maxSliceMs: Math.round(maxSlice * 100) / 100, consistency: 'BASE_PLUS_DELTA' },
      };
    },

    /** Configura a fonte (somente uma vez, antes da primeira captura). */
    configureSource(cfg) {
      if (src.configured || !cfg || !cfg.source || cfg.source === 'top') {
        return src.name;
      }
      const num = String(cfg.source).replace(/^x/, '');
      src.name = cfg.source;
      src.rootFrame = 'c' + num;
      src.nestedPrefix = 'c' + num + 'f';
      src.placeholderPrefix = 'c' + num + 'x';
      src.configured = true;
      NS.registry.configurePrefix(cfg.source);
      return src.name;
    },
    source() {
      return src.name;
    },
    /** Listas ATUAIS (somente elementos conectados) desta fonte, enviadas em cada delta. */
    frameList() {
      return [rootFrameInfo(), ...connectedList(knownFrames)];
    },
    shadowRootList() {
      return connectedList(knownShadows);
    },
    /**
     * Reconciliação limitada de escopos: encontra shadow roots abertos (ou fechados detectáveis) ainda não
     * inventariados — inclusive attachShadow() chamado em host JÁ conectado, que o MutationObserver não vê.
     * Percorre documento, frames acessíveis e shadow roots conhecidos (aninhados).
     */
    discoverShadowHosts() {
      const found = [];
      const scan = (rootNode) => {
        const all = rootNode.querySelectorAll('*');
        for (let i = 0; i < all.length; i++) {
          const el = all[i];
          if (isOverlay(el)) {
            continue;
          }
          if (el.shadowRoot && !openShadowRoots.has(el.shadowRoot)) {
            found.push(el);
          } else if (!el.shadowRoot && !knownShadows.has(el) && el.localName.includes('-')
            && root.chrome && chrome.dom && typeof chrome.dom.openOrClosedShadowRoot === 'function') {
            try {
              if (chrome.dom.openOrClosedShadowRoot(el)) {
                found.push(el);
              }
            } catch (e) {
              // sem acesso
            }
          }
        }
      };
      scan(document);
      for (const w of this.frameWindows()) {
        try {
          scan(w.document);
        } catch (e) {
          // frame inacessível
        }
      }
      for (const sr of Array.from(openShadowRoots)) {
        if (sr.host && sr.host.isConnected) {
          scan(sr);
        }
      }
      return found;
    },

    /** Serializa nós alterados/adicionados para um delta incremental. */
    partial(elements, maxNodes) {
      const info = { frames: [], shadowRoots: [], currentFrame: src.rootFrame };
      const out = new Map();
      let truncated = false;
      newPass();
      for (const { el, subtree } of elements) {
        if (!el.isConnected) {
          continue;
        }
        const sc = scopeOf(el);
        const parent = composedParent(el);
        const parentId = parent ? NS.registry.idFor(parent) : null;
        const parentVisible = parent ? (typeof parent.checkVisibility === 'function' ? parent.checkVisibility({ checkOpacity: true, checkVisibilityCSS: true }) : true) : true;
        if (subtree) {
          const r = serializeSubtree(el, { parentId, frameId: sc.frameId, scopeId: sc.scopeId, parentVisible }, info, maxNodes - out.size);
          r.nodes.forEach((n) => out.set(n.id, n));
          truncated = truncated || r.truncated;
        } else {
          info.currentFrame = sc.frameId;
          const children = composedChildren(el, info);
          const d = describe(el, { parentId, childIds: children.map((c) => NS.registry.idFor(c.el)), frameId: sc.frameId, scopeId: sc.scopeId, parentVisible, modal: modalOf(el.ownerDocument) });
          out.set(d.id, d);
        }
        if (out.size >= maxNodes) {
          truncated = true;
          break;
        }
      }
      return { nodes: Array.from(out.values()), truncated };
    },

    nextCaptureId,
    documentInfo,
    resourceList,
    sanitizeUrl,
    observedRequests,
    frameWindows() {
      return Array.from(frameWindows.values()).filter((w) => {
        try {
          return !!w.document;
        } catch (e) {
          return false;
        }
      });
    },
    /** Raízes que um MutationObserver precisa observar (documento, frames acessíveis, shadow roots abertos). */
    observableRoots() {
      const roots = [document];
      for (const w of this.frameWindows()) {
        roots.push(w.document);
      }
      for (const sr of Array.from(openShadowRoots)) {
        if (sr.host && sr.host.isConnected) {
          roots.push(sr);
        } else {
          openShadowRoots.delete(sr);
        }
      }
      return roots;
    },
    /**
     * Elementos cuja geometria (retângulo / dentro do viewport) mudou desde o último envio. Deslocamentos de layout
     * (rolagem, redimensionamento, conteúdo inserido acima) não são mutações DOM e não chegam ao MutationObserver.
     */
    geometryChanges(limit) {
      const out = [];
      for (const el of NS.registry.connectedElements()) {
        const last = geometry.get(el);
        if (last === undefined || isOverlay(el)) {
          continue;
        }
        const r = el.getBoundingClientRect();
        const win = el.ownerDocument.defaultView;
        const x = round(r.x);
        const y = round(r.y);
        const w = round(r.width);
        const h = round(r.height);
        const inViewport = w > 0 && h > 0 && x < win.innerWidth && y < win.innerHeight && x + w > 0 && y + h > 0;
        if (last !== x + ',' + y + ',' + w + ',' + h + ',' + inViewport) {
          out.push(el);
          if (out.length >= limit) {
            break;
          }
        }
      }
      return out;
    },
    /**
     * Descendentes de {@code rootEl} cuja visibilidade mudou desde o último envio (NEKKORA-REAL-001). Usado quando uma
     * classe/estilo muda num ancestral com subárvore grande (ex.: classe do body ao rolar): em vez de redescrever toda
     * a subárvore, só os elementos que de fato mudaram de visibilidade são redescritos.
     */
    visibilityChanges(rootEl, limit) {
      const out = [];
      for (const el of NS.registry.connectedElements()) {
        const last = visibility.get(el);
        if (last === undefined || el === rootEl || !rootEl.contains(el) || isOverlay(el)) {
          continue;
        }
        const tag = el.tagName.toLowerCase();
        if (tag === 'option' || tag === 'optgroup') {
          continue; // sem caixa própria: seguem o pai
        }
        const now = typeof el.checkVisibility === 'function'
          ? el.checkVisibility({ checkOpacity: true, checkVisibilityCSS: true, contentVisibilityAuto: true })
          : el.getClientRects().length > 0;
        if (now !== last) {
          out.push(el);
          if (out.length >= limit) {
            break;
          }
        }
      }
      return out;
    },
    /** Atualização compacta de geometria (NEKKORA-REAL-001): retângulo atual e presença no viewport. */
    geometryOf(el) {
      const id = NS.registry.peek(el);
      if (!id || isOverlay(el)) {
        return null;
      }
      const r = el.getBoundingClientRect();
      const win = el.ownerDocument.defaultView;
      const x = round(r.x);
      const y = round(r.y);
      const width = round(r.width);
      const height = round(r.height);
      const inViewport = width > 0 && height > 0 && x < win.innerWidth && y < win.innerHeight && x + width > 0 && y + height > 0;
      geometry.set(el, x + ',' + y + ',' + width + ',' + height + ',' + inViewport);
      return { id, x, y, width, height, inViewport };
    },
    frameOffset(el) {
      // Deslocamento acumulado de iframes até o documento principal (coordenadas do viewport principal).
      let x = 0;
      let y = 0;
      let doc = el.ownerDocument;
      while (doc && doc !== document) {
        const host = frameHosts.get(doc);
        if (!host) {
          break;
        }
        const r = host.getBoundingClientRect();
        const cs = host.ownerDocument.defaultView.getComputedStyle(host);
        x += r.left + parseFloat(cs.borderLeftWidth || '0') + parseFloat(cs.paddingLeft || '0');
        y += r.top + parseFloat(cs.borderTopWidth || '0') + parseFloat(cs.paddingTop || '0');
        doc = host.ownerDocument;
      }
      return { x, y };
    },
    isOverlay,
  };
})(globalThis);
