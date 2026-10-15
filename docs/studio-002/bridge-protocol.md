# STUDIO-002 — Protocolo `nekkora-bridge/1`

Contrato: [`contracts/studio-bridge.schema.json`](../../contracts/studio-bridge.schema.json) (JSON Schema 2020-12).
Fixtures compartilhadas: [`contracts/studio-bridge/fixtures`](../../contracts/studio-bridge/fixtures), com 26 válidas e
14 inválidas. O Studio guarda uma cópia e confere que ela não diverge deste repositório. É o mesmo modelo usado no
PageMap.

## Onde ele vale

```
Nekkora Studio  ──nekkora-bridge/1──►  Native Host  ──nekkora-nm/1──►  extensão (service worker)
 (servidor local)                        (cliente)                       (decide e confirma)
```

São duas fronteiras distintas (D-STUDIO-002-06):

- **nekkora-bridge/1**: entre o Studio e o Native Host, por um canal local autenticado.
- **nekkora-nm/1**: o protocolo existente entre o host e a extensão. Recebeu só **acréscimos**: as mensagens `studio.*`,
  descritas abaixo, e o comando de teste `host.hold`.

O host traduz entre os dois protocolos. Ele não guarda estado de gravação e **não repassa nada genericamente**.

## Transporte

| Item | Valor |
|---|---|
| Canal | Unix Domain Socket em `%LOCALAPPDATA%\Nekkora\Studio\bridge\studio.sock`, validado no Windows 11. Alternativa: TCP somente em `127.0.0.1`, com porta dinâmica |
| Quadro | 4 bytes big-endian sem sinal com o tamanho, seguidos do JSON em UTF-8 |
| Limite | 262.144 bytes por quadro: é um canal de **controle**, e PageMaps e snapshots nunca passam por ele |
| Descoberta | `studio-bridge.json` no mesmo diretório, com ACL restrita ao usuário (ver [security.md](security.md)) |
| Batimentos | `bridge.heartbeat` a cada 5 s. Sem sinal por 3 intervalos, o canal é encerrado |

## Envelope

```json
{ "protocol": "nekkora-bridge/1", "kind": "command|response|event|handshake", "type": "...", "id": "...", "seq": 1,
  "payload": {}, "ok": true, "error": null }
```

- `id` (comandos e respostas) é o identificador interno de correlação. `seq` é a sequência de quem envia, por conexão.
- Erros usam `{code, message, reason?, retryable?}`, com códigos estáveis (`ErrorCode` no schema), por exemplo
  `ORIGIN_NOT_ALLOWED` + `reason: PERMISSION_REQUIRED`.

## Handshake (autenticação mútua)

1. Studio → host: `bridge.challenge {instanceId, serverNonce, accepts, studio.version}`.
2. Host → Studio: `bridge.hello {instanceId, clientNonce, proof, client}`, em que
   `proof = HMAC-SHA256(segredo, "nekkora-bridge/1|client|" + instanceId + "|" + serverNonce + "|" + clientNonce)`.
3. Studio → host: `bridge.welcome {proof, heartbeatMs, maxFrameBytes}`, com a prova do servidor
   (`...|server|...`). O host só aceita o Studio que comprova conhecer o mesmo segredo.
4. Em caso de recusa: `bridge.rejected {error}` com `AUTH_FAILED`, `PROTOCOL_UNSUPPORTED` ou `BRIDGE_BUSY`, e o
   canal é fechado.

O segredo **nunca trafega**. Como o nonce do servidor muda a cada conexão, uma prova capturada não serve para outra
conexão (teste de repetição em `BridgeServerTest`).

## Comandos (lista explícita)

| Comando | Atendido por | Payload | Resultado |
|---|---|---|---|
| `bridge.status` | host | `{}` | `{link, extension{version, buildMode}, host{version, engineBusy}}` |
| `bridge.heartbeat` | host | `{}` | `{at}` |
| `browser.tabs.list` | extensão | `{}` | `{tabs[{tabRef, title, host, access, active, recording}], truncated}` |
| `studio.recording.start` | extensão | `{recordingId, operationId, tabRef, mode, leaseMs}` | `{recording, duplicate}` |
| `studio.recording.pause` / `resume` | extensão | `{recordingId, operationId}` | `{recording, duplicate}` |
| `studio.recording.mode` | extensão | `{recordingId, operationId, mode}` | `{recording, duplicate}` |
| `studio.recording.finish` / `cancel` | extensão | `{recordingId, operationId}` | `{recording, summary, duplicate}` |
| `studio.recording.status` | extensão | `{recordingId?}` | `{recordings[]}`, somente as do controlador |
| `studio.recording.renew` | extensão | `{recordingId}` | `{recordingId, state, expiresInMs}` |

`studio.recording.renew` foi acrescentado à lista sugerida pela missão: é a renovação da concessão (D-STUDIO-002-07).
Qualquer outro tipo, inclusive comandos do motor como `page.capture` ou `host.ping`, recebe `UNKNOWN_COMMAND` e não
chega à extensão.

## Eventos

| Evento | Origem | `operationId` |
|---|---|---|
| `bridge.connected` / `bridge.disconnected` (`CHROME_DISCONNECTED`, `LINK_DISABLED`, `HOST_SHUTDOWN`) | host | — |
| `studio.recording.started`, `paused`, `resumed`, `modeChanged`, `finished`, `cancelled` | operação do Studio | id da operação |
| `studio.recording.degraded` (aba fechada, permissão revogada, captura em erro), `recovered`, `expired`, `failed` | navegador | `null` |

`studio.recording.recovered` foi acrescentado: é a retomada a partir de uma interrupção. Todo evento traz
`recordingId` e a fotografia completa `recording`; `finished` e `cancelled` trazem também o `summary`.

## Fotografia da gravação (`Recording`)

`state` (`RECORDING | PAUSED | DEGRADED | COMPLETED | CANCELLED | EXPIRED | FAILED`), `mode`, `tab{tabRef, title, host}`,
`capture{recorderAttached, otherConsumers, captureStatus}`, `coverage{state, framesAttached, framesUnavailable}`,
`counters{segments, technicalCaptures, synchronizations, recoveries}`, `lease{leaseMs, expiresInMs}`, `startedAt`,
`updatedAt` e `reason`.

O `PAUSED` deste contrato é a pausa **do usuário**. O `PAUSED` técnico da CaptureSession (motor indisponível) aparece
apenas em `capture.captureStatus`.

## Resumo (`Summary`)

`pageMapExported` é sempre `false`, por constante (finalizar não é exportar, D-STUDIO-002-03). `actionCapture` é
`"NOT_AVAILABLE"`: nenhuma etapa é produzida. As `limitations` possíveis são `ACTION_CAPTURE_NOT_AVAILABLE`,
`PARTIAL_COVERAGE`, `INTERRUPTED`, `RECOVERIES_OCCURRED` e `NEVER_ATTACHED`.

## Acréscimos ao `nekkora-nm/1`

| Mensagem | Direção | Observação |
|---|---|---|
| `studio.link.configure {enabled, extension}` | extensão → host (requisição) | liga ou desliga a procura do Studio |
| `studio.link.status` | extensão → host (requisição) | estado do canal |
| `studio.command {id: "sc-N", command, payload, controller{instanceId}}` | host → extensão | comando já validado; **não é resposta** |
| `studio.reply {id, ok, payload, error}` | extensão → host | resultado do `studio.command` |
| `studio.event {event, payload, controller}` | extensão → host | evento para o controlador |
| `studio.link {state, detail, controller}` | host → extensão | estado do canal (exibido no painel) |
| `host.hold {ms ≤ 60000}` | extensão → host (requisição) | operação longa **controlada**, só para testes de responsividade (mesma família de `host.generate` e `host.fail`) |

Nenhuma mensagem anterior deixou de ser válida (`BridgeContractTest.mensagensStudioDoNativeMessagingSaoAditivas`).
