/*
 * Mutation Tracker — observa a página e produz DELTAS incrementais (nunca reconstrói a página
 * inteira a cada mutação).
 *
 * - agrega registros do MutationObserver em lotes (debounce 100 ms, espera máxima 500 ms);
 * - ignora a interface da própria extensão (overlay) e atributos internos data-nekkora-*;
 * - re-serializa somente nós alterados (subárvore apenas quando a mudança pode alterar a
 *   visibilidade dos descendentes: class, style, hidden, open);
 * - encadeia deltas pela captura base: um delta só é enviado após o anterior ser confirmado.
 */
(function (root) {
  const NS = root.NekkoraCS;
  if (NS.tracker) {
    return;
  }
  const DEBOUNCE_MS = 100;
  const MAX_WAIT_MS = 500;
  const MAX_DELTA_NODES = 5000;
  const SUBTREE_ATTRS = new Set(['class', 'style', 'hidden', 'open', 'inert']);
  const SKIP = new Set(['script', 'style', 'noscript', 'template', 'head', 'meta', 'link', 'title', 'base']);

  let observers = [];
  let baseCaptureId = null;
  let sink = null;              // (delta) => Promise<{ok, captureId}>
  let onResync = null;          // () => void — pede captura completa
  let dirty = new Map();        // Element -> subtree:boolean
  let removed = new Set();      // ids temporários
  let raw = 0;
  let batches = 0;
  let firstAt = 0;
  let timer = null;
  let inFlight = false;
  let requestsSeen = 0;
  const stats = { deltasSent: 0, rawMutations: 0, batches: 0, ignored: 0, resyncs: 0, lastDeltaMs: 0, maxDeltaMs: 0 };

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
    if (timer) {
      clearTimeout(timer);
    }
    const waited = performance.now() - firstAt;
    const delay = waited >= MAX_WAIT_MS ? 0 : Math.min(DEBOUNCE_MS, MAX_WAIT_MS - waited);
    timer = setTimeout(flush, delay);
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

  async function flush() {
    timer = null;
    if (!sink || dirty.size === 0 && removed.size === 0) {
      firstAt = 0;
      return;
    }
    if (inFlight) {
      timer = setTimeout(flush, 50); // aguarda confirmação do delta anterior
      return;
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
      if (!el.isConnected || coveredBySubtree(el, subtreeRoots)) {
        continue;
      }
      list.push({ el, subtree: sub });
    }
    const removedIds = Array.from(gone).filter((id) => !NS.registry.isConnected(id));
    const part = NS.capture.partial(list, MAX_DELTA_NODES);
    const allRequests = NS.capture.observedRequests();
    const newRequests = allRequests.slice(Math.min(requestsSeen, allRequests.length));
    requestsSeen = allRequests.length;
    const durationMs = Math.round((performance.now() - t0) * 100) / 100;
    stats.rawMutations += rawCount;
    stats.batches += batchCount;
    stats.lastDeltaMs = durationMs;
    stats.maxDeltaMs = Math.max(stats.maxDeltaMs, durationMs);
    if (part.truncated) {
      stats.resyncs++;
      if (onResync) {
        onResync('delta excedeu ' + MAX_DELTA_NODES + ' nós');
      }
      return;
    }
    const delta = {
      baseCaptureId,
      captureId: NS.capture.nextCaptureId(),
      capturedAt: new Date().toISOString(),
      document: NS.capture.documentInfo('MUTATION'),
      upserts: part.nodes,
      removed: removedIds,
      frames: null,
      shadowRoots: null,
      requests: newRequests,
      rawMutationCount: rawCount,
      batchCount,
      stats: { elementsVisited: part.nodes.length, nodesEmitted: part.nodes.length, durationMs, truncated: false, maxNodes: MAX_DELTA_NODES },
    };
    inFlight = true;
    try {
      const ack = await sink(delta);
      if (ack && ack.ok) {
        baseCaptureId = delta.captureId;
        stats.deltasSent++;
      } else {
        stats.resyncs++;
        if (onResync) {
          onResync((ack && ack.error) || 'delta recusado');
        }
      }
    } catch (e) {
      stats.resyncs++;
      if (onResync) {
        onResync(String(e && e.message || e));
      }
    } finally {
      inFlight = false;
    }
  }

  function observeRoots() {
    for (const o of observers) {
      o.disconnect();
    }
    observers = [];
    for (const r of NS.capture.observableRoots()) {
      const o = new MutationObserver(onRecords);
      o.observe(r, { subtree: true, childList: true, attributes: true, characterData: true });
      observers.push(o);
    }
  }

  NS.tracker = {
    start(captureId, deltaSink, resync) {
      baseCaptureId = captureId;
      sink = deltaSink;
      onResync = resync;
      requestsSeen = NS.capture.observedRequests().length;
      observeRoots();
    },
    /** Após uma captura completa: rebaseia e reobserva (novos shadow roots/frames). */
    rebase(captureId) {
      baseCaptureId = captureId;
      dirty = new Map();
      removed = new Set();
      if (sink) {
        observeRoots();
      }
    },
    stop() {
      for (const o of observers) {
        o.disconnect();
      }
      observers = [];
      if (timer) {
        clearTimeout(timer);
        timer = null;
      }
      sink = null;
      onResync = null;
      dirty = new Map();
      removed = new Set();
    },
    active() {
      return observers.length > 0;
    },
    observerCount() {
      return observers.length;
    },
    flushNow() {
      return flush();
    },
    stats() {
      return { ...stats, active: observers.length > 0, baseCaptureId, pending: dirty.size + removed.size };
    },
  };
})(globalThis);
