/*
 * Inspector Controller — modos OFF / INSPECT.
 *
 * Em INSPECT: o elemento sob o cursor recebe contorno rosa tracejado; o clique SELECIONA o elemento
 * e é interceptado na fase de captura (preventDefault + stopImmediatePropagation), de modo que a
 * ação normal do componente NÃO é disparada. Em OFF todos os ouvintes, observadores e o overlay são
 * removidos.
 */
(function (root) {
  const NS = root.NekkoraCS;
  if (NS.inspector) {
    return;
  }
  const BLOCKED = ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click', 'dblclick', 'auxclick', 'submit', 'touchstart', 'touchend'];
  const INTERACTIVE_TAGS = new Set(['a', 'button', 'input', 'select', 'textarea', 'label', 'summary', 'option', 'td', 'th', 'li']);

  let mode = 'OFF';
  let attached = [];       // { target, type, fn }
  let onSelect = null;
  let lastHover = null;

  function relevant(el) {
    // Sobe até 3 níveis buscando um elemento com semântica de controle (ex.: <span> dentro de <button>).
    let cur = el;
    for (let i = 0; i < 4 && cur && cur.nodeType === 1; i++) {
      const tag = cur.tagName.toLowerCase();
      if (INTERACTIVE_TAGS.has(tag) || cur.hasAttribute('role') || cur.hasAttribute('tabindex') || cur.isContentEditable && cur.hasAttribute('contenteditable')) {
        return cur;
      }
      cur = cur.parentElement || (cur.parentNode instanceof ShadowRoot ? cur.parentNode.host : null);
    }
    return el;
  }

  function targetOf(event) {
    const path = event.composedPath ? event.composedPath() : [event.target];
    for (const n of path) {
      if (n && n.nodeType === 1) {
        if (NS.capture.isOverlay(n)) {
          return null;
        }
        return relevant(n);
      }
    }
    return null;
  }

  function onMove(e) {
    const t = targetOf(e);
    if (!t || t === lastHover) {
      return;
    }
    lastHover = t;
    NS.highlighter.show('hover', t, t.tagName.toLowerCase() + (t.id ? '#' + t.id : ''), NS.registry.idFor(t));
  }

  function block(e) {
    if (mode !== 'INSPECT') {
      return;
    }
    e.preventDefault();
    e.stopPropagation();
    e.stopImmediatePropagation();
    if (e.type === 'click') {
      const t = targetOf(e);
      if (t && onSelect) {
        onSelect(t);
      }
    }
  }

  function onKey(e) {
    if (e.key === 'Escape' && mode === 'INSPECT') {
      NS.highlighter.hide('hover');
    }
  }

  function listen(target, type, fn, options) {
    target.addEventListener(type, fn, options);
    attached.push({ target, type, fn, options });
  }

  function windows() {
    return [window, ...NS.capture.frameWindows()];
  }

  NS.inspector = {
    start(selectCallback) {
      if (mode === 'INSPECT') {
        onSelect = selectCallback;
        return;
      }
      mode = 'INSPECT';
      onSelect = selectCallback;
      for (const w of windows()) {
        listen(w, 'pointermove', onMove, { capture: true, passive: true });
        for (const type of BLOCKED) {
          listen(w, type, block, { capture: true });
        }
        listen(w, 'keydown', onKey, { capture: true });
      }
    },
    stop() {
      for (const a of attached) {
        a.target.removeEventListener(a.type, a.fn, a.options);
      }
      attached = [];
      mode = 'OFF';
      onSelect = null;
      lastHover = null;
      NS.highlighter.hide('hover');
    },
    mode() {
      return mode;
    },
    listenerCount() {
      return attached.length;
    },
  };
})(globalThis);
