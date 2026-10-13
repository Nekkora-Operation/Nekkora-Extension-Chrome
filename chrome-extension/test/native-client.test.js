import { test } from 'node:test';
import assert from 'node:assert/strict';
import { NativeClient, splitSafely } from '../src/background/native-client.js';

globalThis.chrome = { runtime: { lastError: null } };

/** Porta falsa que simula o host: responde, fragmenta ou desconecta conforme o tipo. */
function fakePort(behavior) {
  const listeners = { message: [], disconnect: [] };
  const port = {
    sent: [],
    onMessage: { addListener: (f) => listeners.message.push(f) },
    onDisconnect: { addListener: (f) => listeners.disconnect.push(f) },
    postMessage(m) {
      port.sent.push(m);
      queueMicrotask(() => behavior(m, (out) => listeners.message.forEach((f) => f(out)), () => listeners.disconnect.forEach((f) => f())));
    },
    disconnect() {},
  };
  return port;
}

test('splitSafely never breaks surrogate pairs', () => {
  const text = 'a😀'.repeat(1000);
  for (const size of [1, 2, 3, 7, 100]) {
    const parts = splitSafely(text, size);
    assert.equal(parts.join(''), text);
    for (const p of parts) {
      const last = p.charCodeAt(p.length - 1);
      assert.ok(!(last >= 0xd800 && last <= 0xdbff), 'fragmento não termina em high surrogate');
    }
  }
});

test('request/response correlation, chunked response reassembly and errors', async () => {
  const client = new NativeClient({
    connect: () => fakePort((m, reply) => {
      if (m.type === 'host.ping') {
        reply({ protocol: 'nekkora-nm/1', id: m.id, type: 'response', ok: true, payload: { pong: true } });
      } else if (m.type === 'big') {
        const full = JSON.stringify({ protocol: 'nekkora-nm/1', id: m.id, type: 'response', ok: true, payload: { data: 'ç'.repeat(5000) } });
        const parts = splitSafely(full, 1000);
        parts.reverse().forEach((data, i) => reply({ type: 'chunk', transferId: 't1', index: parts.length - 1 - i, count: parts.length, data }));
      } else {
        reply({ protocol: 'nekkora-nm/1', id: m.id, type: 'response', ok: false, error: { code: 'UNKNOWN_COMMAND', message: m.type } });
      }
    }),
  });
  assert.deepEqual((await client.request('host.ping', {})).payload, { pong: true });
  assert.equal((await client.request('big', {})).payload.data.length, 5000);
  await assert.rejects(client.request('nope', {}), (e) => e.code === 'UNKNOWN_COMMAND');
});

test('outbound chunking is applied above threshold', async () => {
  let port;
  const client = new NativeClient({
    chunkThreshold: 100,
    chunkChars: 50,
    connect: () => (port = fakePort((m, reply) => {
      if (m.type === 'chunk' && m.index === m.count - 1) {
        const all = port.sent.filter((x) => x.type === 'chunk').map((x) => x.data).join('');
        const req = JSON.parse(all);
        reply({ protocol: 'nekkora-nm/1', id: req.id, type: 'response', ok: true, payload: { len: req.payload.data.length } });
      }
    })),
  });
  const r = await client.request('host.echo', { data: 'x'.repeat(500) });
  assert.equal(r.payload.len, 500);
  assert.ok(port.sent.length > 5);
});

test('disconnect rejects pending requests and reconnects lazily', async () => {
  let connects = 0;
  const client = new NativeClient({
    connect: () => {
      connects++;
      return fakePort((m, reply, disconnect) => {
        if (connects === 1) {
          chrome.runtime.lastError = { message: 'Native host has exited.' };
          disconnect();
          chrome.runtime.lastError = null;
        } else {
          reply({ protocol: 'nekkora-nm/1', id: m.id, type: 'response', ok: true, payload: { ok: 1 } });
        }
      });
    },
  });
  await assert.rejects(client.request('host.ping', {}), (e) => e.code === 'HOST_DISCONNECTED');
  assert.deepEqual((await client.request('host.ping', {})).payload, { ok: 1 });
  assert.equal(connects, 2);
});

test('missing host is reported as HOST_UNAVAILABLE', async () => {
  const client = new NativeClient({
    connect: () => fakePort((m, reply, disconnect) => {
      chrome.runtime.lastError = { message: 'Specified native messaging host not found.' };
      disconnect();
      chrome.runtime.lastError = null;
    }),
  });
  await assert.rejects(client.request('host.ping', {}), (e) => e.code === 'HOST_UNAVAILABLE');
});

test('timeouts are enforced', async () => {
  const client = new NativeClient({ defaultTimeoutMs: 50, connect: () => fakePort(() => {}) });
  await assert.rejects(client.request('host.ping', {}), (e) => e.code === 'TIMEOUT');
});
