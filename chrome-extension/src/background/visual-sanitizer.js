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

/*
 * STUDIO-012 PDD-01 — política nekkora-visual/2: REPRESENTAÇÃO DOCUMENTAL. Nada do fundo real é copiado: a imagem é
 * redesenhada a partir da geometria do DOM com uma paleta FECHADA (página clara, superfícies por classe de luminância,
 * marcadores de lugar para mídia e frames, botões e campos vazios); os únicos pixels reais continuam sendo os dos
 * portadores de rótulos aprovados pela nekkora-privacy/2.
 *
 * A verificação é MAIS estrita que a da v1: repinta a "tela esperada" na ordem declarada e exige que cada pixel fora dos
 * portadores seja exatamente a cor esperada naquele ponto (a v1 aceitava qualquer das 3 cores em qualquer lugar).
 */
export const VISUAL_POLICY_V2 = 'nekkora-visual/2';
export const VISUAL_POLICIES = Object.freeze([VISUAL_POLICY, VISUAL_POLICY_V2, 'nekkora-visual/3', 'nekkora-visual/4']);
export const V2_PAGE = Object.freeze([255, 255, 255]);
export const V2_TONES = Object.freeze({ LIGHT: Object.freeze([244, 246, 248]), MID: Object.freeze([230, 234, 238]), DARK: Object.freeze([91, 102, 117]) });
export const V2_SURFACE_BORDER = Object.freeze([201, 209, 217]); // #C9D1D9
export const V2_MEDIA_FILL = Object.freeze([237, 239, 242]); // #EDEFF2
export const V2_FRAME_FILL = Object.freeze([244, 246, 248]);
export const V2_CONTROL_FILL = Object.freeze([230, 234, 238]);
/** Ordem de pintura (e de declaração): fundo → superfícies → mídia → frames → controles → campos → portadores (por cima). */
export const V2_KINDS = Object.freeze(['SURFACE', 'MEDIA', 'FRAME', 'CONTROL', 'FIELD', 'CARRIER']);
export const V2_MAX = Object.freeze({ SURFACE: 48, MEDIA: 16, FRAME: 16, CONTROL: 32, FIELD: MAX_REGIONS, CARRIER: MAX_REGIONS });

/** Preenchimento e borda de uma região sintética da v2 (portador não tem: são pixels reais). */
export function v2Paint(kind, tone) {
  switch (kind) {
    case 'SURFACE':
      if (!Object.prototype.hasOwnProperty.call(V2_TONES, tone)) throw codeError('REGION_KIND_UNKNOWN');
      return { fill: V2_TONES[tone], border: V2_SURFACE_BORDER };
    case 'MEDIA':
      return { fill: V2_MEDIA_FILL, border: V2_SURFACE_BORDER };
    case 'FRAME':
      return { fill: V2_FRAME_FILL, border: FIELD_BORDER };
    case 'CONTROL':
      return { fill: V2_CONTROL_FILL, border: FIELD_BORDER };
    case 'FIELD':
      return { fill: FIELD_FILL, border: FIELD_BORDER };
    default:
      throw codeError('REGION_KIND_UNKNOWN');
  }
}

/**
 * Plano v2 (px CSS) → regiões em px do dispositivo, NA ORDEM de pintura. Portadores arredondados para dentro; regiões
 * sintéticas para fora. Recusa (código) geometria incoerente, tipo/tom desconhecido, ordem trocada, tetos estourados e
 * portador sobre campo, frame ou mídia (nunca carrega pixels de conteúdo).
 */
export function deviceRegionsV2(plan, width, height) {
  const dpr = Number(plan && plan.viewport && plan.viewport.dpr);
  const vw = Number(plan && plan.viewport && plan.viewport.width);
  const vh = Number(plan && plan.viewport && plan.viewport.height);
  if (!(dpr > 0 && dpr <= 4) || !(vw > 0) || !(vh > 0)) throw codeError('GEOMETRY_MISMATCH');
  if (Math.abs(Math.round(vw * dpr) - width) > 1 || Math.abs(Math.round(vh * dpr) - height) > 1) throw codeError('GEOMETRY_MISMATCH');
  const list = Array.isArray(plan.regions) ? plan.regions : [];
  const out = [];
  const count = {};
  let rank = 0;
  let carrierArea = 0;
  for (const r of list) {
    const k = V2_KINDS.indexOf(r && r.kind);
    if (k < 0) throw codeError('REGION_KIND_UNKNOWN');
    if (k < rank) throw codeError('REGION_ORDER');
    rank = k;
    count[r.kind] = (count[r.kind] || 0) + 1;
    if (count[r.kind] > V2_MAX[r.kind]) throw codeError('TOO_MANY_REGIONS');
    const x = Number(r.x), y = Number(r.y), w = Number(r.width), h = Number(r.height);
    if (![x, y, w, h].every(Number.isFinite) || w <= 0 || h <= 0 || x < 0 || y < 0 || x + w > vw + 0.5 || y + h > vh + 0.5) {
      throw codeError('REGION_OUT_OF_VIEWPORT');
    }
    if (r.kind === 'CARRIER') {
      if (h > CARRIER_MAX_CSS_HEIGHT || w > vw * CARRIER_MAX_WIDTH_FRACTION) throw codeError('CARRIER_TOO_LARGE');
      const d = { kind: 'CARRIER', x: Math.ceil(x * dpr), y: Math.ceil(y * dpr), x2: Math.min(width, Math.floor((x + w) * dpr)),
        y2: Math.min(height, Math.floor((y + h) * dpr)) };
      if (d.x2 > d.x && d.y2 > d.y) {
        out.push(d);
        carrierArea += (d.x2 - d.x) * (d.y2 - d.y);
      }
    } else {
      const tone = r.kind === 'SURFACE' ? r.tone : undefined;
      v2Paint(r.kind, tone); // tom válido?
      const d = { kind: r.kind, x: Math.max(0, Math.floor(x * dpr)), y: Math.max(0, Math.floor(y * dpr)),
        x2: Math.min(width, Math.ceil((x + w) * dpr)), y2: Math.min(height, Math.ceil((y + h) * dpr)) };
      if (tone) d.tone = tone;
      if (d.x2 > d.x && d.y2 > d.y) out.push(d);
    }
  }
  if (carrierArea > width * height * CARRIERS_MAX_AREA_FRACTION) throw codeError('CARRIER_TOO_LARGE');
  const blockers = out.filter((d) => d.kind === 'FIELD' || d.kind === 'FRAME' || d.kind === 'MEDIA');
  for (const c of out) {
    if (c.kind !== 'CARRIER') continue;
    for (const f of blockers) {
      if (c.x < f.x2 && f.x < c.x2 && c.y < f.y2 && f.y < c.y2) {
        throw codeError(f.kind === 'FIELD' ? 'CARRIER_OVERLAPS_FIELD' : 'CARRIER_OVERLAPS_CONTENT');
      }
    }
  }
  return out;
}

/** A "tela esperada" da v2: só a parte sintética (portadores ficam com o fundo de baixo; quem verifica os pula). */
export function expectedV2(width, height, regions) {
  const out = new Uint8ClampedArray(width * height * 4);
  for (let i = 0; i < out.length; i += 4) {
    out[i] = V2_PAGE[0];
    out[i + 1] = V2_PAGE[1];
    out[i + 2] = V2_PAGE[2];
    out[i + 3] = 255;
  }
  for (const r of regions) {
    if (r.kind === 'CARRIER') continue;
    const { fill, border } = v2Paint(r.kind, r.tone);
    for (let y = r.y; y < r.y2; y++) {
      const row = y * width * 4;
      for (let x = r.x; x < r.x2; x++) {
        const c = y === r.y || y === r.y2 - 1 || x === r.x || x === r.x2 - 1 ? border : fill;
        const k = row + x * 4;
        out[k] = c[0];
        out[k + 1] = c[1];
        out[k + 2] = c[2];
      }
    }
  }
  return out;
}

/** Compõe a imagem v2 a partir da bruta (RGBA): tela esperada + pixels reais SÓ nos portadores. */
export function composeV2(raw, width, height, regions) {
  if (!raw || raw.length !== width * height * 4) throw codeError('GEOMETRY_MISMATCH');
  const out = expectedV2(width, height, regions);
  for (const c of regions) {
    if (c.kind !== 'CARRIER') continue;
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
  return out;
}

/** Verificação independente da v2: pixels fora dos portadores diferentes da tela esperada (0 = conforme). */
export function violationsV2(pixels, width, height, regions) {
  if (!pixels || pixels.length !== width * height * 4) return Number.MAX_SAFE_INTEGER;
  const expected = expectedV2(width, height, regions);
  const inCarrier = new Uint8Array(width * height);
  for (const c of regions) {
    if (c.kind !== 'CARRIER') continue;
    for (let y = c.y; y < c.y2; y++) inCarrier.fill(1, y * width + c.x, y * width + c.x2);
  }
  let bad = 0;
  for (let p = 0, k = 0; p < width * height; p++, k += 4) {
    if (inCarrier[p]) continue;
    if (pixels[k + 3] !== 255 || pixels[k] !== expected[k] || pixels[k + 1] !== expected[k + 1] || pixels[k + 2] !== expected[k + 2]) bad++;
  }
  return bad;
}

/*
 * NEKKORA-STUDIO-013 — política nekkora-visual/3: representação INTEIRAMENTE SINTÉTICA. Nenhum pixel da página é lido:
 * a extensão não chama captureVisibleTab e não envia imagem. O que sai é uma DESCRIÇÃO fechada (geometria em px CSS
 * inteiros, tipos, classes de tom) e, nos rótulos de interface, o TEXTO já aprovado pelo vocabulário controlado
 * (nekkora-privacy/2: interfaceText). O Studio desenha a imagem a partir dela, com a fonte e a paleta próprias.
 *
 * Por que não copiar pixels dos portadores (v2): a verificação de pixels fora dos portadores não prova nada sobre o que
 * está DENTRO deles. Numa aplicação real, um rótulo aprovado pelo texto pode exibir outra coisa nos pixels (fonte web que
 * troca glifos, sobreposição com pointer-events:none, sombra/transformação de vizinhos). Na v3 o que chega ao PDD é o
 * próprio texto aprovado, e cada barreira (service worker, Native Host, Studio) consegue revalidá-lo.
 *
 * Tipos, na ordem de pintura: SURFACE (tom) → MEDIA → FRAME → CONTROL → FIELD → TEXT (barra neutra no lugar de texto
 * que não é rótulo de interface: nunca o texto) → LABEL (texto do vocabulário).
 */
export const VISUAL_POLICY_V3 = 'nekkora-visual/3';
export const V3_KINDS = Object.freeze(['SURFACE', 'MEDIA', 'FRAME', 'CONTROL', 'FIELD', 'TEXT', 'LABEL']);
export const V3_MAX = Object.freeze({ SURFACE: 48, MEDIA: 24, FRAME: 16, CONTROL: 48, FIELD: 96, TEXT: 240, LABEL: 96 });
export const V3_TONES = Object.freeze(['LIGHT', 'MID', 'DARK']);
export const V3_INKS = Object.freeze(['DARK', 'LIGHT']);
export const V3_WEIGHTS = Object.freeze(['NORMAL', 'BOLD']);
export const V3_ALIGNS = Object.freeze(['START', 'CENTER']);
export const V3_TEXT_QUANTUM = 16; // largura das barras TEXT: múltiplo de 16 px CSS (o comprimento exato do texto não sai)
export const V3_MIN_SIZE = 8;
export const V3_MAX_SIZE = 40;
export const V3_MAX_VIEWPORT = 8192;
export const V3_MAX_BYTES = 131072;
/** Campos permitidos por tipo (qualquer outro campo reprova: nada além do contrato atravessa). */
const V3_FIELDS = Object.freeze({
  SURFACE: ['kind', 'x', 'y', 'width', 'height', 'tone'],
  MEDIA: ['kind', 'x', 'y', 'width', 'height'],
  FRAME: ['kind', 'x', 'y', 'width', 'height'],
  CONTROL: ['kind', 'x', 'y', 'width', 'height'],
  FIELD: ['kind', 'x', 'y', 'width', 'height'],
  TEXT: ['kind', 'x', 'y', 'width', 'height', 'ink'],
  LABEL: ['kind', 'x', 'y', 'width', 'height', 'text', 'size', 'weight', 'ink', 'align'],
});

/**
 * Valida a descrição v3 ({viewport, regions}) e devolve uma cópia normalizada. Lança Error com código.
 * @param textPolicy objeto com interfaceText(v) → forma limpa ou null (NekkoraCapturePolicy)
 */
export function validateV3(description, textPolicy) {
  if (!textPolicy || typeof textPolicy.interfaceText !== 'function') throw codeError('INTERNAL');
  const d = description;
  if (!d || typeof d !== 'object' || Array.isArray(d)) throw codeError('GEOMETRY_MISMATCH');
  const vp = d.viewport;
  if (!vp || typeof vp !== 'object' || Object.keys(vp).some((k) => !['width', 'height', 'dpr'].includes(k))) throw codeError('GEOMETRY_MISMATCH');
  const vw = vp.width, vh = vp.height, dpr = vp.dpr;
  if (!Number.isInteger(vw) || !Number.isInteger(vh) || vw < 1 || vh < 1 || vw > V3_MAX_VIEWPORT || vh > V3_MAX_VIEWPORT) throw codeError('GEOMETRY_MISMATCH');
  if (typeof dpr !== 'number' || !(dpr > 0 && dpr <= 4)) throw codeError('GEOMETRY_MISMATCH');
  if (!Array.isArray(d.regions)) throw codeError('GEOMETRY_MISMATCH');
  const count = {};
  let rank = 0;
  const out = [];
  for (const r of d.regions) {
    if (!r || typeof r !== 'object' || Array.isArray(r)) throw codeError('REGION_KIND_UNKNOWN');
    const k = V3_KINDS.indexOf(r.kind);
    if (k < 0) throw codeError('REGION_KIND_UNKNOWN');
    if (Object.keys(r).some((f) => !V3_FIELDS[r.kind].includes(f))) throw codeError('REGION_FIELD_UNKNOWN');
    if (k < rank) throw codeError('REGION_ORDER');
    rank = k;
    count[r.kind] = (count[r.kind] || 0) + 1;
    if (count[r.kind] > V3_MAX[r.kind]) throw codeError('TOO_MANY_REGIONS');
    const { x, y, width, height } = r;
    if (![x, y, width, height].every(Number.isInteger) || width < 1 || height < 1 || x < 0 || y < 0 || x + width > vw || y + height > vh) {
      throw codeError('REGION_OUT_OF_VIEWPORT');
    }
    const n = { kind: r.kind, x, y, width, height };
    if (r.kind === 'SURFACE') {
      if (!V3_TONES.includes(r.tone)) throw codeError('REGION_KIND_UNKNOWN');
      n.tone = r.tone;
    } else if (r.kind === 'TEXT') {
      if (!V3_INKS.includes(r.ink)) throw codeError('REGION_KIND_UNKNOWN');
      if (width % V3_TEXT_QUANTUM !== 0) throw codeError('TEXT_NOT_QUANTIZED');
      n.ink = r.ink;
    } else if (r.kind === 'LABEL') {
      if (typeof r.text !== 'string' || !r.text || textPolicy.interfaceText(r.text) !== r.text) throw codeError('LABEL_NOT_INTERFACE_TEXT');
      if (!Number.isInteger(r.size) || r.size < V3_MIN_SIZE || r.size > V3_MAX_SIZE) throw codeError('REGION_KIND_UNKNOWN');
      if (!V3_WEIGHTS.includes(r.weight) || !V3_INKS.includes(r.ink) || !V3_ALIGNS.includes(r.align)) throw codeError('REGION_KIND_UNKNOWN');
      Object.assign(n, { text: r.text, size: r.size, weight: r.weight, ink: r.ink, align: r.align });
    }
    out.push(n);
  }
  const normalized = { viewport: { width: vw, height: vh, dpr }, regions: out };
  if (JSON.stringify(normalized).length > V3_MAX_BYTES) throw codeError('SANITIZED_IMAGE_TOO_LARGE');
  return normalized;
}

/*
 * NEKKORA-STUDIO-013 (revisão da usuária) — política nekkora-visual/4: PRINT REAL com o TEXTO DE DADOS mascarado.
 * A imagem é a tela capturada (cores, fundos, logotipo, ícones, bordas, botões e rótulos de interface do vocabulário
 * como na página). Por cima, faixas LISAS de cor fechada cobrem tudo o que pode ser dado:
 *   BLOCK  tabela inteira, frame, canvas, vídeo, imagem grande ou quebrada, SVG com texto, fundo com imagem, elemento com
 *          pseudo-elemento de texto, botão cujo texto não é do vocabulário;
 *   FIELD  campo editável (moldura vazia, como na v1);
 *   TEXT   cada linha de texto que não é rótulo de interface (faixa na cor da classe do fundo).
 * Ordem de pintura: BLOCK → FIELD → TEXT. A verificação (aqui, no Native Host e no Studio) repinta as máscaras e exige que
 * cada pixel DENTRO delas seja exatamente a cor esperada; fora delas os pixels são reais — a proteção depende de a página
 * ter sido inteiramente mapeada (limite declarado; provado com a cor-sentinela nas páginas adversariais).
 */
export const VISUAL_POLICY_V4 = 'nekkora-visual/4';
export const V4_KINDS = Object.freeze(['BLOCK', 'FIELD', 'TEXT']);
export const V4_MAX = Object.freeze({ BLOCK: 96, FIELD: 128, TEXT: 600 });
export const V4_BLOCK_FILL = Object.freeze([237, 240, 243]); // #EDF0F3
export const V4_BLOCK_BORDER = Object.freeze([201, 209, 217]); // #C9D1D9
export const V4_BARS = Object.freeze({ DARK: Object.freeze([200, 207, 216]), LIGHT: Object.freeze([135, 146, 162]) }); // #C8CFD8 / #8792A2
export const MAX_PNG_BYTES_V4 = 8000000;

/** Plano v4 (px CSS) → máscaras em px do dispositivo, arredondadas PARA FORA (cobrir mais, nunca menos). */
export function deviceMasksV4(plan, width, height) {
  const dpr = Number(plan && plan.viewport && plan.viewport.dpr);
  const vw = Number(plan && plan.viewport && plan.viewport.width);
  const vh = Number(plan && plan.viewport && plan.viewport.height);
  if (!(dpr > 0 && dpr <= 4) || !(vw > 0) || !(vh > 0)) throw codeError('GEOMETRY_MISMATCH');
  if (Math.abs(Math.round(vw * dpr) - width) > 1 || Math.abs(Math.round(vh * dpr) - height) > 1) throw codeError('GEOMETRY_MISMATCH');
  const list = Array.isArray(plan.regions) ? plan.regions : [];
  const out = [];
  const count = {};
  let rank = 0;
  for (const r of list) {
    const k = V4_KINDS.indexOf(r && r.kind);
    if (k < 0) throw codeError('REGION_KIND_UNKNOWN');
    if (k < rank) throw codeError('REGION_ORDER');
    rank = k;
    count[r.kind] = (count[r.kind] || 0) + 1;
    if (count[r.kind] > V4_MAX[r.kind]) throw codeError('TOO_MANY_REGIONS');
    const x = Number(r.x), y = Number(r.y), w = Number(r.width), h = Number(r.height);
    if (![x, y, w, h].every(Number.isFinite) || w <= 0 || h <= 0 || x < 0 || y < 0 || x + w > vw + 0.5 || y + h > vh + 0.5) {
      throw codeError('REGION_OUT_OF_VIEWPORT');
    }
    if (r.kind === 'TEXT' && !V3_INKS.includes(r.ink)) throw codeError('REGION_KIND_UNKNOWN');
    const d = { kind: r.kind, x: Math.max(0, Math.floor(x * dpr)), y: Math.max(0, Math.floor(y * dpr)),
      x2: Math.min(width, Math.ceil((x + w) * dpr)), y2: Math.min(height, Math.ceil((y + h) * dpr)) };
    if (r.kind === 'TEXT') d.ink = r.ink;
    if (d.x2 > d.x && d.y2 > d.y) out.push(d);
  }
  return out;
}

/** {preenchimento, borda} de uma máscara da v4 (TEXT é faixa lisa: borda = preenchimento). */
export function v4Paint(kind, ink) {
  switch (kind) {
    case 'BLOCK':
      return { fill: V4_BLOCK_FILL, border: V4_BLOCK_BORDER };
    case 'FIELD':
      return { fill: FIELD_FILL, border: FIELD_BORDER };
    case 'TEXT': {
      const c = V4_BARS[ink];
      if (!c) throw codeError('REGION_KIND_UNKNOWN');
      return { fill: c, border: c };
    }
    default:
      throw codeError('REGION_KIND_UNKNOWN');
  }
}

/** Mapa (por pixel) da cor que as máscaras mandam pintar; -1 = fora das máscaras. */
function maskMapV4(width, height, masks) {
  const map = new Int32Array(width * height).fill(-1);
  for (const m of masks) {
    const { fill, border } = v4Paint(m.kind, m.ink);
    const f = (fill[0] << 16) | (fill[1] << 8) | fill[2];
    const b = (border[0] << 16) | (border[1] << 8) | border[2];
    for (let y = m.y; y < m.y2; y++) {
      for (let x = m.x; x < m.x2; x++) map[y * width + x] = y === m.y || y === m.y2 - 1 || x === m.x || x === m.x2 - 1 ? b : f;
    }
  }
  return map;
}

/** Compõe a v4: a tela capturada com as máscaras pintadas por cima (na ordem). A bruta não é alterada. */
export function composeV4(raw, width, height, masks) {
  if (!raw || raw.length !== width * height * 4) throw codeError('GEOMETRY_MISMATCH');
  const out = new Uint8ClampedArray(raw);
  const map = maskMapV4(width, height, masks);
  for (let p = 0, k = 0; p < map.length; p++, k += 4) {
    const c = map[p];
    if (c >= 0) {
      out[k] = (c >> 16) & 255;
      out[k + 1] = (c >> 8) & 255;
      out[k + 2] = c & 255;
    }
    out[k + 3] = 255;
  }
  return out;
}

/** Verificação independente da v4: pixels DENTRO das máscaras diferentes da cor repintada (0 = conforme). */
export function violationsV4(pixels, width, height, masks) {
  if (!pixels || pixels.length !== width * height * 4) return Number.MAX_SAFE_INTEGER;
  const map = maskMapV4(width, height, masks);
  let bad = 0;
  for (let p = 0, k = 0; p < map.length; p++, k += 4) {
    const c = map[p];
    if (c < 0) continue;
    if (pixels[k + 3] !== 255 || pixels[k] !== ((c >> 16) & 255) || pixels[k + 1] !== ((c >> 8) & 255) || pixels[k + 2] !== (c & 255)) bad++;
  }
  return bad;
}

export function codeError(code) {
  const e = new Error(code);
  e.code = code;
  return e;
}
