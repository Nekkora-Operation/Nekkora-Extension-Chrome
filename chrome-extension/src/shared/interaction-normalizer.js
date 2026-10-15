/*
 * InteractionNormalizer (STUDIO-003) — transforma OBSERVAÇÕES do sensor de interações em INTERAÇÕES funcionais,
 * de forma determinística (sem IA, sem heurística de tempo como critério único).
 *
 * Uma ação do usuário produz vários eventos (pointerdown, mousedown, mouseup, click, focus, input…, change, blur):
 * o normalizador agrupa os que pertencem à mesma operação e devolve UMA interação. Ele não vê o DOM: o sensor já
 * resolveu o alvo funcional (target.key = id temporário do nó, target.control = tipo de controle) e entrega somente
 * dados sanitizados — nenhum valor digitado chega aqui.
 *
 * Observação (entrada):
 *   { type: 'click'|'input'|'change'|'focusout'|'keydown'|'submit'|'navigation'|'capture', trusted, at,
 *     target: { key, control, sensitive, checked, option:{label,value}, comboboxKey, viaLabel }, detail, key, submitterKey }
 *   control: TEXT | CHECKBOX | RADIO | SELECT | CLICKABLE | COMBOBOX_OPTION | AMBIGUOUS | NONE
 * Interação (saída, onInteraction):
 *   { localSeq, action: CLICK|FILL|SELECT_OPTION|CHECK|UNCHECK|PRESS_KEY|UNKNOWN, targetKey, observation:
 *     INTERACTION_OBSERVED|PROPOSED, firstAt, lastAt, events, trigger, option, key, sensitive, evidence[], notes[] }
 * Evidência posterior (onEvidence): { localSeq, evidence } — ex.: FORM_SUBMITTED depois de um clique já emitido.
 *
 * Regras (cada uma tem teste em test/interaction-normalizer.test.js):
 *  R1 clique em CLICKABLE ⇒ CLICK; cliques repetidos (detail>1) no mesmo alvo fundem-se (DOUBLE_CLICK).
 *  R2 clique em TEXT só posiciona o cursor: é absorvido pelo preenchimento seguinte (sem etapa se não houver edição).
 *  R3 input em TEXT abre um preenchimento; consolida em focusout, change, Enter, interação em outro alvo, navegação
 *     ou flush (pausa, modo, sessão, finalização). Uma etapa por preenchimento, nunca por caractere.
 *  R4 Enter confiável em TEXT ⇒ consolida o preenchimento e gera PRESS_KEY (Enter); o submit seguinte é evidência.
 *  R5 checkbox: change ⇒ CHECK/UNCHECK pelo estado final; o clique no controle ou na label associada é absorvido.
 *     radio: change marcado ⇒ CHECK.
 *  R6 select: change ⇒ SELECT_OPTION com a opção (label/valor já sanitizados); cliques no select são absorvidos.
 *  R7 submit nunca vira etapa: é evidência do CLICK/PRESS_KEY que o provocou; sem gatilho confiável ⇒ descartado.
 *  R8 evento não confiável (isTrusted=false) ⇒ descartado (UNTRUSTED), contado.
 *  R9 opção de combobox personalizado com relação comprovada (aria-controls/owns) ⇒ SELECT_OPTION no combobox.
 *  R10 alvo ambíguo (sinal de clicável sem semântica) ⇒ UNKNOWN sem escolher ancestral; sem sinal ⇒ ignorado.
 *  R11 CAPTURE_ONLY: a captura (clique, Enter ou Espaço bloqueados) gera uma PROPOSTA pelo tipo de controle.
 */
(function (root) {
  const DISCARD_REASONS = ['UNTRUSTED', 'NON_INTERACTIVE', 'FOCUS_ONLY', 'PROGRAMMATIC_SUBMIT', 'ABSORBED', 'AFTER_STOP'];

  function createNormalizer(options) {
    const onInteraction = options.onInteraction;
    const onEvidence = options.onEvidence || (() => {});
    const doubleClickMs = options.doubleClickMs || 600;
    const submitWindowMs = options.submitWindowMs || 1500;

    let mode = options.mode || 'CAPTURE_AND_INTERACT';
    let active = true;
    let localSeq = 0;
    let fill = null; // { targetKey, firstAt, lastAt, events, sensitive, focusClick }
    let focusClick = null; // { targetKey, at } — clique de posicionamento ainda não absorvido
    let lastEmitted = null; // { localSeq, action, targetKey, at, formKey }
    let absorbNextClickOn = null; // { key, until } — clique técnico depois de change em checkbox/radio (label)
    const stats = { observed: 0, interactions: 0, consolidated: 0, discarded: {} };
    for (const r of DISCARD_REASONS) {
      stats.discarded[r] = 0;
    }

    function discard(reason) {
      stats.discarded[reason] = (stats.discarded[reason] || 0) + 1;
    }

    function emit(i) {
      const out = {
        localSeq: ++localSeq,
        action: i.action,
        targetKey: i.targetKey,
        observation: mode === 'CAPTURE_ONLY' ? 'PROPOSED' : 'INTERACTION_OBSERVED',
        firstAt: i.firstAt,
        lastAt: i.lastAt,
        events: i.events || 1,
        trigger: i.trigger || null,
        option: i.option || null,
        key: i.key || null,
        sensitive: !!i.sensitive,
        evidence: i.evidence || [],
        notes: i.notes || [],
      };
      stats.interactions++;
      stats.consolidated += Math.max(0, out.events - 1);
      lastEmitted = { localSeq: out.localSeq, action: out.action, targetKey: out.targetKey, at: out.lastAt, formKey: i.formKey || null };
      onInteraction(out);
      return out;
    }

    /** Consolida o preenchimento aberto (R3). */
    function closeFill(trigger) {
      if (!fill) {
        return null;
      }
      const f = fill;
      fill = null;
      return emit({ action: 'FILL', targetKey: f.targetKey, firstAt: f.firstAt, lastAt: f.lastAt, events: f.events + (f.focusClick ? 1 : 0),
        trigger, sensitive: f.sensitive, formKey: f.formKey, evidence: f.focusClick ? ['FOCUS_CLICK_ABSORBED'] : [] });
    }

    function closeFocusClick() {
      if (focusClick) {
        focusClick = null;
        discard('FOCUS_ONLY'); // clique só de posicionamento, sem edição: não é uma etapa
      }
    }

    /** Qualquer interação em outro alvo consolida o que estava aberto. */
    function settleOthers(targetKey, trigger) {
      if (fill && fill.targetKey !== targetKey) {
        closeFill(trigger);
      }
      if (focusClick && focusClick.targetKey !== targetKey) {
        closeFocusClick();
      }
    }

    function onClick(o) {
      const t = o.target;
      if (absorbNextClickOn && absorbNextClickOn.key === t.key && o.at <= absorbNextClickOn.until) {
        absorbNextClickOn = null;
        discard('ABSORBED');
        return;
      }
      settleOthers(t.key, 'OTHER_TARGET');
      switch (t.control) {
        case 'TEXT':
          if (!fill) {
            focusClick = { targetKey: t.key, at: o.at }; // R2
          } else {
            fill.events++;
          }
          return;
        case 'CHECKBOX':
        case 'RADIO':
        case 'SELECT':
          discard('ABSORBED'); // R5/R6: a operação vem do change
          return;
        case 'CLICKABLE':
        case 'COMBOBOX_OPTION': {
          // R1: clique repetido (duplo) no mesmo alvo funde-se ao anterior.
          if ((o.detail || 1) > 1 && lastEmitted && lastEmitted.action !== 'FILL' && lastEmitted.targetKey === (t.comboboxKey || t.key)
              && o.at - lastEmitted.at <= doubleClickMs) {
            stats.consolidated++;
            onEvidence({ localSeq: lastEmitted.localSeq, evidence: 'DOUBLE_CLICK' });
            return;
          }
          if (t.control === 'COMBOBOX_OPTION' && t.comboboxKey) { // R9
            emit({ action: 'SELECT_OPTION', targetKey: t.comboboxKey, firstAt: o.at, lastAt: o.at, option: t.option, trigger: 'OPTION_CLICK',
              evidence: ['CUSTOM_COMBOBOX'] });
            return;
          }
          emit({ action: 'CLICK', targetKey: t.key, firstAt: o.at, lastAt: o.at, formKey: t.formKey,
            evidence: o.viaKeyboard ? ['VIA_KEYBOARD'] : [], notes: t.control === 'COMBOBOX_OPTION' ? ['OPTION_WITHOUT_PROVEN_COMBOBOX'] : [] });
          return;
        }
        case 'AMBIGUOUS':
          emit({ action: 'UNKNOWN', targetKey: t.key, firstAt: o.at, lastAt: o.at, notes: ['AMBIGUOUS_TARGET'] }); // R10
          return;
        default:
          discard('NON_INTERACTIVE');
      }
    }

    function onInput(o) {
      const t = o.target;
      if (t.control !== 'TEXT') {
        return; // input de checkbox/select/range: a operação vem do change
      }
      settleOthers(t.key, 'OTHER_TARGET');
      if (fill && fill.targetKey === t.key) {
        fill.events++;
        fill.lastAt = o.at;
        return;
      }
      const absorbed = focusClick && focusClick.targetKey === t.key;
      const firstAt = absorbed ? focusClick.at : o.at;
      focusClick = null;
      fill = { targetKey: t.key, firstAt, lastAt: o.at, events: 1, sensitive: !!t.sensitive,
        focusClick: absorbed, formKey: t.formKey };
    }

    function onChange(o) {
      const t = o.target;
      if (t.control === 'TEXT') {
        if (fill && fill.targetKey === t.key) {
          fill.events++;
          fill.lastAt = o.at;
          closeFill('CHANGE');
        }
        return;
      }
      settleOthers(t.key, 'OTHER_TARGET');
      if (t.control === 'CHECKBOX') {
        absorbNextClickOn = t.viaLabel ? { key: t.key, until: o.at + 50 } : null;
        emit({ action: t.checked ? 'CHECK' : 'UNCHECK', targetKey: t.key, firstAt: o.at, lastAt: o.at, events: 2, trigger: 'CHANGE',
          evidence: t.viaLabel ? ['VIA_LABEL'] : [] });
        return;
      }
      if (t.control === 'RADIO') {
        if (!t.checked) {
          discard('ABSORBED');
          return;
        }
        emit({ action: 'CHECK', targetKey: t.key, firstAt: o.at, lastAt: o.at, events: 2, trigger: 'CHANGE', evidence: t.viaLabel ? ['VIA_LABEL'] : [] });
        return;
      }
      if (t.control === 'SELECT') {
        emit({ action: 'SELECT_OPTION', targetKey: t.key, firstAt: o.at, lastAt: o.at, events: 2, trigger: 'CHANGE', option: t.option || null,
          notes: t.option ? [] : ['OPTION_NOT_IDENTIFIED'] });
        return;
      }
      discard('NON_INTERACTIVE');
    }

    function onKeydown(o) {
      const t = o.target;
      if (o.key !== 'Enter' || t.control !== 'TEXT') {
        return; // somente Enter em campo de texto é uma operação elegível (R4)
      }
      settleOthers(t.key, 'OTHER_TARGET');
      if (fill && fill.targetKey === t.key) {
        closeFill('ENTER');
      } else {
        closeFocusClick();
      }
      emit({ action: 'PRESS_KEY', targetKey: t.key, firstAt: o.at, lastAt: o.at, key: 'Enter', formKey: t.formKey, trigger: 'KEYDOWN' });
    }

    function onSubmit(o) {
      // R7: o submit só é evidência do gatilho confiável que o antecedeu (clique no botão ou Enter).
      if (lastEmitted && (lastEmitted.action === 'CLICK' || lastEmitted.action === 'PRESS_KEY') && o.at - lastEmitted.at <= submitWindowMs
          && (!o.formKey || !lastEmitted.formKey || o.formKey === lastEmitted.formKey)) {
        onEvidence({ localSeq: lastEmitted.localSeq, evidence: 'FORM_SUBMITTED' });
        return;
      }
      discard('PROGRAMMATIC_SUBMIT');
    }

    /** R11: captura sem executar (CAPTURE_ONLY) — proposta pelo tipo de controle. */
    function onCapture(o) {
      const t = o.target;
      settleOthers(t.key, 'OTHER_TARGET');
      const via = o.viaKeyboard ? ['VIA_KEYBOARD'] : [];
      switch (t.control) {
        case 'TEXT':
          emit({ action: 'FILL', targetKey: t.key, firstAt: o.at, lastAt: o.at, sensitive: t.sensitive, evidence: via });
          return;
        case 'CHECKBOX':
          emit({ action: t.checked ? 'UNCHECK' : 'CHECK', targetKey: t.key, firstAt: o.at, lastAt: o.at, evidence: via });
          return;
        case 'RADIO':
          emit({ action: 'CHECK', targetKey: t.key, firstAt: o.at, lastAt: o.at, evidence: via });
          return;
        case 'SELECT':
          emit({ action: 'SELECT_OPTION', targetKey: t.key, firstAt: o.at, lastAt: o.at, evidence: via, notes: ['OPTION_NOT_CHOSEN'] });
          return;
        case 'COMBOBOX_OPTION':
          if (t.comboboxKey) {
            emit({ action: 'SELECT_OPTION', targetKey: t.comboboxKey, firstAt: o.at, lastAt: o.at, option: t.option, evidence: via.concat('CUSTOM_COMBOBOX') });
            return;
          }
          emit({ action: 'CLICK', targetKey: t.key, firstAt: o.at, lastAt: o.at, evidence: via, notes: ['OPTION_WITHOUT_PROVEN_COMBOBOX'] });
          return;
        case 'CLICKABLE':
          emit({ action: 'CLICK', targetKey: t.key, firstAt: o.at, lastAt: o.at, evidence: via });
          return;
        case 'AMBIGUOUS':
          emit({ action: 'UNKNOWN', targetKey: t.key, firstAt: o.at, lastAt: o.at, evidence: via, notes: ['AMBIGUOUS_TARGET'] });
          return;
        default:
          discard('NON_INTERACTIVE');
      }
    }

    return {
      /** Entrega uma observação. Devolve false quando ela foi descartada logo na entrada. */
      observe(o) {
        if (!active) {
          discard('AFTER_STOP');
          return false;
        }
        stats.observed++;
        if (o.trusted === false) {
          discard('UNTRUSTED'); // R8
          return false;
        }
        switch (o.type) {
          case 'click':
            if (mode === 'CAPTURE_ONLY') {
              onCapture(o);
            } else {
              onClick(o);
            }
            break;
          case 'capture':
            onCapture(o);
            break;
          case 'input':
            if (mode !== 'CAPTURE_ONLY') {
              onInput(o);
            }
            break;
          case 'change':
            if (mode !== 'CAPTURE_ONLY') {
              onChange(o);
            }
            break;
          case 'focusout':
            if (fill && fill.targetKey === o.target.key) {
              closeFill('FOCUSOUT');
            } else if (focusClick && focusClick.targetKey === o.target.key) {
              closeFocusClick();
            }
            break;
          case 'keydown':
            if (mode !== 'CAPTURE_ONLY') {
              onKeydown(o);
            }
            break;
          case 'submit':
            onSubmit(o);
            break;
          case 'navigation':
            closeFill('NAVIGATION');
            closeFocusClick();
            break;
          default:
            break;
        }
        return true;
      },
      /** Consolida tudo o que está aberto (pausa, troca de modo, troca de sessão, finalização, saída da página). */
      flush(trigger) {
        const out = closeFill(trigger || 'FLUSH');
        closeFocusClick();
        return out;
      },
      /** Troca de modo: consolida antes (regra determinística) e passa a aplicar as regras do novo modo. */
      setMode(next) {
        this.flush('MODE_CHANGE');
        mode = next;
      },
      /** Parada: com discard=true (cancelamento) o que estava aberto é descartado; senão é consolidado. */
      stop(discardOpen) {
        if (discardOpen) {
          if (fill) {
            discard('AFTER_STOP');
          }
          fill = null;
          focusClick = null;
        } else {
          this.flush('STOP');
        }
        active = false;
      },
      /** Há um preenchimento aberto (usado para declarar perda se o documento sumir antes da consolidação). */
      openInteraction() {
        return fill ? { targetKey: fill.targetKey, action: 'FILL', since: fill.firstAt } : null;
      },
      mode() {
        return mode;
      },
      stats() {
        return JSON.parse(JSON.stringify(stats));
      },
    };
  }

  const NekkoraInteractionNormalizer = { createNormalizer, DISCARD_REASONS };
  if (root.NekkoraCS) {
    root.NekkoraCS.normalizer = NekkoraInteractionNormalizer;
  }
  root.NekkoraInteractionNormalizer = NekkoraInteractionNormalizer;
  if (typeof module !== 'undefined' && module.exports) {
    module.exports = NekkoraInteractionNormalizer;
  }
})(globalThis);
