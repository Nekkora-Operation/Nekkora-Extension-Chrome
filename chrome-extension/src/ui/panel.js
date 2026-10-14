/*
 * Painel lateral: botão único de inspeção (estado sincronizado com o service worker), indicador da captura
 * automática e visualização do elemento selecionado. Atualização seletiva: somente os campos que mudaram;
 * nenhum PageMap trafega para atualizar contadores (o evento session-status traz apenas o resumo).
 */
globalThis.NekkoraTokens.applyCssVariables(document.documentElement);

const $ = (id) => document.getElementById(id);
let tabId = null;
let mode = 'DEV';
let lastUpdateAt = null;
const nf = new Intl.NumberFormat('pt-BR');

async function resolveTabId() {
  const fromQuery = new URLSearchParams(location.search).get('tabId');
  if (fromQuery) {
    return Number(fromQuery);
  }
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  return tab ? tab.id : null;
}

async function call(cmd, args = {}) {
  const res = await chrome.runtime.sendMessage({ target: 'background', cmd, args: { tabId, ...args } });
  if (!res || !res.ok) {
    throw new Error(res && res.error ? res.error.code + ': ' + res.error.message : 'sem resposta');
  }
  return res.result;
}

function setText(el, text) {
  if (el.textContent !== text) {
    el.textContent = text;
  }
}

function message(text, info) {
  const m = $('message');
  m.hidden = !text;
  setText(m, text || '');
  m.className = 'message' + (info ? ' info' : '');
}

function kv(el, pairs) {
  el.replaceChildren();
  for (const [k, v] of pairs) {
    if (v === undefined || v === null || v === '') {
      continue;
    }
    const dt = document.createElement('dt');
    dt.textContent = k;
    const dd = document.createElement('dd');
    dd.textContent = typeof v === 'string' ? v : JSON.stringify(v);
    el.append(dt, dd);
  }
}

async function run(button, fn) {
  button.disabled = true;
  message('');
  try {
    await fn();
  } catch (e) {
    message(String(e.message || e));
  } finally {
    button.disabled = false;
  }
}

function renderInspection(data) {
  const ins = data.inspection;
  const c = ins.classification;
  kv($('element-info'), [
    ['Nó (temporário)', ins.nodeId],
    ['Alvo lógico', data.identity && data.identity.targetId],
    ['Tipo', c.kind + ' · papel ' + c.role + ' · ' + c.confidence],
    ['Nome', c.name.name + ' (' + c.name.source + (c.name.confidence ? ', ' + c.name.confidence : '') + ')'],
    ['Condições', c.conditions.join(', ')],
    ['Evidências', c.evidence.join('; ')],
    ['Incertezas', c.uncertainties.join('; ')],
    ['Parecidos', ins.lookalikes.length + ' elemento(s)'],
    ['Discriminadores', data.identity && data.identity.discriminators.join(', ')],
  ]);
  const f = ins.family;
  kv($('family-info'), [
    ['Pai', f.parent && f.parent.tag + (f.parent.idAttr ? '#' + f.parent.idAttr : '')],
    ['Formulário', f.form && (f.form.idAttr || f.form.name)],
    ['Grupo', f.fieldGroup && f.fieldGroup.name],
    ['Label', f.labels.map((l) => l.name).join(', ')],
    ['Tabela', f.table && (f.table.idAttr || f.table.name)],
    ['Linha', f.row && JSON.stringify(f.row.rowKey) + ' · ' + f.row.cellTexts.join(' | ')],
    ['Coluna', f.column && f.column.headerText],
    ['Diálogo', f.dialog && f.dialog.name],
    ['Região', f.region && f.region.name],
    ['Título', f.containerHeading],
    ['Contexto', f.contextualTexts.join(' | ')],
    ['Escopo', f.scopeChain.map((h) => h.tag + (h.idAttr ? '#' + h.idAttr : '')).join(' › ')],
  ]);
  const tbody = $('locators').querySelector('tbody');
  tbody.replaceChildren();
  const checks = new Map((data.liveChecks || []).map((x) => [x.semantic, x]));
  const all = [ins.locators.primary, ...ins.locators.fallbacks].filter(Boolean);
  all.forEach((l, i) => {
    const tr = document.createElement('tr');
    if (i === 0) {
      tr.className = 'primary-row';
    }
    const live = checks.get(l.semantic);
    const liveText = live ? [live.cssCount >= 0 ? 'css ' + live.cssCount : null, live.xpathCount >= 0 ? 'xp ' + live.xpathCount : null].filter(Boolean).join(' / ') || '—' : 'motor';
    for (const text of [l.strategy, l.css || l.xpath || l.semantic, String(l.matchCount), liveText, l.stability]) {
      const td = document.createElement('td');
      td.textContent = text;
      tr.append(td);
    }
    tbody.append(tr);
    tr.children[1].className = 'sel';
  });
}

function renderResolution(r) {
  const lines = ['status: ' + r.status, 'nó: ' + (r.resolvedNodeId || '—'), 'pontuação: ' + r.score, 'substituído: ' + r.nodeReplaced, ''];
  lines.push(...r.evidence);
  $('resolution').textContent = lines.join('\n');
}

// ------------------------------------------------------------------------- botão único e status

const BUTTON = {
  INACTIVE: { text: 'Ativar inspeção', pressed: false, busy: false },
  ACTIVATING: { text: 'Ativando inspeção...', pressed: false, busy: true },
  ACTIVE: { text: 'Desativar inspeção', pressed: true, busy: false },
  DEACTIVATING: { text: 'Desativando inspeção...', pressed: true, busy: true },
  DEGRADED: { text: 'Desativar inspeção', pressed: true, busy: false },
  ERROR: { text: 'Ativar inspeção', pressed: false, busy: false },
};

const CAPTURE_TEXT = {
  IDLE: 'Inativa', STARTING: 'Iniciando captura…', ACTIVE: 'Automática ativa', PAUSED: 'Pausada (motor indisponível)',
  RECOVERING: 'Ressincronizando…', FINALIZING: 'Finalizando gravação…', COMPLETED: 'Inativa', ERROR: 'Erro na captura',
};

function renderInspectionState(st) {
  const b = $('btn-inspect');
  const cfg = BUTTON[st.state] || BUTTON.INACTIVE;
  setText(b, cfg.text);
  b.setAttribute('aria-pressed', String(cfg.pressed));
  b.setAttribute('aria-busy', String(cfg.busy));
  b.dataset.state = st.state;
  b.disabled = cfg.busy;
  if (st.state === 'ERROR' && st.error) {
    message('Falha ao ativar a inspeção: ' + st.error.code + ' — ' + st.error.message);
  } else if (st.state === 'DEGRADED') {
    message('Inspeção ativa com captura degradada' + (st.error ? ' (' + st.error.code + ')' : '') + '. Recuperando…');
  } else if (st.state === 'ACTIVE' || st.state === 'INACTIVE') {
    message('');
  }
  renderSession(st.capture);
}

function relative(iso) {
  if (!iso) {
    return '—';
  }
  const s = Math.max(0, Math.round((Date.now() - Date.parse(iso)) / 1000));
  if (s < 2) {
    return 'agora';
  }
  return s < 60 ? 'há ' + s + ' s' : new Date(iso).toLocaleTimeString('pt-BR');
}

function renderSession(session) {
  const status = session ? session.status : 'IDLE';
  setText($('st-capture'), CAPTURE_TEXT[status] || status);
  $('st-capture').dataset.status = status;
  setText($('st-nodes'), session && session.nodes ? nf.format(session.nodes) : '—');
  setText($('st-deltas'), session ? nf.format(session.deltasApplied) : '—');
  lastUpdateAt = session ? session.lastUpdateAt : null;
  setText($('st-updated'), relative(lastUpdateAt));
}

function renderMode(m) {
  mode = m.mode;
  const dev = mode === 'DEV';
  $('dev-tools').hidden = !dev;
  $('dev-output').hidden = !dev;
  if (!dev) {
    // Em PRD os recursos de desenvolvimento deixam de existir no documento (não apenas ficam ocultos).
    $('dev-tools').remove();
    $('dev-output').remove();
  }
  $('mode-badge').hidden = false;
  setText($('mode-badge'), mode + (m.simulated ? ' (simulado)' : ''));
}

async function refreshHost() {
  try {
    const s = await call('host.ping');
    setText($('host-status'), 'Conectado · Java ' + s.ping.java);
    $('host-status').className = 'status ok';
  } catch (e) {
    setText($('host-status'), 'Indisponível');
    $('host-status').className = 'status err';
    message('Native Messaging Host não respondeu: ' + e.message + '. Veja README › Solução de problemas.');
  }
}

async function refreshState() {
  if (tabId == null) {
    return;
  }
  try {
    renderInspectionState(await call('inspection.state'));
  } catch (e) {
    message(String(e.message || e));
  }
}

/**
 * Homologação do painel NATIVO (side panel não é uma janela controlável pelo WebDriver): páginas internas da
 * extensão podem pedir o estado visível do painel e acionar um botão dele. Mensagens de páginas web não chegam aqui.
 */
chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  if (!msg || msg.target !== 'panel' || sender.id !== chrome.runtime.id) {
    return false;
  }
  if (msg.cmd === 'panel.snapshot') {
    const b = $('btn-inspect');
    sendResponse({ url: location.href, tabId, mode, button: b.textContent, pressed: b.getAttribute('aria-pressed'),
      capture: $('st-capture').textContent, nodes: $('st-nodes').textContent, deltas: $('st-deltas').textContent,
      updated: $('st-updated').textContent, host: $('host-status').textContent, devTools: !!$('dev-tools'),
      element: $('element-info').textContent, width: innerWidth, height: innerHeight });
  } else if (msg.cmd === 'panel.click' && $(msg.id)) {
    $(msg.id).click();
    sendResponse({ clicked: msg.id });
  } else {
    sendResponse({ error: 'UNKNOWN_PANEL_COMMAND' });
  }
  return false;
});

chrome.runtime.onMessage.addListener((msg) => {
  if (!msg || msg.source !== 'background') {
    return;
  }
  if (msg.event === 'config') {
    location.reload();
    return;
  }
  if (!msg.data || msg.data.tabId !== tabId) {
    return;
  }
  if (msg.event === 'inspection-state') {
    renderInspectionState(msg.data);
  } else if (msg.event === 'session-status') {
    const sess = msg.data.session;
    renderSession(sess && sess.status !== 'COMPLETED' ? sess : null);
    if (sess && sess.hostState === 'DEGRADED') {
      setText($('host-status'), 'Indisponível · reconectando…');
      $('host-status').className = 'status err';
    } else if (sess && sess.hostState === 'OK' && $('host-status').className !== 'status ok') {
      refreshHost();
    }
  } else if (msg.event === 'inspection') {
    renderInspection(msg.data);
  } else if (msg.event === 'resolution') {
    if (mode === 'DEV') {
      renderResolution(msg.data.result);
    }
  } else if (msg.event === 'error') {
    message(msg.data.code + ': ' + msg.data.message);
  }
});

async function switchTab(id) {
  tabId = id;
  $('element-info').replaceChildren();
  $('family-info').replaceChildren();
  $('locators').querySelector('tbody').replaceChildren();
  await refreshState();
  const stored = await chrome.storage.session.get('inspection:' + tabId).catch(() => ({}));
  if (stored && stored['inspection:' + tabId]) {
    renderInspection(stored['inspection:' + tabId]);
  }
}

(async () => {
  const fixedTab = new URLSearchParams(location.search).has('tabId');
  renderMode(await call('config.get'));
  await refreshHost();
  await switchTab(await resolveTabId());
  setInterval(() => setText($('st-updated'), relative(lastUpdateAt)), 1000);
  if (!fixedTab) {
    // Painel nativo: acompanha a aba ativa da janela (o estado é por aba).
    chrome.tabs.onActivated.addListener((info) => switchTab(info.tabId));
  }
  $('btn-inspect').onclick = async (e) => {
    const b = e.currentTarget;
    if (b.disabled) {
      return; // transição em andamento: clique duplo ignorado
    }
    b.disabled = true;
    message('');
    try {
      renderInspectionState(await call('inspection.toggle'));
    } catch (err) {
      await refreshState();
      message(String(err.message || err));
    }
  };
  if (mode !== 'DEV') {
    return;
  }
  $('btn-capture').onclick = (e) => run(e.target, async () => {
    const r = await call('page.capture');
    message('Captura completa: ' + nf.format(r.nodes) + ' elementos (' + r.browser.captureMs + ' ms na página).', true);
  });
  $('btn-resolve').onclick = (e) => run(e.target, async () => renderResolution((await call('target.resolve')).result));
  $('btn-compare').onclick = (e) => run(e.target, async () => {
    const m = await call('page.compare');
    $('resolution').textContent = JSON.stringify({ relevante: m.relevant, motivos: m.relevanceReasons, adicionados: m.addedCount, removidos: m.removedCount, alterados: m.changedCount, substituidos: m.replaced }, null, 2);
  });
  $('btn-export').onclick = (e) => run(e.target, async () => {
    const r = await call('pagemap.export', { fresh: true });
    const blob = new Blob([JSON.stringify(r.pageMap, null, 2)], { type: 'application/json' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = 'pagemap-' + r.pageMap.snapshot.captureId + '.json';
    a.click();
    URL.revokeObjectURL(a.href);
    message('PageMap exportado (' + r.pageMap.elements.length + ' elementos). Cópia local: ' + r.path, true);
  });
})();
