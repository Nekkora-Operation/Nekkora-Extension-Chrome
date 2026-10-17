/*
 * STUDIO-010 — política visual nekkora-visual/1: sanitização de pixels por LISTA DE PERMISSÃO.
 *
 * Funções PURAS sobre buffers RGBA (testáveis sem navegador). O service worker usa-as entre a captura bruta
 * (chrome.tabs.captureVisibleTab) e a saída: nada da imagem bruta sai daqui.
 *
 *  - A imagem sanitizada começa INTEIRAMENTE opaca (OPAQUE).
 *  - Só os retângulos de PORTADORES (rótulos de interface já aprovados pela nekkora-privacy/2 no content script) recebem
 *    os pixels reais correspondentes.
 *  - Campos editáveis viram MOLDURAS SINTÉTICAS (FIELD_FILL com borda FIELD_BORDER): nenhum pixel do campo é copiado.
 *  - verify() confere, pixel a pixel, que tudo fora dos portadores é uma das três cores sintéticas. O host e o Studio
 *    repetem a mesma verificação (defesa em profundidade).
 */
export const VISUAL_POLICY = 'nekkora-visual/1';
export const OPAQUE = Object.freeze([213, 216, 221]); // #D5D8DD
export const FIELD_FILL = Object.freeze([255, 255, 255]);
export const FIELD_BORDER = Object.freeze([138, 144, 153]); // #8A9099
export const MAX_REGIONS = 64;
/** Teto de um portador: altura em px CSS e fração do viewport — um rótulo nunca é uma área grande da tela. */
export const CARRIER_MAX_CSS_HEIGHT = 64;
export const CARRIER_MAX_WIDTH_FRACTION = 0.6;
export const CARRIERS_MAX_AREA_FRACTION = 0.2;
export const MAX_PNG_BYTES = 150000;

/**
 * Converte e valida as regiões do plano (px CSS do viewport) para px do dispositivo. Portadores são arredondados PARA
 * DENTRO (copiar menos, nunca mais); campos, para fora (cobrir mais). Lança Error com código se algo não fecha.
 */
export function deviceRegions(plan, width, height) {
  const dpr = Number(plan && plan.viewport && plan.viewport.dpr);
  const vw = Number(plan && plan.viewport && plan.viewport.width);
  const vh = Number(plan && plan.viewport && plan.viewport.height);
  if (!(dpr > 0 && dpr <= 4) || !(vw > 0) || !(vh > 0)) {
    throw codeError('GEOMETRY_MISMATCH');
  }
  if (Math.abs(Math.round(vw * dpr) - width) > 1 || Math.abs(Math.round(vh * dpr) - height) > 1) {
    throw codeError('GEOMETRY_MISMATCH'); // a imagem não é o viewport do plano
  }
  const regions = Array.isArray(plan.regions) ? plan.regions : [];
  if (regions.length > MAX_REGIONS * 2) {
    throw codeError('TOO_MANY_REGIONS');
  }
  const carriers = [];
  const fields = [];
  let carrierArea = 0;
  for (const r of regions) {
    const x = Number(r.x), y = Number(r.y), w = Number(r.width), h = Number(r.height);
    if (![x, y, w, h].every(Number.isFinite) || w <= 0 || h <= 0 || x < 0 || y < 0 || x + w > vw + 0.5 || y + h > vh + 0.5) {
      throw codeError('REGION_OUT_OF_VIEWPORT');
    }
    if (r.kind === 'CARRIER') {
      if (h > CARRIER_MAX_CSS_HEIGHT || w > vw * CARRIER_MAX_WIDTH_FRACTION) {
        throw codeError('CARRIER_TOO_LARGE');
      }
      const d = { x: Math.ceil(x * dpr), y: Math.ceil(y * dpr), x2: Math.floor((x + w) * dpr), y2: Math.floor((y + h) * dpr) };
      d.x2 = Math.min(d.x2, width);
      d.y2 = Math.min(d.y2, height);
      if (d.x2 > d.x && d.y2 > d.y) {
        carriers.push(d);
        carrierArea += (d.x2 - d.x) * (d.y2 - d.y);
      }
    } else if (r.kind === 'FIELD') {
      const d = { x: Math.max(0, Math.floor(x * dpr)), y: Math.max(0, Math.floor(y * dpr)),
        x2: Math.min(width, Math.ceil((x + w) * dpr)), y2: Math.min(height, Math.ceil((y + h) * dpr)) };
      if (d.x2 > d.x && d.y2 > d.y) {
        fields.push(d);
      }
    } else {
      throw codeError('REGION_KIND_UNKNOWN');
    }
  }
  if (carriers.length > MAX_REGIONS || fields.length > MAX_REGIONS) {
    throw codeError('TOO_MANY_REGIONS');
  }
  if (carrierArea > width * height * CARRIERS_MAX_AREA_FRACTION) {
    throw codeError('CARRIER_TOO_LARGE');
  }
  for (const c of carriers) {
    for (const f of fields) {
      if (c.x < f.x2 && f.x < c.x2 && c.y < f.y2 && f.y < c.y2) {
        throw codeError('CARRIER_OVERLAPS_FIELD'); // um rótulo nunca carrega pixels de um campo
      }
    }
  }
  return { carriers, fields };
}

/** Compõe a imagem sanitizada a partir da bruta (RGBA). A bruta não é alterada nem retida. */
export function compose(raw, width, height, regions) {
  if (!raw || raw.length !== width * height * 4) {
    throw codeError('GEOMETRY_MISMATCH');
  }
  const out = new Uint8ClampedArray(width * height * 4);
  for (let i = 0; i < out.length; i += 4) {
    out[i] = OPAQUE[0];
    out[i + 1] = OPAQUE[1];
    out[i + 2] = OPAQUE[2];
    out[i + 3] = 255;
  }
  for (const c of regions.carriers) {
    for (let y = c.y; y < c.y2; y++) {
      const row = y * width * 4;
      for (let x = c.x; x < c.x2; x++) {
        const k = row + x * 4;
        out[k] = raw[k];
        out[k + 1] = raw[k + 1];
        out[k + 2] = raw[k + 2];
        out[k + 3] = 255;
      }
    }
  }
  for (const f of regions.fields) {
    for (let y = f.y; y < f.y2; y++) {
      const row = y * width * 4;
      for (let x = f.x; x < f.x2; x++) {
        const edge = y === f.y || y === f.y2 - 1 || x === f.x || x === f.x2 - 1;
        const color = edge ? FIELD_BORDER : FIELD_FILL;
        const k = row + x * 4;
        out[k] = color[0];
        out[k + 1] = color[1];
        out[k + 2] = color[2];
        out[k + 3] = 255;
      }
    }
  }
  return out;
}

/**
 * Verificação independente: fora dos portadores, só as três cores sintéticas (opacas). Devolve o número de pixels
 * fora da regra (0 = conforme). Usada no service worker, e reimplementada no host e no Studio.
 */
export function violations(pixels, width, height, regions) {
  if (!pixels || pixels.length !== width * height * 4) {
    return Number.MAX_SAFE_INTEGER;
  }
  const inCarrier = new Uint8Array(width * height);
  for (const c of regions.carriers) {
    for (let y = c.y; y < c.y2; y++) {
      inCarrier.fill(1, y * width + c.x, y * width + c.x2);
    }
  }
  let bad = 0;
  for (let p = 0, k = 0; p < width * height; p++, k += 4) {
    if (inCarrier[p]) {
      continue;
    }
    const r = pixels[k], g = pixels[k + 1], b = pixels[k + 2], a = pixels[k + 3];
    if (a !== 255 || !(same(r, g, b, OPAQUE) || same(r, g, b, FIELD_FILL) || same(r, g, b, FIELD_BORDER))) {
      bad++;
    }
  }
  return bad;
}

function same(r, g, b, c) {
  return r === c[0] && g === c[1] && b === c[2];
}

export function codeError(code) {
  const e = new Error(code);
  e.code = code;
  return e;
}
