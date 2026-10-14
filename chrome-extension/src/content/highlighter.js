/*
 * Destaque visual com o rosa oficial da Nekkora (#C2466D).
 *
 * Estratégia: overlay independente (host <nekkora-overlay> com shadow root FECHADO, posição fixa,
 * pointer-events:none). Não altera estilo nem layout do elemento destacado. A caixa reproduz
 * "outline: 2px solid; outline-offset: 2px" e acompanha scroll, redimensionamento, reposicionamento
 * e re-renderização por meio de um laço requestAnimationFrame ativo apenas enquanto há destaque.
 */
(function (root) {
  const NS = root.NekkoraCS;
  if (NS.highlighter) {
    return;
  }
  const T = root.NekkoraTokens;

  let host = null;
  let shadow = null;
  const boxes = {};          // kind -> { el, label, target, text }
  let raf = 0;
  let detachedHandler = null;

  function ensureHost() {
    if (host && host.isConnected) {
      return;
    }
    host = document.createElement('nekkora-overlay');
    host.setAttribute('data-nekkora-overlay', '');
    host.style.cssText = 'all: initial; position: fixed; inset: 0; pointer-events: none; z-index: ' + T.zIndexOverlay + ';';
    shadow = host.attachShadow({ mode: 'closed' });
    const style = document.createElement('style');
    style.textContent = `
      :host { all: initial; }
      .box { position: fixed; box-sizing: border-box; pointer-events: none; display: none;
             border: ${T.highlightWidthPx}px solid ${T.primary}; border-radius: 2px; }
      .box.hover { border-style: dashed; }
      .box.selection { box-shadow: 0 0 0 1px rgba(255,255,255,.85); }
      .box.frame { border-style: dotted; background: rgba(194,70,109,.06); } /* região do iframe imediato (DEV) */
      .label { position: fixed; display: none; pointer-events: none; font: 600 11px/1.4 ${T.fontFamily};
               color: #fff; background: ${T.primary}; padding: 1px 6px; border-radius: 4px; white-space: nowrap;
               max-width: 60vw; overflow: hidden; text-overflow: ellipsis; }
    `;
    shadow.appendChild(style);
    document.documentElement.appendChild(host);
  }

  function box(kind) {
    ensureHost();
    if (!boxes[kind]) {
      const el = document.createElement('div');
      el.className = 'box ' + kind;
      const label = document.createElement('div');
      label.className = 'label';
      shadow.appendChild(el);
      shadow.appendChild(label);
      boxes[kind] = { el, label, target: null, text: '', lastRect: null };
    }
    return boxes[kind];
  }

  /** Retângulo do elemento no viewport principal, com a folga do outline-offset. */
  function geometry(target) {
    const r = target.getBoundingClientRect();
    const off = NS.capture.frameOffset(target);
    const pad = T.highlightOffsetPx + T.highlightWidthPx;
    return { left: r.left + off.x - pad, top: r.top + off.y - pad, width: r.width + pad * 2, height: r.height + pad * 2, inner: r };
  }

  function paint() {
    raf = 0;
    let active = false;
    for (const kind of Object.keys(boxes)) {
      const b = boxes[kind];
      if (!b.target) {
        b.el.style.display = 'none';
        b.label.style.display = 'none';
        continue;
      }
      if (!b.target.isConnected) {
        const lost = b.target;
        b.target = null;
        b.el.style.display = 'none';
        b.label.style.display = 'none';
        if (kind === 'selection' && detachedHandler) {
          detachedHandler(lost, b.nodeId);
        }
        continue;
      }
      active = true;
      const g = geometry(b.target);
      const s = b.el.style;
      s.display = g.inner.width === 0 && g.inner.height === 0 ? 'none' : 'block';
      s.left = g.left + 'px';
      s.top = g.top + 'px';
      s.width = g.width + 'px';
      s.height = g.height + 'px';
      b.lastRect = { left: g.left, top: g.top, width: g.width, height: g.height };
      if (b.text) {
        const l = b.label.style;
        l.display = s.display;
        l.left = Math.max(0, g.left) + 'px';
        l.top = (g.top > 20 ? g.top - 20 : g.top + g.height + 2) + 'px';
        b.label.textContent = b.text;
      } else {
        b.label.style.display = 'none';
      }
    }
    if (active) {
      raf = requestAnimationFrame(paint);
    }
  }

  function startLoop() {
    if (!raf) {
      raf = requestAnimationFrame(paint);
    }
  }

  NS.highlighter = {
    show(kind, target, text, nodeId) {
      if (!target || NS.capture.isOverlay(target)) {
        return;
      }
      const b = box(kind);
      b.target = target;
      b.text = text || '';
      b.nodeId = nodeId || null;
      paint(); // posiciona imediatamente (e agenda acompanhamento)
      startLoop();
    },
    hide(kind) {
      if (boxes[kind]) {
        boxes[kind].target = null;
        boxes[kind].el.style.display = 'none';
        boxes[kind].label.style.display = 'none';
      }
    },
    onSelectionDetached(fn) {
      detachedHandler = fn;
    },
    /** Remove completamente o overlay e o laço de acompanhamento. */
    destroy() {
      if (raf) {
        cancelAnimationFrame(raf);
        raf = 0;
      }
      for (const k of Object.keys(boxes)) {
        delete boxes[k];
      }
      if (host) {
        host.remove();
      }
      host = null;
      shadow = null;
    },
    /** Estado para testes e diagnóstico (lido pelo próprio mundo isolado). */
    state() {
      const out = { overlayPresent: !!(host && host.isConnected), loopActive: raf !== 0, boxes: {} };
      for (const [k, b] of Object.entries(boxes)) {
        const cs = b.el.isConnected ? getComputedStyle(b.el) : null;
        out.boxes[k] = {
          visible: !!b.target && b.el.style.display === 'block',
          nodeId: b.nodeId,
          rect: b.lastRect,
          targetRect: b.target ? (() => { const r = b.target.getBoundingClientRect(); const o = NS.capture.frameOffset(b.target); return { left: r.left + o.x, top: r.top + o.y, width: r.width, height: r.height }; })() : null,
          borderColor: cs ? cs.borderTopColor : null,
          borderWidth: cs ? cs.borderTopWidth : null,
          label: b.text,
        };
      }
      return out;
    },
  };
})(globalThis);
