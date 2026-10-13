/*
 * Mutation Tracker — observa a página e produz DELTAS incrementais sequenciados (nunca reconstrói a página
 * inteira a cada mutação).
 *
 * Ciclo (CHROME-002):
 *   prepare(sessão)      observadores ligados em modo BUFFER antes da captura-base (nada se perde durante a
 *                         travessia cooperativa);
 *   confirmBase(época)   base aceita pelo motor Java: o que foi acumulado vira o delta #1 e o envio começa;
 *   deltas               lotes (debounce 100 ms, espera máxima 500 ms) com {sessionId, source, epoch, seq};
 *   falha no envio       o tracker PAUSA (não recaptura sozinho): o service worker decide a ressincronização;
 *   flushNow()           barreira de sincronização: envia o pendente e aguarda a confirmação.
 *
 * Também reconcilia periodicamente (1 s) escopos novos: shadow roots criados por attachShadow() em host já
 * conectado não geram registro no MutationObserver.
 */
(function (root) {
  const NS = root.NekkoraCS;
  if (NS.tracker) {
    return;
  }
  const DEBOUNCE_MS = 100;
  const MAX_WAIT_MS = 500;
  const MAX_DELTA_NODES = 5000;
  const MAX_PENDING = 50000;
  const RECONCILE_MS = 1000;
  const GEOMETRY_DEBOUNCE_MS = 300;
  const SUBTREE_ATTRS = new Set(['class', 'style', 'hidden', 'open', 'inert']);
  const SKIP = new Set(['script', 'style', 'noscript', 'template', 'head', 'meta', 'link', 'title', 'base']);

  let mode = 'OFF';             // OFF | BUFFER | ACTIVE | PAUSED
  let session = null;           // { sessionId, source, epoch }
  let seq = 0;
  let sink = null;              // async (delta) => { ok, error }
  let observers = new Map();    // raiz observada -> MutationObserver
  let dirty = new Map();        // Element -> subtree:boolean
  let removed = new Set();      // ids temporários
  let raw = 0;
  let batches = 0;
  let firstAt = 0;
  let timer = null;
  let reconcileTimer = null;
  let inFlight = null;          // Promise do envio em andamento
  let requestsSeen = 0;
  let pendingNavigation = null;
  let overflow = false;
  let geometryTimer = null;
  let keepLast = false;         // instrumento de homologação (DEV): guarda o último delta enviado
  let lastDelta = null;
  const stats = { geometryRefreshes: 0, geometryChanged: 0, deltasSent: 0, rawMutations: 0, batches: 0, ignored: 0, failures: 0, lastDeltaMs: 0, maxDeltaMs: 0,
    shadowRootsDiscovered: 0, resyncs: 0, swept: 0, lastError: null, lastDeltaAt: 0 };

  function insideOverlay(node) {
    for (let n = node; n; n = n.parentNode || (n instanceof ShadowRoot ? n.host : null)) {
      if (n.nodeType === 1 && NS.capture.isOverlay(n)) {
        return true;
      }
    }
    return false;
  }

  function mark(el, subtree) {
    if (!el || el.nodeType !== 1 || SKIP.has(el.tagName.toLowerCase())) {
      return;
    }
    dirty.set(el, (dirty.get(el) || false) || subtree);
    if (dirty.size > MAX_PENDING) {
      overflow = true; // sem limite de memória ilimitado: força ressincronização
      dirty = new Map();
      removed = new Set();
    }
  }

  function onRecords(records) {
    let relevant = 0;
    for (const r of records) {
      raw++;
      if (insideOverlay(r.target)) {
        stats.ignored++;
        continue;
      }
      if (r.type === 'attributes') {
        if (r.attributeName && r.attributeName.startsWith('data-nekkora')) {
          stats.ignored++;
          continue;
        }
        mark(r.target, SUBTREE_ATTRS.has(r.attributeName));
      } else if (r.type === 'characterData') {
        mark(r.target.parentElement, false);
      } else if (r.type === 'childList') {
        const parent = r.target.nodeType === 1 ? r.target : (r.target instanceof ShadowRoot ? r.target.host : null);
        mark(parent, false);
        for (const n of r.addedNodes) {
          if (n.nodeType === 1 && !NS.capture.isOverlay(n)) {
            mark(n, true);
          }
        }
        for (const n of r.removedNodes) {
          if (n.nodeType === 1) {
            const id = NS.registry.peek(n);
            if (id) {
              removed.add(id);
            }
          }
        }
      }
      relevant++;
    }
    if (relevant === 0) {
      return;
    }
    batches++;
    if (!firstAt) {
      firstAt = performance.now();
    }
    schedule();
  }

  function schedule() {
    if (mode !== 'ACTIVE') {
      return; // BUFFER/PAUSED: acumula sem enviar
    }
    if (timer) {
      clearTimeout(timer);
    }
    const waited = performance.now() - (firstAt || performance.now());
    const delay = waited >= MAX_WAIT_MS ? 0 : Math.min(DEBOUNCE_MS, MAX_WAIT_MS - waited);
    timer = setTimeout(() => {
      timer = null;
      flush(false);
    }, delay);
  }

  function coveredBySubtree(el, subtreeRoots) {
    for (let p = el.parentElement || (el.parentNode instanceof ShadowRoot ? el.parentNode.host : null); p;
      p = p.parentElement || (p.parentNode instanceof ShadowRoot ? p.parentNode.host : null)) {
      if (subtreeRoots.has(p)) {
        return true;
      }
    }
    return false;
  }

  function observeRoots() {
    const wanted = NS.capture.observableRoots();
    for (const r of wanted) {
      if (!observers.has(r)) {
        const o = new MutationObserver(onRecords);
        o.observe(r, { subtree: true, childList: true, attributes: true, characterData: true });
        observers.set(r, o);
      }
    }
    for (const [r, o] of observers) {
      const alive = r === document || (r instanceof ShadowRoot ? r.host && r.host.isConnected : r.defaultView);
      if (!alive) {
        o.disconnect();
        observers.delete(r);
      }
    }
  }

  function reconcileScopes() {
    if (mode !== 'ACTIVE' && mode !== 'BUFFER') {
      return;
    }
    const hosts = NS.capture.discoverShadowHosts();
    if (hosts.length) {
      stats.shadowRootsDiscovered += hosts.length;
      for (const h of hosts) {
        mark(h, true);
      }
      if (!firstAt) {
        firstAt = performance.now();
      }
      schedule();
    }
  }

  /** Marca elementos cuja geometria mudou (rolagem, redimensionamento, deslocamento de layout). */
  function refreshGeometry() {
    if (mode !== 'ACTIVE' && mode !== 'BUFFER') {
      return 0;
    }
    const changed = NS.capture.geometryChanges(MAX_DELTA_NODES);
    stats.geometryRefreshes++;
    stats.geometryChanged += changed.length;
    for (const el of changed) {
      mark(el, false);
    }
    if (changed.length) {
      if (!firstAt) {
        firstAt = performance.now();
      }
      schedule();
    }
    return changed.length;
  }

  function onLayoutEvent() {
    if (geometryTimer) {
      clearTimeout(geometryTimer);
    }
    geometryTimer = setTimeout(() => {
      geometryTimer = null;
      refreshGeometry();
    }, GEOMETRY_DEBOUNCE_MS);
  }

  async function flush(force) {
    if (inFlight) {
      await inFlight; // um delta por vez: a sequência é estritamente crescente
    }
    if (mode !== 'ACTIVE' || !sink || (!force && dirty.size === 0 && removed.size === 0 && !pendingNavigation)) {
      firstAt = 0;
      return { sent: false };
    }
    if (overflow) {
      overflow = false;
      mode = 'PAUSED';
      stats.lastError = 'OVERFLOW';
      stats.resyncs++;
      sink({ __resync: 'pendências acima de ' + MAX_PENDING + ' elementos' });
      return { sent: false, error: 'OVERFLOW' };
    }
    const t0 = performance.now();
    const entries = dirty;
    const gone = removed;
    const rawCount = raw;
    const batchCount = batches;
    dirty = new Map();
    removed = new Set();
    raw = 0;
    batches = 0;
    firstAt = 0;
    const subtreeRoots = new Set();
    for (const [el, sub] of entries) {
      if (sub) {
        subtreeRoots.add(el);
      }
    }
    const list = [];
    for (const [el, sub] of entries) {
      if (el.isConnected && !coveredBySubtree(el, subtreeRoots)) {
        list.push({ el, subtree: sub });
      }
    }
    const removedIds = Array.from(gone).filter((id) => !NS.registry.isConnected(id));
    const part = NS.capture.partial(list, MAX_DELTA_NODES);
    observeRoots(); // escopos novos (shadow roots / frames de mesma origem) entram na observação
    const allRequests = NS.capture.observedRequests();
    const newRequests = allRequests.slice(Math.min(requestsSeen, allRequests.length));
    requestsSeen = allRequests.length;
    const durationMs = Math.round((performance.now() - t0) * 100) / 100;
    stats.rawMutations += rawCount;
    stats.batches += batchCount;
    stats.lastDeltaMs = durationMs;
    stats.maxDeltaMs = Math.max(stats.maxDeltaMs, durationMs);
    if (part.truncated) {
      mode = 'PAUSED';
      stats.lastError = 'DELTA_TOO_LARGE';
      stats.resyncs++;
      sink({ __resync: 'delta excedeu ' + MAX_DELTA_NODES + ' nós' });
      return { sent: false, error: 'DELTA_TOO_LARGE' };
    }
    seq++;
    const delta = {
      baseCaptureId: null,
      captureId: NS.capture.nextCaptureId(),
      capturedAt: new Date().toISOString(),
      document: NS.capture.documentInfo(pendingNavigation || 'MUTATION'),
      upserts: part.nodes,
      removed: removedIds,
      frames: NS.capture.frameList(),
      shadowRoots: NS.capture.shadowRootList(),
      requests: newRequests,
      rawMutationCount: rawCount,
      batchCount,
      stats: { elementsVisited: part.nodes.length, nodesEmitted: part.nodes.length, durationMs, truncated: false, maxNodes: MAX_DELTA_NODES },
      sessionId: session.sessionId,
      source: session.source,
      epoch: session.epoch,
      seq,
    };
    pendingNavigation = null;
    if (keepLast) {
      lastDelta = delta;
    }
    inFlight = (async () => {
      try {
        const ack = await sink(delta);
        if (ack && ack.ok) {
          stats.deltasSent++;
          if (stats.deltasSent % 50 === 0) {
            stats.swept += NS.registry.sweep(); // ids de nós desconectados não se acumulam em sessões longas
          }
          stats.lastDeltaAt = Date.now();
          return { sent: true, seq };
        }
        stats.failures++;
        stats.lastError = (ack && ack.error) || 'DELTA_REJECTED';
        mode = 'PAUSED'; // o service worker decide recuperar (ressincronização controlada)
        return { sent: false, error: stats.lastError };
      } catch (e) {
        stats.failures++;
        stats.lastError = String(e && e.message || e);
        mode = 'PAUSED';
        return { sent: false, error: stats.lastError };
      } finally {
        inFlight = null;
      }
    })();
    return inFlight;
  }

  function disconnectAll() {
    for (const o of observers.values()) {
      o.disconnect();
    }
    observers = new Map();
    if (timer) {
      clearTimeout(timer);
      timer = null;
    }
    if (reconcileTimer) {
      clearInterval(reconcileTimer);
      reconcileTimer = null;
    }
    if (geometryTimer) {
      clearTimeout(geometryTimer);
      geometryTimer = null;
    }
    root.removeEventListener('scroll', onLayoutEvent, true);
    root.removeEventListener('resize', onLayoutEvent);
  }

  NS.tracker = {
    /** Liga os observadores em modo BUFFER para uma sessão (antes da captura-base). */
    prepare(cfg, deltaSink) {
      disconnectAll();
      session = { sessionId: cfg.sessionId, source: cfg.source || 'top', epoch: 0 };
      sink = deltaSink;
      dirty = new Map();
      removed = new Set();
      raw = 0;
      batches = 0;
      firstAt = 0;
      seq = 0;
      overflow = false;
      pendingNavigation = null;
      mode = 'BUFFER';
      requestsSeen = NS.capture.observedRequests().length;
      observeRoots();
      reconcileTimer = setInterval(reconcileScopes, RECONCILE_MS);
      root.addEventListener('scroll', onLayoutEvent, { capture: true, passive: true });
      root.addEventListener('resize', onLayoutEvent, { passive: true });
    },
    /** Base aceita pelo motor: o acumulado durante a captura vira o delta #1. */
    confirmBase(cfg) {
      if (!session || session.sessionId !== cfg.sessionId) {
        return { ok: false, error: 'SESSION_MISMATCH' };
      }
      session.epoch = cfg.epoch;
      seq = 0;
      mode = 'ACTIVE';
      observeRoots();
      const buffered = dirty.size + removed.size;
      if (buffered) {
        firstAt = firstAt || performance.now();
        schedule();
      }
      return { ok: true, buffered };
    },
    pause(reason) {
      if (mode === 'ACTIVE') {
        mode = 'PAUSED';
        stats.lastError = reason || 'PAUSED';
      }
    },
    /** Navegação SPA: força um delta (mesmo sem mudanças de nós) com o novo contexto de URL. */
    noteNavigation(kind) {
      pendingNavigation = kind || 'SPA_ROUTE';
      if (!firstAt) {
        firstAt = performance.now();
      }
      schedule();
    },
    stop() {
      disconnectAll();
      mode = 'OFF';
      session = null;
      sink = null;
      dirty = new Map();
      removed = new Set();
    },
    active() {
      return mode !== 'OFF';
    },
    mode() {
      return mode;
    },
    sessionId() {
      return session ? session.sessionId : null;
    },
    observerCount() {
      return observers.size;
    },
    /** Barreira: envia o pendente e aguarda a confirmação. */
    async flushNow(options) {
      if (options && options.geometry) {
        refreshGeometry(); // barreira de exportação: geometria atual incluída no estado consolidado
      }
      const r = await flush(!!(options && options.force));
      if (inFlight) {
        await inFlight;
      }
      return { ...r, pending: dirty.size + removed.size, mode, seq };
    },
    reconcileNow() {
      reconcileScopes();
    },
    /** Instrumento de homologação (somente DEV): simula a perda do próximo delta pulando um número de sequência. */
    debugSkipSeq() {
      seq++;
      return seq;
    },
    debugKeepLast(on) {
      keepLast = !!on;
      lastDelta = on ? lastDelta : null;
    },
    debugLastDelta() {
      return lastDelta;
    },
    stats() {
      return { ...stats, active: mode !== 'OFF', mode, sessionId: session ? session.sessionId : null, epoch: session ? session.epoch : null,
        seq, pending: dirty.size + removed.size, inFlight: !!inFlight, observers: observers.size };
    },
  };
})(globalThis);
