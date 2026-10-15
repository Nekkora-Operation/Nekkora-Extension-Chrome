/*
 * Inspector Controller — modos OFF / INSPECT / SUSPENDED.
 *
 * Em INSPECT: o elemento sob o cursor recebe contorno rosa tracejado; o clique SELECIONA o elemento
 * e é interceptado na fase de captura (preventDefault + stopImmediatePropagation), de modo que a
 * ação normal do componente NÃO é disparada. Em OFF todos os ouvintes, observadores e o overlay são
 * removidos.
 *
 * SUSPENDED (STUDIO-003, D-STUDIO-003-03): enquanto os sensores do Recorder do Studio estão ligados neste documento, a
 * inspeção pedida fica suspensa — sem ouvintes, para que nunca haja dois controladores interceptando o mesmo clique.
 * O pedido é lembrado: ao desligar o Recorder, os ouvintes voltam (prioridade explícita e reversível).
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
  let suspendedBy = null; // motivo da suspensão (ex.: RECORDER)
  let requested = false; // a inspeção foi pedida e deve voltar quando a suspensão terminar

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

  function attachAll() {
    for (const w of windows()) {
      listen(w, 'pointermove', onMove, { capture: true, passive: true });
      for (const type of BLOCKED) {
        listen(w, type, block, { capture: true });
      }
      listen(w, 'keydown', onKey, { capture: true });
    }
  }

  function detachAll() {
    for (const a of attached) {
      a.target.removeEventListener(a.type, a.fn, a.options);
    }
    attached = [];
    lastHover = null;
    NS.highlighter.hide('hover');
  }

  NS.inspector = {
    start(selectCallback) {
      onSelect = selectCallback;
      requested = true;
      if (mode === 'INSPECT' || mode === 'SUSPENDED') {
        return; // já ativa, ou suspensa pelo Recorder (volta quando ele parar)
      }
      if (suspendedBy) {
        mode = 'SUSPENDED';
        return;
      }
      mode = 'INSPECT';
      attachAll();
    },
    stop() {
      detachAll();
      mode = 'OFF';
      onSelect = null;
      requested = false;
    },
    /** O Recorder assume a interceptação deste documento: a inspeção pedida fica suspensa (sem ouvintes). */
    suspend(reason) {
      suspendedBy = reason || 'RECORDER';
      if (mode === 'INSPECT') {
        detachAll();
        mode = 'SUSPENDED';
      }
    },
    /** O Recorder liberou o documento: a inspeção pedida volta com os ouvintes de antes. */
    resume() {
      suspendedBy = null;
      if (mode === 'SUSPENDED' && requested) {
        mode = 'INSPECT';
        attachAll();
      } else if (mode === 'SUSPENDED') {
        mode = 'OFF';
      }
    },
    mode() {
      return mode;
    },
    listenerCount() {
      return attached.length;
    },
  };
})(globalThis);
