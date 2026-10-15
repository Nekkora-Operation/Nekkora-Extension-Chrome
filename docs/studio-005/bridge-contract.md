# STUDIO-005 — Contrato `nekkora-bridge/1` + `evidence/1`

O protocolo continua `nekkora-bridge/1` e só **recebeu acréscimos** (`contracts/studio-bridge.schema.json`). Nada
existente mudou de significado.

## Capacidade

- `studio.recording.start.capabilities` aceita `steps/1` e `evidence/1`. A `evidence/1` **exige** `steps/1`
  (`BAD_REQUEST` sem ela). Uma capacidade desconhecida (ex.: `evidence/2`) é recusada.
- Studio antigo (sem `evidence/1`): nada muda. Não há observador nem evento novo, e as respostas são iguais às da
  STUDIO-003/004. Isso foi testado em Node, no IT `s005Compat` e no Studio (`RecorderEvidenceTest`).
- Extensão antiga (sem `evidence/1`): ela recusa a capacidade desconhecida com `BAD_REQUEST`. O Studio novo tenta de novo
  só com `steps/1`; se for recusado outra vez, grava só a parte técnica (recuo da STUDIO-003).

## Acréscimos

| Item | Tipo |
|---|---|
| `studio.recording.evidence` | evento (`EvidenceEventPayload`) |
| `studio.recording.evidence.ack` | comando (`upToSequence`, `rejected[]`) → `EvidenceAckResult` |
| `studio.recording.evidence.sync` | comando (`afterSequence`) → `EvidenceSyncResult` |
| `Recording.evidence`, `RenewResult.evidence` | `EvidenceState` (enabled, lastSequence, ackedSequence, pending ≤ 1000, lost) |
| `Summary.evidence` | `EvidenceSummary` |
| limitações do resumo | `EVIDENCE_UNCONFIRMED`, `EVIDENCE_LOST` |

## `EvidenceEventPayload` (todos os campos obrigatórios; `additionalProperties: false` em todos os níveis)

`recordingId`, `evidenceId`, `evidenceSequence`, `eventId` (null = ambígua), `stepSequence`, `observedAt`, `type`,
`source`, `relation`, `confidence`, `classification`, `document {frame, inShadow, title ≤ 120, url ≤ 2000}`,
`details {region ≤ 120, message {availability, text ≤ 160}, field ≤ 120, state, navigation, signals ≤ 8, candidates,
signalsObserved}` e `limitations ≤ 8`.

Vocabulários **fechados**:

- `type` (14): `FORM_SUBMITTED`, `ROUTE_CHANGED`, `DOCUMENT_CHANGED`, `VISIBLE_ALERT`, `VISIBLE_ERROR_MESSAGE`,
  `VISIBLE_STATUS_MESSAGE`, `VALIDATION_FEEDBACK`, `LOADING_INDICATOR`, `LOADING_FINISHED`, `ELEMENT_STATE_CHANGED`,
  `CONTENT_CHANGED`, `OBSERVATION_COMPLETE`, `OBSERVATION_INCOMPLETE`, `AMBIGUOUS_FEEDBACK`. A taxonomia da missão
  previa `NAVIGATION_OCCURRED`. Ela foi dividida em `ROUTE_CHANGED` e `DOCUMENT_CHANGED` porque as regras de produção são
  diferentes. `OBSERVATION_COMPLETE` foi acrescentado para distinguir "nada observado" de "observação indisponível".
  Cada tipo tem regra de produção e teste.
- `source`, `relation`, `confidence` (sem percentuais), `classification`, `signals` (15), `limitations` (8), atributos
  e valores de estado: ver o schema.

O texto de mensagem (`AVAILABLE` + trecho) continua permitido pelo schema, porque o contrato não mudou, mas o modo
estrito faz com que nenhuma barreira o produza ou deixe passar (ver `privacy.md`). Não existe campo de resultado de negócio. As fixtures inválidas provam a recusa de `businessOutcome`, `html`, `value`,
`pageMap`, tipo e sinal desconhecidos, confiança numérica, mensagem acima de 160 caracteres, falta de sequência e
evidência num evento de ciclo de vida.

## Fixtures

`contracts/studio-bridge/fixtures/{valid,invalid}/s005-*.json`: 14 válidas e 11 inválidas, geradas por
`scripts/gen-studio-bridge-fixtures.py`, que reproduz byte a byte as versionadas. Elas são validadas por
`BridgeContractTest` (host) e, no Studio, por `BridgeContractFixturesTest`, que exige cópia idêntica. Os testes Node
validam cada payload emitido contra o mesmo schema (`test/helpers/mini-schema.js`).

## Host

`BridgeContract` encaminha os comandos `evidence.ack/.sync` e o evento `studio.recording.evidence`. `StudioLink`
reaplica `EvidencePrivacy` (3ª barreira) antes de validar e encaminhar. Um evento fora do contrato não sai.
O PageMap 0.2.0 não mudou.
