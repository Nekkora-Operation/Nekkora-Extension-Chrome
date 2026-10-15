# STUDIO-003 — Entrega confiável das etapas

## Ordem (requisito bloqueante)

- A **sequência** é atribuída no service worker quando a interação chega, uma por gravação.
- Dentro de um documento, a ordem do sensor é preservada pela sequência local, que só aumenta.
- Entre documentos diferentes (iframes de outra origem), a ordem é a **de recepção** no service worker. Ela não pode
  ser comprovada além disso, e a etapa leva a nota `CROSS_FRAME_ORDER_BY_RECEPTION`.
- Timestamps são apenas informativos (`observedAt`).
- A correlação e a emissão são **serializadas** por gravação: as etapas saem em ordem de sequência.

## Confirmação, retenção e retransmissão

| Mecanismo | Como |
|---|---|
| Outbox | cada etapa emitida fica retida (persistida em `storage.session`, sem valores digitados) até a confirmação |
| ACK | `studio.recording.steps.ack {upToSequence, rejected[]}`, cumulativo. O Studio o envia **depois** do domínio processar (aceitar ou recusar). As recusadas são contadas à parte (`rejected`), nunca como aceitas |
| Sync | `studio.recording.steps.sync {afterSequence}` reemite em ordem o que está retido, com a nota `RESENT` |
| Detecção de perda | `studio.recording.renew`, `status` e o próprio ACK devolvem `steps.lastSequence`. Se o Studio recebeu menos, pede o sync |
| Deduplicação | o Studio descarta sequência ou `eventId` já processados (reemissões, reconexões) |
| Controladores antigos | comandos de etapas exigem o controlador dono (`NOT_CONTROLLER`). Eventos de outra gravação ou de gravação encerrada no Studio são ignorados |

## Limite e backpressure

- Até **100** etapas produzidas e não confirmadas, como **limite rígido**: ao atingir o limite, a admissão fecha na
  hora, de forma síncrona, antes de a degradação (assíncrona) terminar. A gravação vai para `DEGRADED`
  (`STEP_BACKLOG_FULL`), os sensores param e o consumidor é liberado.
- Interação que chega com a admissão fechada é **recusada e contada como perda declarada** (`steps.lost`, limitação
  `INTERACTIONS_LOST`). Nunca some em silêncio e nunca ultrapassa o limite.
- As etapas admitidas continuam retidas para o sync. O resumo registra a limitação `STEP_BACKLOG_FULL`.
- Depois que o Studio confirma, a gravação pode ser retomada (`resume`), e a admissão reabre na nova ativação.

## Finalização e cancelamento

- **Finalizar**:
  1. consolida (`flush`) e espera as interações chegarem;
  2. desliga os sensores e espera a correlação terminar;
  3. espera as confirmações por até **5 s**;
  4. responde com o resumo:
     - `actionCapture` é `AVAILABLE`, ou `PARTIAL` quando há etapas não confirmadas ou recusadas, perda declarada,
       cobertura parcial, interrupção ou backlog;
     - `steps` traz `produced`, `acknowledged`, `rejected`, `unacknowledged`, `lost` e `discarded`.

  O que não foi confirmado continua disponível ao sync (retenção de gravações encerradas).
- **Cancelar**: interrompe a emissão, desliga os sensores descartando o que estava aberto e esvazia a outbox. O
  Studio descarta as etapas da gravação.

## Rota de sessão lógica

`studio.recording.route {routeRef}`:

1. consolida a época da rota anterior (`flush` e espera das interações, até 1,5 s);
2. abre uma **época nova pertencente à rota nova**, confirmada pelo sensor principal (`{active, epoch, mode}`);
3. devolve `boundarySequence`, informativa (a sequência no momento da troca).

Cada época pertence a uma única rota, e a etapa leva a **rota da época em que a interação aconteceu**, não a rota
vigente na chegada. Um evento da sessão anterior que chega depois da espera de 1,5 s continua na sessão anterior.
Se ele chegar depois de a época sair da janela de épocas aceitas (pausa, cancelamento, mais de 8 ativações), é
descartado e contado (`STALE_EPOCH`), nunca atribuído à sessão seguinte.

Sem a confirmação do sensor principal, a troca é desfeita: a rota volta à anterior (inclusive para a época aberta
na tentativa), os sensores são religados nela e a resposta é `CAPTURE_FAILED` (`SENSOR_NOT_CONFIRMED`). Se nem
isso for confirmado, a gravação vai para `DEGRADED`. O Studio só considera a troca feita com a confirmação
(`Nekkora-Studio/docs/studio-003/session-routing.md`).

Evidência:

- `Studio003InteractionsIT.s003Sessions`: o preenchimento aberto na troca saiu com a rota anterior;
- Node `REV-P1b`: a mensagem retida além da espera de 1,5 s saiu com a rota anterior; com várias trocas, cada
  evento atrasado ficou com a rota da sua época.

## Limitação declarada

Não há persistência definitiva no Studio nesta missão. Se o **Studio** for encerrado abruptamente, as etapas que ele
ainda não tinha recebido ficam retidas na extensão até a concessão expirar e a retenção limpar. **Não há promessa de
recuperar todas as etapas** depois do encerramento abrupto do Studio.

## Testes

- Node, `studio-steps.test.js` (28 testes): ordem, ACK, sync, rota, pausa, modo, backlog, cancelamento, perda,
  `UNRESOLVED` e evidência. Dez deles vêm da revisão técnica (`REV-P1a`, `REV-P1b`, `REV-P2`).
- Chrome real, `Studio003InteractionsIT`:
  - `s003Reconnect`: Bridge derrubado, 2 pendentes, sync reemite 2, nenhuma duplicada;
  - `s003Sessions`, `s003Pause`, `s003Cancel`, `s003StudioLost`.
- Cruzado no Studio, `RealNativeHostBridgeTest.etapasPeloHostReal`: o ACK atravessa o host real.
