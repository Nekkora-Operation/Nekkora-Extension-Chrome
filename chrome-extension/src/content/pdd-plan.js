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

  /*
   * NEKKORA-STUDIO-013 — plano da nekkora-visual/3 (inteiramente sintética). Nenhum pixel é copiado depois, então os
   * cuidados da v2 com fundo, sobreposição e pseudo-elemento deixam de ser de PRIVACIDADE (continuam de fidelidade).
   * O que sai: geometria em px CSS INTEIROS, tipos, classe de tom e, só em LABEL, o texto que o vocabulário aprova
   * (interfaceText: o texto INTEIRO do rótulo precisa ser texto de interface; senão vira barra TEXT ou nada).
   *   - texto que não é rótulo de interface vira barra TEXT com largura em múltiplos de 16 px (nunca o texto);
   *   - nada dentro de tabela é descrito (a tabela é uma superfície só), como na v2;
   *   - campos: moldura vazia — valor, placeholder e preenchimento automático nunca entram;
   *   - shadow roots abertas e fechadas (chrome.dom.openOrClosedShadowRoot) são percorridas: o que está nelas segue as
   *     mesmas regras;
   *   - frames: só o lugar; o conteúdo nunca é lido.
   */
  const V3_MAX = { SURFACE: 48, MEDIA: 24, FRAME: 16, CONTROL: 48, FIELD: 96, TEXT: 240, LABEL: 96 };
  const V3_SCAN = 6000;
  const V3_TEXT_SCAN = 4000;
  const QUANTUM = 16;
  const FIELD_ROLES = new Set(['textbox', 'searchbox', 'combobox', 'spinbutton']);
  const SKIP_TEXT = new Set(['SCRIPT', 'STYLE', 'NOSCRIPT', 'TEMPLATE', 'TEXTAREA', 'SELECT', 'OPTION', 'OPTGROUP', 'IFRAME', 'OBJECT', 'EMBED',
    'CANVAS', 'VIDEO', 'AUDIO', 'TITLE', 'HEAD']);

  function shadowOf(el) {
    try {
      if (root.chrome && root.chrome.dom && typeof root.chrome.dom.openOrClosedShadowRoot === 'function') {
        return root.chrome.dom.openOrClosedShadowRoot(el) || null;
      }
    } catch (e) {
      // sem a API: só a raiz aberta
    }
    return el.shadowRoot || null;
  }

  /** Elementos do documento e das shadow roots, na ordem do documento, até o teto. */
  function deepElements(limit) {
    const out = [];
    const stack = [document.documentElement];
    while (stack.length && out.length < limit) {
      const el = stack.pop();
      if (!el) continue;
      out.push(el);
      const kids = [];
      const sr = shadowOf(el);
      if (sr) kids.push(...sr.children);
      kids.push(...el.children);
      for (let i = kids.length - 1; i >= 0; i--) stack.push(kids[i]);
    }
    return { list: out, truncated: stack.length > 0 };
  }

  function shown(el) {
    if (typeof el.checkVisibility === 'function') {
      try {
        return el.checkVisibility({ opacityProperty: true, visibilityProperty: true });
      } catch (e) {
        // segue para a regra simples
      }
    }
    const cs = getComputedStyle(el);
    return visible(el, cs);
  }

  /** Retângulo inteiro (px CSS) recortado ao viewport: para fora, como as regiões sintéticas da v2. */
  function box(r, vw, vh) {
    const x = Math.max(0, Math.floor(r.left));
    const y = Math.max(0, Math.floor(r.top));
    const x2 = Math.min(vw, Math.ceil(r.right));
    const y2 = Math.min(vh, Math.ceil(r.bottom));
    return x2 - x >= 1 && y2 - y >= 1 ? { x, y, width: x2 - x, height: y2 - y } : null;
  }

  function closestAcross(el, selector) {
    for (let n = el; n; ) {
      if (n.nodeType === 1 && n.matches(selector)) return n;
      n = n.parentElement || (n.getRootNode && n.getRootNode().host) || null;
    }
    return null;
  }

  function inkFor(el) {
    const own = rgba(getComputedStyle(el).backgroundColor);
    const bg = own && own[3] >= 0.5 ? own : backdrop(el);
    return tone(bg) === 'DARK' ? 'LIGHT' : 'DARK';
  }

  function isField(el) {
    const tag = el.tagName;
    if (tag === 'INPUT') return !NOT_FIELD_INPUTS.has((el.getAttribute('type') || '').toLowerCase());
    if (tag === 'TEXTAREA' || tag === 'SELECT') return true;
    if (el.isContentEditable && el.getAttribute('contenteditable') !== 'false') return !el.parentElement || !el.parentElement.isContentEditable;
    return FIELD_ROLES.has(String(el.getAttribute('role') || '').trim().split(/\s+/)[0].toLowerCase());
  }

  function isButtonLike(el) {
    const t = (el.getAttribute('type') || '').toLowerCase();
    return el.tagName === 'BUTTON' || (el.tagName === 'INPUT' && (BUTTON_INPUTS.has(t) || t === 'image'))
      || /^button\b/i.test(el.getAttribute('role') || '');
  }

  function v3(policy, vw, vh, reject) {
    const { list, truncated } = deepElements(V3_SCAN);
    const k = { SURFACE: [], MEDIA: [], FRAME: [], CONTROL: [], FIELD: [], TEXT: [], LABEL: [] };
    const labelled = new Set(); // elementos cujo texto já virou LABEL (o texto deles não vira barra)
    const limitations = new Set();
    if (truncated) limitations.add('PAGE_TOO_LARGE');
    let tables = false;
    for (const el of list) {
      if (el === document.documentElement || el === document.body) continue;
      const tag = el.tagName;
      if (tag === 'SCRIPT' || tag === 'STYLE' || tag === 'TEMPLATE' || tag === 'HEAD') continue;
      const inTable = tag !== 'TABLE' && !!closestAcross(el.parentElement || el, 'table');
      if (tag === 'TABLE') tables = true;
      if (inTable && !isField(el) && !/^(IFRAME|FRAME|OBJECT|EMBED)$/.test(tag)) continue; // nada de dentro da tabela, exceto campos e frames
      const b = el.getBoundingClientRect();
      if (!(b.width > 0 && b.height > 0) || b.right <= 0 || b.bottom <= 0 || b.left >= vw || b.top >= vh) continue;
      if (!shown(el)) continue;
      const r = box(b, vw, vh);
      if (!r) continue;
      if (/^(IFRAME|FRAME|OBJECT|EMBED)$/.test(tag)) { k.FRAME.push({ kind: 'FRAME', ...r }); continue; }
      if (isField(el)) { k.FIELD.push({ kind: 'FIELD', ...r }); continue; }
      if (/^(IMG|CANVAS|VIDEO|PICTURE)$/.test(tag) || (tag.toLowerCase() === 'svg' && !(el.parentElement && el.parentElement.closest('svg')))) {
        if (el.parentElement && el.parentElement.closest('picture') && tag !== 'PICTURE') continue;
        if (b.width >= MEDIA_MIN && b.height >= MEDIA_MIN) k.MEDIA.push({ kind: 'MEDIA', ...r });
        continue;
      }
      if (el.closest('svg')) continue;
      const cs = getComputedStyle(el);
      // imagem de fundo (não gradiente) de tamanho médio: só o lugar
      if (cs.backgroundImage && /url\(/i.test(cs.backgroundImage) && b.width >= 48 && b.height >= 48 && r.width * r.height < vw * vh * 0.5) {
        k.MEDIA.push({ kind: 'MEDIA', ...r });
      }
      const buttonLike = isButtonLike(el);
      if (buttonLike) k.CONTROL.push({ kind: 'CONTROL', ...r });
      if (el.matches(CONTAINERS) || tag === 'TABLE') {
        if (b.width >= SURFACE_MIN_W && b.height >= SURFACE_MIN_H) {
          const own = rgba(cs.backgroundColor);
          const behind = backdrop(el);
          const distinct = own && own[3] >= 0.5 && Math.abs(luminance(own) - luminance(behind)) > 0.01;
          if (distinct || hasBorder(cs) || tag === 'TABLE') k.SURFACE.push({ kind: 'SURFACE', tone: tone(distinct ? own : behind), ...r });
        }
      }
      // rótulo de interface: mesmos portadores da v1/v2 (isCarrier) e input de botão com value
      const t = (el.getAttribute('type') || '').toLowerCase();
      const inputButton = tag === 'INPUT' && BUTTON_INPUTS.has(t);
      if (!inputButton && !policy.isCarrier(tag.toLowerCase(), el.getAttribute('role'), el.hasAttribute('href'))) continue;
      if (inTable) continue;
      const raw = inputButton ? el.value : el.textContent;
      const text = policy.interfaceText(raw || '');
      if (!text) {
        if (raw && raw.trim()) reject('TEXT_NOT_INTERFACE');
        continue;
      }
      if (b.left < 0 || b.top < 0 || b.right > vw || b.bottom > vh) { reject('OUTSIDE_VIEWPORT'); continue; }
      // fidelidade: rótulo coberto (ex.: diálogo por cima) não é desenhado — a raiz certa responde dentro de shadow DOM
      const scope = el.getRootNode && el.getRootNode() !== document && el.getRootNode().elementFromPoint ? el.getRootNode() : document;
      const top = scope.elementFromPoint(b.left + b.width / 2, b.top + b.height / 2);
      if (!top || !(top === el || el.contains(top))) { reject('COVERED'); continue; }
      const size = Math.max(8, Math.min(40, Math.round(parseFloat(cs.fontSize) || 14)));
      const weight = (parseInt(cs.fontWeight, 10) || 400) >= 600 ? 'BOLD' : 'NORMAL';
      // botão: o Studio desenha o CONTROL claro por baixo — tinta escura (a do fundo da página ficaria ilegível)
      k.LABEL.push({ kind: 'LABEL', ...r, text, size, weight, ink: buttonLike ? 'DARK' : inkFor(el), align: buttonLike || cs.textAlign === 'center' ? 'CENTER' : 'START' });
      labelled.add(el);
    }
    // texto que não é rótulo: barras neutras quantizadas (o comprimento exato não sai; o texto, nunca)
    let scannedText = 0;
    const roots = [document.body || document.documentElement];
    for (const el of list) {
      const sr = shadowOf(el);
      if (sr) roots.push(sr);
    }
    const range = document.createRange();
    outer: for (const start of roots) {
      const walker = document.createTreeWalker(start, NodeFilter.SHOW_TEXT);
      for (let n = walker.nextNode(); n; n = walker.nextNode()) {
        if (++scannedText > V3_TEXT_SCAN) { limitations.add('TEXT_SIMPLIFIED'); break outer; }
        if (!n.nodeValue || !n.nodeValue.trim()) continue;
        const p = n.parentElement;
        if (!p || SKIP_TEXT.has(p.tagName) || closestAcross(p, 'table, svg')) continue;
        // texto de rótulo já desenhado, ou CONTEÚDO DE CAMPO (contenteditable, role textbox…): nunca vira barra
        let skip = false;
        for (let a = p; a; a = a.parentElement || (a.getRootNode && a.getRootNode() !== document ? a.getRootNode().host || null : null)) {
          if (labelled.has(a) || isField(a)) { skip = true; break; }
        }
        if (skip || !shown(p)) continue;
        range.selectNodeContents(n);
        const ink = inkFor(p);
        for (const lr of range.getClientRects()) {
          if (lr.width < 1 || lr.height < 4 || lr.right <= 0 || lr.bottom <= 0 || lr.left >= vw || lr.top >= vh) continue;
          const x = Math.max(0, Math.floor(lr.left / 4) * 4);
          let w = Math.ceil((Math.min(vw, lr.right) - x) / QUANTUM) * QUANTUM;
          if (x + w > vw) w = Math.floor((vw - x) / QUANTUM) * QUANTUM;
          if (w < QUANTUM) continue;
          const h = Math.max(4, Math.round(lr.height * 0.45));
          const y = Math.max(0, Math.min(vh - h, Math.round(lr.top + (lr.height - h) / 2)));
          if (k.TEXT.length >= V3_MAX.TEXT) { limitations.add('TEXT_SIMPLIFIED'); break outer; }
          k.TEXT.push({ kind: 'TEXT', x, y, width: w, height: h, ink });
        }
      }
    }
    range.detach();
    if (tables) limitations.add('TABLE_CONTENT_HIDDEN');
    for (const kind of Object.keys(k)) {
      if (k[kind].length > V3_MAX[kind]) limitations.add(kind === 'TEXT' ? 'TEXT_SIMPLIFIED' : 'SOME_REGIONS_OMITTED');
    }
    const regions = ['SURFACE', 'MEDIA', 'FRAME', 'CONTROL', 'FIELD', 'TEXT', 'LABEL'].flatMap((kind) => cap(k[kind], V3_MAX[kind]));
    return { regions, hasFrames: k.FRAME.length > 0 || !!document.querySelector('iframe, frame, object, embed'), limitations: [...limitations] };
  }

  /*
   * NEKKORA-STUDIO-013 (revisão da usuária) — plano da nekkora-visual/4: PRINT REAL com o texto de dados mascarado. O que
   * sai são só as MÁSCARAS (px CSS, para fora): o service worker captura a tela e pinta as faixas por cima. Fica REAL o que
   * não carrega dado: fundos, cores, bordas, ícones e imagens pequenas, botões e rótulos de interface cujo texto INTEIRO é
   * do vocabulário. É coberto:
   *   BLOCK  tabela inteira; iframe/frame/object/embed; canvas; vídeo; imagem grande (> 240 px num lado) ou quebrada (o
   *          texto alternativo apareceria); SVG com <text>/<foreignObject>; fundo com url() maior que 64 px; elemento com
   *          pseudo-elemento de texto (::before/::after); input de botão cujo value não é do vocabulário;
   *   FIELD  todo campo (valor, placeholder e autofill nunca aparecem);
   *   TEXT   cada linha de texto que não é rótulo de interface, com 2 px de folga (sombra, acento, descendentes).
   * Na dúvida, cobre: um rótulo encoberto, fora do viewport ou além dos tetos vira TEXT. Acima dos tetos de máscaras a
   * captura FALHA (TOO_MANY_REGIONS) — nunca sai uma linha sem máscara.
   */
  const V4_MAX = { BLOCK: 96, FIELD: 128, TEXT: 600 };
  const PAD = 2;

  function padded(r, vw, vh, p) {
    const x = Math.max(0, Math.floor(r.left - p));
    const y = Math.max(0, Math.floor(r.top - p));
    const x2 = Math.min(vw, Math.ceil(r.right + p));
    const y2 = Math.min(vh, Math.ceil(r.bottom + p));
    return x2 - x >= 1 && y2 - y >= 1 ? { x, y, width: x2 - x, height: y2 - y } : null;
  }

  function pseudoContent(el) {
    return ['::before', '::after'].some((p) => {
      const c = getComputedStyle(el, p).content;
      return c && c !== 'none' && c !== 'normal' && c !== '""' && c !== "''" && !/^url\(/.test(c);
    });
  }

  function v4(policy, vw, vh, reject) {
    const { list, truncated } = deepElements(V3_SCAN * 2);
    const m = { BLOCK: [], FIELD: [], TEXT: [] };
    const covered = new Set(); // elementos cujo conteúdo já está inteiro sob um BLOCK ou FIELD
    const real = new Set(); // rótulos de interface que ficam como na página
    const limitations = new Set();
    let overflow = false;
    if (truncated) overflow = true; // página maior do que o que se consegue mapear: falha, nunca print parcial
    const block = (el, r) => {
      m.BLOCK.push({ kind: 'BLOCK', ...r });
      covered.add(el);
    };
    for (const el of list) {
      if (el === document.documentElement || el === document.body) continue;
      const tag = el.tagName;
      if (tag === 'SCRIPT' || tag === 'STYLE' || tag === 'TEMPLATE' || tag === 'HEAD') continue;
      const b = el.getBoundingClientRect();
      if (!(b.width > 0 && b.height > 0) || b.right <= 0 || b.bottom <= 0 || b.left >= vw || b.top >= vh) continue;
      let anc = false;
      for (let a = el.parentElement || (el.getRootNode() !== document ? el.getRootNode().host : null); a;
        a = a.parentElement || (a.getRootNode && a.getRootNode() !== document ? a.getRootNode().host || null : null)) {
        if (covered.has(a)) { anc = true; break; }
      }
      if (anc) continue; // já está sob uma máscara inteira
      if (!shown(el)) continue;
      const r = box(b, vw, vh);
      if (!r) continue;
      const lower = tag.toLowerCase();
      if (tag === 'TABLE') { block(el, r); limitations.add('TABLE_CONTENT_HIDDEN'); continue; }
      if (/^(IFRAME|FRAME|OBJECT|EMBED)$/.test(tag)) { block(el, r); limitations.add('FRAMES_HIDDEN'); continue; }
      if (isField(el)) { m.FIELD.push({ kind: 'FIELD', ...r }); covered.add(el); continue; }
      if (tag === 'CANVAS' || tag === 'VIDEO') { block(el, r); continue; }
      if (tag === 'IMG' && (!el.complete || el.naturalWidth === 0 || b.width > 240 || b.height > 240)) { block(el, r); continue; }
      if (lower === 'svg' && (el.querySelector('text, foreignObject') || b.width > 240 || b.height > 240)) { block(el, r); continue; }
      const cs = getComputedStyle(el);
      if (cs.backgroundImage && /url\(/i.test(cs.backgroundImage) && (b.width > 64 || b.height > 64)) { block(el, r); continue; }
      if (pseudoContent(el)) { block(el, padded(b, vw, vh, PAD) || r); continue; }
      const t = (el.getAttribute('type') || '').toLowerCase();
      if (tag === 'INPUT' && (BUTTON_INPUTS.has(t) || t === 'image')) {
        // o texto do botão é o value (não é nó de texto): do vocabulário fica real; senão, o botão inteiro é coberto
        if (t === 'image' || !policy.interfaceText(el.value || '')) block(el, r);
        else real.add(el);
        continue;
      }
      if (!policy.isCarrier(lower, el.getAttribute('role'), el.hasAttribute('href'))) continue;
      const text = policy.interfaceText(el.textContent || '');
      if (!text) continue; // o texto dele vira TEXT logo abaixo
      if (b.left < 0 || b.top < 0 || b.right > vw || b.bottom > vh) continue; // parcialmente fora: mascarado como texto
      const scope = el.getRootNode && el.getRootNode() !== document && el.getRootNode().elementFromPoint ? el.getRootNode() : document;
      const top = scope.elementFromPoint(b.left + b.width / 2, b.top + b.height / 2);
      if (!top || !(top === el || el.contains(top))) { reject('COVERED'); continue; }
      real.add(el);
    }
    // texto que não é rótulo de interface: cada linha coberta, com folga
    const roots = [document.body || document.documentElement];
    for (const el of list) {
      const sr = shadowOf(el);
      if (sr) roots.push(sr);
    }
    const range = document.createRange();
    let scanned = 0;
    outer: for (const start of roots) {
      const walker = document.createTreeWalker(start, NodeFilter.SHOW_TEXT);
      for (let n = walker.nextNode(); n; n = walker.nextNode()) {
        if (++scanned > V3_TEXT_SCAN * 2) { overflow = true; break outer; }
        if (!n.nodeValue || !n.nodeValue.trim()) continue;
        const p = n.parentElement;
        if (!p || SKIP_TEXT.has(p.tagName)) continue;
        let skip = false;
        for (let a = p; a; a = a.parentElement || (a.getRootNode && a.getRootNode() !== document ? a.getRootNode().host || null : null)) {
          if (real.has(a) || covered.has(a)) { skip = true; break; }
        }
        if (skip) continue;
        range.selectNodeContents(n);
        const ink = inkFor(p);
        for (const lr of range.getClientRects()) {
          if (lr.width <= 0 || lr.height <= 0 || lr.right <= 0 || lr.bottom <= 0 || lr.left >= vw || lr.top >= vh) continue;
          const r = padded(lr, vw, vh, PAD);
          if (!r) continue;
          m.TEXT.push({ kind: 'TEXT', ...r, ink });
          if (m.TEXT.length > V4_MAX.TEXT) { overflow = true; break outer; }
        }
      }
    }
    range.detach();
    if (m.BLOCK.length > V4_MAX.BLOCK || m.FIELD.length > V4_MAX.FIELD) overflow = true;
    return { regions: [...m.BLOCK, ...m.FIELD, ...m.TEXT], incomplete: overflow, labels: real.size, limitations: [...limitations],
      hasFrames: !!document.querySelector('iframe, frame, object, embed') };
  }

  /*
   * Revisão da usuária (19/10/2026) — plano da nekkora-visual/5: PRINT INTEGRAL. Tudo fica como na página, menos:
   *   FIELD  todo campo de senha, sempre, com folga: type=password, autocomplete de senha, ou nome/id de senha (um campo
   *          "mostrar senha" vira type=text e continua coberto). Inclui shadow roots; não exige estar visível — cobrir um
   *          campo invisível só esconde a moldura dele;
   *   BLOCK  iframe/frame/object/embed, só quando o service worker não confirmou que nenhum frame mostra senha (blockFrames).
   * Página maior do que o mapeável ou acima dos tetos: a captura FALHA (nunca uma senha sem máscara).
   */
  const SECRET_NAME = /senha|password|passwd|pwd|passcode/i;

  function isSecret(el) {
    if (el.tagName !== 'INPUT') return false;
    const t = (el.getAttribute('type') || '').toLowerCase();
    if (t === 'password') return true;
    if (BUTTON_INPUTS.has(t) || t === 'image' || t === 'hidden' || t === 'checkbox' || t === 'radio') return false;
    if (/password/i.test(el.getAttribute('autocomplete') || '')) return true;
    return SECRET_NAME.test(el.getAttribute('name') || '') || SECRET_NAME.test(el.id || '');
  }

  function v5(vw, vh, blockFrames) {
    const { list, truncated } = deepElements(V3_SCAN * 2);
    const m = { BLOCK: [], FIELD: [] };
    for (const el of list) {
      const frame = /^(IFRAME|FRAME|OBJECT|EMBED)$/.test(el.tagName);
      if (!(frame ? blockFrames : isSecret(el))) continue;
      const b = el.getBoundingClientRect();
      if (!(b.width > 0 && b.height > 0) || b.right <= 0 || b.bottom <= 0 || b.left >= vw || b.top >= vh) continue;
      const r = padded(b, vw, vh, PAD);
      if (r) m[frame ? 'BLOCK' : 'FIELD'].push({ kind: frame ? 'BLOCK' : 'FIELD', ...r });
    }
    const incomplete = !!truncated || m.BLOCK.length > V4_MAX.BLOCK || m.FIELD.length > V4_MAX.FIELD;
    return { regions: [...m.BLOCK, ...m.FIELD], incomplete, limitations: m.BLOCK.length ? ['FRAMES_HIDDEN'] : [],
      hasFrames: !!document.querySelector('iframe, frame, object, embed') };
  }

  /** Campos de senha neste documento (frame), inclusive em shadow roots; mapa truncado conta como "tem senha". */
  function secrets() {
    const { list, truncated } = deepElements(V3_SCAN * 2);
    if (truncated) return Number.MAX_SAFE_INTEGER;
    let n = 0;
    for (const el of list) if (isSecret(el)) n++;
    return n;
  }

  NS.pddPlan = Object.freeze({ v2, v3, v4, v5, secrets });
})(globalThis);
