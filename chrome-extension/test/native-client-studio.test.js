// NativeClient (STUDIO-002): mensagens iniciadas pelo host têm roteamento próprio e nunca viram respostas.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { NativeClient } from '../src/background/native-client.js';

globalThis.chrome = { runtime: { lastError: null } };

function fakePort() {
  const listeners = { message: [], disconnect: [] };
  const port = {
    sent: [],
    onMessage: { addListener: (f) => listeners.message.push(f) },
    onDisconnect: { addListener: (f) => listeners.disconnect.push(f) },
    postMessage(m) {
      port.sent.push(m);
    },
    disconnect() {},
    deliver: (m) => listeners.message.forEach((f) => f(m)),
    drop: () => listeners.disconnect.forEach((f) => f()),
  };
  return port;
}

test('comando do host é entregue ao manipulador do tipo, nunca à requisição pendente com o mesmo id', async () => {
  const port = fakePort();
  const client = new NativeClient({ connect: () => port });
  const got = [];
  client.onHostMessage('studio.command', (m) => got.push(m));
  const pending = client.request('host.ping', {}, { timeoutMs: 2000 });
  await new Promise((r) => setTimeout(r, 0));
  const id = port.sent[0].id;
  // Mensagem espontânea do host com o MESMO id: não pode resolver a requisição.
  port.deliver({ protocol: 'nekkora-nm/1', type: 'studio.command', id, command: 'studio.recording.status', payload: {}, controller: { instanceId: 'a' } });
  assert.equal(got.length, 1);
  assert.equal(client.status().pending, 1, 'a requisição continua aguardando a resposta verdadeira');
  port.deliver({ protocol: 'nekkora-nm/1', id, type: 'response', ok: true, payload: { pong: 1 } });
  assert.deepEqual((await pending).payload, { pong: 1 });
});

test('tipos sem manipulador e protocolo errado são descartados (nunca executados)', async () => {
  const port = fakePort();
  const client = new NativeClient({ connect: () => port });
  let called = 0;
  client.onHostMessage('studio.command', () => called++);
  await client.ensureConnected();
  port.deliver({ protocol: 'nekkora-nm/1', type: 'eval', code: 'alert(1)' });
  port.deliver({ protocol: 'nekkora-nm/1', type: 'studio.unknown' });
  port.deliver({ protocol: 'outro/9', type: 'studio.command', id: 'sc-1' });
  assert.equal(called, 0);
  assert.equal(client.status().ignored, 3);
  assert.throws(() => client.onHostMessage('response', () => {}), /reservado/);
  assert.throws(() => client.onHostMessage('chunk', () => {}), /reservado/);
});

test('mensagem do host fragmentada é remontada e roteada pelo tipo', async () => {
  const port = fakePort();
  const client = new NativeClient({ connect: () => port });
  const got = [];
  client.onHostMessage('studio.link', (m) => got.push(m));
  await client.ensureConnected();
  const json = JSON.stringify({ protocol: 'nekkora-nm/1', type: 'studio.link', state: 'CONNECTED', detail: null, controller: { instanceId: 'x'.repeat(50) } });
  const half = Math.floor(json.length / 2);
  port.deliver({ protocol: 'nekkora-nm/1', type: 'chunk', transferId: 'h-1', index: 0, count: 2, data: json.slice(0, half) });
  port.deliver({ protocol: 'nekkora-nm/1', type: 'chunk', transferId: 'h-1', index: 1, count: 2, data: json.slice(half) });
  assert.equal(got.length, 1);
  assert.equal(got[0].state, 'CONNECTED');
});

test('post envia mensagens de controle sem correlação; ouvintes de conexão a cada nova porta', async () => {
  const ports = [];
  const client = new NativeClient({ connect: () => { const p = fakePort(); ports.push(p); return p; } });
  let connects = 0;
  client.onConnected(() => connects++);
  await client.post({ protocol: 'nekkora-nm/1', type: 'studio.reply', id: 'sc-1', ok: true, payload: {}, error: null });
  assert.equal(ports[0].sent[0].type, 'studio.reply');
  assert.equal(client.status().pending, 0, 'post não cria requisição pendente');
  assert.equal(connects, 1);
  ports[0].drop();
  await client.post({ protocol: 'nekkora-nm/1', type: 'studio.event', event: 'studio.recording.paused', payload: {}, controller: { instanceId: 'a' } });
  assert.equal(connects, 2, 'nova porta = novo processo do host: ouvintes avisados');
  await assert.rejects(client.post({ big: 'x'.repeat(300 * 1024) }), /MESSAGE_TOO_LARGE/);
});

test('STUDIO-013: só o print da nekkora-visual/4 passa do teto de controle (até 12 MiB); qualquer outra mensagem grande é recusada', async () => {
  const port = fakePort();
  const client = new NativeClient({ connect: () => port });
  const big = 'A'.repeat(600 * 1024);
  const ev = (policy, event = 'studio.pdd.captured') => ({ protocol: 'nekkora-nm/1', type: 'studio.event', event, payload: { policy, png: big },
    controller: { instanceId: 'a' } });
  await client.post(ev('nekkora-visual/4'));
  await assert.rejects(client.post(ev('nekkora-visual/2')), /MESSAGE_TOO_LARGE/);
  await assert.rejects(client.post(ev('nekkora-visual/4', 'studio.recording.step')), /MESSAGE_TOO_LARGE/);
  await assert.rejects(client.post({ ...ev('nekkora-visual/4'), type: 'studio.reply' }), /MESSAGE_TOO_LARGE/);
  await assert.rejects(client.post(ev('nekkora-visual/4', 'studio.pdd.captured') && { ...ev('nekkora-visual/4'), payload: { policy: 'nekkora-visual/4', png: 'A'.repeat(13 * 1024 * 1024) } }),
    /MESSAGE_TOO_LARGE/);
});
