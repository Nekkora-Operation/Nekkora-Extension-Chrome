/*
 * Service worker (MV3): orquestra content script ↔ motor Java ↔ interface.
 * Não executa regras de análise: classificação, família, localizadores e resolução são do Java.
 */
import { NativeClient, HostError } from './native-client.js';

const client = new NativeClient();
const tabs = new Map(); // tabId -> estado
const EVENT_LOG_LIMIT = 200;
const eventLog = [];
/** Hooks de teste permitidos (somente leitura, declarados pelas páginas do laboratório). */
const ALLOWED_TEST_HOOKS = ['getCurrentFilters', 'getVisibleResultCount', 'getLastApiQuery', 'getRenderCount', 'getActionLog'];

function tabState(tabId) {
  let s = tabs.get(tabId);
  if (!s) {
    s = { tabId, captures: [], tracking: false, inspecting: false, selected: null, lastInspection: null, lastResolution: null, navigation: [] };
    tabs.set(tabId, s);
  }
  return s;
}

function tabKey(tabId) {
  return 'tab-' + tabId;
}

function isAllowedUrl(url) {
  try {
    const u = new URL(url);
    return (u.protocol === 'http:' || u.protocol === 'https:') && (u.hostname === '127.0.0.1' || u.hostname === 'localhost');
  } catch (e) {
    return false;
  }
}

async function assertAllowed(tabId) {
  const tab = await chrome.tabs.get(tabId);
  if (!tab || !tab.url || !isAllowedUrl(tab.url)) {
    throw new HostError('ORIGIN_NOT_ALLOWED', 'A extensão atua somente em páginas locais (127.0.0.1/localhost)');
  }
  return tab;
}

function broadcast(event, data) {
  const entry = { event, data, at: Date.now() };
  eventLog.push(entry);
  if (eventLog.length > EVENT_LOG_LIMIT) {
    eventLog.shift();
  }
  chrome.runtime.sendMessage({ source: 'background', event, data }).catch(() => {});
}

async function toContent(tabId, cmd, args) {
  const res = await chrome.tabs.sendMessage(tabId, { target: 'content', cmd, args: args || {} });
  if (res && res.__error) {
    throw new HostError('CONTENT_ERROR', res.__error);
  }
  return res;
}

async function host(type, payload, options) {
  return (await client.request(type, payload, options)).payload;
}

// ------------------------------------------------------------------------------------- fluxos

async function capture(tabId, options = {}) {
  await assertAllowed(tabId);
  const s = tabState(tabId);
  const t0 = performance.now();
  const snapshot = await toContent(tabId, 'cs.capture', { maxNodes: options.maxNodes, navigationKind: options.navigationKind });
  const t1 = performance.now();
  const json = JSON.stringify(snapshot);
  const res = await client.request('snapshot.submit', { tabKey: tabKey(tabId), snapshot, compareWithPrevious: !!options.compareWithPrevious });
  const t2 = performance.now();
  s.captures.push(snapshot.captureId);
  if (s.captures.length > 20) {
    s.captures.shift();
  }
  if (s.tracking) {
    await toContent(tabId, 'cs.tracking.rebase', { baseCaptureId: snapshot.captureId });
  }
  const summary = res.payload;
  summary.browser = {
    captureMs: snapshot.stats.durationMs,
    roundTripMs: Math.round((t1 - t0) * 100) / 100,
    hostRoundTripMs: Math.round((t2 - t1) * 100) / 100,
    snapshotBytes: json.length,
    chunked: res.chunked,
  };
  broadcast('captured', { tabId, captureId: snapshot.captureId, nodes: summary.nodes, browser: summary.browser });
  return summary;
}

async function selectNode(tabId, nodeId) {
  const s = tabState(tabId);
  const cap = await capture(tabId);
  const inspection = await host('element.inspect', { tabKey: tabKey(tabId), captureId: cap.captureId, nodeId });
  const identity = await host('target.register', { tabKey: tabKey(tabId), captureId: cap.captureId, nodeId });
  const live = await toContent(tabId, 'cs.verifyLocators', { nodeId, locators: inspection.locators });
  const verified = await host('locators.verify', { tabKey: tabKey(tabId), captureId: cap.captureId, nodeId, checks: live.checks });
  const label = inspection.classification.kind + (inspection.classification.name.name ? ' · ' + inspection.classification.name.name : '');
  await toContent(tabId, 'cs.highlight', { nodeId, kind: 'selection', label, targetId: identity.targetId });
  s.selected = { nodeId, targetId: identity.targetId };
  s.lastInspection = { captureId: cap.captureId, inspection, identity, verification: verified, liveChecks: live.checks };
  await chrome.storage.session.set({ ['inspection:' + tabId]: s.lastInspection }).catch(() => {});
  broadcast('inspection', { tabId, ...s.lastInspection });
  return s.lastInspection;
}

async function resolveTarget(tabId, targetId, options = {}) {
  const s = tabState(tabId);
  const id = targetId || (s.selected && s.selected.targetId);
  if (!id) {
    throw new HostError('NO_TARGET', 'Nenhum alvo selecionado/registrado');
  }
  const cap = options.reuseCapture && s.captures.length ? { captureId: s.captures[s.captures.length - 1] } : await capture(tabId);
  const res = await host('target.resolve', { tabKey: tabKey(tabId), captureId: cap.captureId, targetId: id });
  const r = res.result;
  if (r.status === 'RESOLVED') {
    await toContent(tabId, 'cs.highlight', { nodeId: r.resolvedNodeId, kind: 'selection', label: 'RESOLVED · ' + (r.score), targetId: id });
    s.selected = { nodeId: r.resolvedNodeId, targetId: id };
  } else {
    await toContent(tabId, 'cs.clearHighlight', { kind: 'selection' });
  }
  s.lastResolution = r;
  broadcast('resolution', { tabId, result: r, timings: res.timings });
  return res;
}

async function readTestHooks(tabId) {
  await assertAllowed(tabId);
  const [result] = await chrome.scripting.executeScript({
    target: { tabId },
    world: 'MAIN',
    args: [ALLOWED_TEST_HOOKS],
    func: (allowed) => {
      // Executado no mundo da página de laboratório: lê SOMENTE hooks declarados e marcados.
      const h = window.nekkoraTestHooks;
      if (!h || h.__nekkoraLab !== true) {
        return { available: false, source: 'TEST_INSTRUMENTATION' };
      }
      const values = {};
      for (const name of allowed) {
        if (typeof h[name] === 'function') {
          try {
            values[name] = JSON.parse(JSON.stringify(h[name]()));
          } catch (e) {
            values[name] = { error: String(e) };
          }
        }
      }
      return { available: true, source: 'TEST_INSTRUMENTATION', values };
    },
  });
  return result ? result.result : { available: false };
}

async function startTracking(tabId) {
  const s = tabState(tabId);
  const cap = s.captures.length ? { captureId: s.captures[s.captures.length - 1] } : await capture(tabId);
  s.tracking = true;
  return toContent(tabId, 'cs.tracking.start', { baseCaptureId: cap.captureId });
}

async function onDelta(tabId, delta) {
  const s = tabState(tabId);
  try {
    const res = await host('snapshot.delta', { tabKey: tabKey(tabId), delta, autoResolve: true });
    s.captures.push(res.captureId);
    if (s.captures.length > 20) {
      s.captures.shift();
    }
    for (const r of res.autoResolutions || []) {
      if (s.selected && r.targetId === s.selected.targetId) {
        if (r.status === 'RESOLVED') {
          await toContent(tabId, 'cs.highlight', { nodeId: r.resolvedNodeId, kind: 'selection', label: 'RESOLVED · re-render', targetId: r.targetId });
          s.selected.nodeId = r.resolvedNodeId;
        }
        s.lastResolution = r;
        broadcast('resolution', { tabId, result: r, automatic: true });
      }
    }
    broadcast('delta', { tabId, captureId: res.captureId, mutation: res.mutation, timings: res.timings });
    return { ok: true, captureId: res.captureId };
  } catch (e) {
    return { ok: false, error: e.code || String(e) };
  }
}

// ------------------------------------------------------------------------------- comandos UI

const commands = {
  'host.ping': async () => ({ ping: await host('host.ping', {}), client: client.status() }),
  'host.request': async (a) => client.request(a.type, a.payload, { forceChunkChars: a.forceChunkChars, timeoutMs: a.timeoutMs }),
  'host.disconnect': async () => {
    client.disconnect();
    return client.status();
  },
  'host.status': async () => client.status(),
  'page.capture': async (a) => capture(a.tabId, a),
  'page.inspect.start': async (a) => {
    await assertAllowed(a.tabId);
    tabState(a.tabId).inspecting = true;
    return toContent(a.tabId, 'cs.inspect.start');
  },
  'page.inspect.stop': async (a) => {
    tabState(a.tabId).inspecting = false;
    return toContent(a.tabId, 'cs.inspect.stop');
  },
  'page.disable': async (a) => {
    const s = tabState(a.tabId);
    s.inspecting = false;
    s.tracking = false;
    s.selected = null;
    return toContent(a.tabId, 'cs.disable');
  },
  'page.select': async (a) => {
    let nodeId = a.nodeId;
    if (!nodeId && a.css) {
      const found = await toContent(a.tabId, 'cs.idFor', { css: a.css, scope: a.scope });
      if (found.error || found.count !== 1) {
        // Nunca escolhe silenciosamente o primeiro resultado de uma busca ambígua.
        throw new HostError(found.count > 1 ? 'AMBIGUOUS_SELECTOR' : 'NOT_FOUND', a.css + ' → ' + (found.error || found.count + ' elementos'));
      }
      nodeId = found.nodeIds[0];
    }
    return selectNode(a.tabId, nodeId);
  },
  'target.resolve': async (a) => resolveTarget(a.tabId, a.targetId, a),
  'target.list': async () => host('target.list', {}),
  'page.compare': async (a) => {
    const s = tabState(a.tabId);
    const base = a.baseCaptureId || (s.captures.length ? s.captures[s.captures.length - 1] : null);
    if (!base) {
      throw new HostError('NO_CAPTURE', 'Capture a página antes de comparar');
    }
    const cap = await capture(a.tabId);
    return host('snapshot.compare', { tabKey: tabKey(a.tabId), baseCaptureId: base, captureId: cap.captureId });
  },
  'pagemap.export': async (a) => {
    const s = tabState(a.tabId);
    if (!s.captures.length || a.fresh) {
      await capture(a.tabId);
    }
    return host('pagemap.export', { tabKey: tabKey(a.tabId), includeFamilies: a.includeFamilies !== false, includeLocators: a.includeLocators !== false, returnPageMap: a.returnPageMap !== false }, { timeoutMs: 120000 });
  },
  'interaction.observe': async (a) => {
    const s = tabState(a.tabId);
    const after = a.afterCaptureId || (await capture(a.tabId)).captureId;
    const instrumentation = a.readHooks ? await readTestHooks(a.tabId) : null;
    return host('interaction.observe', { tabKey: tabKey(a.tabId), beforeCaptureId: a.beforeCaptureId, afterCaptureId: after, instrumentation });
  },
  'testHooks.read': async (a) => readTestHooks(a.tabId),
  'tracking.start': async (a) => startTracking(a.tabId),
  'tracking.stop': async (a) => {
    tabState(a.tabId).tracking = false;
    return toContent(a.tabId, 'cs.tracking.stop');
  },
  'tracking.flush': async (a) => toContent(a.tabId, 'cs.tracking.flush'),
  'page.diagnostics': async (a) => toContent(a.tabId, 'cs.diagnostics'),
  'page.nodeInfo': async (a) => toContent(a.tabId, 'cs.nodeInfo', { nodeId: a.nodeId }),
  'page.idFor': async (a) => toContent(a.tabId, 'cs.idFor', { css: a.css, scope: a.scope }),
  'page.highlight': async (a) => toContent(a.tabId, 'cs.highlight', a),
  'page.runLocator': async (a) => toContent(a.tabId, 'cs.runLocator', a),
  'state.get': async (a) => {
    const s = a.tabId != null ? tabState(a.tabId) : null;
    return { client: client.status(), tab: s, events: eventLog.slice(-50) };
  },
  'events.since': async (a) => eventLog.filter((e) => e.at > (a.since || 0)),
};

chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  if (!msg || sender.id !== chrome.runtime.id) {
    return false;
  }
  // Eventos do content script
  if (msg.source === 'content' && sender.tab) {
    const tabId = sender.tab.id;
    if (msg.event === 'delta') {
      onDelta(tabId, msg.data).then(sendResponse);
      return true;
    }
    if (msg.event === 'element-selected') {
      selectNode(tabId, msg.data.nodeId)
        .catch((e) => broadcast('error', { tabId, where: 'selection', code: e.code, message: e.message }));
    } else if (msg.event === 'selection-detached') {
      resolveTarget(tabId, msg.data.targetId)
        .catch((e) => broadcast('error', { tabId, where: 'reacquire', code: e.code, message: e.message }));
    } else if (msg.event === 'resync-needed') {
      capture(tabId).catch((e) => broadcast('error', { tabId, where: 'resync', code: e.code, message: e.message }));
    } else if (msg.event === 'content-ready') {
      const s = tabState(tabId);
      s.captures = [];
      s.tracking = false;
      s.inspecting = false;
      s.selected = null;
      broadcast('content-ready', { tabId, ...msg.data });
    }
    sendResponse({ ok: true });
    return false;
  }
  // Comandos das páginas da extensão (painel / harness)
  if (msg.target === 'background' && sender.url && sender.url.startsWith(chrome.runtime.getURL(''))) {
    const fn = commands[msg.cmd];
    if (!fn) {
      sendResponse({ ok: false, error: { code: 'UNKNOWN_COMMAND', message: msg.cmd } });
      return false;
    }
    fn(msg.args || {})
      .then((result) => sendResponse({ ok: true, result }))
      .catch((e) => sendResponse({ ok: false, error: { code: e.code || 'ERROR', message: String(e && e.message || e) } }));
    return true;
  }
  return false;
});

// Navegação SPA (history.pushState / hash) em páginas locais.
for (const evt of ['onHistoryStateUpdated', 'onReferenceFragmentUpdated']) {
  chrome.webNavigation[evt].addListener((d) => {
    if (d.frameId !== 0 || !isAllowedUrl(d.url)) {
      return;
    }
    const s = tabState(d.tabId);
    const nav = { kind: evt === 'onHistoryStateUpdated' ? 'SPA_ROUTE' : 'FRAGMENT', url: d.url, at: Date.now(), transitionType: d.transitionType };
    s.navigation.push(nav);
    if (s.navigation.length > 50) {
      s.navigation.shift();
    }
    broadcast('navigation', { tabId: d.tabId, ...nav });
    if (s.tracking) {
      capture(d.tabId, { navigationKind: 'SPA_ROUTE' }).catch(() => {});
    }
  }, { url: [{ hostEquals: '127.0.0.1' }, { hostEquals: 'localhost' }] });
}

chrome.tabs.onRemoved.addListener((tabId) => tabs.delete(tabId));
chrome.runtime.onInstalled.addListener(() => {
  chrome.sidePanel.setPanelBehavior({ openPanelOnActionClick: true }).catch(() => {});
});
