/*
 * Service worker (MV3): orquestra content scripts ↔ motor Java ↔ interface.
 * Não executa regras de análise: classificação, família, localizadores, resolução e consistência dos deltas
 * são do Java. Módulos:
 *   capture-session  ciclo de captura automática/contínua por aba (consumidores, deltas, recuperação)
 *   frames           iframes de outra origem local permitida (handshake + anexação)
 *   inspection       botão único, estados da inspeção, seleção e reencontro
 *   recording        contrato de eventos de gravação (exportação na finalização)
 *   config           modo DEV/PRD (controlado pelo projeto)
 */
import { client, host, toContent, tabKey, broadcast, eventLog, isAllowedUrl, assertAllowed, errorInfo, HostError } from './runtime.js';
import * as config from './config.js';
import * as capture from './capture-session.js';
import * as frames from './frames.js';
import * as inspection from './inspection.js';
import * as recording from './recording.js';

/** Hooks de teste permitidos (somente leitura, declarados pelas páginas do laboratório). */
const ALLOWED_TEST_HOOKS = ['getCurrentFilters', 'getVisibleResultCount', 'getLastApiQuery', 'getRenderCount', 'getActionLog'];

const ready = (async () => {
  await config.loadConfig();
  await capture.restore();
  await inspection.restore();
})();
ready.then(() => capture.reconcile()).catch(() => {});

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

/** Resumo de captura no formato do comando page.capture (compatível com CHROME-001). */
function captureSummary(rec, c) {
  return {
    captureId: c.captureId, pageSessionId: rec.documentId, nodes: c.nodes, interactive: c.interactive, text: c.text,
    coverage: c.coverage, navigation: c.navigation, timings: c.timings, sessionId: rec.sessionId, epoch: rec.epoch,
    browser: { captureMs: c.captureMs, mode: c.mode, slices: c.slices, maxSliceMs: c.maxSliceMs, roundTripMs: c.browserMs, hostRoundTripMs: c.hostMs },
  };
}

async function diagnostics(tabId, frameId = 0) {
  return toContent(tabId, 'cs.diagnostics', {}, frameId);
}

// ------------------------------------------------------------------------------- comandos UI

const commands = {
  // Motor
  'host.ping': async () => ({ ping: await host('host.ping', {}), client: client.status() }),
  'host.request': async (a) => client.request(a.type, a.payload, { forceChunkChars: a.forceChunkChars, timeoutMs: a.timeoutMs }),
  'host.disconnect': async () => {
    client.disconnect();
    return client.status();
  },
  'host.status': async () => client.status(),
  'host.memory': async (a) => host('host.memory', { gc: !!a.gc }),
  'host.reconnect': async () => capture.tryReconnect(),

  // Configuração
  'config.get': async () => config.describe(),
  'config.setMode': async (a) => {
    const r = await config.setMode(a.mode);
    broadcast('config', r);
    return r;
  },

  // Inspeção (botão único)
  'inspection.toggle': async (a) => inspection.toggle(a.tabId),
  'inspection.activate': async (a) => inspection.activate(a.tabId),
  'inspection.deactivate': async (a) => inspection.deactivate(a.tabId),
  'inspection.state': async (a) => ({ ...inspection.describe(a.tabId), mode: config.mode(), host: client.status() }),

  // Sessão de captura (comandos internos; contrato para o futuro Studio)
  'session.status': async (a) => capture.status(a.tabId),
  'session.list': async () => capture.list(),
  'session.synchronize': async (a) => capture.synchronize(a.tabId, { geometry: !!a.geometry }),
  'session.forceFull': async (a) => {
    const rec = capture.get(a.tabId);
    if (!rec) {
      throw new HostError('NO_SESSION', 'Nenhuma sessão de captura ativa');
    }
    return captureSummary(rec, await capture.rebase(a.tabId, 'FORCED_FULL', { cooperative: a.cooperative !== false }));
  },
  'session.acquire': async (a) => capture.describe(await capture.acquire(a.tabId, a.consumer || 'manual', { cooperative: a.cooperative !== false, maxNodes: a.maxNodes })),
  'session.release': async (a) => capture.release(a.tabId, a.consumer || 'manual', a.reason || 'RELEASED'),
  'session.end': async (a) => capture.end(a.tabId, a.reason || 'ENDED'),
  /** Instrumentos de homologação (DEV): simulação de perda de delta. */
  'session.debug': async (a) => toContent(a.tabId, 'cs.session.debug', { action: a.action }, a.frameId || 0),
  'frames.list': async (a) => ({ chrome: await chrome.webNavigation.getAllFrames({ tabId: a.tabId }), session: (capture.get(a.tabId) || { frames: {} }).frames }),
  'sw.diagnostics': async () => ({ ...capture.diagnostics(), mode: config.describe(), client: client.status() }),

  // Gravação (eventos simulados pelo harness; contrato futuro da plataforma)
  'recording.event': async (a) => recording.dispatch(a.event || a),
  'recording.list': async () => recording.list(),

  // Compatibilidade CHROME-001 (mapeados para o ciclo automático quando há sessão)
  'page.capture': async (a) => {
    if (capture.isActive(a.tabId)) {
      const rec = capture.get(a.tabId);
      return captureSummary(rec, await capture.rebase(a.tabId, 'FORCED_FULL', { navigationKind: a.navigationKind, maxNodes: a.maxNodes, cooperative: a.cooperative !== false }));
    }
    return inspection.captureOnce(a.tabId, a);
  },
  'page.rawSnapshot': async (a) => {
    await assertAllowed(a.tabId);
    return toContent(a.tabId, 'cs.capture', { maxNodes: a.maxNodes }, a.frameId || 0);
  },
  'page.inspect.start': async (a) => {
    await inspection.activate(a.tabId);
    return diagnostics(a.tabId);
  },
  'page.inspect.stop': async (a) => {
    await inspection.deactivate(a.tabId);
    return diagnostics(a.tabId);
  },
  /** Desliga tudo na aba: inspeção, sessão de captura (todos os consumidores), destaques e observadores. */
  'page.disable': async (a) => {
    await inspection.deactivate(a.tabId);
    await capture.end(a.tabId, 'DISABLED');
    inspection.get(a.tabId).selected = null;
    return toContent(a.tabId, 'cs.disable');
  },
  'page.select': async (a) => {
    let nodeId = a.nodeId;
    if (!nodeId && a.css) {
      const found = await toContent(a.tabId, 'cs.idFor', { css: a.css, scope: a.scope }, a.frameId || 0);
      if (found.error || found.count !== 1) {
        // Nunca escolhe silenciosamente o primeiro resultado de uma busca ambígua.
        throw new HostError(found.count > 1 ? 'AMBIGUOUS_SELECTOR' : 'NOT_FOUND', a.css + ' → ' + (found.error || found.count + ' elementos'));
      }
      nodeId = found.nodeIds[0];
    }
    return inspection.selectNode(a.tabId, nodeId);
  },
  'target.resolve': async (a) => inspection.resolveTarget(a.tabId, a.targetId, a),
  'target.list': async () => host('target.list', {}),
  'page.compare': async (a) => {
    const s = inspection.get(a.tabId);
    if (capture.isActive(a.tabId)) {
      const rec = capture.get(a.tabId);
      await capture.synchronize(a.tabId);
      const base = a.baseCaptureId || rec.referenceCaptureId || rec.baseCaptureId;
      const res = await host('snapshot.compare', { tabKey: tabKey(a.tabId), baseCaptureId: base });
      const pinned = await host('capture.pin', { tabKey: tabKey(a.tabId) }).catch(() => null);
      if (pinned) {
        rec.referenceCaptureId = pinned.referenceCaptureId;
      }
      return res;
    }
    const base = a.baseCaptureId || (s.captures.length ? s.captures[s.captures.length - 1] : null);
    if (!base) {
      throw new HostError('NO_CAPTURE', 'Capture a página antes de comparar');
    }
    const cap = await inspection.captureOnce(a.tabId);
    return host('snapshot.compare', { tabKey: tabKey(a.tabId), baseCaptureId: base, captureId: cap.captureId });
  },
  'pagemap.export': async (a) => {
    const s = inspection.get(a.tabId);
    let sync = null;
    if (capture.isActive(a.tabId)) {
      sync = await capture.synchronize(a.tabId, { geometry: true });
    } else if (!s.captures.length || a.fresh) {
      await inspection.captureOnce(a.tabId);
    }
    const res = await host('pagemap.export', { tabKey: tabKey(a.tabId), includeFamilies: a.includeFamilies !== false, includeLocators: a.includeLocators !== false, returnPageMap: a.returnPageMap !== false }, { timeoutMs: 120000 });
    return sync ? { ...res, syncState: sync.syncState } : res;
  },
  'interaction.observe': async (a) => {
    let after = a.afterCaptureId;
    if (!after) {
      if (capture.isActive(a.tabId)) {
        after = (await capture.synchronize(a.tabId)).latestCaptureId;
      } else {
        after = (await inspection.captureOnce(a.tabId)).captureId;
      }
    }
    const instrumentation = a.readHooks ? await readTestHooks(a.tabId) : null;
    return host('interaction.observe', { tabKey: tabKey(a.tabId), beforeCaptureId: a.beforeCaptureId, afterCaptureId: after, instrumentation });
  },
  'testHooks.read': async (a) => readTestHooks(a.tabId),
  'tracking.start': async (a) => {
    await assertAllowed(a.tabId);
    await capture.acquire(a.tabId, 'tracking');
    return diagnostics(a.tabId);
  },
  'tracking.stop': async (a) => {
    await capture.release(a.tabId, 'tracking', 'TRACKING_STOPPED');
    return diagnostics(a.tabId);
  },
  'tracking.flush': async (a) => {
    const sync = await capture.synchronize(a.tabId);
    return { ...(await diagnostics(a.tabId)).tracker, sync };
  },
  'page.diagnostics': async (a) => diagnostics(a.tabId, a.frameId || 0),
  'page.nodeInfo': async (a) => toContent(a.tabId, 'cs.nodeInfo', { nodeId: a.nodeId }, a.frameId || 0),
  'page.idFor': async (a) => toContent(a.tabId, 'cs.idFor', { css: a.css, scope: a.scope }, a.frameId || 0),
  'page.highlight': async (a) => toContent(a.tabId, 'cs.highlight', a, a.frameId || 0),
  'page.runLocator': async (a) => toContent(a.tabId, 'cs.runLocator', a, a.frameId || 0),
  'state.get': async (a) => {
    const tab = a.tabId != null ? inspection.get(a.tabId) : null;
    return { client: client.status(), tab, inspection: a.tabId != null ? inspection.describe(a.tabId) : null,
      session: a.tabId != null ? capture.status(a.tabId) : null, mode: config.describe(), events: eventLog.slice(-50) };
  },
  'events.since': async (a) => eventLog.filter((e) => e.at > (a.since || 0)),
};

/** Comandos que só se aplicam a páginas locais permitidas. */
const TAB_COMMANDS = new Set(['session.acquire', 'page.select', 'page.capture']); // inspection.* valida e registra ERROR

async function runCommand(cmd, args) {
  await ready;
  const fn = commands[cmd];
  if (!fn) {
    throw new HostError('UNKNOWN_COMMAND', cmd);
  }
  config.assertCommandAllowed(cmd);
  if (TAB_COMMANDS.has(cmd)) {
    await assertAllowed(args.tabId);
  }
  return fn(args);
}

async function onContentEvent(msg, sender) {
  const tabId = sender.tab.id;
  const frameId = sender.frameId || 0;
  if (msg.event === 'capture-part') {
    return capture.onCapturePart(tabId, frameId, msg.data);
  }
  await ready;
  switch (msg.event) {
    case 'delta':
      return capture.onDelta(tabId, frameId, msg.data);
    case 'element-selected':
      inspection.selectNode(tabId, msg.data.nodeId)
        .catch((e) => broadcast('error', { tabId, where: 'selection', ...errorInfo(e) }));
      return { ok: true };
    case 'selection-detached':
      inspection.resolveTarget(tabId, msg.data.targetId)
        .catch((e) => broadcast('error', { tabId, where: 'reacquire', ...errorInfo(e) }));
      return { ok: true };
    case 'resync-needed': {
      const rec = capture.get(tabId);
      if (rec && rec.sessionId === msg.data.sessionId) {
        capture.scheduleResync(rec, 'CONTENT:' + msg.data.reason);
      }
      return { ok: true };
    }
    case 'content-ready':
      if (frameId === 0) {
        const s = inspection.get(tabId);
        s.captures = [];
        s.selected = null;
        broadcast('content-ready', { tabId, ...msg.data });
        capture.onContentReady(tabId, 0, msg.data).catch((e) => broadcast('error', { tabId, where: 'content-ready', ...errorInfo(e) }));
      } else {
        broadcast('frame-ready', { tabId, frameId, ...msg.data });
        frames.onFrameReady(tabId, frameId, msg.data).catch((e) => broadcast('error', { tabId, where: 'frame-ready', ...errorInfo(e) }));
      }
      return { ok: true };
    default:
      return { ok: false, error: 'UNKNOWN_EVENT' };
  }
}

chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  if (!msg || sender.id !== chrome.runtime.id) {
    return false;
  }
  // Eventos dos content scripts (somente de páginas permitidas)
  if (msg.source === 'content' && sender.tab) {
    if (!isAllowedUrl(sender.url || sender.tab.url || '')) {
      sendResponse({ ok: false, error: 'ORIGIN_NOT_ALLOWED' });
      return false;
    }
    onContentEvent(msg, sender).then(sendResponse, (e) => sendResponse({ ok: false, error: errorInfo(e).code }));
    return true;
  }
  // Comandos das páginas da extensão (painel / harness)
  if (msg.target === 'background' && sender.url && sender.url.startsWith(chrome.runtime.getURL(''))) {
    runCommand(msg.cmd, msg.args || {})
      .then((result) => sendResponse({ ok: true, result }))
      .catch((e) => sendResponse({ ok: false, error: errorInfo(e) }));
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
    const kind = evt === 'onHistoryStateUpdated' ? 'SPA_ROUTE' : 'FRAGMENT';
    broadcast('navigation', { tabId: d.tabId, kind, url: d.url, at: Date.now(), transitionType: d.transitionType });
    ready.then(() => capture.onNavigation(d.tabId, kind)).catch(() => {});
  }, { url: [{ hostEquals: '127.0.0.1' }, { hostEquals: 'localhost' }] });
}

chrome.tabs.onRemoved.addListener((tabId) => {
  ready.then(async () => {
    await capture.end(tabId, 'TAB_CLOSED');
    inspection.forget(tabId);
  }).catch(() => {});
});

chrome.runtime.onInstalled.addListener(() => {
  chrome.sidePanel.setPanelBehavior({ openPanelOnActionClick: true }).catch(() => {});
});
