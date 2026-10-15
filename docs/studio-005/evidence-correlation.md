# STUDIO-005 — Correlação de evidências com etapas

Correlacionar é dizer **a qual etapa** um acontecimento observado pertence. A regra é determinística e conservadora.
Quando houver dúvida, a evidência fica **sem etapa** (`AMBIGUOUS_FEEDBACK`), e nenhuma etapa é escolhida "por proximidade".

## Regras (em `shared/feedback-rules.js` e `content/feedback-observer.js`)

1. **Mesmo documento.** Só concorrem as janelas cujo alvo está no mesmo `ownerDocument` da região observada. Um aviso
   escrito num iframe não é consequência de uma ação do documento principal, nem quando é o próprio documento principal
   que o escreve. Isso foi homologado com o botão "Avisar no quadro".
2. **Só ações que operam a página.** `CLICK`, `PRESS_KEY`, `SELECT_OPTION`, `CHECK`, `UNCHECK` e `UNKNOWN` concorrem
   por avisos, carregamento e conteúdo. `FILL` só concorre pela **validação do próprio campo**: se o campo preenchido
   ficou inválido ao sair dele, a evidência é `DIRECTLY_OBSERVED` dessa etapa.
3. **Dentro da janela.** `openedAt ≤ instante ≤ closesAt`. Fora de qualquer janela, o acontecimento não é evidência de
   etapa nenhuma. Foi o caso do "Pedido lento registrado", 4,5 s depois do clique.
4. **Uma candidata** → `TEMPORALLY_ASSOCIATED`. **Várias** → `AMBIGUOUS_FEEDBACK`, com `eventId: null`,
   `stepSequence: null` e `candidates: n`. A evidência é contada no resumo como `unattributed`.
5. **Estado do elemento acionado** e **envio do formulário** são `DIRECTLY_OBSERVED`: a relação é com o próprio alvo.

## Confiança (sem percentuais)

| Valor | Quando |
|---|---|
| `DIRECTLY_OBSERVED` | o próprio alvo mudou (atributo, validação do campo preenchido), o envio foi observado no formulário da ação, ou é o fim de uma janela concluída |
| `TEMPORALLY_ASSOCIATED` | única ação candidata, no mesmo documento, dentro da janela |
| `AMBIGUOUS` | mais de uma candidata (sem etapa) |
| `INCOMPLETE` | observação interrompida (`OBSERVATION_INCOMPLETE`) |

## Navegação

A navegação vem do service worker, não da página:

- **SPA** (`webNavigation.onHistoryStateUpdated`/`onReferenceFragmentUpdated`) → `ROUTE_CHANGED`, `SAME_DOCUMENT`, sinal `HISTORY_API`.
- **Novo documento**: o content script do documento novo fica pronto e chama `onDocumentReplaced(rec, data.url)`. Vira
  `DOCUMENT_CHANGED`, `NEW_DOCUMENT`, sinal `NEW_DOCUMENT`. Primeiro é emitida a navegação, depois as janelas do
  documento substituído são declaradas `WINDOW_CUT_BY_NAVIGATION`.
- Candidatas: ações que operam a página, executadas, no documento principal e recebidas até **3 s** antes. Com uma
  candidata, a navegação fica associada a ela; com várias, `AMBIGUOUS_FEEDBACK` com `details.navigation`; sem nenhuma,
  não há evidência. A navegação repetida (mesmo tipo e URL) não duplica.

## Vínculo com a etapa no service worker

- Cada interação aceita pelo pipeline de etapas registra um vínculo `epoch|frameId|localSeq`. A evidência só é aceita
  com a época vigente (épocas encerradas: `STALE_EPOCH`) e com um vínculo conhecido. O vínculo é por `frameId`: uma
  mensagem de outro documento com a mesma sequência local é recusada (`UNKNOWN_STEP`).
- A evidência **sai depois da etapa**: enquanto a etapa está em correlação (motor Java, verificação ao vivo), as
  evidências dela esperam numa fila de até 40 por etapa. Quando a etapa é emitida, as evidências saem em ordem.
  Nenhuma etapa fictícia é criada.
- O `FORM_SUBMITTED` estruturado sai com a etapa. Se o envio chegar depois da emissão (`interaction-evidence` tardio),
  sai sozinho e uma única vez. A etapa **não é recriada nem alterada**, porque o código de evidência da STUDIO-003 continua
  no evento da etapa.

## Testes

`test/feedback-rules.test.js` (correlação), `test/studio-evidence.test.js` (vínculo, ordem, iframe, navegação, ambígua)
e `Studio005EvidenceIT` nos cenários 1 a 7 no Chrome real.
