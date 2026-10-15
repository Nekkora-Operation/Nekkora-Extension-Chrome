# STUDIO-003 — Evolução do contrato `nekkora-bridge/1`

**Somente acréscimos.** Nenhum comando, evento ou campo existente mudou de significado. As 42 fixtures válidas e as
24 inválidas estão em `contracts/studio-bridge/fixtures`; as 26 válidas e 14 inválidas da STUDIO-002 **não mudaram**
(gerador determinístico, `scripts/gen-studio-bridge-fixtures.py`).

## Acréscimos

| Tipo | Nome | Payload |
|---|---|---|
| campo opcional | `StartPayload.capabilities` | `["steps/1"]`: o Studio recebe e confirma etapas. **Ausente** (Studio da STUDIO-002): gravação só técnica, sem sensores |
| campo opcional | `StartPayload.routeRef` | rota inicial (opaca, gerada pelo Studio) |
| comando | `studio.recording.steps.ack` | `{recordingId, upToSequence, rejected?[]}` → `AckResult {recordingId, steps}` |
| comando | `studio.recording.steps.sync` | `{recordingId, afterSequence}` → `SyncResult {recordingId, resent, steps}` |
| comando | `studio.recording.route` | `{recordingId, operationId, routeRef}` → `RouteResult {recording, boundarySequence, duplicate}` |
| evento | `studio.recording.step` | `StepEventPayload` (abaixo) |
| campo opcional | `Recording.steps`, `RenewResult.steps` | `StepsState {enabled, lastSequence, ackedSequence, pending, lost}` |
| enum | `Summary.actionCapture` | era a constante `NOT_AVAILABLE`; agora `NOT_AVAILABLE`, `AVAILABLE` ou `PARTIAL` |
| campo opcional | `Summary.steps` | `StepsSummary {produced, acknowledged, rejected, unacknowledged, lost, discarded{motivo: n}}` |
| enum | `Summary.limitations` | + `STEPS_UNCONFIRMED`, `STEPS_REJECTED`, `INTERACTIONS_LOST`, `STEP_BACKLOG_FULL` |

O `if` dos eventos `studio.recording.*` passou a excluir `studio.recording.step`, que tem o próprio payload.

## `StepEventPayload` (estrito: `additionalProperties: false` em todos os níveis)

- Identificação: `recordingId`, `eventId`, `sequence`, `routeRef`, `observedAt`.
- Modo e observação: `mode`, `observation` (`INTERACTION_OBSERVED` | `PROPOSED`).
- `action`: `CLICK` | `FILL` | `SELECT_OPTION` | `CHECK` | `UNCHECK` | `PRESS_KEY` | `UNKNOWN`.
- `element`: `ElementRef` compacto (ver `element-correlation.md`); `correlation` com chaves internas, nunca exibidas.
- `parameters`:
  - `valueCaptured` é a constante **`false`**: não existe campo para valor digitado;
  - `option`: `{label, value}` sanitizados ou `null`;
  - `key`: `Enter` ou `null`.
- `context`: `url` sanitizada, `title`, `frame` (`MAIN` | `FRAME`), `after` (navegação observada logo depois).
- `evidence[]`, `notes[]` (enums fechados) e `events` (eventos consolidados).

Fixtures inválidas novas, todas recusadas pelo host:

- etapa com `valueCaptured: true`;
- etapa com campo `value`;
- etapa com `pageMap`;
- elemento com `nodes`;
- ação `NAVIGATE`;
- etapa sem sequência;
- etapa com payload de ciclo de vida;
- ACK negativo;
- capacidade desconhecida;
- `actionCapture` fora do enum.

## Compatibilidade

| Combinação | Resultado |
|---|---|
| Studio STUDIO-002 + extensão STUDIO-003 | o Studio não pede `steps/1`: sem sensores, resumo `NOT_AVAILABLE`, mensagens idênticas às da STUDIO-002 (`Studio003InteractionsIT.s003Compat`) |
| Studio STUDIO-003 + extensão STUDIO-002 | o início com `capabilities` é recusado pelo contrato antigo (`BAD_REQUEST`); o Studio refaz o início **sem** a capacidade e grava só a parte técnica (`RecorderStepsTest` / `FakeBrowser.legacy`) |

## Native Messaging (`nekkora-nm/1`)

Requisições `recorder.element.describe` e `recorder.element.verify` acrescentadas ao enum. O evento
`studio.recording.step` usa o `studio.event` existente (o padrão `^studio\.recording\.[A-Za-z]+$` já o cobria).

## Host

- `BridgeContract`: listas explícitas estendidas, sem repasse genérico. O teste `listaDoHostCorrespondeAoContrato`
  garante que lista e contrato sejam iguais.
- `StepPrivacy`: terceira barreira, aplicada antes de repassar uma etapa.
- Todo evento é validado contra o schema; o que estiver fora do contrato é recusado e registrado no log **sem o
  conteúdo**.
