# STUDIO-005 — Entrega confiável das evidências

Mesmo modelo das etapas (STUDIO-003, `docs/studio-003/event-delivery.md`), com uma **sequência própria**.

| Mecanismo | Como |
|---|---|
| Identidade | `evidenceId` (`evd-<gravação>-<n>`) e `evidenceSequence` (1, 2, 3…) atribuídos no service worker, na emissão |
| Ordem | as evidências de uma etapa saem **depois** dela (fila por etapa, até 40); as ambíguas saem na hora; dentro da gravação, a sequência é a ordem técnica |
| Outbox | retida (em `storage.session`, sem valores digitados e sem texto de mensagem) até o ACK |
| ACK | `studio.recording.evidence.ack {upToSequence, rejected[]}`, cumulativo, enviado pelo Studio **depois** de registrar no domínio (fora da fila de comandos) |
| Sync | `studio.recording.evidence.sync {afterSequence}` reemite em ordem com a **mesma identidade** (o Studio deduplica) |
| Detecção de lacuna | `renew`, `status` e o próprio ACK devolvem `evidence.lastSequence`. Se faltar algo no Studio, ele pede o sync |
| Limite | 200 pendentes. Acima disso a evidência não sai e a perda é **declarada** (`evidence.lost`, limitação `EVIDENCE_LOST`). A gravação **não** degrada, porque evidência não é etapa |
| Fronteiras | `flush`/`stop` devolvem `lastFeedback` (sequência das mensagens do observador na época) e o service worker espera a chegada antes de encerrar a época |
| Exatamente uma vez | cada janela termina uma vez; a conclusão repetida ou tardia é recusada |
| Cancelamento | outbox, filas e janelas descartadas; nada mais é emitido |
| Reinício do service worker (revisão do PR #6) | a janela aberta é persistida ao abrir, com o vínculo exato (`eventId`/`sequence`) e a marca "etapa emitida" (gravada na emissão). Depois do reinício, o vínculo é reconstruído **só** desse registro, nunca por proximidade. Etapa não emitida → perda declarada. Invariante na emissão: sem etapa, só `AMBIGUOUS_FEEDBACK` sai; qualquer outra vira perda declarada, nunca evento inválido na outbox |
| Finalização | drena, declara as janelas abertas (`WINDOW_CUT_BY_STOP`) e espera os ACKs por até 3 s. O que faltar fica em `EVIDENCE_UNCONFIRMED` |

## Ciclo de vida

| Evento | Efeito nas evidências |
|---|---|
| START (`evidence/1`) | observador ligado com a época nova |
| PAUSE | janelas cortadas (`WINDOW_CUT_BY_PAUSE`), época encerrada (o que chegar depois é `STALE_EPOCH`) |
| RESUME | época nova; nada da pausa entra |
| MODE | janelas cortadas (`PARTIAL_COVERAGE`); em `CAPTURE_ONLY` o observador fica desligado |
| ROUTE | janelas da época anterior cortadas (`PARTIAL_COVERAGE`), época nova |
| Documento substituído | `DOCUMENT_CHANGED` (se houver ação candidata) e janelas `WINDOW_CUT_BY_NAVIGATION` |
| DEGRADED (captura encerrada, permissão revogada, sensor sem confirmação, fila de etapas cheia) | sensores e observador desligados, janelas `WINDOW_CUT_BY_STOP` |
| FINISH | ver acima |
| CANCEL | descarte |

## Resumo da gravação (`summary.evidence`)

`produced`, `acknowledged`, `rejected`, `unacknowledged`, `lost`, `unattributed`, `incomplete`. As limitações
`EVIDENCE_UNCONFIRMED` e `EVIDENCE_LOST` descrevem a observação e **não** mudam `actionCapture`.

## Métricas

`studio.evidence.metrics {recordingId}` (diagnóstico local): produzidas, pendentes, perdidas, não atribuídas,
incompletas, recusadas no service worker (`dropped`), janelas abertas, contagem por tipo, última observação e latência
de emissão (p50/p95/máx). Não contém texto da página.
