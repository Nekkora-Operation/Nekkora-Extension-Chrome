/*
 * Ponto de entrada do content script: recebe comandos do service worker e publica eventos
 * (seleção, deltas, alvo desconectado). Só atua em origens locais de laboratório.
 */
(function (root) {
  const NS = root.NekkoraCS;
  if (NS.main || !NS.isAllowedLocation(location)) {
    return;
  }

  let selected = null; // { nodeId, targetId, label }

  function send(event, data) {
    try {
      return chrome.runtime.sendMessage({ source: 'content', event, data });
    } catch (e) {
      return Promise.resolve(null); // contexto da extensão invalidado (recarga)
    }
  }

  NS.highlighter.onSelectionDetached((el, nodeId) => {
    if (selected && selected.nodeId === nodeId) {
      send('selection-detached', { nodeId, targetId: selected.targetId });
    }
  });

  function onInspectorSelect(el) {
    const nodeId = NS.registry.idFor(el);
    selected = { nodeId, targetId: null, label: '' };
    NS.highlighter.show('selection', el, 'selecionado: ' + el.tagName.toLowerCase(), nodeId);
    send('element-selected', { nodeId });
  }

  function diagnostics() {
    return {
      url: location.href,
      pageSessionId: NS.registry.pageSessionId,
      inspectorMode: NS.inspector.mode(),
      listeners: NS.inspector.listenerCount(),
      observers: NS.tracker.observerCount(),
      tracker: NS.tracker.stats(),
      highlight: NS.highlighter.state(),
      registrySize: NS.registry.size(),
    };
  }

  const handlers = {
    'cs.ping': () => ({ ok: true, url: location.href, pageSessionId: NS.registry.pageSessionId }),
    'cs.capture': (a) => NS.capture.full({ maxNodes: a && a.maxNodes, navigationKind: a && a.navigationKind }),
    'cs.inspect.start': () => {
      NS.inspector.start(onInspectorSelect);
      return diagnostics();
    },
    'cs.inspect.stop': () => {
      NS.inspector.stop();
      return diagnostics();
    },
    /** Desliga tudo: inspeção, rastreamento e overlay. */
    'cs.disable': () => {
      NS.inspector.stop();
      NS.tracker.stop();
      NS.highlighter.destroy();
      selected = null;
      NS.registry.sweep();
      return diagnostics();
    },
    'cs.highlight': (a) => {
      const el = NS.registry.elementFor(a.nodeId);
      if (!el || !el.isConnected) {
        return { ok: false, error: 'NODE_NOT_CONNECTED' };
      }
      if (a.kind === 'selection') {
        selected = { nodeId: a.nodeId, targetId: a.targetId || (selected && selected.targetId) || null, label: a.label || '' };
      }
      NS.highlighter.show(a.kind || 'selection', el, a.label || '', a.nodeId);
      return { ok: true };
    },
    'cs.clearHighlight': (a) => {
      NS.highlighter.hide((a && a.kind) || 'selection');
      if (!a || a.kind === 'selection') {
        selected = null;
      }
      return { ok: true };
    },
    'cs.setSelectedTarget': (a) => {
      if (selected && selected.nodeId === a.nodeId) {
        selected.targetId = a.targetId;
      }
      return { ok: true };
    },
    'cs.verifyLocators': (a) => ({ checks: NS.locators.verify(a.nodeId, a.locators) }),
    'cs.runLocator': (a) => NS.locators.runOne(a.locator, a.nodeId ? NS.registry.elementFor(a.nodeId) : null),
    'cs.tracking.start': (a) => {
      NS.tracker.start(a.baseCaptureId,
        (delta) => send('delta', delta),
        (reason) => send('resync-needed', { reason }));
      return diagnostics();
    },
    'cs.tracking.rebase': (a) => {
      NS.tracker.rebase(a.baseCaptureId);
      return { ok: true };
    },
    'cs.tracking.stop': () => {
      NS.tracker.stop();
      return diagnostics();
    },
    'cs.tracking.flush': async () => {
      await NS.tracker.flushNow();
      return NS.tracker.stats();
    },
    'cs.diagnostics': () => diagnostics(),
    'cs.nodeInfo': (a) => {
      const el = NS.registry.elementFor(a.nodeId);
      if (!el) {
        return { exists: false, connected: false };
      }
      const r = el.getBoundingClientRect();
      return { exists: true, connected: el.isConnected, tag: el.tagName.toLowerCase(), id: el.id || null,
        text: (el.textContent || '').trim().slice(0, 80), rect: { left: r.left, top: r.top, width: r.width, height: r.height } };
    },
    /** Encontra o id temporário de um elemento por CSS (uso do harness de testes; somente leitura). */
    'cs.idFor': (a) => {
      let r = document;
      if (a.scope) {
        const s = NS.locators.scopeRoot(a.scope);
        if (s.error) {
          return { error: s.error };
        }
        r = s.root;
      }
      const found = Array.from(r.querySelectorAll(a.css));
      return { count: found.length, nodeIds: found.map((el) => NS.registry.idFor(el)) };
    },
  };

  chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
    if (!msg || msg.target !== 'content' || sender.id !== chrome.runtime.id) {
      return false;
    }
    const h = handlers[msg.cmd];
    if (!h) {
      sendResponse({ __error: 'UNKNOWN_CONTENT_COMMAND: ' + msg.cmd });
      return false;
    }
    Promise.resolve()
      .then(() => h(msg.args || {}))
      .then((result) => sendResponse(result))
      .catch((e) => sendResponse({ __error: String(e && e.stack || e) }));
    return true; // resposta assíncrona
  });

  NS.main = { diagnostics };
  send('content-ready', { url: location.href, pageSessionId: NS.registry.pageSessionId });
})(globalThis);
