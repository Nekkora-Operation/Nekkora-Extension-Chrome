/*
 * Cliente do Native Messaging Host Java (protocolo nekkora-nm/1).
 *
 * - correlação requisição/resposta por id;
 * - fragmentação de mensagens grandes na ida e remontagem na volta;
 * - timeout por requisição;
 * - reconexão preguiçosa com espera progressiva após desconexão;
 * - falhas propagadas com código estável (HOST_UNAVAILABLE, HOST_DISCONNECTED, TIMEOUT, códigos do host).
 */
export const PROTOCOL = 'nekkora-nm/1';
export const HOST_NAME = 'com.nekkora.extension_chrome';

/** Divide sem separar pares substitutos UTF-16 (caracteres fora do BMP). */
export function splitSafely(text, size) {
  const out = [];
  let pos = 0;
  while (pos < text.length) {
    let end = Math.min(text.length, pos + size);
    if (end < text.length) {
      const c = text.charCodeAt(end - 1);
      if (c >= 0xd800 && c <= 0xdbff) {
        end = end - 1 > pos ? end - 1 : end + 1; // mantém o par junto e sempre avança
      }
    }
    out.push(text.slice(pos, end));
    pos = end;
  }
  return out;
}

export class HostError extends Error {
  constructor(code, message) {
    super(code + ': ' + message);
    this.code = code;
  }
}

export class NativeClient {
  constructor(options = {}) {
    this.hostName = options.hostName || HOST_NAME;
    this.chunkThreshold = options.chunkThreshold || 4 * 1024 * 1024;
    this.chunkChars = options.chunkChars || 1024 * 1024;
    this.defaultTimeoutMs = options.defaultTimeoutMs || 60000;
    this.connectImpl = options.connect || ((name) => chrome.runtime.connectNative(name));
    this.port = null;
    this.pending = new Map();
    this.incoming = new Map();
    this.seq = 0;
    this.transferSeq = 0;
    this.stats = { connects: 0, disconnects: 0, requests: 0, responses: 0, chunksIn: 0, chunksOut: 0, lastError: null, lastDisconnectAt: 0 };
  }

  status() {
    return { connected: !!this.port, pending: this.pending.size, ...this.stats };
  }

  async ensureConnected() {
    if (this.port) {
      return this.port;
    }
    // Espera progressiva: evita laço de reconexão quando o host não está instalado.
    const since = Date.now() - this.stats.lastDisconnectAt;
    const backoff = Math.min(2000, 200 * Math.max(0, this.stats.disconnects));
    if (this.stats.lastDisconnectAt && since < backoff) {
      await new Promise((r) => setTimeout(r, backoff - since));
    }
    let port;
    try {
      port = this.connectImpl(this.hostName);
    } catch (e) {
      throw new HostError('HOST_UNAVAILABLE', String(e && e.message || e));
    }
    this.port = port;
    this.stats.connects++;
    port.onMessage.addListener((m) => this.onMessage(m));
    port.onDisconnect.addListener(() => this.onDisconnect(port));
    return port;
  }

  onDisconnect(port) {
    const err = (chrome.runtime && chrome.runtime.lastError && chrome.runtime.lastError.message) || 'host encerrou a conexão';
    if (this.port === port) {
      this.port = null;
    }
    this.stats.disconnects++;
    this.stats.lastDisconnectAt = Date.now();
    this.stats.lastError = err;
    const code = /not found|not exist|forbidden|Specified native messaging host/i.test(err) ? 'HOST_UNAVAILABLE' : 'HOST_DISCONNECTED';
    for (const [, p] of this.pending) {
      clearTimeout(p.timer);
      p.reject(new HostError(code, err));
    }
    this.pending.clear();
    this.incoming.clear();
  }

  onMessage(msg) {
    if (msg && msg.type === 'chunk') {
      this.stats.chunksIn++;
      let t = this.incoming.get(msg.transferId);
      if (!t) {
        t = { parts: new Array(msg.count), received: 0 };
        this.incoming.set(msg.transferId, t);
      }
      if (t.parts[msg.index] === undefined) {
        t.parts[msg.index] = msg.data;
        t.received++;
      }
      if (t.received < msg.count) {
        return;
      }
      this.incoming.delete(msg.transferId);
      try {
        msg = JSON.parse(t.parts.join(''));
      } catch (e) {
        this.stats.lastError = 'remontagem inválida: ' + e.message;
        return;
      }
    }
    if (!msg || msg.type !== 'response') {
      return;
    }
    const p = this.pending.get(msg.id);
    if (!p) {
      return; // resposta tardia de requisição expirada
    }
    this.pending.delete(msg.id);
    clearTimeout(p.timer);
    this.stats.responses++;
    if (msg.ok) {
      p.resolve({ payload: msg.payload, timing: msg.timing, chunked: p.chunked });
    } else {
      const e = msg.error || {};
      p.reject(new HostError(e.code || 'HOST_ERROR', e.message || 'erro do host'));
    }
  }

  /**
   * Envia uma requisição. options.forceChunkChars força a fragmentação de saída (testes de transporte).
   * @returns {Promise<{payload: any, timing: object}>}
   */
  async request(type, payload, options = {}) {
    const port = await this.ensureConnected();
    const id = 'x' + (++this.seq) + '-' + Date.now().toString(36);
    const envelope = { protocol: PROTOCOL, id, type, payload: payload || {} };
    const json = JSON.stringify(envelope);
    const chunkChars = options.forceChunkChars || (json.length > this.chunkThreshold ? this.chunkChars : 0);
    this.stats.requests++;
    return new Promise((resolve, reject) => {
      const timeoutMs = options.timeoutMs || this.defaultTimeoutMs;
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new HostError('TIMEOUT', type + ' sem resposta em ' + timeoutMs + ' ms'));
      }, timeoutMs);
      this.pending.set(id, { resolve, reject, timer, chunked: chunkChars > 0 });
      try {
        if (chunkChars > 0) {
          const transferId = 'e-' + (++this.transferSeq);
          const pieces = splitSafely(json, chunkChars);
          pieces.forEach((data, index) => {
            port.postMessage({ protocol: PROTOCOL, type: 'chunk', transferId, index, count: pieces.length, data });
            this.stats.chunksOut++;
          });
        } else {
          port.postMessage(envelope);
        }
      } catch (e) {
        clearTimeout(timer);
        this.pending.delete(id);
        reject(new HostError('HOST_DISCONNECTED', String(e && e.message || e)));
      }
    });
  }

  disconnect() {
    if (this.port) {
      const p = this.port;
      this.port = null;
      p.disconnect();
      this.onDisconnect(p);
    }
  }
}
