# STUDIO-003 — Sensor de interações e normalizador (lado da extensão)

Código:

- `chrome-extension/src/content/interaction-recorder.js`: sensor, um por documento acompanhado.
- `chrome-extension/src/shared/interaction-normalizer.js`: normalizador puro, testado em Node.
- `chrome-extension/src/background/studio-steps.js`: pipeline de etapas no service worker.
- Integração em `studio-recording.js` e roteamento em `service-worker.js`.

## Responsabilidades (D-STUDIO-003-01)

| Peça | Faz | Não faz |
|---|---|---|
| Sensor (content script) | observa os eventos do usuário, resolve o **alvo funcional** no DOM e entrega observações sanitizadas | não lê valores digitados, não dispara eventos, não altera a página (em CAPTURE_AND_INTERACT) |
| Normalizador | agrupa eventos da mesma operação e produz **uma** interação por operação, com regras determinísticas | não vê o DOM nem a rede |
| Pipeline (service worker) | atribui a sequência, correlaciona o elemento com o motor Java, monta e entrega a etapa, retém até a confirmação | não interpreta regras de negócio |
| Motor Java (Native Host) | classifica o elemento, gera e valida localizadores, registra a identidade lógica, calcula a trajetória | — |
| Studio | ordena, roteia por sessão lógica, transforma em `StepDraft` e entrega ao `StepIntake` | não analisa DOM nem recalcula localizadores |

## Ciclo do sensor

O sensor só existe enquanto uma gravação do Studio **com a capacidade `steps/1`** está em RECORDING na aba. Ele é
ligado nos mesmos documentos que a inspeção usa:

- o documento principal;
- os iframes de outra origem permitida, anexados (`ATTACHED`).

Iframes de mesma origem são observados pela instância do documento pai (`frameWindows()`), exatamente como a captura.

| Momento | Ação |
|---|---|
| início confirmado | `cs.recorder.start {epoch, mode}` em cada documento (época nova) |
| novo documento / nova base (`afterBase`) | sensores religados com época nova; a época anterior continua aceita para a consolidação feita na saída da página |
| frame anexado depois (`frameAttached`) | sensor ligado no frame com a época vigente |
| troca de modo | `cs.recorder.flush` (consolidação) → espera as interações chegarem → `cs.recorder.mode` |
| pausa / finalização | `flush` → espera → `cs.recorder.stop` (a época é encerrada) |
| cancelamento | `cs.recorder.stop {discard:true}` (o que estava aberto é descartado) |
| degradação, concessão expirada | `stop` (nada continua sendo registrado em silêncio) |

**Épocas.** Cada ativação recebe uma época aleatória. Uma interação de época encerrada (chegando depois de pausar,
cancelar ou finalizar) é descartada e contada como `STALE_EPOCH`.

**Fronteira exata.** A resposta do `flush` e as mensagens `interaction` correm por canais diferentes, sem ordem
garantida entre elas. Por isso o `flush` devolve `lastSeq`, e o service worker espera ter recebido as interações até
essa sequência, por no máximo 1,5 s. É assim que a troca de modo, a rota de sessão e a finalização ficam exatas.

## Alvo funcional

O sensor percorre o caminho composto do evento (`composedPath`), que atravessa o Shadow DOM, e para no primeiro
elemento com semântica de controle, até 8 níveis:

- `input` por tipo (texto, checkbox, radio, botão);
- `textarea`, `select`, `button`, `summary`, `a[href]`;
- editor `contenteditable`;
- papéis ARIA `button`, `link`, `menuitem*`, `tab`, `switch`, `treeitem`, `checkbox`, `radio`, `combobox` e `option`;
- a `label` resolve para o controle associado.

Assim, o clique no ícone dentro de `<button id="consultar">` registra **o botão**. Sem semântica de controle, o
resultado depende de haver um sinal de clicável no próprio elemento (cursor pointer, `tabindex`, atributo `onclick`):

- com o sinal, o alvo é marcado **AMBÍGUO** e vira `UNKNOWN`, sem escolher um ancestral por palpite;
- sem o sinal, o clique é ignorado e contado como `NON_INTERACTIVE`.

Uma opção (`role=option`) só vira `SELECT_OPTION` quando a lista que a contém é **comprovadamente** controlada por um
`role=combobox` (`aria-controls` ou `aria-owns`). Sem essa relação, ela vira CLICK com a nota
`OPTION_WITHOUT_PROVEN_COMBOBOX`.

## Regras de normalização

Cada regra tem teste próprio em `chrome-extension/test/interaction-normalizer.test.js` (24 testes).

| Regra | Entrada (eventos) | Saída |
|---|---|---|
| R1 | clique em controle clicável; repetição (`detail>1`) no mesmo alvo | `CLICK`; o clique duplo é fundido (`DOUBLE_CLICK`) |
| R1 | clique com `detail=0` sem Enter antes (Enter/Espaço num botão focado) | `CLICK` com `VIA_KEYBOARD` |
| R2 | clique num campo de texto | foco: absorvido pelo preenchimento seguinte; sem edição, nenhuma etapa (`FOCUS_ONLY`) |
| R3 | `input` repetidos num campo | **um** `FILL`. Consolida em `focusout`, `change`, Enter, interação em outro alvo, navegação ou `flush`. Nunca uma etapa por caractere |
| R4 | Enter confiável num campo | consolida o `FILL` e gera `PRESS_KEY` (Enter); o `submit` seguinte é evidência |
| R4 | clique sintético do envio implícito (`detail=0`, mesmo formulário, até 150 ms depois do Enter) | absorvido: **nenhum clique inventado** |
| R5 | `change` de checkbox | `CHECK` ou `UNCHECK` pelo estado final; o clique no controle ou na label é absorvido |
| R5 | `change` de checkbox ou radio **sem ativação real antes** (clique confiável no controle ou na label, Espaço ou setas) | descartado (`PROGRAMMATIC_CHANGE`): o `.click()` feito pela página dispara um `change` que o Chrome marca como confiável |
| R5 | `change` de radio marcado | `CHECK` (a opção marcada) |
| R6 | `change` de select | **um** `SELECT_OPTION` com a opção (label e valor sanitizados) como parâmetro |
| R7 | `submit` | nunca vira etapa: é evidência (`FORM_SUBMITTED`) do CLICK ou PRESS_KEY que o provocou; sem gatilho confiável, `PROGRAMMATIC_SUBMIT` |
| R8 | `isTrusted=false` | descartado (`UNTRUSTED`) |
| R9 | clique em opção de combobox comprovado | `SELECT_OPTION` no combobox |
| R10 | alvo ambíguo / sem sinal | `UNKNOWN` com `AMBIGUOUS_TARGET` / ignorado (`NON_INTERACTIVE`) |
| R11 | CAPTURE_ONLY: clique, Enter ou Espaço | **proposta** pelo tipo de controle: campo → `FILL`; checkbox → inverso do estado atual; radio → `CHECK`; select → `SELECT_OPTION` sem opção; clicável → `CLICK` |

Os descartes são contados por motivo, somados no service worker e devolvidos no resumo
(`summary.steps.discarded`). Nenhum descarte é silencioso.

## Navegação e perda

- A navegação de SPA (`history.pushState`, fragmento) consolida o preenchimento aberto
  (`cs.recorder.navigation`) e vira **contexto** da etapa anterior, sem gerar uma etapa NAVIGATE.
- Na saída da página (`pagehide`), o sensor consolida o que estiver aberto.
- Política para o que não chegou a tempo: o sensor sinaliza ao service worker quando há um preenchimento aberto
  (`interaction-open`). Se o documento for substituído com essa marca ligada, o service worker registra uma **perda
  declarada** (`steps.lost`, limitação `INTERACTIONS_LOST`). Nada é inventado para preencher a lacuna.

## Inspeção manual (D-STUDIO-003-03)

Com os sensores ligados num documento, o Inspector fica `SUSPENDED`: seus ouvintes saem, e o consumidor `inspection`
da CaptureSession continua. Ao desligar os sensores, o Inspector volta como estava.

Essa é uma prioridade explícita e reversível: nunca há dois interceptadores no mesmo clique. Teste:
`Studio003InteractionsIT.s003Inspector`.
