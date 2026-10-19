/*
 * STUDIO-012 PDD-01 — PLANO VISUAL da política nekkora-visual/2 (representação documental) no documento principal.
 *
 * Devolve só GEOMETRIA e TIPOS, na ordem de pintura — nenhum texto, cor real, URL ou atributo sai daqui:
 *   SURFACE  contêiner visível com fundo próprio ou borda (≥ 96×32 px CSS), com o TOM da classe de luminância do fundo
 *            (LIGHT/MID/DARK), nunca a cor; uma tabela é UMA superfície e nada dentro dela é descrito (linhas, células);
 *   MEDIA    imagem/svg/canvas/vídeo (≥ 16×16): só o lugar;
 *   FRAME    iframe/frame/object/embed: só o lugar — o conteúdo do frame nunca é lido;
 *   CONTROL  botão cujo rótulo não é elegível: só o formato;
 *   FIELD    campo editável: moldura vazia (como na v1);
 *   CARRIER  rótulo elegível (as regras da v1) e, na v2, também input[type=submit|button|reset] cujo value inteiro é texto
 *            de interface do vocabulário. Recusado se tiver imagem de fundo (própria ou de um ancestral), se estiver
 *            coberto, fora do viewport, ou se tocar campo, frame ou mídia.
 * O service worker redesenha tudo com a paleta fechada; só os portadores recebem pixels reais.
 */
(function (root) {
  const NS = root.NekkoraCS;
  if (!NS || NS.pddPlan) {
    return;
  }

  const INLINE = new Set(['SPAN', 'B', 'STRONG', 'I', 'EM', 'SMALL', 'ABBR', 'U', 'S', 'SUB', 'SUP', 'MARK', 'BR']);
  const BUTTON_INPUTS = new Set(['submit', 'button', 'reset']);
  const NOT_FIELD_INPUTS = new Set(['hidden', 'button', 'submit', 'reset', 'image']);
  const CONTAINERS = 'div, section, header, footer, nav, main, aside, article, form, fieldset, table, dialog, details, ul, ol, '
    + '[role="dialog"], [role="region"], [role="navigation"], [role="banner"], [role="contentinfo"], [role="menu"], [role="tablist"], [role="toolbar"]';
  const MAX = { SURFACE: 48, MEDIA: 16, FRAME: 16, CONTROL: 32, FIELD: 64, CARRIER: 64 };
  const MAX_SCANNED = 4000;
  const SURFACE_MIN_W = 96;
  const SURFACE_MIN_H = 32;
  const MEDIA_MIN = 16;
  // mesmos tetos do sanitizador: um portador grande demais vira só o formato (em vez de derrubar a captura inteira)
  const CARRIER_MAX_CSS_HEIGHT = 64;
  const CARRIER_MAX_WIDTH_FRACTION = 0.6;
  const CARRIERS_MAX_AREA_FRACTION = 0.18;

  /** rgba(...) → [r, g, b, a] ou null (transparente/indefinido). */
  function rgba(css) {
    const m = /^rgba?\(([^)]+)\)$/.exec(String(css || '').trim());
    if (!m) return null;
    const p = m[1].split(/[\s,/]+/).filter(Boolean).map(Number);
    if (p.length < 3 || p.slice(0, 3).some((v) => !Number.isFinite(v))) return null;
    const a = p.length > 3 && Number.isFinite(p[3]) ? p[3] : 1;
    return a > 0 ? [p[0], p[1], p[2], a] : null;
  }

  function luminance(c) {
    const lin = (v) => {
      const s = v / 255;
      return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
    };
    return 0.2126 * lin(c[0]) + 0.7152 * lin(c[1]) + 0.0722 * lin(c[2]);
  }

  /** Classe do tom (nunca a cor): claro, médio ou escuro. */
  function tone(c) {
    const l = luminance(c);
    return l > 0.8 ? 'LIGHT' : l > 0.35 ? 'MID' : 'DARK';
  }

  function visible(el, cs) {
    return cs.display !== 'none' && cs.visibility === 'visible' && Number(cs.opacity) > 0;
  }

  /** Retângulo recortado ao viewport (px CSS) ou null. */
  function clip(r, vw, vh) {
    const x = Math.max(0, r.left);
    const y = Math.max(0, r.top);
    const x2 = Math.min(vw, r.right);
    const y2 = Math.min(vh, r.bottom);
    return x2 - x > 0 && y2 - y > 0 ? { x, y, width: x2 - x, height: y2 - y } : null;
  }

  function hasBorder(cs) {
    return ['Top', 'Right', 'Bottom', 'Left'].some((s) => parseFloat(cs['border' + s + 'Width']) >= 1 && cs['border' + s + 'Style'] !== 'none'
      && cs['border' + s + 'Style'] !== 'hidden' && rgba(cs['border' + s + 'Color']));
  }

  /** Cor de fundo efetiva por trás de um elemento (o primeiro ancestral opaco; a página é branca por padrão). */
  function backdrop(el) {
    for (let a = el.parentElement; a; a = a.parentElement) {
      const c = rgba(getComputedStyle(a).backgroundColor);
      if (c && c[3] >= 0.5) return c;
    }
    return [255, 255, 255, 1];
  }

  function overlaps(a, b) {
    return a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height;
  }

  /** Mantém no máximo n itens (os de maior área) SEM perder a ordem do documento. */
  function cap(list, n) {
    if (list.length <= n) return list;
    const keep = new Set(list.map((r, i) => [r.width * r.height, i]).sort((a, b) => b[0] - a[0]).slice(0, n).map((p) => p[1]));
    return list.filter((r, i) => keep.has(i));
  }

  function pseudoText(el) {
    return ['::before', '::after'].some((p) => {
      const c = getComputedStyle(el, p).content;
      return c && c !== 'none' && c !== 'normal' && c !== '""' && c !== "''";
    });
  }

  function clean(el) {
    const cs = getComputedStyle(el);
    if (cs.backgroundImage && cs.backgroundImage !== 'none') return false;
    if (pseudoText(el)) return false;
    for (const child of el.children) {
      if (!INLINE.has(child.tagName) || !clean(child)) return false;
    }
    return true;
  }

  /** Algum ancestral pinta imagem (ou gradiente) de fundo? Então os pixels do rótulo podem trazer pedaços dela. */
  function overImage(el) {
    for (let a = el.parentElement; a && a !== document.documentElement; a = a.parentElement) {
      const bi = getComputedStyle(a).backgroundImage;
      if (bi && bi !== 'none') return true;
    }
    return false;
  }

  function v2(policy, vw, vh, reject) {
    const fields = [];
    for (const el of document.querySelectorAll('input, textarea, select, [contenteditable]')) {
      const t = (el.getAttribute('type') || '').toLowerCase();
      if (el.tagName === 'INPUT' && NOT_FIELD_INPUTS.has(t)) continue;
      if (el.getAttribute('contenteditable') === 'false') continue;
      const cs = getComputedStyle(el);
      if (!visible(el, cs)) continue;
      const r = clip(el.getBoundingClientRect(), vw, vh);
      if (r) fields.push({ kind: 'FIELD', ...r });
    }
    const frames = [];
    for (const el of document.querySelectorAll('iframe, frame, object, embed')) {
      const cs = getComputedStyle(el);
      if (!visible(el, cs)) continue;
      const r = clip(el.getBoundingClientRect(), vw, vh);
      if (r) frames.push({ kind: 'FRAME', ...r });
    }
    const media = [];
    for (const el of document.querySelectorAll('img, svg, canvas, video, picture')) {
      if (el.parentElement && el.parentElement.closest('svg, picture')) continue; // o contêiner já é o lugar
      const cs = getComputedStyle(el);
      if (!visible(el, cs)) continue;
      const b = el.getBoundingClientRect();
      if (b.width < MEDIA_MIN || b.height < MEDIA_MIN) continue;
      const r = clip(b, vw, vh);
      if (r) media.push({ kind: 'MEDIA', ...r });
    }
    const surfaces = [];
    let scanned = 0;
    for (const el of document.querySelectorAll(CONTAINERS)) {
      if (++scanned > MAX_SCANNED) break;
      if (el.tagName !== 'TABLE' && el.closest('table')) continue; // a tabela é uma superfície só: nada de dentro dela
      const b = el.getBoundingClientRect();
      if (b.width < SURFACE_MIN_W || b.height < SURFACE_MIN_H) continue;
      const cs = getComputedStyle(el);
      if (!visible(el, cs)) continue;
      const own = rgba(cs.backgroundColor);
      const behind = backdrop(el);
      const distinct = own && own[3] >= 0.5 && Math.abs(luminance(own) - luminance(behind)) > 0.01;
      if (!distinct && !hasBorder(cs) && el.tagName !== 'TABLE') continue;
      const r = clip(b, vw, vh);
      if (r) surfaces.push({ kind: 'SURFACE', tone: tone(distinct ? own : behind), ...r });
    }
    const blockers = [...fields, ...frames, ...media];
    const carriers = [];
    const controls = [];
    let carrierArea = 0;
    const candidates = document.querySelectorAll('button, summary, label, legend, a[href], [role], input[type="submit" i], input[type="button" i], input[type="reset" i], input[type="image" i]');
    for (const el of candidates) {
      const t = (el.getAttribute('type') || '').toLowerCase();
      const inputButton = el.tagName === 'INPUT' && BUTTON_INPUTS.has(t);
      const imageButton = el.tagName === 'INPUT' && t === 'image';
      const isButton = el.tagName === 'BUTTON' || inputButton || imageButton || /^button\b/i.test(el.getAttribute('role') || '');
      if (!inputButton && !imageButton && !policy.isCarrier(el.tagName.toLowerCase(), el.getAttribute('role'), el.hasAttribute('href'))) continue;
      const b = el.getBoundingClientRect();
      if (!(b.width > 0 && b.height > 0)) continue;
      const cs = getComputedStyle(el);
      const asControl = (why) => {
        reject(why);
        if (isButton && visible(el, cs)) {
          const r = clip(b, vw, vh);
          if (r) controls.push({ kind: 'CONTROL', ...r });
        }
      };
      if (b.left < 0 || b.top < 0 || b.right > vw || b.bottom > vh) { asControl('OUTSIDE_VIEWPORT'); continue; }
      if (cs.visibility !== 'visible' || Number(cs.opacity) === 0) { reject('NOT_VISIBLE'); continue; }
      if (imageButton) { asControl('IMAGE_BUTTON'); continue; }
      const text = inputButton ? el.value : el.textContent;
      if (!policy.interfaceText(text || '')) { asControl('TEXT_NOT_INTERFACE'); continue; }
      if (!clean(el)) { asControl('NOT_PLAIN_TEXT'); continue; }
      if (overImage(el)) { asControl('OVER_BACKGROUND_IMAGE'); continue; }
      const pts = [[b.left + b.width / 2, b.top + b.height / 2], [b.left + 2, b.top + 2], [b.right - 2, b.top + 2], [b.left + 2, b.bottom - 2],
        [b.right - 2, b.bottom - 2]];
      if (!pts.every(([x, y]) => { const top = document.elementFromPoint(x, y); return top && (top === el || el.contains(top)); })) {
        reject('COVERED');
        continue;
      }
      const box = { x: b.left, y: b.top, width: b.width, height: b.height };
      if (blockers.some((f) => overlaps(box, f))) { asControl('OVERLAPS_CONTENT'); continue; }
      if (b.height > CARRIER_MAX_CSS_HEIGHT || b.width > vw * CARRIER_MAX_WIDTH_FRACTION) { asControl('CARRIER_TOO_LARGE'); continue; }
      if (carrierArea + b.width * b.height > vw * vh * CARRIERS_MAX_AREA_FRACTION) { asControl('CARRIERS_AREA'); continue; }
      carrierArea += b.width * b.height;
      carriers.push({ kind: 'CARRIER', ...box });
    }
    const regions = [...cap(surfaces, MAX.SURFACE), ...cap(media, MAX.MEDIA), ...cap(frames, MAX.FRAME), ...cap(controls, MAX.CONTROL),
      ...cap(fields, MAX.FIELD), ...cap(carriers, MAX.CARRIER)];
    return { regions, hasFrames: frames.length > 0 || !!document.querySelector('iframe, frame, object, embed') };
  }

  NS.pddPlan = Object.freeze({ v2 });
})(globalThis);
