/* Painel lateral: ações de inspeção e visualização de resultados. */
globalThis.NekkoraTokens.applyCssVariables(document.documentElement);

const $ = (id) => document.getElementById(id);
let tabId = null;

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

function message(text, info) {
  const m = $('message');
  m.hidden = !text;
  m.textContent = text || '';
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

function renderCapture(c) {
  const nav = c.navigation || {};
  kv($('page-info'), [
    ['Captura', c.captureId],
    ['URL', nav.observed && nav.observed.href],
    ['Tela (inferida)', nav.inferred && nav.inferred.screen],
    ['Query', nav.observed && JSON.stringify(nav.observed.query)],
    ['Elementos', c.nodes + ' (' + c.interactive + ' interativos, ' + c.text + ' textuais)'],
    ['Tempo', (c.browser ? c.browser.captureMs + ' ms captura, ' : '') + (c.timings ? c.timings.classificationMs + ' ms classificação' : '')],
    ['Limitações', (c.coverage && c.coverage.limitations || []).join(' | ') || 'nenhuma declarada'],
  ]);
}

function renderInspection(data) {
  const ins = data.inspection;
  const c = ins.classification;
  kv($('element-info'), [
    ['Nó (temporário)', ins.nodeId],
    ['Alvo lógico', data.identity && data.identity.targetId],
    ['Tipo', c.kind + ' · papel ' + c.role + ' · ' + c.confidence],
    ['Nome', c.name.name + ' (' + c.name.source + ')'],
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

async function refreshHost() {
  try {
    const s = await call('host.ping');
    $('host-status').textContent = 'host Java ' + s.ping.java + ' · ' + s.ping.hostVersion;
    $('host-status').className = 'status ok';
  } catch (e) {
    $('host-status').textContent = 'host indisponível';
    $('host-status').className = 'status err';
    message('Native Messaging Host não respondeu: ' + e.message + '. Veja README › Solução de problemas.');
  }
}

chrome.runtime.onMessage.addListener((msg) => {
  if (!msg || msg.source !== 'background' || (msg.data && msg.data.tabId !== tabId)) {
    return;
  }
  if (msg.event === 'inspection') {
    renderInspection(msg.data);
  } else if (msg.event === 'resolution') {
    renderResolution(msg.data.result);
  } else if (msg.event === 'error') {
    message(msg.data.code + ': ' + msg.data.message);
  }
});

(async () => {
  tabId = await resolveTabId();
  await refreshHost();
  $('btn-inspect').onclick = (e) => run(e.target, async () => {
    await call('page.inspect.start');
    message('Inspeção ativa: passe o mouse e clique em um elemento (a ação do elemento não será executada).', true);
  });
  $('btn-disable').onclick = (e) => run(e.target, async () => {
    await call('page.disable');
    $('chk-tracking').checked = false;
    message('Inspeção desativada: destaques, ouvintes e observadores removidos.', true);
  });
  $('btn-capture').onclick = (e) => run(e.target, async () => renderCapture(await call('page.capture')));
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
  $('chk-tracking').onchange = (e) => run(e.target, async () => {
    await call(e.target.checked ? 'tracking.start' : 'tracking.stop');
  });
  const stored = await chrome.storage.session.get('inspection:' + tabId).catch(() => ({}));
  if (stored && stored['inspection:' + tabId]) {
    renderInspection(stored['inspection:' + tabId]);
  }
})();
