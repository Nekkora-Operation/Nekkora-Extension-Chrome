// Gera chrome-extension/src/shared/ui-vocabulary.js a partir da fonte única contracts/privacy/ui-vocabulary.json
// (CHROME-SECURITY-001). O teste test/capture-policy.test.js falha se os dois divergirem.
// Uso: node scripts/gen-ui-vocabulary.mjs
import { readFileSync, writeFileSync } from 'node:fs';

const src = new URL('../contracts/privacy/ui-vocabulary.json', import.meta.url);
const out = new URL('../chrome-extension/src/shared/ui-vocabulary.js', import.meta.url);
const v = JSON.parse(readFileSync(src, 'utf8'));
const body = `/*
 * GERADO por scripts/gen-ui-vocabulary.mjs a partir de contracts/privacy/ui-vocabulary.json — não editar à mão.
 * ${v.version}: vocabulário FECHADO de metadados de interface (CHROME-SECURITY-001). ${v.terms.length} termos.
 */
(function (root) {
  if (root.NekkoraVocabulary) {
    return;
  }
  root.NekkoraVocabulary = Object.freeze({
    version: ${JSON.stringify(v.version)},
    terms: new Set(${JSON.stringify(v.terms)}),
  });
})(globalThis);
`;
writeFileSync(out, body);
console.log(`ui-vocabulary.js: ${v.terms.length} termos (${v.version})`);
