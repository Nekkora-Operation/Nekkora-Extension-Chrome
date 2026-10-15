# NEKKORA-STUDIO-002 — Relatório final (Nekkora-Extension-Chrome)

Studio Bridge, Native Host bidirecional e gravação técnica controlada pelo Nekkora Studio. Branch
`feat/nekkora-studio-002-bridge`. O PR do Studio fica no repositório Nekkora-Studio (`feat/nekkora-studio-002`).

## 1. Baseline inicial

| Item | Valor |
|---|---|
| Commit de partida | `3c25a77` (main; merge do PR #3 — REAL-001), igual ao baseline da missão |
| Java (core 175, host 27, laboratório 4, contrato 2) | **208**, 0 falhas |
| Node | **26**, 0 falhas |
| Navegador (Chrome for Testing 155, `scripts/run-tests.ps1 -Suite all`) | **186**, 0 falhas |

## 2. Commits

Ver a lista no PR. A ordem é: contrato e Native Host; extensão; testes de navegador; documentação e relatório.

## 3. Componentes implementados

| Camada | Componente | Arquivo |
|---|---|---|
| Contrato | `nekkora-bridge/1` (JSON Schema) + 26 fixtures válidas e 14 inválidas | `contracts/studio-bridge.schema.json`, `contracts/studio-bridge/` |
| Contrato | Acréscimos ao `nekkora-nm/1` (`studio.*`, `host.hold`) | `contracts/native-protocol.schema.json` |
| Host | Leitor, motor, controle e escritor separados; transmissões atômicas | `runtime/HostRuntime`, `runtime/ChromeOutboundWriter` |
| Host | Cliente do Studio: descoberta segura, HMAC mútuo, lista explícita, contrato validado nos dois sentidos, batimentos | `bridge/StudioLink`, `BridgeDiscovery`, `BridgeAuth`, `BridgeFrames`, `BridgeContract` |
| Extensão | Mensagens iniciadas pelo host roteadas por tipo | `native-client.js` |
| Extensão | Canal com o Studio (opt-in, manutenção da porta, estado) | `studio-link.js` |
| Extensão | Gravação técnica: consumidor próprio, estados, concessão, idempotência, degradação, restauração | `studio-recording.js`, `studio-recorder.js` |
| Extensão | Liberação de consumidor sem esperar o motor | `capture-session.js` (`release(…, {detachHost})`) |
| Extensão | Painel: seção "Nekkora Studio" com o interruptor e o estado | `ui/panel.*` |
| Extensão | Correção: a última inspeção da aba sai do `storage.session` ao fechar a aba (vazamento que esgotava a cota) | `inspection.js` |

## 4. Arquitetura final e 5. diagrama

```
Nekkora Studio ──nekkora-bridge/1 (UDS, HMAC mútuo)──► Native Host
                                                        leitor ─┬─► MOTOR (1 thread) ─► MessageProcessor ─┐
                                                                └─► CONTROLE ─► StudioLink ───────────────┤
                                                        escritor único (transmissões atômicas) ◄──────────┘
                                                              │ nekkora-nm/1 (+ studio.*)
                                                              ▼
                                   service worker: studio-link · studio-recording ─► CaptureSession ─► página
```

Detalhes em `docs/studio-002/native-host-integration.md`.

## 6. Contratos

`docs/studio-002/bridge-protocol.md`. São 11 comandos (incluindo `studio.recording.renew`) e 12 eventos (incluindo
`studio.recording.recovered`). `pageMapExported` é constante `false`. O `nekkora-nm/1` recebeu só acréscimos: nenhuma
mensagem anterior deixou de valer (teste). O PageMap 0.2.0 não mudou.

## 7. Máquina de estados

Na extensão, o estado é autoritativo: `RECORDING ⇄ PAUSED`, `DEGRADED → RECORDING` (recovered), e os terminais
`COMPLETED`, `CANCELLED`, `EXPIRED` e `FAILED`. Detalhes em `docs/studio-002/studio-recording.md`. O `PAUSED` do usuário
é distinto do `PAUSED` técnico da CaptureSession, que aparece em `capture.captureStatus`.

## 8. Segurança

`docs/studio-002/security.md`. Inclui a validação real do Unix Domain Socket no Windows 11 e o limite declarado: o
segredo em arquivo não protege contra um processo malicioso do mesmo usuário.

## 9. Recuperação

Concessão renovável (o Studio usa 30 s). Studio derrubado → `EXPIRED`, e só o consumidor do Recorder é liberado.
Service worker reiniciado → estado mínimo restaurado e o host reconecta. Aba fechada ou permissão revogada →
`DEGRADED`. Comandos repetidos → `duplicate`.

## 10. Testes realizados e 12. resultados

| Suíte | Antes | Depois | Falhas |
|---|---|---|---|
| Java: core | 175 | 175 | 0 |
| Java: Native Host | 27 | **43** (+16: `StudioLinkTest` 11, `HostConcurrencyTest` 2, `BridgeContractTest` 3) | 0 |
| Java: laboratório | 4 | 4 | 0 |
| Java: contrato (integration-tests, unidade) | 2 | 2 | 0 |
| Node | 26 | **44** (+18: `studio-recording` 14, `native-client-studio` 4; 42 antes da revisão) | 0 |
| Navegador (`run-tests.ps1 -Suite all`) | 186 | **197** (+11: `Studio002BridgeIT`) | 0 |

Medições:

| Medição | Valor |
|---|---|
| S002-021: 3 respostas grandes + mensagens de controle concorrentes | 153 fragmentos, 361 mensagens de controle, **0 intercalações** |
| S002-022 (unidade): motor ocupado 6 s | `bridge.status` 3 ms; ida e volta da pausa 4 ms; ping do motor depois do hold (ordem preservada) |
| S002-022 (Chrome): motor ocupado 8 s | `bridge.status` 1 ms; **pausa confirmada em 3 ms**; renovação 1 ms |
| S002-018 (Chrome): concessão de 5 s, Studio derrubado | expirou em **5.053 ms** (execução depois das correções da revisão); a inspeção continuou consumidora |

## 11. Evidências

- `reports/evidence/S002-008-finish-summary.json`: resposta real de finalização (sem PageMap e sem etapas).
- `reports/evidence/S002-018-lease-expiry.json`, `S002-022-browser-long-operation.json`,
  `S002-020-storage-session.json`.
- Medições de unidade: `java-core/native-host/target/studio-002/*.json`, copiadas para `reports/studio-002/`.
- Homologação real com o Studio de verdade (Windows 11, Chrome for Testing 155, host instalado pelo
  `install-host.ps1`): **42 verificações, 0 falhas**. O relatório e as capturas estão no repositório Nekkora-Studio
  (`reports/studio-002-final-report.md`, `reports/studio-002/evidence/`), incluindo o painel da extensão "Conectada ao
  Nekkora Studio" e a concessão expirada 10.143 ms após a queda do Studio, com a inspeção preservada.

## 13. Falhas encontradas e 14. correções

| Falha (encontrada por teste real) | Correção |
|---|---|
| Na suíte completa, a gravação não sobrevivia ao reinício do service worker (`RECORDING_NOT_FOUND`), embora passasse isolada | causa: `inspection.js` gravava `inspection:<aba>` no `storage.session` e nunca apagava; centenas de abas esgotavam a cota de 10 MB e o estado mínimo de captura e gravação deixava de ser salvo em silêncio. Agora a chave sai ao fechar a aba, e a falha de gravação do Recorder é sinalizada |
| Interruptor do painel clicado logo na abertura era desfeito | o manipulador passou a ser ligado antes de qualquer espera da inicialização do painel |
| Primeira versão da prova de concorrência não exercitava disputa real (0 mensagens de controle entre transferências) | fluxo de controle contínuo durante a escrita, e o teste exige disputa |
| Fixtures com nonce de tamanho errado | gerador determinístico (32 bytes em base64url) |
| Versão da extensão no `bridge.status` (0.3.0) | `manifest.json` e `ns.js` em 0.4.0 |

## 15. Limitações e 16. pendências

- Opt-in obrigatório no painel para o Studio conversar com o navegador; com ele ligado, o host fica em execução.
- Um navegador e uma instância do Studio por vez (`BRIDGE_BUSY` / `OTHER_INSTANCE`).
- Retomar sem outro consumidor exige nova captura-base: espera se o motor estiver ocupado.
- O diálogo de permissão do Chrome não foi automatizado (regra da missão); conceder e revogar foram homologados pelo
  controle "Acesso ao site".
- Pendências para revisão: nenhuma bloqueante. Sugestão: a retenção do `storage.session` merece uma auditoria geral (a
  correção acima resolve o vazamento identificado).

## 17. Preparação para a STUDIO-003

- O modo (`CAPTURE_ONLY` / `CAPTURE_AND_INTERACT`) já chega confirmado à extensão e fica no registro da gravação. Os
  sensores de interação podem lê-lo sem mudança de contrato.
- O consumidor `studio-recorder:<id>` já garante a CaptureSession sincronizada durante a gravação, ou seja, a base para
  correlacionar cada interação a um elemento do PageMap.
- Eventos novos (ex.: `studio.recording.step`) entram como acréscimo ao `nekkora-bridge/1`, com fixtures.

## Correções da revisão técnica (P1)

A revisão dos PRs #3 e #4 apontou dois problemas de concorrência que bloqueavam o merge. Os dois foram corrigidos nas
branches atuais.

### P1 — renovação não reativa concessão vencida (este repositório)

**Antes:** `renew()` só verificava se o estado não era terminal. Um heartbeat atrasado, chegando depois do prazo e
antes de o temporizador de expiração rodar, renovava a concessão. O mesmo acontecia com a expiração em andamento,
porque o estado ainda era `RECORDING` enquanto o consumidor era liberado.

**Depois** (`studio-recording.js`):

- O prazo é **absoluto**. Com `now() >= leaseExpiresAt`, a renovação é recusada com `RECORDING_ENDED` e motivo
  `LEASE_EXPIRED`, e o prazo não é prolongado. O contrato não muda: o código já existia.
- A rotina de expiração marca `expiring` dentro da fila, antes de liberar o consumidor. Uma renovação concorrente vê a
  marca e também é recusada.
- A renovação recusada aciona a mesma rotina de expiração. O consumidor é liberado uma única vez, e um único
  `studio.recording.expired` é emitido.
- A renovação continua fora da fila e responde na hora, sem esperar exportações nem o motor.

Regressões determinísticas, com relógio controlado (`test/studio-recording.test.js`):

| Teste | Antes da correção | Depois |
|---|---|---|
| Renovação 10.001 ms depois da última, sem o temporizador ter rodado | **renovada** (`ok: true`) | recusada (`RECORDING_ENDED`/`LEASE_EXPIRED`); prazo intacto; `EXPIRED` pela rotina; consumidor liberado uma vez; 1 evento |
| Renovação concorrente com a expiração em andamento (liberação do consumidor presa) | **renovada** | recusada na hora (< 50 ms); prazo intacto; um único desfecho `EXPIRED` |

O Studio, ao receber a recusa, consulta o estado real (`RecorderControllerTest`, no PR do Studio).

### P1 — handshakes concorrentes (repositório Nekkora-Studio)

A correção está no `BridgeServer` do Studio: reserva atômica, mensagens só da conexão ativa e pendentes vinculados à
conexão. O lado do Native Host não mudou. Ver `reports/studio-002-final-report.md` no Studio.

### Resultados depois das correções (`scripts/run-tests.ps1 -Suite all`)

| Suíte | Resultado |
|---|---|
| Java (core 175, host 43, laboratório 4, IT unidade 2) | **224**, 0 falhas |
| Node | **44**, 0 falhas |
| Navegador (Chrome for Testing real, inclui `Studio002BridgeIT` 11) | **197**, 0 falhas |
| Studio com esta branch ao lado (Windows e Linux, testes cruzados com o host real) | **218**, 0 falhas, 0 ignorados |

As evidências `reports/evidence/S002-*.json` são desta execução. Os relatórios das missões anteriores que a suíte
regenera foram restaurados. A homologação real (42/0) foi preservada.
