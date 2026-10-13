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
  let frameCounter = 0;
  let captureSeq = 0;

  function frameIdOf(doc) {
    if (doc === document) {
      return 'f0';
    }
    return frameIds.get(doc) || null;
  }

  function isOverlay(el) {
    return el.hasAttribute && el.hasAttribute('data-nekkora-overlay');
  }

  function scopeOf(el) {
    const r = el.getRootNode();
    if (r instanceof ShadowRoot) {
      return { scopeId: 'shadow:' + NS.registry.idFor(r.host), frameId: frameIdOf(r.host.ownerDocument) || 'f0' };
    }
    const fid = frameIdOf(el.ownerDocument) || 'f0';
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

  function ownText(el) {
    let s = '';
    for (const n of el.childNodes) {
      if (n.nodeType === Node.TEXT_NODE) {
        s += ' ' + n.nodeValue;
      }
    }
    s = s.replace(/\s+/g, ' ').trim();
    return s.length > MAX_TEXT_LEN ? s.slice(0, MAX_TEXT_LEN) : s;
  }

  function attributes(el) {
    const out = {};
    for (const a of el.attributes) {
      const name = a.name;
      if (name === 'style' || name.startsWith('data-nekkora')) {
        continue;
      }
      let v = a.value;
      if (name.startsWith('on')) {
        v = '[manipulador declarado]';
      } else if (name === 'value' && !NS.sensitivity.valueAttributeAllowed(el)) {
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
    const rectObj = el.getBoundingClientRect();
    const rect = { x: round(rectObj.x), y: round(rectObj.y), width: round(rectObj.width), height: round(rectObj.height) };
    const vw = win.innerWidth;
    const vh = win.innerHeight;
    const inViewport = rect.width > 0 && rect.height > 0 && rect.x < vw && rect.y < vh && rect.x + rect.width > 0 && rect.y + rect.height > 0;
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
    let exposedValue = null;
    if (textEntry || tag === 'select') {
      const v = el.value == null ? '' : String(el.value);
      hasValue = v.length > 0;
      valueLength = sensitive ? null : v.length;
      if (textEntry && NS.sensitivity.isSearchField(el)) {
        exposedValue = v.length > 200 ? v.slice(0, 200) : v;
      }
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

    return {
      id: NS.registry.idFor(el),
      parentId: ctx.parentId,
      childIds: ctx.childIds,
      tag,
      frameId: ctx.frameId,
      scopeId: ctx.scopeId,
      attributes: attributes(el),
      ownText: ownText(el),
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
      info.shadowRoots.push({ hostNodeId: hostId, mode: el.shadowRoot.mode || 'open', inspected: true, detection: 'ELEMENT_SHADOW_ROOT', reason: null });
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
          info.shadowRoots.push({ hostNodeId: hostId, mode: 'closed', inspected: false, detection: 'EXTENSION_API',
            reason: 'shadow root fechado: encapsulamento intencional, conteúdo não inventariado' });
        }
      } else if (tag.includes('-') && el.children.length === 0 && el.getClientRects().length > 0) {
        info.shadowRoots.push({ hostNodeId: hostId, mode: 'closed', inspected: false, detection: 'HEURISTIC',
          reason: 'elemento personalizado renderizado sem filhos acessíveis (suspeita de shadow root fechado)' });
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
        fid = 'f' + (++frameCounter);
        frameIds.set(doc, fid);
      }
      if (doc && doc.documentElement) {
        frameHosts.set(doc, el);
        frameWindows.set(fid, el.contentWindow);
        info.frames.push({ frameId: fid, parentFrameId: parentFrame, hostNodeId: hostId, url: doc.location.href, accessible: true, reason: null });
        out.push({ el: doc.documentElement, scopeId: 'doc:' + fid, frameId: fid });
      } else {
        info.frames.push({ frameId: 'x' + hostId, parentFrameId: parentFrame, hostNodeId: hostId,
          url: el.getAttribute('src') || null, accessible: false, reason });
      }
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
    const nodes = [];
    const stack = [{ el: start, parentId: startCtx.parentId, frameId: startCtx.frameId, scopeId: startCtx.scopeId, parentVisible: startCtx.parentVisible }];
    let truncated = false;
    let visited = 0;
    const modals = new Map();
    while (stack.length) {
      const item = stack.pop();
      if (nodes.length >= maxNodes) {
        truncated = true;
        break;
      }
      visited++;
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
      nodes.push(d);
      for (let i = children.length - 1; i >= 0; i--) {
        const c = children[i];
        stack.push({
          el: c.el, parentId: d.id, frameId: c.frameId || item.frameId, scopeId: c.scopeId || item.scopeId,
          parentVisible: d.state.visible,
        });
      }
    }
    return { nodes, truncated, visited };
  }

  function observedRequests() {
    try {
      return performance.getEntriesByType('resource')
        .filter((e) => e.initiatorType === 'fetch' || e.initiatorType === 'xmlhttprequest')
        .slice(-200)
        .map((e) => ({ url: e.name, initiatorType: e.initiatorType, startTime: Math.round(e.startTime * 10) / 10, duration: Math.round(e.duration * 10) / 10 }));
    } catch (e) {
      return [];
    }
  }

  function documentInfo(navigationKind) {
    return {
      url: location.href,
      title: document.title,
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
      const info = { frames: [{ frameId: 'f0', parentFrameId: null, hostNodeId: null, url: location.href, accessible: true, reason: null }], shadowRoots: [], currentFrame: 'f0' };
      const r = serializeSubtree(document.documentElement, { parentId: null, frameId: 'f0', scopeId: 'doc:f0', parentVisible: true }, info, maxNodes);
      const durationMs = Math.round((performance.now() - t0) * 100) / 100;
      return {
        captureId: nextCaptureId(),
        capturedAt: new Date().toISOString(),
        document: documentInfo(opts.navigationKind),
        frames: info.frames,
        shadowRoots: info.shadowRoots,
        nodes: r.nodes,
        requests: observedRequests(),
        stats: { elementsVisited: r.visited, nodesEmitted: r.nodes.length, durationMs, truncated: r.truncated, maxNodes },
      };
    },

    /** Serializa nós alterados/adicionados para um delta incremental. */
    partial(elements, maxNodes) {
      const info = { frames: [], shadowRoots: [], currentFrame: 'f0' };
      const out = new Map();
      let truncated = false;
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
