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
  const announced = new Map(); // token -> iframe hospedeiro (handshake de frames)

  /** Todos os iframes deste documento, de frames acessíveis e de shadow roots abertos. */
  function allIframes() {
    const out = [];
    for (const r of NS.capture.observableRoots()) {
      out.push(...r.querySelectorAll('iframe, frame'));
    }
    return out;
  }

  root.addEventListener('message', (e) => {
    const t = e.data && typeof e.data === 'object' ? e.data.__nekkoraFrame : null;
    if (typeof t !== 'string' || !t.startsWith('nkf-')) {
      return;
    }
    if (announced.size > 50) {
      announced.clear(); // anúncios não consultados (outro ancestral era o dono da captura)
    }
    // Aceita somente o anúncio vindo de um iframe deste documento (e.source = contentWindow do hospedeiro).
    const host = allIframes().find((f) => f.contentWindow === e.source);
    if (host) {
      announced.set(t, host);
    }
  });

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
      url: NS.privacyUrl(),
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
    'cs.ping': () => ({ ok: true, url: NS.privacyUrl(), pageSessionId: NS.registry.pageSessionId }),
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
      if (a.kind === 'frame') {
        el.scrollIntoView({ block: 'nearest', inline: 'nearest' }); // "Destacar iframe": a região precisa ficar visível
      }
      NS.highlighter.show(a.kind || 'selection', el, a.label || '', a.nodeId);
      return { ok: true };
    },
    /** Remove a camada de destaque do documento (desativação da inspeção). */
    'cs.overlay.destroy': () => {
      NS.highlighter.destroy();
      selected = null;
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
    // ---- sessão de captura (CHROME-002): base cooperativa + deltas sequenciados
    /** Liga o rastreamento em modo BUFFER e faz a captura-base em fatias, entregando as partes ao service worker. */
    'cs.session.begin': async (a) => {
      if (a.source && a.source !== 'top') {
        NS.capture.configureSource({ source: a.source });
      }
      const sessionId = a.sessionId;
      NS.tracker.prepare({ sessionId, source: NS.capture.source() }, (delta) => {
        if (delta && delta.__resync) {
          return send('resync-needed', { sessionId, reason: delta.__resync });
        }
        return send('delta', delta);
      });
      if (a.cooperative === false) {
        const snap = NS.capture.full({ maxNodes: a.maxNodes, navigationKind: a.navigationKind });
        return snap; // nós em linha (modo síncrono, usado para comparação de desempenho)
      }
      const header = await NS.capture.fullAsync({ maxNodes: a.maxNodes, navigationKind: a.navigationKind, sliceMs: a.sliceMs, partSize: a.partSize },
        async (nodes, captureId) => {
          const ack = await send('capture-part', { sessionId, captureId, nodes });
          if (!ack || !ack.ok) {
            throw new Error('parte da captura recusada: ' + (ack && ack.error));
          }
        });
      return header;
    },
    'cs.session.confirmBase': (a) => NS.tracker.confirmBase(a),
    'cs.session.flush': (a) => NS.tracker.flushNow(a),
    'cs.session.pause': (a) => {
      NS.tracker.pause(a && a.reason);
      return NS.tracker.stats();
    },
    'cs.session.navigation': (a) => {
      NS.tracker.noteNavigation(a && a.kind);
      return { ok: true };
    },
    'cs.session.stop': () => {
      NS.tracker.stop();
      NS.registry.sweep();
      return diagnostics();
    },
    'cs.session.debug': (a) => {
      if (a.action === 'skipSeq') {
        return { seq: NS.tracker.debugSkipSeq() };
      }
      if (a.action === 'failFlushes') {
        return { failFlushes: NS.tracker.debugFailFlushes(a.count == null ? 1 : a.count) };
      }
      if (a.action === 'keepLastDelta') {
        NS.tracker.debugKeepLast(true);
        return { ok: true };
      }
      if (a.action === 'lastDelta') {
        return { delta: NS.tracker.debugLastDelta() };
      }
      return { error: 'UNKNOWN_DEBUG_ACTION' };
    },
    'cs.session.status': () => ({ tracker: NS.tracker.stats(), inspectorMode: NS.inspector.mode(), url: NS.privacyUrl(),
      pageSessionId: NS.registry.pageSessionId, source: NS.capture.source() }),
    // ---- iframes de outra origem permitida: handshake para localizar o iframe hospedeiro no documento pai
    'cs.frame.announce': (a) => {
      if (root.top === root) {
        return { ok: false, error: 'NOT_A_FRAME' };
      }
      // Token aleatório (nenhum dado da página) a TODOS os ancestrais: em cadeias de mesma origem o iframe
      // hospedeiro é capturado pelo content script de um ancestral (dono da captura), não pelo pai imediato.
      for (let w = root.parent, guard = 0; w && guard < 20; w = w === w.parent ? null : w.parent, guard++) {
        w.postMessage({ __nekkoraFrame: a.token }, '*');
      }
      return { ok: true };
    },
    'cs.frame.lookup': async (a) => {
      const deadline = Date.now() + (a.waitMs || 1000);
      while (!announced.has(a.token) && Date.now() < deadline) {
        await new Promise((r) => setTimeout(r, 25));
      }
      const iframe = announced.get(a.token);
      announced.delete(a.token);
      if (!iframe) {
        return { found: false };
      }
      let accessibleFromParent = false;
      try {
        accessibleFromParent = !!(iframe.contentDocument && iframe.contentDocument.documentElement);
      } catch (e) {
        accessibleFromParent = false;
      }
      return { found: true, hostNodeId: NS.registry.idFor(iframe), accessibleFromParent, sandboxed: iframe.hasAttribute('sandbox') };
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
  send('content-ready', { url: NS.privacyUrl(), pageSessionId: NS.registry.pageSessionId, top: root.top === root });
})(globalThis);
