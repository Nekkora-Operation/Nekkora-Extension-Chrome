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
2. troca do modo no registro da gravação;
3. `cs.recorder.mode` em cada documento: os ouvintes antigos saem antes de os novos entrarem, e nenhum sensor
   antigo fica ativo.

Uma interação parcialmente registrada (ex.: preenchimento aberto) é **consolidada** no modo anterior, com
`trigger: MODE_CHANGE`.

## Evidência

- Node: `interaction-normalizer.test.js` (R11; troca de modo consolida antes) e `studio-steps.test.js` (S003-005).
- Chrome real, `Studio003InteractionsIT.s003CaptureOnly`:
  - o clique e o Enter em "Adicionar linha" não alteram a página (contador continua em 0);
  - as quatro propostas saem com `observation: PROPOSED`;
  - depois da alternância, o mesmo botão funciona (contador 1) e a etapa sai com `INTERACTION_OBSERVED`.
