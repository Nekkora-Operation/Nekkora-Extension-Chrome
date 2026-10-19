/*
 * NEKKORA-STUDIO-013 — AUTORIZAÇÃO PRÓPRIA da captura visual do PDD, por ORIGEM EXATA (esquema + host + porta).
 *
 * É independente da permissão de site do Chrome (que autoriza INSPEÇÃO e GRAVAÇÃO): mesmo com "https://*\/*" concedido,
 * nenhuma captura de PDD acontece numa origem sem este consentimento. Regras (contrato nekkora-pdd-consent/1):
 *   - só a interface CONFIÁVEL concede ou revoga: o painel da extensão (página da extensão) e o Studio (pelo Bridge
 *     autenticado). Content scripts não têm comando para isso (o roteador do service worker não os encaminha);
 *   - o registro guarda SÓ a origem, o escopo, quem decidiu (DEVELOPER ou TEST_AGENT), por onde (STUDIO ou EXTENSION) e
 *     quando — nunca caminho, query, título, credencial ou identificador de sessão;
 *   - escopo SESSION (padrão): vale até o navegador fechar (chrome.storage.session, que content scripts não leem);
 *     escopo REMEMBERED (escolha explícita): IndexedDB da origem da extensão (fora do alcance de content scripts e da
 *     página). Revogar remove os dois;
 *   - o laboratório local (127.0.0.1/localhost) não precisa de consentimento e não aceita registro.
 *
 * nekkora-pdd-consent/2 (revisão da usuária, 19/10/2026) acrescenta o NÍVEL da captura no site:
 *   - MASKED (padrão): print com o texto de dados mascarado (nekkora-visual/4) ou a descrição sintética (/3);
 *   - FULL (escolha explícita): também o print integral (nekkora-visual/5), com os dados da tela; só senhas cobertas.
 * Registro da /1 (sem nível) vale como MASKED. Conceder de novo com outro nível troca o nível (FULL → MASKED rebaixa).
 */
export const CONSENT_VERSION = 'nekkora-pdd-consent/2';
export const LEVELS = Object.freeze(['MASKED', 'FULL']);
export const SCOPES = Object.freeze(['SESSION', 'REMEMBERED']);
export const ACTORS = Object.freeze(['DEVELOPER', 'TEST_AGENT']);
export const VIA = Object.freeze(['STUDIO', 'EXTENSION']);
export const MAX_CONSENTS = 200;
const SESSION_KEY = 'nk.pdd.consent';
const LAB_HOSTS = new Set(['127.0.0.1', 'localhost']);

/** Origem exata http(s) aceitável para consentimento (forma canônica do URL), ou null. */
export function canonicalOrigin(value) {
  if (typeof value !== 'string' || value.length > 300) return null;
  let u;
  try {
    u = new URL(value);
  } catch (e) {
    return null;
  }
  if (u.protocol !== 'http:' && u.protocol !== 'https:') return null;
  if (u.username || u.password) return null;
  if (LAB_HOSTS.has(u.hostname)) return null;
  // a forma enviada precisa SER a origem: nada de caminho, query ou fragmento escondido
  return u.origin === value ? u.origin : null;
}

/** Origem (exata) de uma URL completa — usada para derivar a origem da aba; nunca devolve o caminho. */
export function originOf(url) {
  try {
    const u = new URL(url);
    return u.protocol === 'http:' || u.protocol === 'https:' ? u.origin : null;
  } catch (e) {
    return null;
  }
}

/**
 * @param deps.chrome     APIs do Chrome (storage.session)
 * @param deps.persistent armazenamento dos sites lembrados ({getAll, put, delete}); padrão: IndexedDB da extensão
 * @param deps.now        relógio (testes)
 */
export function createPddConsent(deps = {}) {
  const chromeApi = deps.chrome || globalThis.chrome;
  const persistent = deps.persistent || indexedDbStore();
  const now = () => (deps.now ? deps.now() : Date.now());
  const session = new Map(); // origin -> registro
  const remembered = new Map();
  const listeners = [];
  let loading = null;

  function load() {
    if (!loading) {
      loading = (async () => {
        try {
          const o = await chromeApi.storage.session.get(SESSION_KEY);
          for (const r of Object.values((o && o[SESSION_KEY]) || {})) {
            const v = valid(r, 'SESSION');
            if (v) session.set(v.origin, v);
          }
        } catch (e) {
          // sem storage.session: começa vazio (fail-closed)
        }
        try {
          for (const r of await persistent.getAll()) {
            const v = valid(r, 'REMEMBERED');
            if (v) remembered.set(v.origin, v);
          }
        } catch (e) {
          // armazenamento indisponível: nenhum site lembrado (fail-closed)
        }
      })();
    }
    return loading;
  }

  function valid(r, scope) {
    if (!r || typeof r !== 'object') return null;
    const origin = canonicalOrigin(r.origin);
    if (!origin || r.scope !== scope || !ACTORS.includes(r.actor) || !VIA.includes(r.via) || typeof r.grantedAt !== 'string') return null;
    if (r.level != null && !LEVELS.includes(r.level)) return null; // nível desconhecido: registro descartado (fail-closed)
    return { origin, scope, level: r.level || 'MASKED', actor: r.actor, via: r.via, grantedAt: r.grantedAt.slice(0, 40) };
  }

  function saveSession() {
    const o = {};
    for (const [k, v] of session) o[k] = v;
    return chromeApi.storage.session.set({ [SESSION_KEY]: o }).catch(() => {});
  }

  function emit(change) {
    for (const fn of listeners) {
      try {
        fn(change);
      } catch (e) {
        // ouvinte com falha não impede os demais
      }
    }
  }

  /** Consentimento vigente para a origem (o lembrado vence o de sessão) ou null. Síncrono, sobre o estado carregado. */
  function get(origin) {
    return remembered.get(origin) || session.get(origin) || null;
  }

  async function grant(originValue, scope, actor, via, level = 'MASKED') {
    await load();
    const origin = canonicalOrigin(originValue);
    if (!origin) throw codeError('BAD_REQUEST', 'origem inválida para consentimento');
    if (!SCOPES.includes(scope)) throw codeError('BAD_REQUEST', 'escopo inválido');
    if (!ACTORS.includes(actor)) throw codeError('BAD_REQUEST', 'quem decide é inválido');
    if (!VIA.includes(via)) throw codeError('BAD_REQUEST', 'canal inválido');
    if (!LEVELS.includes(level)) throw codeError('BAD_REQUEST', 'nível inválido');
    if (!get(origin) && session.size + remembered.size >= MAX_CONSENTS) throw codeError('INVALID_STATE', 'limite de sites autorizados');
    const rec = { origin, scope, level, actor, via, grantedAt: new Date(now()).toISOString() };
    if (scope === 'REMEMBERED') {
      await persistent.put(rec); // falha de gravação = sem consentimento lembrado (o erro sobe)
      remembered.set(origin, rec);
      session.delete(origin);
    } else {
      // escolher "só nesta sessão" depois de "lembrar" rebaixa: o lembrado é apagado
      if (remembered.has(origin)) {
        await persistent.delete(origin).catch(() => {});
        remembered.delete(origin);
      }
      session.set(origin, rec);
    }
    await saveSession();
    emit({ type: 'granted', origin, scope, level });
    return { ...rec };
  }

  async function revoke(originValue) {
    await load();
    const origin = canonicalOrigin(originValue);
    if (!origin) throw codeError('BAD_REQUEST', 'origem inválida para consentimento');
    const had = session.delete(origin) | remembered.delete(origin);
    await persistent.delete(origin).catch(() => {});
    await saveSession();
    if (had) emit({ type: 'revoked', origin });
    return { origin, revoked: !!had };
  }

  /** Remove os consentimentos cujas origens o Chrome deixou de permitir (revogação de acesso respeitada). */
  async function prune(stillAllowed) {
    await load();
    const gone = [];
    for (const origin of new Set([...session.keys(), ...remembered.keys()])) {
      if (!stillAllowed(origin)) gone.push(origin);
    }
    for (const origin of gone) {
      session.delete(origin);
      remembered.delete(origin);
      await persistent.delete(origin).catch(() => {});
    }
    if (gone.length) {
      await saveSession();
      for (const origin of gone) emit({ type: 'revoked', origin, reason: 'PERMISSION_REVOKED' });
    }
    return gone;
  }

  async function list() {
    await load();
    const all = new Map(session);
    for (const [k, v] of remembered) all.set(k, v);
    return [...all.values()].sort((a, b) => a.origin.localeCompare(b.origin)).map((r) => ({ ...r }));
  }

  function onChange(fn) {
    listeners.push(fn);
  }

  return { load, get, grant, revoke, prune, list, onChange };
}

function codeError(code, message) {
  const e = new Error(code + ': ' + message);
  e.code = code;
  return e;
}

/** Sites lembrados no IndexedDB da origem da extensão (service worker). */
function indexedDbStore() {
  const DB = 'nekkora-pdd-consent';
  const STORE = 'origins';
  let db = null;
  function open() {
    if (db) return db;
    db = new Promise((resolve, reject) => {
      const rq = globalThis.indexedDB.open(DB, 1);
      rq.onupgradeneeded = () => rq.result.createObjectStore(STORE, { keyPath: 'origin' });
      rq.onsuccess = () => resolve(rq.result);
      rq.onerror = () => reject(rq.error);
    });
    db.catch(() => {
      db = null;
    });
    return db;
  }
  async function tx(mode, fn) {
    const d = await open();
    return new Promise((resolve, reject) => {
      const t = d.transaction(STORE, mode);
      const out = fn(t.objectStore(STORE));
      t.oncomplete = () => resolve(out && 'result' in out ? out.result : undefined);
      t.onerror = () => reject(t.error);
      t.onabort = () => reject(t.error);
    });
  }
  return {
    getAll: () => tx('readonly', (s) => s.getAll()).then((r) => r || []),
    put: (rec) => tx('readwrite', (s) => s.put(rec)),
    delete: (origin) => tx('readwrite', (s) => s.delete(origin)),
  };
}
