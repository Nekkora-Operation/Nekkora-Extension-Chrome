// Validador mínimo de JSON Schema (subconjunto usado por contracts/studio-bridge.schema.json) para os testes em Node:
// type, enum, const, required, properties, additionalProperties, $ref (#/$defs/X), items, minItems, maxItems, uniqueItems,
// minimum, maximum, minLength, maxLength, pattern, oneOf, anyOf. Palavras-chave desconhecidas fazem o teste FALHAR
// (nunca são ignoradas em silêncio).
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
export const schema = JSON.parse(readFileSync(path.join(here, '../../../contracts/studio-bridge.schema.json'), 'utf8'));

const KNOWN = new Set(['type', 'enum', 'const', 'required', 'properties', 'additionalProperties', '$ref', 'items', 'minItems', 'maxItems', 'uniqueItems',
  'minimum', 'maximum', 'minLength', 'maxLength', 'pattern', 'oneOf', 'anyOf', 'propertyNames', 'description', 'title', 'format', '$comment', 'default', 'examples']);

function typeOf(v) {
  if (v === null) {
    return 'null';
  }
  if (Array.isArray(v)) {
    return 'array';
  }
  if (typeof v === 'number') {
    return Number.isInteger(v) ? 'integer' : 'number';
  }
  return typeof v;
}

function typeOk(t, v) {
  const actual = typeOf(v);
  return t === actual || (t === 'number' && actual === 'integer');
}

export function errors(s, v, at = '$') {
  const out = [];
  for (const k of Object.keys(s)) {
    if (!KNOWN.has(k)) {
      out.push(at + ': palavra-chave não suportada pelo validador de teste: ' + k);
    }
  }
  if (s.$ref) {
    // ponteiro local dentro de $defs (ex.: #/$defs/StepEventPayload/properties/notes)
    const parts = s.$ref.replace('#/$defs/', '').split('/');
    let target = schema.$defs[parts[0]];
    for (const part of parts.slice(1)) {
      target = target[part];
    }
    return out.concat(errors(target, v, at));
  }
  if (s.type !== undefined) {
    const types = Array.isArray(s.type) ? s.type : [s.type];
    if (!types.some((t) => typeOk(t, v))) {
      return out.concat([at + ': tipo ' + typeOf(v) + ' ∉ ' + types.join('|')]);
    }
  }
  if (s.const !== undefined && JSON.stringify(s.const) !== JSON.stringify(v)) {
    out.push(at + ': const ' + JSON.stringify(s.const));
  }
  if (s.enum && !s.enum.some((e) => JSON.stringify(e) === JSON.stringify(v))) {
    out.push(at + ': ' + JSON.stringify(v) + ' fora do enum');
  }
  if (typeof v === 'string') {
    if (s.maxLength !== undefined && v.length > s.maxLength) {
      out.push(at + ': maxLength ' + s.maxLength);
    }
    if (s.minLength !== undefined && v.length < s.minLength) {
      out.push(at + ': minLength ' + s.minLength);
    }
    if (s.pattern && !new RegExp(s.pattern, 'u').test(v)) {
      out.push(at + ': pattern ' + s.pattern + ' ≠ ' + v);
    }
  }
  if (typeof v === 'number') {
    if (s.minimum !== undefined && v < s.minimum) {
      out.push(at + ': minimum ' + s.minimum);
    }
    if (s.maximum !== undefined && v > s.maximum) {
      out.push(at + ': maximum ' + s.maximum);
    }
  }
  if (Array.isArray(v)) {
    if (s.maxItems !== undefined && v.length > s.maxItems) {
      out.push(at + ': maxItems ' + s.maxItems);
    }
    if (s.minItems !== undefined && v.length < s.minItems) {
      out.push(at + ': minItems ' + s.minItems);
    }
    if (s.uniqueItems && new Set(v.map((x) => JSON.stringify(x))).size !== v.length) {
      out.push(at + ': uniqueItems');
    }
    if (s.items) {
      v.forEach((x, i) => out.push(...errors(s.items, x, at + '[' + i + ']')));
    }
  }
  if (v && typeof v === 'object' && !Array.isArray(v)) {
    if (s.propertyNames) {
      for (const k of Object.keys(v)) {
        out.push(...errors(s.propertyNames, k, at + '{' + k + '}'));
      }
    }
    for (const r of s.required || []) {
      if (!(r in v)) {
        out.push(at + ': falta ' + r);
      }
    }
    for (const [k, x] of Object.entries(v)) {
      if (s.properties && s.properties[k]) {
        out.push(...errors(s.properties[k], x, at + '.' + k));
      } else if (s.additionalProperties === false) {
        out.push(at + ': propriedade não permitida ' + k);
      } else if (s.additionalProperties && typeof s.additionalProperties === 'object') {
        out.push(...errors(s.additionalProperties, x, at + '.' + k));
      }
    }
  }
  if (s.oneOf) {
    const ok = s.oneOf.filter((alt) => errors(alt, v, at).length === 0).length;
    if (ok !== 1) {
      out.push(at + ': oneOf com ' + ok + ' alternativas válidas');
    }
  }
  if (s.anyOf && !s.anyOf.some((alt) => errors(alt, v, at).length === 0)) {
    out.push(at + ': anyOf sem alternativa válida');
  }
  return out;
}

/** Erros de `value` contra $defs[name]. */
export function check(name, value) {
  return errors({ $ref: '#/$defs/' + name }, value);
}
