/*
 * STUDIO-010 — captura visual SANITIZADA para o PDD (capacidade pdd-capture/1, política nekkora-visual/1).
 * STUDIO-012 PDD-01 — o Studio pode pedir a política nekkora-visual/2 (representação documental: estrutura redesenhada
 * com paleta fechada + portadores); sem pedido, tudo segue exatamente como na v1.
 *
 * Máquina de estados (a concessão do activeTab sozinha não basta — ela persiste depois do gesto):
 *   1. studio.pdd.capture.arm  — o Studio arma UMA captura para um bloco: aba LAB, etapas (targetId + descritor),
 *      validade curta. Nada é capturado ainda; o ícone da extensão mostra "PDD".
 *   2. Gesto: o atalho "nk-pdd-capturar" (Alt+Shift+K) no Chrome. Só vale com armação válida, aba ativa = aba armada,
 *      origem LAB, mesma época da página e uso único.
 *   3. Resolução das etapas pelo motor (somente leitura, por identidade), plano visual do content script, ficha de
 *      layout, captureVisibleTab, ficha de novo (página mudou → descarta), sanitização por lista de permissão
 *      (visual-sanitizer.js), autoverificação de pixels.
 *      captureVisibleTab captura a aba ATIVA da janela: a identidade da aba armada é conferida antes do plano, logo antes
 *      e logo depois da captura (ativa na mesma janela, mesma janela, nenhuma troca de aba na janela desde o gesto —
 *      inclusive A→B→A). Qualquer divergência descarta a imagem: ACTIVE_TAB_CHANGED.
 *   4. studio.pdd.captured — PNG JÁ sanitizado + regiões (tipo e retângulo) + anotações normalizadas; ou só o código da
 *      falha. A imagem bruta nunca é gravada, registrada, enviada nem guardada em storage: existe só dentro de
 *      shoot(), e é liberada no finally (inclusive em exceção).
 *
 * NEKKORA-STUDIO-013 — sites autorizados. A lista compilada de origens saiu: fora do laboratório, a captura exige a
 * permissão de site do Chrome E o consentimento PDD da origem exata (pdd-consent.js), concedido só pela interface
 * confiável (comandos studio.pdd.consent.* do Studio ou o painel da extensão). Nesse perfil (AUTHORIZED_SITE) só vale a
 * nekkora-visual/3: a extensão NÃO chama captureVisibleTab e envia apenas a descrição sintética já validada. A armação
 * guarda a origem; mudança de origem, revogação da permissão do Chrome ou do consentimento PDD invalidam a armação e a
 * captura em curso (PERMISSION_REVOKED / PDD_CONSENT_REVOKED / ORIGIN_CHANGED).
 *
 * Revisão da usuária (19/10/2026) — duas opções no site autorizado: a nekkora-visual/4 (print mascarado, padrão) e a
 * nekkora-visual/5 (print integral: a tela como está, só senhas cobertas). A v5 exige o nível FULL no consentimento da
 * origem, conferido na armação, antes e depois da captura; rebaixar o nível derruba a armação e a captura em curso.
 */
import { VISUAL_POLICY, VISUAL_POLICY_V2, VISUAL_POLICY_V3, VISUAL_POLICY_V4, VISUAL_POLICY_V5, VISUAL_POLICIES, MAX_PNG_BYTES, MAX_PNG_BYTES_V4,
  codeError, compose, composeV2, composeV4, deviceRegions, deviceRegionsV2, deviceMasksV4, deviceMasksV5, violations, violationsV2, violationsV4,
  validateV3 } from './visual-sanitizer.js';
import { visualProfile, denialReason, levelAllows, POLICIES_BY_PROFILE } from './visual-capture-profile.js';
import { originOf, canonicalOrigin, SCOPES, ACTORS, LEVELS } from './pdd-consent.js';

const PROTOCOL = 'nekkora-nm/1';
const ID = /^[A-Za-z0-9._:-]{1,80}$/;
const TAB_REF = /^t-[A-Za-z0-9_-]{6,40}$/;
const ACTIONS = new Set(['CLICK', 'FILL', 'SELECT_OPTION', 'CHECK', 'UNCHECK', 'PRESS_KEY', 'NAVIGATE', 'WAIT_FOR', 'OBSERVE', 'UNKNOWN']);
const MAX_STEPS = 32;
export const SHORTCUT = 'nk-pdd-capturar';
export const FAILURES = new Set(['VISUAL_CAPTURE_NOT_ALLOWED', 'NOT_ARMED', 'ARM_EXPIRED', 'TAB_MISMATCH', 'PAGE_CHANGED_DURING_CAPTURE',
  'GEOMETRY_MISMATCH', 'SANITIZATION_CHECK_FAILED', 'SANITIZED_IMAGE_TOO_LARGE', 'TAB_CLOSED', 'PAGE_CHANGED', 'PERMISSION_REVOKED', 'CAPTURE_BUSY',
  'CAPTURE_DENIED', 'REGION_OUT_OF_VIEWPORT', 'CARRIER_TOO_LARGE', 'CARRIER_OVERLAPS_FIELD', 'TOO_MANY_REGIONS', 'REGION_KIND_UNKNOWN', 'CONTENT_UNAVAILABLE',
  'CANCELLED', 'ACTIVE_TAB_CHANGED', 'INTERNAL', 'REGION_ORDER', 'CARRIER_OVERLAPS_CONTENT',
  // STUDIO-013
  'PDD_CONSENT_REVOKED', 'ORIGIN_CHANGED', 'REGION_FIELD_UNKNOWN', 'TEXT_NOT_QUANTIZED', 'LABEL_NOT_INTERFACE_TEXT']);
const OMIT = new Set(['NOT_FOUND', 'AMBIGUOUS', 'IDENTITY_MISMATCH', 'STALE_TARGET', 'SCOPE_UNAVAILABLE', 'INSUFFICIENT_EVIDENCE', 'CONTEXT_UNAVAILABLE',
  'NODE_NOT_CONNECTED', 'TARGET_OUTSIDE_VIEWPORT', 'TARGET_IN_FRAME', 'NO_TARGET', 'RESOLUTION_FAILED']);

export function createStudioPdd(deps) {
  const { client, policy, tabs } = deps;
  const chromeApi = deps.chrome || globalThis.chrome;
  const host = deps.host;
  const toContent = deps.toContent;
  const tabKey = deps.tabKey || ((tabId) => 'tab-' + tabId);
  const frameOfNode = deps.frameOfNode || (() => 0);
  const currentCapture = deps.currentCapture;
  const imaging = deps.imaging || browserImaging(); // decodificar/codificar PNG (OffscreenCanvas no service worker)
  const now = () => (deps.now ? deps.now() : Date.now());
  const consent = deps.consent || { get: () => null, load: async () => {}, list: async () => [] }; // sem módulo: só o laboratório
  const textPolicy = () => deps.textPolicy || globalThis.NekkoraCapturePolicy;
  let armed = null; // { captureId, controller, tabId, tabRef, steps, expiresAt, policy, profile, origin }
  let running = false;
  let inflight = null; // { captureId, controller, tabId, windowId, switched, cancelled } — do gesto até o evento

  const COMMANDS = {
    'studio.pdd.capture.arm': arm,
    'studio.pdd.capture.cancel': cancel,
    // STUDIO-013 (nekkora-pdd-consent/1): consultar/conceder/revogar o consentimento PDD por origem exata
    'studio.pdd.consent.status': consentStatus,
    'studio.pdd.consent.grant': consentGrant,
    'studio.pdd.consent.revoke': consentRevoke,
    'studio.pdd.consent.list': consentList,
  };

  function fail(code, message, extra = {}) {
    const e = new Error(code + ': ' + message);
    e.code = code;
    Object.assign(e, extra);
    return e;
  }

  function assertOnly(p, allowed) {
    if (!p || typeof p !== 'object' || Array.isArray(p)) {
      throw fail('BAD_REQUEST', 'payload inválido');
    }
    for (const k of Object.keys(p)) {
      if (!allowed.includes(k)) {
        throw fail('BAD_REQUEST', 'campo não permitido');
      }
    }
  }

  function badge(tabId, text) {
    try {
      chromeApi.action.setBadgeText({ tabId, text }).catch(() => {});
    } catch (e) {
      // sem ícone (teste)
    }
  }

  function postEvent(controller, payload) {
    if (typeof client.status !== 'function' || client.status().connected) {
      client.post({ protocol: PROTOCOL, type: 'studio.event', event: 'studio.pdd.captured', payload, controller: { instanceId: controller } })
        .catch(() => {});
    }
  }

  function failed(a, code) {
    postEvent(a.controller, { captureId: a.captureId, status: 'FAILED', code: FAILURES.has(code) ? code : 'INTERNAL', observedAt: new Date(now()).toISOString() });
  }

  function disarm(reasonCode) {
    const a = armed;
    armed = null;
    if (a) {
      badge(a.tabId, '');
      if (reasonCode) {
        failed(a, reasonCode);
      }
    }
    return a;
  }

  async function arm(p, controller) {
    assertOnly(p, ['captureId', 'tabRef', 'steps', 'leaseMs', 'policy', 'origin']);
    if (p.origin != null && !canonicalOriginOrLab(p.origin)) throw fail('BAD_REQUEST', 'origem inválida');
    // STUDIO-012 PDD-01: o Studio pede a política; sem o campo, a v1 (Studios anteriores continuam iguais)
    const visual = p.policy == null ? VISUAL_POLICY : p.policy;
    if (!VISUAL_POLICIES.includes(visual)) throw fail('BAD_REQUEST', 'política visual inválida');
    if (typeof p.captureId !== 'string' || !ID.test(p.captureId)) throw fail('BAD_REQUEST', 'captureId inválido');
    if (typeof p.tabRef !== 'string' || !TAB_REF.test(p.tabRef)) throw fail('BAD_REQUEST', 'tabRef inválido');
    if (!Number.isInteger(p.leaseMs) || p.leaseMs < 5000 || p.leaseMs > 120000) throw fail('BAD_REQUEST', 'leaseMs inválido');
    if (!Array.isArray(p.steps) || p.steps.length < 1 || p.steps.length > MAX_STEPS) throw fail('BAD_REQUEST', 'etapas inválidas');
    const steps = p.steps.map((s) => {
      assertOnly(s, ['stepRef', 'targetId', 'descriptor', 'actionType']);
      if (typeof s.stepRef !== 'string' || !ID.test(s.stepRef)) throw fail('BAD_REQUEST', 'stepRef inválido');
      if (s.targetId != null && (typeof s.targetId !== 'string' || !ID.test(s.targetId))) throw fail('BAD_REQUEST', 'targetId inválido');
      if (!ACTIONS.has(s.actionType)) throw fail('BAD_REQUEST', 'actionType inválido');
      return { stepRef: s.stepRef, targetId: s.targetId || null, descriptor: deps.descriptorOf ? deps.descriptorOf(s.descriptor) : s.descriptor || null,
        actionType: s.actionType };
    });
    if (armed && armed.captureId === p.captureId && armed.controller === controller) {
      return { captureId: p.captureId, armed: true, duplicate: true, shortcut: await shortcut(), ...(p.policy != null ? { policy: armed.policy } : {}),
        ...(p.origin != null ? { profile: armed.profile } : {}) };
    }
    if (running || armed) throw fail('INVALID_STATE', 'Já há uma captura armada ou em andamento.', { reason: 'CAPTURE_BUSY' });
    const tabId = tabs.tabOf(p.tabRef);
    const tab = tabId == null ? null : await chromeApi.tabs.get(tabId).catch(() => null);
    if (!tab) throw fail('TAB_NOT_FOUND', 'Aba não encontrada.');
    await consent.load();
    const url = await policy.tabUrl(tabId).catch(() => null);
    const ev = policy.evaluate(url);
    const c = ev.origin ? consent.get(ev.origin) : null;
    // origem que o Studio mostrou ao usuário: se a aba (permitida) mudou de origem desde então, é isso que se diz primeiro
    if (p.origin != null && ev.allowed && p.origin !== ev.origin) throw fail('VISUAL_CAPTURE_NOT_ALLOWED', 'A aba mudou de origem.', { reason: 'ORIGIN_CHANGED' });
    const profile = visualProfile(ev, c);
    if (!profile) {
      const reason = denialReason(ev, c);
      throw fail('VISUAL_CAPTURE_NOT_ALLOWED', reason === 'PDD_CONSENT_REQUIRED'
        ? 'Esta página permite gravação, mas a captura de PDD ainda não está autorizada nesta origem.'
        : 'A captura de tela para o PDD exige o laboratório local ou um site autorizado no Chrome e para o PDD.', { reason });
    }
    if (!POLICIES_BY_PROFILE[profile].includes(visual)) {
      // Studio anterior (v1/v2) num site autorizado: recusado — a v2 copiaria pixels reais dos rótulos
      throw fail('VISUAL_CAPTURE_NOT_ALLOWED', 'Neste site a captura do PDD exige a política nekkora-visual/3 (atualize o Studio).',
        { reason: 'POLICY_REQUIRES_V3' });
    }
    if (!levelAllows(profile, visual, c)) {
      throw fail('VISUAL_CAPTURE_NOT_ALLOWED', 'O print integral não está liberado neste site (só o print mascarado).', { reason: 'PDD_FULL_REQUIRED' });
    }
    if (tabs.recordingOn && tabs.recordingOn(tabId)) throw fail('INVALID_STATE', 'Há uma gravação nesta aba.', { reason: 'RECORDING_ACTIVE' });
    armed = { captureId: p.captureId, controller, tabId, tabRef: p.tabRef, steps, expiresAt: now() + p.leaseMs, policy: visual, profile, origin: ev.origin };
    badge(tabId, 'PDD');
    return { captureId: p.captureId, armed: true, duplicate: false, shortcut: await shortcut(), ...(p.policy != null ? { policy: visual } : {}),
      ...(p.origin != null ? { profile } : {}) }; // sem os pedidos: resposta da v1, igual
  }

  async function cancel(p, controller) {
    assertOnly(p, ['captureId']);
    if (armed && armed.captureId === p.captureId && armed.controller === controller) {
      disarm('CANCELLED');
      return { captureId: p.captureId, cancelled: true };
    }
    if (inflight && inflight.captureId === p.captureId && inflight.controller === controller) {
      inflight.cancelled = true; // a captura em andamento termina em CANCELLED: nenhuma imagem é publicada
      return { captureId: p.captureId, cancelled: true };
    }
    return { captureId: typeof p.captureId === 'string' ? p.captureId.slice(0, 80) : '', cancelled: false };
  }

  /** Situação da aba para o PDD: acesso do Chrome, perfil e consentimento — origem sem caminho, nunca a URL. */
  async function consentStatus(p) {
    assertOnly(p, ['tabRef']);
    if (typeof p.tabRef !== 'string' || !TAB_REF.test(p.tabRef)) throw fail('BAD_REQUEST', 'tabRef inválido');
    const tabId = tabs.tabOf(p.tabRef);
    const tab = tabId == null ? null : await chromeApi.tabs.get(tabId).catch(() => null);
    if (!tab) throw fail('TAB_NOT_FOUND', 'Aba não encontrada.');
    await consent.load();
    const ev = policy.evaluate(await policy.tabUrl(tabId).catch(() => null));
    const c = ev.allowed && ev.kind === 'AUTHORIZED' ? consent.get(ev.origin) : null;
    return { tabRef: p.tabRef, origin: ev.allowed ? ev.origin : null, access: ev.allowed ? ev.kind : ev.reason,
      consent: !ev.allowed ? 'NONE' : ev.kind === 'LAB' ? 'NOT_REQUIRED' : c ? c.scope : 'NONE', ...(c ? { level: c.level, actor: c.actor, via: c.via } : {}),
      visualCapture: visualProfile(ev, c), reason: denialReason(ev, c) };
  }

  /**
   * Concede o consentimento PDD para a origem ATUAL da aba. O Studio manda a origem que mostrou ao usuário: se a aba já
   * estiver em outra origem, nada é concedido (ORIGIN_CHANGED). Só vale para site já autorizado pelo Chrome.
   */
  async function consentGrant(p) {
    assertOnly(p, ['tabRef', 'origin', 'scope', 'actor', 'level']);
    if (typeof p.tabRef !== 'string' || !TAB_REF.test(p.tabRef)) throw fail('BAD_REQUEST', 'tabRef inválido');
    if (!canonicalOrigin(p.origin)) throw fail('BAD_REQUEST', 'origem inválida');
    if (!SCOPES.includes(p.scope)) throw fail('BAD_REQUEST', 'escopo inválido');
    if (!ACTORS.includes(p.actor)) throw fail('BAD_REQUEST', 'quem decide é inválido');
    if (p.level != null && !LEVELS.includes(p.level)) throw fail('BAD_REQUEST', 'nível inválido'); // ausente = MASKED (Studio anterior)
    if (!deps.consent) throw fail('INVALID_STATE', 'Consentimento indisponível.');
    const tabId = tabs.tabOf(p.tabRef);
    const tab = tabId == null ? null : await chromeApi.tabs.get(tabId).catch(() => null);
    if (!tab) throw fail('TAB_NOT_FOUND', 'Aba não encontrada.');
    const ev = policy.evaluate(await policy.tabUrl(tabId).catch(() => null));
    if (!ev.allowed || ev.kind !== 'AUTHORIZED') {
      throw fail('VISUAL_CAPTURE_NOT_ALLOWED', 'Conceda primeiro o acesso ao site no Chrome.', { reason: ev.allowed ? 'NOT_REQUIRED' : ev.reason });
    }
    if (ev.origin !== p.origin) throw fail('VISUAL_CAPTURE_NOT_ALLOWED', 'A aba mudou de origem.', { reason: 'ORIGIN_CHANGED' });
    const rec = await consent.grant(ev.origin, p.scope, p.actor, 'STUDIO', p.level || 'MASKED');
    return { origin: rec.origin, consent: rec.scope, level: rec.level, actor: rec.actor, via: rec.via, grantedAt: rec.grantedAt };
  }

  async function consentRevoke(p) {
    assertOnly(p, ['origin']);
    if (!canonicalOrigin(p.origin)) throw fail('BAD_REQUEST', 'origem inválida');
    if (!deps.consent) return { origin: p.origin, revoked: false };
    return consent.revoke(p.origin); // o ouvinte de mudanças desarma/cancela o que estiver em curso nessa origem
  }

  async function consentList(p) {
    assertOnly(p, []);
    const out = [];
    for (const r of await consent.list()) {
      out.push({ origin: r.origin, consent: r.scope, level: r.level || 'MASKED', actor: r.actor, via: r.via, grantedAt: r.grantedAt,
        chromeAccess: policy.evaluate(r.origin + '/').kind === 'AUTHORIZED' });
    }
    return { consents: out };
  }

  async function shortcut() {
    try {
      const all = await chromeApi.commands.getAll();
      const c = all.find((x) => x.name === SHORTCUT);
      return c && c.shortcut ? c.shortcut : '';
    } catch (e) {
      return '';
    }
  }

  /** Gesto do usuário no Chrome (chrome.commands). Sem armação válida para ESTA aba: nada é capturado. */
  async function onShortcut(command, tab) {
    if (command !== SHORTCUT) return { ignored: true };
    const a = armed;
    if (!a || running) return { ignored: true };
    if (!tab || tab.id !== a.tabId) {
      disarm('TAB_MISMATCH');
      return { failed: 'TAB_MISMATCH' };
    }
    if (now() > a.expiresAt) {
      disarm('ARM_EXPIRED');
      return { failed: 'ARM_EXPIRED' };
    }
    armed = null; // uso único: daqui em diante o gesto foi consumido
    running = true;
    inflight = { captureId: a.captureId, controller: a.controller, tabId: a.tabId, windowId: tab.windowId, switched: false, cancelled: false,
      revoked: null, origin: a.origin, profile: a.profile, policy: a.policy };
    try {
      const out = await shoot(a, tab);
      await sameTab(); // conferência final: cancelamento ou troca durante a sanitização → nada é publicado
      postEvent(a.controller, out);
      return { captured: out.status === 'CAPTURED' };
    } catch (e) {
      const code = e && FAILURES.has(e.code) ? e.code : 'INTERNAL';
      failed(a, code);
      return { failed: code };
    } finally {
      running = false;
      inflight = null;
      badge(a.tabId, '');
      toContent(a.tabId, 'cs.pdd.release', {}, 0).catch(() => {});
    }
  }

  /**
   * A aba armada continua sendo EXATAMENTE a aba ativa da janela do gesto, e nenhuma troca de aba aconteceu nessa
   * janela desde o gesto (uma ida e volta A→B→A também conta: não se sabe qual aba a captura pegou)?
   */
  async function sameTab() {
    const f = inflight;
    if (!f) throw codeError('INTERNAL');
    if (f.cancelled) throw codeError('CANCELLED');
    if (f.revoked) throw codeError(f.revoked);
    if (f.switched) throw codeError('ACTIVE_TAB_CHANGED');
    const t = await chromeApi.tabs.get(f.tabId).catch(() => null);
    if (!t) throw codeError('TAB_CLOSED');
    if (!t.active || t.windowId !== f.windowId) throw codeError('ACTIVE_TAB_CHANGED');
    const act = await chromeApi.tabs.query({ active: true, windowId: f.windowId }).catch(() => null);
    if (!Array.isArray(act) || act.length !== 1 || act[0].id !== f.tabId) throw codeError('ACTIVE_TAB_CHANGED');
    await sameAuthorization(f);
    if (f.cancelled) throw codeError('CANCELLED');
    if (f.revoked) throw codeError(f.revoked);
    if (f.switched) throw codeError('ACTIVE_TAB_CHANGED');
  }

  /** A aba continua na MESMA origem da armação, com o mesmo perfil (Chrome e, fora do laboratório, consentimento PDD). */
  async function sameAuthorization(f) {
    const ev = policy.evaluate(await policy.tabUrl(f.tabId).catch(() => null));
    if (!ev.allowed) throw codeError(ev.reason === 'PERMISSION_REQUIRED' ? 'PERMISSION_REVOKED' : 'VISUAL_CAPTURE_NOT_ALLOWED');
    if (ev.origin !== f.origin) throw codeError('ORIGIN_CHANGED');
    const c = ev.kind === 'AUTHORIZED' ? consent.get(ev.origin) : null;
    const profile = visualProfile(ev, c);
    if (profile !== f.profile) throw codeError(f.profile === 'AUTHORIZED_SITE' && ev.kind === 'AUTHORIZED' ? 'PDD_CONSENT_REVOKED' : 'VISUAL_CAPTURE_NOT_ALLOWED');
    if (!levelAllows(profile, f.policy, c)) throw codeError('PDD_CONSENT_REVOKED'); // o print integral foi rebaixado para mascarado
  }

  /**
   * v5: os frames só ficam como na página se TODO frame da aba respondeu que não mostra campo de senha. Frame sem o
   * content script (sem acesso do Chrome, about:blank) ou que não responde conta como "pode ter senha": os frames saem
   * cobertos (BLOCK). Saber qual iframe é qual frame do Chrome seria frágil; cobrir todos é o lado seguro.
   */
  async function framesWithoutSecrets(tabId) {
    const frames = await chromeApi.webNavigation.getAllFrames({ tabId }).catch(() => null);
    if (!Array.isArray(frames)) return false;
    for (const f of frames) {
      if (f.frameId === 0) continue;
      const r = await toContent(tabId, 'cs.pdd.secrets', {}, f.frameId).catch(() => null);
      if (!r || r.ok !== true || r.secrets !== 0) return false;
    }
    return true;
  }

  async function shoot(a, tab) {
    await sameTab(); // inclui origem, permissão do Chrome e consentimento PDD (sameAuthorization)
    if (!POLICIES_BY_PROFILE[a.profile] || !POLICIES_BY_PROFILE[a.profile].includes(a.policy)) throw codeError('VISUAL_CAPTURE_NOT_ALLOWED');
    // 1. alvos das etapas pelo motor (somente leitura, por identidade — nunca por localizador)
    const resolved = new Map();
    let cap = null;
    try {
      cap = await currentCapture(a.tabId);
    } catch (e) {
      cap = null;
    }
    for (const s of a.steps) {
      if (!s.targetId && !s.descriptor) {
        resolved.set(s.stepRef, { reason: 'NO_TARGET' });
        continue;
      }
      if (!cap) {
        resolved.set(s.stepRef, { reason: 'RESOLUTION_FAILED' });
        continue;
      }
      try {
        const r = await host('debug.resolve', { tabKey: tabKey(a.tabId), captureId: cap.captureId, targetId: s.targetId, actionType: s.actionType,
          descriptor: s.descriptor }, { timeoutMs: 10000 });
        if (r.status === 'RESOLVED' && r.resolvedNodeId) {
          resolved.set(s.stepRef, frameOfNode(r.resolvedNodeId) === 0 ? { nodeId: r.resolvedNodeId } : { reason: 'TARGET_IN_FRAME' });
        } else {
          resolved.set(s.stepRef, { reason: OMIT.has(r.status) ? r.status : 'RESOLUTION_FAILED' });
        }
      } catch (e) {
        resolved.set(s.stepRef, { reason: 'RESOLUTION_FAILED' });
      }
    }
    if (a.policy === VISUAL_POLICY_V3) return describeV3(a, resolved);
    // 2. plano visual + retângulos dos alvos, na MESMA ficha de layout
    const v2 = a.policy === VISUAL_POLICY_V2;
    const v5 = a.policy === VISUAL_POLICY_V5; // revisão da usuária: print integral (só senhas cobertas)
    const v4 = a.policy === VISUAL_POLICY_V4 || v5; // STUDIO-013: print real com o texto de dados mascarado — a v5 usa as mesmas máscaras
    const asked = v2 || v4 ? { policy: a.policy, ...(v5 ? { blockFrames: !(await framesWithoutSecrets(a.tabId)) } : {}) } : {};
    const plan = await toContent(a.tabId, 'cs.pdd.plan', asked, 0).catch(() => null);
    if (!plan || !plan.ok || ((v2 || v4) && plan.policy !== a.policy)) throw codeError('CONTENT_UNAVAILABLE');
    if (v4 && plan.incomplete) throw codeError('TOO_MANY_REGIONS'); // página não mapeada inteira: nenhum print parcial
    const nodeIds = [...resolved.values()].filter((x) => x.nodeId).map((x) => x.nodeId);
    const rects = nodeIds.length ? await toContent(a.tabId, 'cs.pdd.rects', { nodeIds }, 0).catch(() => null) : { ok: true, rects: [], token: plan.token };
    if (!rects || !rects.ok || rects.token !== plan.token) throw codeError('PAGE_CHANGED_DURING_CAPTURE');
    // 3. a imagem bruta: só aqui dentro, entre duas conferências da identidade da aba
    let raw = null;
    let decoded = null;
    try {
      await sameTab();
      try {
        raw = await chromeApi.tabs.captureVisibleTab(tab.windowId, { format: 'png' });
      } catch (e) {
        throw codeError('CAPTURE_DENIED');
      }
      await sameTab(); // falhou → o finally descarta a imagem bruta sem decodificar
      const after = await toContent(a.tabId, 'cs.pdd.token', {}, 0).catch(() => null);
      if (!after || after.token !== plan.token) throw codeError('PAGE_CHANGED_DURING_CAPTURE');
      decoded = await imaging.decode(raw);
      raw = null;
      const regions = v5 ? deviceMasksV5(plan, decoded.width, decoded.height) : v4 ? deviceMasksV4(plan, decoded.width, decoded.height) : v2 ? deviceRegionsV2(plan, decoded.width, decoded.height)
        : deviceRegions(plan, decoded.width, decoded.height);
      const pixels = v4 ? composeV4(decoded.pixels, decoded.width, decoded.height, regions)
        : v2 ? composeV2(decoded.pixels, decoded.width, decoded.height, regions) : compose(decoded.pixels, decoded.width, decoded.height, regions);
      decoded.release();
      decoded = { width: decoded.width, height: decoded.height, release() {} };
      const bad = v4 ? violationsV4(pixels, decoded.width, decoded.height, regions)
        : v2 ? violationsV2(pixels, decoded.width, decoded.height, regions) : violations(pixels, decoded.width, decoded.height, regions);
      if (bad !== 0) throw codeError('SANITIZATION_CHECK_FAILED');
      const png = await imaging.encode(pixels, decoded.width, decoded.height);
      if (v4) pixels.fill(0); // a composição v4 tem pixels reais fora das máscaras: zerada depois de codificar
      if (png.length > (v4 ? MAX_PNG_BYTES_V4 : MAX_PNG_BYTES)) throw codeError('SANITIZED_IMAGE_TOO_LARGE');
      const vw = plan.viewport.width;
      const vh = plan.viewport.height;
      const byNode = new Map((rects.rects || []).map((r) => [r.nodeId, r]));
      const annotations = a.steps.map((s) => {
        const res = resolved.get(s.stepRef) || { reason: 'RESOLUTION_FAILED' };
        const r = res.nodeId ? byNode.get(res.nodeId) : null;
        if (!r || r.reason) {
          return { stepRef: s.stepRef, status: 'OMITTED', x: 0, y: 0, width: 0, height: 0, reason: r && r.reason ? r.reason : res.reason || 'RESOLUTION_FAILED' };
        }
        return { stepRef: s.stepRef, status: 'PLACED', x: round6(r.x / vw), y: round6(r.y / vh), width: round6(r.width / vw), height: round6(r.height / vh),
          reason: '' };
      });
      const limitations = [];
      if (plan.rejected && Object.keys(plan.rejected).length) limitations.push('SOME_LABELS_HIDDEN');
      if (v5 ? Array.isArray(plan.limitations) && plan.limitations.includes('FRAMES_HIDDEN') : plan.hasFrames) limitations.push('FRAMES_HIDDEN');
      if (v4 && Array.isArray(plan.limitations) && plan.limitations.includes('TABLE_CONTENT_HIDDEN')) limitations.push('TABLE_CONTENT_HIDDEN');
      const outRegions = v4
        ? regions.map((r) => ({ kind: r.kind, x: r.x, y: r.y, width: r.x2 - r.x, height: r.y2 - r.y, ...(r.ink ? { ink: r.ink } : {}) }))
        : v2
        ? regions.map((r) => ({ kind: r.kind, x: r.x, y: r.y, width: r.x2 - r.x, height: r.y2 - r.y, ...(r.tone ? { tone: r.tone } : {}) }))
        : [...regions.carriers.map((c) => ({ kind: 'CARRIER', x: c.x, y: c.y, width: c.x2 - c.x, height: c.y2 - c.y })),
          ...regions.fields.map((f) => ({ kind: 'FIELD', x: f.x, y: f.y, width: f.x2 - f.x, height: f.y2 - f.y }))];
      return {
        captureId: a.captureId, status: 'CAPTURED', policy: v5 ? VISUAL_POLICY_V5 : v4 ? VISUAL_POLICY_V4 : v2 ? VISUAL_POLICY_V2 : VISUAL_POLICY, observedAt: new Date(now()).toISOString(),
        width: decoded.width, height: decoded.height, devicePixelRatio: plan.viewport.dpr,
        png: base64(png), sha256: await sha256(png),
        regions: outRegions,
        annotations, limitations,
      };
    } finally {
      raw = null;
      if (decoded) decoded.release();
      decoded = null;
    }
  }

  /**
   * STUDIO-013 — nekkora-visual/3: SEM captureVisibleTab. Plano sintético do content script, retângulos dos alvos na
   * mesma ficha, conferências da aba/origem/autorização antes e depois, e a descrição validada aqui (1ª barreira) antes de
   * sair. Nenhum pixel da página existe em nenhum momento deste caminho.
   */
  async function describeV3(a, resolved) {
    const plan = await toContent(a.tabId, 'cs.pdd.plan', { policy: VISUAL_POLICY_V3 }, 0).catch(() => null);
    if (!plan || !plan.ok || plan.policy !== VISUAL_POLICY_V3) throw codeError('CONTENT_UNAVAILABLE');
    const nodeIds = [...resolved.values()].filter((x) => x.nodeId).map((x) => x.nodeId);
    const rects = nodeIds.length ? await toContent(a.tabId, 'cs.pdd.rects', { nodeIds }, 0).catch(() => null) : { ok: true, rects: [], token: plan.token };
    if (!rects || !rects.ok || rects.token !== plan.token) throw codeError('PAGE_CHANGED_DURING_CAPTURE');
    await sameTab();
    const description = validateV3({ viewport: { width: plan.viewport.width, height: plan.viewport.height, dpr: plan.viewport.dpr }, regions: plan.regions },
      textPolicy());
    const vw = description.viewport.width;
    const vh = description.viewport.height;
    const byNode = new Map((rects.rects || []).map((r) => [r.nodeId, r]));
    const annotations = a.steps.map((s) => {
      const res = resolved.get(s.stepRef) || { reason: 'RESOLUTION_FAILED' };
      const r = res.nodeId ? byNode.get(res.nodeId) : null;
      if (!r || r.reason) {
        return { stepRef: s.stepRef, status: 'OMITTED', x: 0, y: 0, width: 0, height: 0, reason: r && r.reason ? r.reason : res.reason || 'RESOLUTION_FAILED' };
      }
      return { stepRef: s.stepRef, status: 'PLACED', x: round6(r.x / vw), y: round6(r.y / vh), width: round6(r.width / vw), height: round6(r.height / vh),
        reason: '' };
    });
    const limitations = new Set((Array.isArray(plan.limitations) ? plan.limitations : []).filter((x) => V3_LIMITATIONS.has(x)));
    if (plan.rejected && Object.keys(plan.rejected).length) limitations.add('SOME_LABELS_HIDDEN');
    if (plan.hasFrames) limitations.add('FRAMES_HIDDEN');
    return {
      captureId: a.captureId, status: 'CAPTURED', policy: VISUAL_POLICY_V3, observedAt: new Date(now()).toISOString(),
      viewport: description.viewport, regions: description.regions, annotations, limitations: [...limitations],
    };
  }

  /** Navegação/aba fechada/permissão: a armação não vale mais (e a captura em curso termina sem imagem). */
  function onPageChanged(tabId) {
    if (armed && armed.tabId === tabId) disarm('PAGE_CHANGED');
    if (inflight && inflight.tabId === tabId && !inflight.revoked) inflight.revoked = 'PAGE_CHANGED';
  }

  /**
   * STUDIO-013: a permissão do Chrome ou o consentimento PDD mudou (revogação). Reavalia a aba armada e a captura em
   * curso: se o perfil deixou de valer, a armação cai e a captura em curso termina sem nada publicado.
   */
  async function onAccessChanged() {
    for (const target of [armed, inflight]) {
      if (!target) continue;
      const ev = policy.evaluate(await policy.tabUrl(target.tabId).catch(() => null));
      const c = ev.origin === target.origin && ev.kind === 'AUTHORIZED' ? consent.get(ev.origin) : null;
      const profile = ev.origin === target.origin ? visualProfile(ev, c) : null;
      if (profile === target.profile && levelAllows(profile, target.policy, c)) continue;
      const code = !ev.allowed ? 'PERMISSION_REVOKED' : ev.origin !== target.origin ? 'ORIGIN_CHANGED' : 'PDD_CONSENT_REVOKED';
      if (target === armed) disarm(code);
      else if (!target.revoked) target.revoked = code;
    }
  }

  function onTabRemoved(tabId) {
    if (armed && armed.tabId === tabId) disarm('TAB_CLOSED');
    if (inflight && inflight.tabId === tabId) inflight.switched = true;
  }

  /** chrome.tabs.onActivated: qualquer troca de aba na janela da captura em andamento a invalida. */
  function onTabActivated(info) {
    if (inflight && info && (info.windowId === inflight.windowId || info.tabId === inflight.tabId)) inflight.switched = true;
  }

  /** chrome.tabs.onDetached/onAttached: a aba armada mudou de janela. */
  function onTabMoved(tabId) {
    if (inflight && inflight.tabId === tabId) inflight.switched = true;
  }

  function checkLease() {
    if (armed && now() > armed.expiresAt) disarm('ARM_EXPIRED');
  }

  function state() {
    return { armed: !!armed, running, captureId: armed ? armed.captureId : inflight ? inflight.captureId : null,
      profile: armed ? armed.profile : inflight ? inflight.profile : null };
  }

  return { COMMANDS, onShortcut, onPageChanged, onTabRemoved, onTabActivated, onTabMoved, onAccessChanged, checkLease, state };
}

const V3_LIMITATIONS = new Set(['PAGE_TOO_LARGE', 'TEXT_SIMPLIFIED', 'TABLE_CONTENT_HIDDEN', 'SOME_REGIONS_OMITTED']);

/** Origem exata aceitável no pedido do Studio: a de consentimento ou a do laboratório local. */
function canonicalOriginOrLab(v) {
  if (canonicalOrigin(v)) return true;
  try {
    const u = new URL(v);
    return (u.protocol === 'http:' || u.protocol === 'https:') && (u.hostname === '127.0.0.1' || u.hostname === 'localhost') && u.origin === v;
  } catch (e) {
    return false;
  }
}

function round6(v) {
  return Math.round(Math.min(1, Math.max(0, v)) * 1e6) / 1e6;
}

function base64(bytes) {
  let s = '';
  for (let i = 0; i < bytes.length; i += 0x8000) {
    s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
  }
  return btoa(s);
}

async function sha256(bytes) {
  const d = new Uint8Array(await crypto.subtle.digest('SHA-256', bytes));
  return 'sha256:' + Array.from(d, (b) => b.toString(16).padStart(2, '0')).join('');
}

/** PNG ⇄ pixels no service worker (OffscreenCanvas). Os canvases são zerados e descartados depois do uso. */
function browserImaging() {
  return {
    async decode(dataUrl) {
      const blob = await (await fetch(dataUrl)).blob();
      const bmp = await createImageBitmap(blob);
      const c = new OffscreenCanvas(bmp.width, bmp.height);
      const ctx = c.getContext('2d', { willReadFrequently: true });
      ctx.drawImage(bmp, 0, 0);
      const img = ctx.getImageData(0, 0, bmp.width, bmp.height);
      const out = { width: bmp.width, height: bmp.height, pixels: img.data,
        release() {
          ctx.clearRect(0, 0, c.width, c.height);
          img.data.fill(0);
          c.width = 1;
          c.height = 1;
        } };
      bmp.close();
      return out;
    },
    async encode(pixels, width, height) {
      const c = new OffscreenCanvas(width, height);
      const ctx = c.getContext('2d');
      ctx.putImageData(new ImageData(pixels, width, height), 0, 0);
      const blob = await c.convertToBlob({ type: 'image/png' });
      return new Uint8Array(await blob.arrayBuffer());
    },
  };
}
