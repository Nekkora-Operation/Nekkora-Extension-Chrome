# STUDIO-005 — Observador de consequências (evidências)

O observador registra o que a página **mostrou** depois de cada interação gravada: mensagens, validação, carregamento,
estado do elemento acionado e conteúdo atualizado. Ele não interpreta o negócio. Uma mensagem de erro observada **não**
é uma falha validada, e um "Pedido registrado com sucesso" observado **não** é um sucesso validado.

## Onde vive

| Peça | Arquivo | Papel |
|---|---|---|
| Regras puras | `src/shared/feedback-rules.js` | política de mensagem, classificação por sinais, correlação, normalização de estado (testadas em Node) |
| Observador da página | `src/content/feedback-observer.js` | janelas de observação, varredura das regiões, evento nativo `invalid`, estado do alvo |
| Ligação com o sensor | `src/content/interaction-recorder.js` | abre a janela na entrega da interação; corta as janelas na pausa, parada, troca de modo/rota e saída da página |
| Pipeline do service worker | `src/background/studio-evidence.js` | 2ª barreira, vínculo com a etapa, evidências de formulário e navegação, entrega confiável |

## Quando liga

- Só com a capacidade `evidence/1` negociada pelo Studio. Ela exige `steps/1`, porque toda evidência pertence a uma etapa.
- Só em `CAPTURE_AND_INTERACT`. Em `CAPTURE_ONLY` a ação não é executada, então não há consequência para observar.
- O início, a retomada e a troca de rota ou de documento passam `evidence: true` ao sensor (`cs.recorder.start`). O sensor
  confirma `{active, epoch, mode}` como na STUDIO-003, e o estado do observador aparece em `page.diagnostics → recorder.feedback`.

## Janela de observação

| Item | Valor | Por quê |
|---|---|---|
| Abertura | na entrega da interação ao service worker (clique na fase de captura, antes dos handlers da página; preenchimento ao sair do campo) | a fotografia "antes" é tirada antes da reação da página |
| Duração | 3 s | cobre respostas típicas (inclusive a mensagem tardia de 1 s) sem acumular janelas |
| Extensão | até 8 s enquanto houver carregamento começado na janela; 1 s a mais depois do fim do carregamento | a mensagem costuma vir logo depois do carregamento |
| Janelas simultâneas | no máximo 8 (a mais antiga termina `EVIDENCE_LIMIT_REACHED`) | limite de memória e de ambiguidade |
| Evidências por janela | no máximo 12 (`EVIDENCE_LIMIT_REACHED` na conclusão) | sem inundar o Studio |
| Fim | `OBSERVATION_COMPLETE` com `signalsObserved`, ou `OBSERVATION_INCOMPLETE` com o motivo | ausência de sinal ≠ observação indisponível |

`MutationObserver` existe **só** enquanto houver janela aberta, nas raízes observáveis do documento: o próprio
documento, os iframes de mesma origem e os shadow roots abertos. Ele é desligado quando a última janela termina. O
desempenho mede `observers = 0` em repouso. A varredura tem debounce de 100 ms e uma varredura final antes de concluir.

## O que é observado (regiões)

Seletor fechado: `[role=alert]`, `[role=alertdialog]`, `[role=status]`, `[aria-live]`, `[aria-invalid]`, `[aria-busy]`,
`[role=progressbar]`. São até 40 regiões acompanhadas. A assinatura de cada região tem visível, tamanho e hash do texto,
inválido e ocupado. O hash e o texto **nunca saem**: servem só para comparar.

| Mudança | Evidência | Sinais |
|---|---|---|
| alert/alertdialog/aria-live=assertive com texto novo | `VISIBLE_ALERT` (`UNKNOWN_FEEDBACK`) | `ROLE_ALERT`, `ROLE_ALERTDIALOG`, `ARIA_LIVE_ASSERTIVE` |
| idem + indício de erro (`error`, `erro`, `danger`, `invalid`, `falha`… em classe, id ou data-*) ou região apontada por `aria-errormessage` | `VISIBLE_ERROR_MESSAGE` (`ERROR_INDICATOR`) | + `ERROR_HINT` / `ARIA_ERRORMESSAGE` |
| status / aria-live=polite com texto novo | `VISIBLE_STATUS_MESSAGE` (`STATUS_INDICATOR`) | `ROLE_STATUS`, `ARIA_LIVE_POLITE` |
| campo passou a `aria-invalid=true` | `VALIDATION_FEEDBACK` (`VALIDATION_INDICATOR`) | `ARIA_INVALID` (+ `ARIA_ERRORMESSAGE`) |
| evento nativo `invalid` (o navegador recusou o envio) | `VALIDATION_FEEDBACK` | `NATIVE_INVALID` |
| `aria-busy` → true, ou `progressbar` visível | `LOADING_INDICATOR` | `ARIA_BUSY` / `ROLE_PROGRESSBAR` |
| fim do carregamento | `LOADING_FINISHED` (+ `CONTENT_CHANGED` se o conteúdo da região mudou) | idem |
| atributo de estado do elemento acionado (`aria-expanded/pressed/checked/selected/disabled`, `disabled`) | `ELEMENT_STATE_CHANGED` (`DIRECTLY_OBSERVED`) | `TARGET_ATTRIBUTE` |

Uma **classe CSS "error" sozinha não é evidência**: o indício só reforça um alerta que já tem papel semântico. Isso foi
homologado com o botão "Ajuda" da página de laboratório.

Uma mensagem **que já estava na tela** antes da interação e não mudou não vira evidência. Isso foi homologado com
"Sistema em manutenção às 22h". O mesmo texto repetido na mesma região e na mesma janela também não duplica.

## O que o observador nunca faz

- Não dispara eventos, não chama `reportValidity()`/`checkValidity()` e não escreve atributos.
- Não lê nem envia HTML (`innerHTML`). O texto (`textContent`) é lido só para o hash local (mudança/duplicata); pelo
  modo estrito ele nunca sai.
- Não observa documentos sem autorização: os iframes de outra origem sem permissão não recebem content script.
- Não tira capturas de tela.
- Não lê os valores dos campos (o modo estrito tornou desnecessária qualquer comparação com eles).

## Cortes

| Situação | Quem declara | Limitação |
|---|---|---|
| saída da página (`pagehide`) | página e, como reserva, o service worker em `onDocumentReplaced` | `WINDOW_CUT_BY_NAVIGATION` |
| pausa | página no `flush('PAUSE')`; reserva do service worker depois de `stopSensors` | `WINDOW_CUT_BY_PAUSE` |
| finalização, expiração, degradação | página; reserva do service worker | `WINDOW_CUT_BY_STOP` |
| troca de modo/rota/época | página | `PARTIAL_COVERAGE` |
| carregamento que não terminou em 8 s | página | `PARTIAL_COVERAGE` |
| cancelamento | ninguém: nada é enviado e o service worker descarta | — |

Cada janela termina **exatamente uma vez**. A primeira conclusão que chega ao service worker vale, e as demais são
recusadas (`WINDOW_NOT_OPEN`).
