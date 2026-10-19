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
  /** STUDIO-010: estado da captura visual em curso (só contagem de mutações; nenhum conteúdo). */
  const pdd = {
    observer: null,
    mutations: 0,
    token() {
      return [root.innerWidth, root.innerHeight, root.devicePixelRatio, Math.round(root.scrollX), Math.round(root.scrollY), this.mutations].join(':');
    },
  };
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
      recorder: NS.interactionRecorder ? NS.interactionRecorder.status() : null,
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
    /** Desliga tudo: inspeção, sensor do Recorder, rastreamento e overlay. */
    'cs.disable': () => {
      NS.interactionRecorder.stop({ discard: false });
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
    /**
     * STUDIO-009 (debug guiado): destaque rosa pelo overlay (pointer-events:none, shadow fechado) — nenhum evento é
     * disparado no elemento, nada é rolado, nenhum estilo da página muda. Devolve a caixa em px do viewport principal.
     */
    /**
     * NEKKORA-STUDIO-012: confirma a opção de uma lista de seleção SEM tirar texto da página. O texto esperado vem do
     * Studio (configurado pela usuária); a resposta é só quantas opções têm esse rótulo (ou valor) e se a selecionada é
     * uma delas. Lista personalizada (não <select>): não aplicável.
     */
    'cs.debug.optionState': (a) => {
      const el = NS.registry.elementFor(a.nodeId);
      if (!el || !el.isConnected) {
        return { ok: false, error: 'NODE_NOT_CONNECTED' };
      }
      if (el.tagName !== 'SELECT' || typeof a.expected !== 'string' || !a.expected) {
        return { ok: true, applicable: false };
      }
      const norm = (t) => String(t == null ? '' : t).normalize('NFC').replace(/\s+/g, ' ').trim();
      const want = norm(a.expected);
      const same = (o) => norm(o.textContent) === want || (o.hasAttribute('value') && norm(o.value) === want);
      const matches = Array.from(el.options).filter(same).length;
      const sel = el.selectedOptions && el.selectedOptions[0];
      return { ok: true, applicable: true, matches: Math.min(matches, 9), selected: !!(sel && same(sel)) };
    },
    'cs.debug.mark': (a) => {
      const el = NS.registry.elementFor(a.nodeId);
      if (!el || !el.isConnected) {
        return { ok: false, error: 'NODE_NOT_CONNECTED' };
      }
      for (const k of ['debug', 'debugReview', 'debugCandidate']) {
        NS.highlighter.hide(k);
      }
      const kind = a.style === 'review' ? 'debugReview' : a.style === 'candidate' ? 'debugCandidate' : 'debug'; // 'confirmed' e 'selected': sólido
      NS.highlighter.show(kind, el, typeof a.label === 'string' ? a.label.slice(0, 60) : '', a.nodeId);
      const r = el.getBoundingClientRect();
      const o = NS.capture.frameOffset(el);
      return { ok: true, box: { x: r.left + o.x, y: r.top + o.y, width: r.width, height: r.height,
        viewportWidth: document.documentElement.clientWidth, viewportHeight: document.documentElement.clientHeight } };
    },
    /**
     * STUDIO-010 (PDD visual, política nekkora-visual/1): PLANO VISUAL do documento principal para a sanitização por
     * lista de permissão. Devolve só geometria e tipos — nenhum texto sai daqui. Portador (pixels reais) apenas se:
     * portador de nome pela nekkora-privacy/2, texto INTEIRO de interface do vocabulário, só texto/formatação inline por
     * dentro, sem imagem de fundo nem pseudo-elemento com conteúdo, inteiro no viewport, no topo nos pontos de amostra.
     * Campos editáveis entram só como moldura (o service worker desenha uma moldura sintética). Iframes, shadow DOM,
     * títulos, parágrafos, tabelas, mensagens, imagens, SVG e canvas ficam de fora (opacos). Liga um observador de
     * mutações: a ficha de layout ({@code cs.pdd.token}) muda se a página mudar até a captura.
     */
    'cs.pdd.plan': (a) => {
      for (const k of ['debug', 'debugReview', 'debugCandidate', 'selection', 'hover']) {
        NS.highlighter.hide(k); // o PDD não desenha nada na página
      }
      pdd.mutations = 0;
      if (pdd.observer) {
        pdd.observer.disconnect();
      }
      pdd.observer = new MutationObserver((list) => { pdd.mutations += list.length; });
      pdd.observer.observe(document.documentElement, { subtree: true, childList: true, attributes: true, characterData: true });
      const vw = root.innerWidth;
      const vh = root.innerHeight;
      const regions = [];
      const rejected = {};
      const reject = (why) => { rejected[why] = (rejected[why] || 0) + 1; };
      const policy = root.NekkoraCapturePolicy;
      const viewport = { width: vw, height: vh, dpr: root.devicePixelRatio, scrollX: root.scrollX, scrollY: root.scrollY };
      if (a && a.policy === 'nekkora-visual/4') {
        // print real com o texto de dados mascarado (pdd-plan.js): só as máscaras saem daqui
        if (!policy || !NS.pddPlan || !NS.pddPlan.v4) {
          return { ok: false, error: 'PLAN_UNAVAILABLE' };
        }
        const v4 = NS.pddPlan.v4(policy, Math.floor(vw), Math.floor(vh), reject);
        return { ok: true, policy: 'nekkora-visual/4', viewport, token: pdd.token(), regions: v4.regions, rejected, incomplete: v4.incomplete,
          labels: v4.labels, hasFrames: v4.hasFrames, limitations: v4.limitations };
      }
      if (a && a.policy === 'nekkora-visual/3') {
        // NEKKORA-STUDIO-013: descrição inteiramente sintética (pdd-plan.js) — px CSS inteiros, nada de pixels da página
        if (!policy || !NS.pddPlan || !NS.pddPlan.v3) {
          return { ok: false, error: 'PLAN_UNAVAILABLE' };
        }
        const v3 = NS.pddPlan.v3(policy, Math.floor(vw), Math.floor(vh), reject);
        return { ok: true, policy: 'nekkora-visual/3', viewport: { ...viewport, width: Math.floor(vw), height: Math.floor(vh) }, token: pdd.token(),
          regions: v3.regions, rejected, hasFrames: v3.hasFrames, limitations: v3.limitations };
      }
      if (a && a.policy === 'nekkora-visual/2') {
        // STUDIO-012 PDD-01: representação documental (pdd-plan.js) — mesma ficha de layout, mesmas recusas por código
        if (!policy || !NS.pddPlan) {
          return { ok: false, error: 'PLAN_UNAVAILABLE' };
        }
        const v2 = NS.pddPlan.v2(policy, vw, vh, reject);
        return { ok: true, policy: 'nekkora-visual/2', viewport, token: pdd.token(), regions: v2.regions, rejected, hasFrames: v2.hasFrames };
      }
      const fields = [];
      for (const el of document.querySelectorAll('input, textarea, select, [contenteditable]')) {
        const t = (el.getAttribute('type') || '').toLowerCase();
        if (el.tagName === 'INPUT' && ['hidden', 'button', 'submit', 'reset', 'image'].includes(t)) {
          continue;
        }
        if (el.getAttribute('contenteditable') === 'false') {
          continue;
        }
        const r = el.getBoundingClientRect();
        if (r.width > 0 && r.height > 0 && r.right > 0 && r.bottom > 0 && r.left < vw && r.top < vh) {
          const x = Math.max(0, r.left), y = Math.max(0, r.top);
          fields.push({ kind: 'FIELD', x, y, width: Math.min(vw, r.right) - x, height: Math.min(vh, r.bottom) - y });
        }
      }
      const INLINE = new Set(['SPAN', 'B', 'STRONG', 'I', 'EM', 'SMALL', 'ABBR', 'U', 'S', 'SUB', 'SUP', 'MARK', 'BR']);
      const pseudoText = (el) => ['::before', '::after'].some((p) => {
        const c = getComputedStyle(el, p).content;
        return c && c !== 'none' && c !== 'normal' && c !== '""' && c !== "''";
      });
      const clean = (el) => {
        const cs = getComputedStyle(el);
        if (cs.backgroundImage && cs.backgroundImage !== 'none') return false;
        if (pseudoText(el)) return false;
        for (const child of el.children) {
          if (!INLINE.has(child.tagName) || !clean(child)) return false;
        }
        return true;
      };
      for (const el of document.querySelectorAll('button, summary, label, legend, a[href], [role]')) {
        if (!policy || !policy.isCarrier(el.tagName.toLowerCase(), el.getAttribute('role'), el.hasAttribute('href'))) {
          continue;
        }
        const r = el.getBoundingClientRect();
        if (!(r.width > 0 && r.height > 0)) continue;
        if (r.left < 0 || r.top < 0 || r.right > vw || r.bottom > vh) { reject('OUTSIDE_VIEWPORT'); continue; }
        const cs = getComputedStyle(el);
        if (cs.visibility !== 'visible' || Number(cs.opacity) === 0) { reject('NOT_VISIBLE'); continue; }
        if (!policy.interfaceText(el.textContent || '')) { reject('TEXT_NOT_INTERFACE'); continue; }
        if (!clean(el)) { reject('NOT_PLAIN_TEXT'); continue; }
        const pts = [[r.left + r.width / 2, r.top + r.height / 2], [r.left + 2, r.top + 2], [r.right - 2, r.top + 2], [r.left + 2, r.bottom - 2],
          [r.right - 2, r.bottom - 2]];
        if (!pts.every(([x, y]) => { const top = document.elementFromPoint(x, y); return top && (top === el || el.contains(top)); })) {
          reject('COVERED'); continue;
        }
        if (fields.some((f) => r.left < f.x + f.width && f.x < r.right && r.top < f.y + f.height && f.y < r.bottom)) {
          reject('OVERLAPS_FIELD'); continue;
        }
        regions.push({ kind: 'CARRIER', x: r.left, y: r.top, width: r.width, height: r.height });
      }
      regions.push(...fields);
      return { ok: true, viewport: { width: vw, height: vh, dpr: root.devicePixelRatio, scrollX: root.scrollX, scrollY: root.scrollY },
        token: pdd.token(), regions, rejected, hasFrames: !!document.querySelector('iframe, frame, object, embed') };
    },
    /** Ficha do layout: muda com rolagem, tamanho, escala ou qualquer mutação desde o plano. */
    'cs.pdd.token': () => ({ ok: true, token: pdd.token() }),
    /** Retângulos (px CSS do viewport) dos nós já resolvidos pelo motor; nó desconectado ou fora do viewport → motivo. */
    'cs.pdd.rects': (a) => {
      const out = [];
      for (const nodeId of (a && Array.isArray(a.nodeIds) ? a.nodeIds : []).slice(0, 32)) {
        const el = NS.registry.elementFor(nodeId);
        if (!el || !el.isConnected) { out.push({ nodeId, reason: 'NODE_NOT_CONNECTED' }); continue; }
        const r = el.getBoundingClientRect();
        if (!(r.width > 0 && r.height > 0) || r.left < 0 || r.top < 0 || r.right > root.innerWidth || r.bottom > root.innerHeight) {
          out.push({ nodeId, reason: 'TARGET_OUTSIDE_VIEWPORT' });
          continue;
        }
        out.push({ nodeId, x: r.left, y: r.top, width: r.width, height: r.height });
      }
      return { ok: true, rects: out, token: pdd.token() };
    },
    'cs.pdd.release': () => {
      if (pdd.observer) {
        pdd.observer.disconnect();
        pdd.observer = null;
      }
      return { ok: true };
    },
    'cs.debug.unmark': () => {
      for (const k of ['debug', 'debugReview', 'debugCandidate']) {
        NS.highlighter.hide(k);
      }
      return { ok: true };
    },
    /**
     * STUDIO-009 G6 — sonda de LABORATÓRIO (o service worker só a envia para origem LAB e alvo reencontrado): preencher
     * com o valor sintético recebido (nunca devolvido), clicar ou Enter. Eventos sintéticos, declarados como tal; o
     * resultado diz só se a operação foi feita e se a página mudou — nenhum conteúdo da página volta.
     */
    'cs.debug.probe': async (a) => {
      const el = NS.registry.elementFor(a.nodeId);
      if (!el || !el.isConnected) {
        return { ok: false, error: 'NODE_NOT_CONNECTED' };
      }
      let mutations = 0;
      const mo = new MutationObserver((list) => { mutations += list.length; });
      mo.observe(document.documentElement, { subtree: true, childList: true, characterData: true, attributes: true });
      try {
        if (a.operation === 'FILL_FIXTURE') {
          if (typeof a.value !== 'string') {
            return { ok: false, error: 'NO_VALUE' };
          }
          el.focus();
          const proto = el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
          const setter = Object.getOwnPropertyDescriptor(proto, 'value');
          if (setter && setter.set && (el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement)) {
            setter.set.call(el, a.value);
          } else {
            return { ok: false, error: 'NOT_A_FIELD' };
          }
          el.dispatchEvent(new Event('input', { bubbles: true }));
          el.dispatchEvent(new Event('change', { bubbles: true }));
        } else if (a.operation === 'CLICK') {
          el.click();
        } else if (a.operation === 'PRESS_ENTER') {
          el.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', bubbles: true }));
          if (el.form && typeof el.form.requestSubmit === 'function') {
            el.form.requestSubmit();
          }
        } else {
          return { ok: false, error: 'UNKNOWN_OPERATION' };
        }
        await new Promise((r) => setTimeout(r, 400));
        return { ok: true, domChanged: mutations > 0 };
      } finally {
        mo.disconnect();
      }
    },
    /** Geometria da janela (documento principal) para a posição aproximada na tela. Sem conteúdo da página. */
    'cs.debug.viewport': () => ({
      screenX: window.screenX, screenY: window.screenY, chromeLeft: Math.max(0, Math.round((window.outerWidth - window.innerWidth) / 2)),
      chromeTop: Math.max(0, window.outerHeight - window.innerHeight), innerWidth: window.innerWidth, innerHeight: window.innerHeight,
      devicePixelRatio: window.devicePixelRatio,
    }),
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
    // ---- sensor de interações do Recorder do Studio (STUDIO-003)
    'cs.recorder.start': (a) => NS.interactionRecorder.start({ epoch: a.epoch, mode: a.mode, evidence: !!a.evidence }),
    'cs.recorder.mode': (a) => NS.interactionRecorder.setMode({ epoch: a.epoch, mode: a.mode }),
    'cs.recorder.flush': (a) => NS.interactionRecorder.flush({ reason: a.reason }),
    'cs.recorder.stop': (a) => NS.interactionRecorder.stop({ discard: !!a.discard }),
    'cs.recorder.navigation': () => NS.interactionRecorder.navigation(),
    'cs.recorder.status': () => NS.interactionRecorder.status(),
    'cs.diagnostics': () => diagnostics(),
    'cs.nodeInfo': (a) => {
      const el = NS.registry.elementFor(a.nodeId);
      if (!el) {
        return { exists: false, connected: false };
      }
      const r = el.getBoundingClientRect();
      // CHROME-SECURITY-001 (2ª revisão): nada de texto da página nem id cru — só o que a política deixa sair
      const CP = root.NekkoraCapturePolicy;
      return { exists: true, connected: el.isConnected, tag: CP ? CP.tagName(el.tagName) : null, id: CP && el.id ? CP.developerIdentifier(el.id) : null,
        rect: { left: r.left, top: r.top, width: r.width, height: r.height } };
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
