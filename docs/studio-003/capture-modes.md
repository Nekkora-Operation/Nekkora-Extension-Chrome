# STUDIO-003 — Modos de captura

| | CAPTURE_ONLY ("Capturar") | CAPTURE_AND_INTERACT ("Capturar e interagir") |
|---|---|---|
| Ouvintes | interceptam na fase de captura (`preventDefault` + `stopImmediatePropagation`) os eventos `pointerdown/up`, `mousedown/up`, `click`, `dblclick`, `auxclick`, `submit`, `touchstart/end`, como o Inspector | **passivos** (`capture: true, passive: true`): nada é bloqueado nem alterado |
| Ação da página | **não** acontece | acontece normalmente |
| Teclado | Enter e Espaço no elemento focado selecionam (são bloqueados, inclusive `keypress`/`keyup`); Tab, Escape e atalhos ficam livres | só Enter (operação) e Espaço/setas (ativação de checkbox/radio) são lidos; o resto da digitação não é lido |
| Destaque | contorno no elemento sob o cursor ("Recorder · tag") | nenhum |
| Etapa | `observation: PROPOSED` — a ação **não** foi executada | `observation: INTERACTION_OBSERVED` — a interação foi encaminhada à página; isso **não** comprova o resultado de negócio |

## Alternância

A alternância é pedida pelo Floating Recorder (`studio.recording.mode`), e o modo só vale depois da confirmação da
extensão. A sequência é determinística:

1. consolidação no modo anterior (`flush` e espera das interações);
2. `cs.recorder.mode` no documento principal: os ouvintes antigos saem antes de os novos entrarem, e nenhum sensor
   antigo fica ativo;
3. **confirmação obrigatória**: o sensor principal precisa responder `{active: true, epoch, mode}` com a época
   vigente e o modo pedido. Só então o modo da gravação muda;
4. `cs.recorder.mode` em cada iframe. Iframe sem confirmação tem o sensor desligado (um iframe em modo desconhecido
   não pode executar cliques durante um CAPTURE_ONLY) e conta como cobertura parcial (`PARTIAL_COVERAGE`).

Sem a confirmação do sensor principal (mensagem perdida, recusa ou `{active: false}`), **nada é confirmado**:

- a extensão religa os sensores no modo anterior (época nova) e responde `CAPTURE_FAILED` com o motivo
  `SENSOR_NOT_CONFIRMED`. O Studio continua apresentando o modo anterior, que é o modo ativo no documento;
- se nem o modo anterior for confirmado, a gravação vai para `DEGRADED` (`SENSOR_NOT_CONFIRMED`): a captura de
  ações fica interrompida e declarada, nunca num modo suposto.

A mesma regra vale para o início com `steps/1` (início recusado e captura liberada), para a retomada e para a troca
de sessão. Cada etapa leva o modo informado pelo próprio sensor que a observou.

Uma interação parcialmente registrada (ex.: preenchimento aberto) é **consolidada** no modo anterior, com
`trigger: MODE_CHANGE`.

## Evidência

- Node: `interaction-normalizer.test.js` (R11; troca de modo consolida antes) e `studio-steps.test.js` (S003-005 e
  os testes `REV-P1a` da revisão técnica: `cs.recorder.mode` recusado e `{active: false}`, sem restauração possível,
  início com o sensor recusado ou inativo, iframe sem confirmação).
- Chrome real, `Studio003InteractionsIT.s003CaptureOnly`:
  - o clique e o Enter em "Adicionar linha" não alteram a página (contador continua em 0);
  - as quatro propostas saem com `observation: PROPOSED`;
  - depois da alternância, o mesmo botão funciona (contador 1) e a etapa sai com `INTERACTION_OBSERVED`.
