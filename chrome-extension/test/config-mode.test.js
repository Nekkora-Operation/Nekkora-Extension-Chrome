import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync } from 'node:fs';
import { fileURLToPath, pathToFileURL } from 'node:url';
import path from 'node:path';

/** chrome.storage.local mínimo (o módulo de configuração só usa get/set). */
function mockChrome() {
  const store = {};
  globalThis.chrome = {
    storage: {
      local: {
        async get(k) {
          return { [k]: store[k] };
        },
        async set(o) {
          Object.assign(store, o);
        },
      },
    },
    runtime: {},
  };
  return store;
}

const here = path.dirname(fileURLToPath(import.meta.url));

test('DEV build: PRD can be simulated by extension pages and DEV-only commands are then refused', async () => {
  mockChrome();
  const config = await import(pathToFileURL(path.join(here, '../src/background/config.js')).href + '?dev');
  await config.loadConfig();
  assert.equal(config.mode(), 'DEV');
  config.assertCommandAllowed('page.capture');
  const r = await config.setMode('PRD');
  assert.deepEqual(r, { mode: 'PRD', buildMode: 'DEV', simulated: true });
  // CHROME-SECURITY-001: diagnósticos que devolvem dados crus da página (URLs do navegador, hooks da página) também
  for (const cmd of ['page.capture', 'session.forceFull', 'pagemap.export', 'page.compare', 'target.resolve', 'session.debug', 'frames.list',
    'testHooks.read', 'studio.tabRef']) {
    assert.throws(() => config.assertCommandAllowed(cmd), (e) => e.code === 'MODE_RESTRICTED', cmd);
  }
  config.assertCommandAllowed('inspection.toggle');
  config.assertCommandAllowed('recording.event');
  await assert.rejects(config.setMode('XYZ'), (e) => e.code === 'INVALID_MODE');
  await config.setMode('DEV');
  assert.equal(config.mode(), 'DEV');
});

const prd = path.join(here, '../../dist/prd');

test('PRD build (dist/prd): mode locked, harness absent', { skip: !existsSync(prd) && 'execute scripts/build.ps1 -Mode PRD' }, async () => {
  mockChrome();
  assert.equal(existsSync(path.join(prd, 'src/ui/harness.html')), false, 'harness não faz parte da construção PRD');
  assert.equal(existsSync(path.join(prd, 'src/ui/panel.html')), true);
  const config = await import(pathToFileURL(path.join(prd, 'src/background/config.js')).href + '?prd');
  await config.loadConfig();
  assert.equal(config.mode(), 'PRD');
  assert.equal(config.describe().buildMode, 'PRD');
  await assert.rejects(config.setMode('DEV'), (e) => e.code === 'MODE_LOCKED');
  assert.throws(() => config.assertCommandAllowed('page.capture'), (e) => e.code === 'MODE_RESTRICTED');
});
