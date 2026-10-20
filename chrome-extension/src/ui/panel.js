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
  renderAccessPath(ins.accessPath, ins.documentId);
}

const STEP_TEXT = { MAIN_DOCUMENT: 'DOCUMENTO', IFRAME: 'IFRAME', SHADOW_ROOT: 'SHADOW', ELEMENT: 'ELEMENTO' };

/** Trajetória (documento → iframes → shadow roots → elemento) produzida pelo motor Java. */
function renderAccessPath(path, documentId) {
  const ol = $('path-steps');
  ol.replaceChildren();
  if (!path) {
    kv($('path-info'), [['Trajetória', 'indisponível para este nó']]);
    setText($('path-explanation'), '');
    return;
  }
  const iframes = path.steps.filter((s) => s.type === 'IFRAME');
  const current = iframes[iframes.length - 1];
  const element = path.steps[path.steps.length - 1];
  kv($('path-info'), [
    ['Elemento', path.target.kind + (path.target.name ? ' · ' + path.target.name : '')],
    ['Documento', element.documentId || documentId],
    ['Iframe atual', current ? current.label : 'nenhum (documento principal)'],
    ['Profundidade', path.frameDepth + ' iframe(s) · ' + path.shadowDepth + ' shadow root(s)'],
    ['Estado', path.accessible ? 'acessível' : 'restrito: ' + path.blockingRestriction],
    ['Estabilidade', path.stability],
  ]);
  for (const s of path.steps) {
    const li = document.createElement('li');
    const t = document.createElement('span');
    t.className = 'step-type';
    t.textContent = STEP_TEXT[s.type] || s.type;
    li.append(t, document.createTextNode((s.label || '—') + (s.locator && s.type !== 'MAIN_DOCUMENT' ? ' — ' + s.locator : '')));
    if (s.restriction) {
      li.dataset.restricted = s.restriction;
      li.title = s.restriction;
    }
    ol.append(li);
  }
  setText($('path-explanation'), path.explanation || '');
}

const PROTECTION_TEXT = {
  NOT_OBSERVED: 'nenhuma proteção observada (não significa ausência)', TECHNOLOGY_DETECTED: 'tecnologia detectada',
  CHALLENGE_PRESENT: 'desafio presente', CHALLENGE_ACTIVE: 'desafio ativo', CHALLENGE_COMPLETED: 'desafio concluído',
  ACCESS_BLOCKED: 'acesso bloqueado', UNKNOWN: 'indeterminado', INSUFFICIENT_EVIDENCE: 'evidência insuficiente',
};

let lastDiagnosis = null;

function renderDiagnosis(d) {
  lastDiagnosis = d;
  const c = d.counts || {};
  setText($('st-docs'), nf.format(c.documents || 0) + (c.inaccessible ? ' (' + c.inaccessible + ' inacessível)' : ''));
  setText($('st-frames'), nf.format(c.frames || 0) + (d.maxDepth ? ' · profundidade ' + d.maxDepth : ''));
  setText($('st-shadow'), nf.format((c.shadowRootsOpen || 0) + (c.shadowRootsClosed || 0)) + (c.shadowRootsClosed ? ' (' + c.shadowRootsClosed + ' fechado)' : ''));
  const p = d.protection;
  const top = p.assessments[0];
  const r = d.readiness;
  const loc = top && top.location;
  kv($('diag-info'), [
    ['Proteção', top ? p.assessments.map((a) => a.provider + (a.product ? ' ' + a.product : '')).join(', ') : PROTECTION_TEXT[p.status] || p.status],
    ['Confiança', top ? top.confidence : '—'],
    ['Estado', PROTECTION_TEXT[top ? top.status : p.status] || (top ? top.status : p.status)],
    ['Localização', loc ? (loc.documentId || '') + (loc.frameChain.length ? ' · iframe nível ' + loc.frameChain.length : '') + (loc.inspectable ? '' : ' · não inspecionável') : null],
    ['Prontidão', r.status + ' (confiança ' + r.confidence + ')'],
    ['Obstáculos', r.obstacles.length ? r.obstacles.map((o) => o.type + (o.count > 1 ? ' ×' + o.count : '')).join(', ') : 'nenhum conhecido'],
    ['Recomendação', r.recommendations.map((x) => x.type).join(', ')],
  ]);
  for (const dd of $('diag-info').querySelectorAll('dd')) {
    const key = dd.previousElementSibling.textContent;
    if (key === 'Confiança') {
      dd.dataset.level = top ? top.confidence : '';
    } else if (key === 'Prontidão') {
      dd.dataset.level = r.status;
    }
  }
}

let diagTimer = null;
let diagRunning = false;

/** Diagnóstico com limitação de frequência: no máximo um a cada 2 s, nunca dois em paralelo. */
function scheduleDiagnosis(delayMs = 2000) {
  if (diagTimer || tabId == null) {
    return;
  }
  diagTimer = setTimeout(async () => {
    diagTimer = null;
    if (diagRunning) {
      scheduleDiagnosis();
      return;
    }
    diagRunning = true;
    try {
      renderDiagnosis(await call('page.diagnose'));
    } catch (e) {
      // sem captura ainda (ou motor indisponível): o painel mantém o último diagnóstico
    } finally {
      diagRunning = false;
    }
  }, delayMs);
}

function clearDiagnosis() {
  lastDiagnosis = null;
  for (const id of ['st-docs', 'st-frames', 'st-shadow']) {
    setText($(id), '—');
  }
  kv($('diag-info'), [['Página', 'ative a inspeção para diagnosticar']]);
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

// ------------------------------------------------------------------------- acesso ao site (REAL-001)

const BROAD = ['https://*/*', 'http://*/*'];
const ACCESS_TEXT = {
  LAB: 'Laboratório local',
  PERMISSION_REQUIRED: 'Não autorizado',
  BROWSER_INTERNAL: 'Página interna do navegador (indisponível)',
  BROWSER_RESTRICTED: 'Bloqueado pelo Chrome (indisponível)',
  UNSUPPORTED_SCHEME: 'Esquema não suportado (somente http/https)',
  INVALID_URL: 'Endereço inválido',
  URL_UNAVAILABLE: 'Página não informada pelo Chrome',
};
let access = null;

function renderAccess(d) {
  access = d;
  const kind = d.allowed ? d.kind : 'DENIED';
  setText($('acc-origin'), d.origin || '—');
  $('acc-state').dataset.kind = kind;
  setText($('acc-state'), d.allowed
    ? (d.kind === 'LAB' ? ACCESS_TEXT.LAB : 'Autorizado' + (BROAD.includes(d.pattern) ? ' (autorização ampla)' : ' (este site)'))
    : ACCESS_TEXT[d.reason] || d.reason);
  setText($('acc-hint'), d.allowed ? (d.kind === 'AUTHORIZED' ? 'A captura só começa quando você ativar a inspeção.' : '') : (d.message || ''));
  $('btn-authorize').hidden = d.reason !== 'PERMISSION_REQUIRED';
  $('btn-authorize-all').hidden = d.reason !== 'PERMISSION_REQUIRED' || d.broad;
  $('btn-revoke').hidden = d.kind !== 'AUTHORIZED';
  setText($('btn-revoke'), BROAD.includes(d.pattern) ? 'Revogar autorização ampla' : 'Revogar acesso a este site');
  if (d.reason !== 'PERMISSION_REQUIRED') {
    $('acc-confirm-all').hidden = true;
  }
}

// STUDIO-013 — consentimento de captura do PDD (separado do acesso do Chrome)
const PDD_STATE = {
  NOT_REQUIRED: 'Laboratório local (não precisa de autorização)',
  NONE: 'Não autorizada neste site',
  SESSION: 'Autorizada até fechar o navegador',
  REMEMBERED: 'Autorizada (site lembrado)',
};
const ACTOR_TEXT = { DEVELOPER: 'desenvolvedor', TEST_AGENT: 'agente de teste' };
const LEVEL_TEXT = { MASKED: 'print mascarado', FULL: 'print integral' };
let pdd = null;

function renderPdd(d) {
  pdd = d;
  setText($('pdd-origin'), d.origin || '—');
  $('pdd-state').dataset.consent = d.consent;
  const who = d.actor ? ' — decidido por ' + (ACTOR_TEXT[d.actor] || d.actor) + (d.via === 'STUDIO' ? ', no Studio' : ', na extensão') : '';
  const granted = d.consent === 'SESSION' || d.consent === 'REMEMBERED';
  const level = granted ? ' · ' + (LEVEL_TEXT[d.level] || LEVEL_TEXT.MASKED) : '';
  setText($('pdd-state'), d.access === 'AUTHORIZED' || d.access === 'LAB' ? (PDD_STATE[d.consent] || d.consent) + level + who
    : 'Indisponível: conceda antes o acesso ao site');
  const can = d.access === 'AUTHORIZED';
  $('btn-pdd-grant').hidden = !can || d.consent !== 'NONE';
  $('pdd-remember-box').hidden = !can || d.consent !== 'NONE';
  $('pdd-level-box').hidden = !can || d.consent !== 'NONE';
  $('btn-pdd-level').hidden = !can || !granted;
  setText($('btn-pdd-level'), d.level === 'FULL' ? 'Voltar ao print mascarado neste site' : 'Liberar print integral neste site');
  $('btn-pdd-revoke').hidden = !can || !granted;
}

async function refreshPdd() {
  if (tabId == null) {
    return;
  }
  try {
    renderPdd(await call('pdd.consent.describe'));
  } catch (e) {
    renderPdd({ origin: null, access: 'URL_UNAVAILABLE', consent: 'NONE' });
  }
  try {
    const { consents } = await call('pdd.consent.list');
    setText($('pdd-count'), String(consents.length));
    const ul = $('pdd-list');
    ul.replaceChildren();
    for (const c of consents) {
      const li = document.createElement('li');
      const label = document.createElement('span');
      label.textContent = c.origin + ' · ' + (c.scope === 'REMEMBERED' ? 'lembrado' : 'nesta sessão') + ' · ' + (LEVEL_TEXT[c.level] || LEVEL_TEXT.MASKED)
        + ' · ' + (ACTOR_TEXT[c.actor] || c.actor);
      const b = document.createElement('button');
      b.type = 'button';
      b.textContent = 'Revogar';
      b.setAttribute('aria-label', 'Revogar captura PDD em ' + c.origin);
      b.onclick = (e) => run(e.target, async () => {
        await call('pdd.consent.revoke', { origin: c.origin });
        message('Captura PDD revogada em ' + c.origin + '.', true);
        await refreshPdd();
      });
      li.append(label, b);
      ul.append(li);
    }
  } catch (e) {
    setText($('pdd-count'), '?');
  }
}

async function refreshAccess() {
  await refreshPdd();
  if (tabId == null) {
    return;
  }
  try {
    renderAccess(await call('access.describe'));
  } catch (e) {
    renderAccess({ allowed: false, reason: 'URL_UNAVAILABLE', message: String(e.message || e) });
  }
}

/** Concessão: SEMPRE no clique do usuário, pelo diálogo do Chrome (nunca automática). */
async function requestOrigins(origins) {
  message('');
  let granted = false;
  try {
    granted = await chrome.permissions.request({ origins });
  } catch (e) {
    message('O Chrome recusou o pedido de autorização: ' + (e.message || e));
    return;
  }
  message(granted ? 'Autorização concedida. Ative a inspeção quando quiser inspecionar a página.' : 'Autorização não concedida.', granted);
  await refreshAccess();
}

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
  } else if (st.state === 'INACTIVE' && st.error) {
    message(st.error.message || st.error.code); // ex.: autorização revogada durante a inspeção
  } else if (st.state === 'ACTIVE' || st.state === 'INACTIVE') {
    message('');
  }
  if (st.state === 'ACTIVE' || st.state === 'DEGRADED') {
    scheduleDiagnosis(lastDiagnosis ? 2000 : 300);
  } else if (st.state === 'INACTIVE') {
    clearDiagnosis();
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

const STUDIO_STATES = {
  DISABLED: ['Desligada', ''],
  SEARCHING: ['Aguardando o Nekkora Studio (abra o Studio neste computador)', 'wait'],
  CONNECTING: ['Conectando…', 'wait'],
  CONNECTED: ['Conectada ao Nekkora Studio', 'ok'],
  INSECURE: ['Recusada: arquivo de conexão do Studio sem proteção adequada', 'err'],
  INCOMPATIBLE: ['Recusada: versão do Studio incompatível', 'err'],
  AUTH_FAILED: ['Recusada: autenticação do Studio falhou', 'err'],
  HOST_DISCONNECTED: ['Motor Java reiniciando…', 'wait'],
  HOST_UNAVAILABLE: ['Motor Java indisponível', 'err'],
};

function renderStudioLink(d) {
  $('studio-enabled').checked = !!d.enabled;
  const [text, kind] = d.enabled ? (STUDIO_STATES[d.state] || [d.state, 'wait']) : STUDIO_STATES.DISABLED;
  setText($('studio-state'), text);
  $('studio-state').dataset.kind = kind;
}

async function refreshStudioLink() {
  try {
    renderStudioLink(await call('studio.link.get'));
  } catch (e) {
    setText($('studio-state'), 'Indisponível');
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
      element: $('element-info').textContent, width: innerWidth, height: innerHeight,
      documents: $('st-docs').textContent, frames: $('st-frames').textContent, shadowRoots: $('st-shadow').textContent,
      path: $('path-info').textContent, pathSteps: [...$('path-steps').children].map((li) => li.textContent),
      explanation: $('path-explanation').textContent, diagnosis: $('diag-info').textContent,
      access: { origin: $('acc-origin').textContent, state: $('acc-state').textContent, kind: $('acc-state').dataset.kind,
        hint: $('acc-hint').textContent, authorize: !$('btn-authorize').hidden, authorizeAll: !$('btn-authorize-all').hidden,
        revoke: !$('btn-revoke').hidden }, studio: { enabled: $('studio-enabled').checked, state: $('studio-state').textContent },
      pdd: { origin: $('pdd-origin').textContent, state: $('pdd-state').textContent, consent: $('pdd-state').dataset.consent,
        grant: !$('btn-pdd-grant').hidden, revoke: !$('btn-pdd-revoke').hidden, sites: [...$('pdd-list').children].map((li) => li.firstChild.textContent) },
      message: $('message').hidden ? '' : $('message').textContent });
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
  if (msg.event === 'access-changed') {
    refreshAccess();
    return;
  }
  if (msg.event === 'pdd-consent-changed') {
    refreshPdd();
    return;
  }
  if (msg.event === 'studio-link') {
    renderStudioLink(msg.data);
    return;
  }
  if (!msg.data || msg.data.tabId !== tabId) {
    return;
  }
  if (msg.event === 'tab-navigated' || msg.event === 'access-revoked') {
    refreshAccess();
  } else if (msg.event === 'inspection-state') {
    renderInspectionState(msg.data);
  } else if (msg.event === 'session-status') {
    const sess = msg.data.session;
    renderSession(sess && sess.status !== 'COMPLETED' ? sess : null);
    if (sess && sess.status === 'ACTIVE') {
      scheduleDiagnosis();
    }
    if (sess && sess.hostState === 'DEGRADED') {
      setText($('host-status'), 'Indisponível · reconectando…');
      $('host-status').className = 'status err';
    } else if (sess && sess.hostState === 'OK' && $('host-status').className !== 'status ok') {
      refreshHost();
    }
  } else if (msg.event === 'frames-updated') {
    scheduleDiagnosis(300);
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
  $('path-info').replaceChildren();
  $('path-steps').replaceChildren();
  setText($('path-explanation'), '');
  clearDiagnosis();
  await refreshAccess();
  await refreshState();
  const stored = await chrome.storage.session.get('inspection:' + tabId).catch(() => ({}));
  if (stored && stored['inspection:' + tabId]) {
    renderInspection(stored['inspection:' + tabId]);
  }
}

(async () => {
  // Interruptor do Studio ligado ANTES de qualquer espera: um clique logo na abertura do painel não se perde.
  $('studio-enabled').onchange = async (e) => {
    const box = e.currentTarget;
    box.disabled = true;
    try {
      renderStudioLink(await call('studio.link.set', { enabled: box.checked }));
    } catch (err) {
      message(String(err.message || err));
      await refreshStudioLink();
    } finally {
      box.disabled = false;
    }
  };
  const fixedTab = new URLSearchParams(location.search).has('tabId');
  renderMode(await call('config.get'));
  await refreshHost();
  await switchTab(await resolveTabId());
  setInterval(() => setText($('st-updated'), relative(lastUpdateAt)), 1000);
  if (!fixedTab) {
    // Painel nativo: acompanha a aba ativa da janela (o estado é por aba).
    chrome.tabs.onActivated.addListener((info) => switchTab(info.tabId));
  }
  await refreshStudioLink();
  $('btn-authorize').onclick = () => access && access.pattern && requestOrigins([access.pattern]);
  $('btn-authorize-all').onclick = () => {
    $('acc-confirm-all').hidden = false; // autorização ampla exige uma segunda confirmação explícita
  };
  $('btn-cancel-all').onclick = () => {
    $('acc-confirm-all').hidden = true;
  };
  $('btn-confirm-all').onclick = () => {
    $('acc-confirm-all').hidden = true;
    requestOrigins(BROAD);
  };
  // STUDIO-013: só o clique do usuário no painel concede (a origem é a da aba, conferida de novo no service worker)
  // pedido da usuária: o print integral é o padrão e não pede segundo clique (o aviso fica fixo na escolha)
  const pddGrant = async (level, scope) => {
    const r = await call('pdd.consent.grant', { origin: pdd && pdd.origin, scope, level });
    $('pdd-remember').checked = false;
    for (const el of document.querySelectorAll('input[name="pdd-level"]')) el.checked = el.value === 'FULL';
    message('Captura PDD autorizada em ' + r.origin + ' (' + LEVEL_TEXT[r.level] + ')'
      + (r.scope === 'REMEMBERED' ? ', site lembrado.' : ', até fechar o navegador.'), true);
    await refreshPdd();
  };
  const levelChosen = () => (document.querySelector('input[name="pdd-level"]:checked') || {}).value === 'FULL' ? 'FULL' : 'MASKED';
  $('btn-pdd-grant').onclick = (e) => run(e.target, async () => {
    await pddGrant(levelChosen(), $('pdd-remember').checked ? 'REMEMBERED' : 'SESSION');
  });
  $('btn-pdd-level').onclick = (e) => run(e.target, async () => {
    const scope = pdd && pdd.consent === 'REMEMBERED' ? 'REMEMBERED' : 'SESSION';
    await pddGrant(pdd && pdd.level === 'FULL' ? 'MASKED' : 'FULL', scope);
  });
  $('btn-pdd-revoke').onclick = (e) => run(e.target, async () => {
    await call('pdd.consent.revoke', { origin: pdd && pdd.origin });
    message('Captura PDD revogada neste site.', true);
    await refreshPdd();
  });
  $('btn-revoke').onclick = (e) => run(e.target, async () => {
    const r = await call('access.revoke', { pattern: access && access.pattern });
    message(r.removed ? 'Autorização revogada (' + r.pattern + ').' : 'O Chrome não removeu a autorização.', r.removed);
    await refreshAccess();
  });
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
  $('btn-frame').onclick = (e) => run(e.target, async () => {
    const r = await call('page.highlightFrame');
    message(r.highlighted ? 'Iframe destacado: ' + r.label + ' (nível ' + r.depth + ').' : 'O elemento está no documento principal.', true);
  });
  $('btn-diagnose').onclick = (e) => run(e.target, async () => {
    const d = await call('page.diagnose');
    renderDiagnosis(d);
    $('resolution').textContent = JSON.stringify({ protecao: d.protection, prontidao: d.readiness, tempos: d.timings }, null, 2);
  });
  $('btn-resolve').onclick = (e) => run(e.target, async () => renderResolution((await call('target.resolve')).result));
  $('btn-compare').onclick = (e) => run(e.target, async () => {
    const m = await call('page.compare');
    $('resolution').textContent = JSON.stringify({ relevante: m.relevant, motivos: m.relevanceReasons, adicionados: m.addedCount, removidos: m.removedCount, alterados: m.changedCount, substituidos: m.replaced }, null, 2);
  });
  $('btn-export').onclick = (e) => run(e.target, async () => {
    const r = await call('pagemap.export', { fresh: true });
    if (!r.pageMap) {
      message('PageMap grande demais para o download pelo painel (' + Math.round(r.bytes / 1048576) + ' MB). Cópia completa: ' + r.path, true);
      return;
    }
    const blob = new Blob([JSON.stringify(r.pageMap, null, 2)], { type: 'application/json' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = 'pagemap-' + r.pageMap.snapshot.captureId + '.json';
    a.click();
    URL.revokeObjectURL(a.href);
    message('PageMap exportado (' + r.pageMap.elements.length + ' elementos). Cópia local: ' + r.path, true);
  });
})();
