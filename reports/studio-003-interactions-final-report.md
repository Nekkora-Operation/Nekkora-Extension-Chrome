# NEKKORA-STUDIO-003 — Relatório final (Chrome Extension e Native Host)

**Captura funcional de interações: o lado da extensão.** Branch `feat/nekkora-studio-003-interactions`. O lado do
Studio está no PR do repositório Nekkora-Studio (`feat/nekkora-studio-003`), com o relatório
`reports/studio-003-final-report.md`. Os dois PRs são separados e **nenhum merge foi feito**.

## 1. Baseline inicial

| Item | Valor |
|---|---|
| Extensão | `1bf84c5` (main; merge do PR #4 pela usuária) |
| Studio | `d64f8a0` (main; merge do PR #3) |
| `scripts/run-tests.ps1 -Suite all` | Java **224** (core 175, host 43, laboratório 4, IT unidade 2) · Node **44** · navegador **197**, 0 falhas |

## 2. Arquitetura implementada

```
página ─► interaction-recorder.js (content, por documento) ─► interaction-normalizer.js (regras R1..R11)
      ─► studio-steps.js (service worker: sequência, correlação, outbox, ACK, sync, rota, épocas)
      ─► recorder.element.describe/verify (motor Java) ─► studio.recording.step ─► StepPrivacy + schema (host) ─► Studio
```

| Arquivo | Papel |
|---|---|
| `src/shared/interaction-normalizer.js` | normalizador puro e determinístico |
| `src/content/interaction-recorder.js` | sensor por documento; alvo funcional; modos |
| `src/content/inspector.js` | estado SUSPENDED (prioridade do Recorder, reversível) |
| `src/background/studio-steps.js` | pipeline de etapas |
| `src/background/studio-recording.js` | capacidade `steps/1`; pausa, modo, rota e finalização consolidam antes; cancelamento; resumo |
| `src/content/capture.js` | conteúdo de `contenteditable` tratado como valor de campo (privacidade) |
| `java-core/native-host/.../RecorderCommands.java` | referência compacta do elemento |
| `java-core/native-host/.../bridge/StepPrivacy.java` | terceira barreira de privacidade |

Documentação: `docs/studio-003/`.

## 3. Alterações nos contratos

Somente acréscimos (`docs/studio-003/bridge-contract.md`):

- `nekkora-bridge/1`:
  - `capabilities` e `routeRef` no `start`;
  - comandos `steps.ack`, `steps.sync` e `route`;
  - evento `studio.recording.step` estrito, sem campo de valor;
  - `steps` em Recording, Renew e Summary;
  - enum `actionCapture`.
- `nekkora-nm/1`: requisições `recorder.element.describe` e `recorder.element.verify`.
- Fixtures: +16 válidas e +10 inválidas. As 26 válidas e 14 inválidas da STUDIO-002 **não mudaram**. O PageMap
  0.2.0 também não mudou.

## 4. Sensores de interação

Ver `docs/studio-003/interaction-recorder.md` e `capture-modes.md`.

- CAPTURE_AND_INTERACT: ouvintes passivos; nada é bloqueado, alterado ou disparado.
- CAPTURE_ONLY: interceptação como no Inspector; Enter e Espaço selecionam; Tab e atalhos ficam livres; destaque
  "Recorder · tag".
- O sensor só é ligado com a gravação do Studio confirmada **e** `steps/1`.
- Épocas por ativação; fronteira exata por `lastSeq` (flush e espera).

## 5. Regras de normalização

R1 a R11, com 24 testes em Node, incluindo o determinismo. Destaques encontrados no Chrome real:

- o `.click()` da página num checkbox não vira etapa (ativação real obrigatória);
- o clique sintético do envio implícito por Enter é absorvido, sem clique inventado.

## 6. Integração com o domínio

A extensão entrega etapas compactas e ordenadas. O Studio as transforma em `StepDraft` e as insere pelo `StepIntake`.
A confirmação (ACK) só vem depois do domínio aceitar ou recusar cada etapa.

## 7. Privacidade

Três barreiras (content, service worker, host) e contrato sem campo de valor (`docs/studio-003/privacy.md`).

O teste real encontrou e corrigiu o texto de `contenteditable` exposto pela captura técnica: no delta de mutação e no
log do service worker.

## 8. Tratamento de duplicações

- Idempotência por `operationId` (route).
- `eventId` e sequência permitem ao Studio descartar reemissões (nota `RESENT`).
- O clique duplo é fundido.
- O `submit` não duplica o clique.
- O clique de foco é absorvido pelo preenchimento.

## 9. Tratamento de eventos perdidos

- Outbox persistida até o ACK.
- Sync a pedido.
- `lastSequence` na renovação, no status e no ACK permite ao Studio detectar lacunas.
- Limite de 100 pendentes → DEGRADED (`STEP_BACKLOG_FULL`), sem descarte.
- Perda **declarada** quando o documento é substituído com preenchimento aberto.
- Descartes contados por motivo.

## 10. Testes

| Suíte | Antes | Depois | Falhas |
|---|---|---|---|
| Java core | 175 | 175 | 0 |
| Java Native Host | 43 | **51** (+5 `RecorderCommandsTest`, +3 `StepPrivacyTest`) | 0 |
| Java laboratório | 4 | 4 | 0 |
| Java IT (unidade) | 2 | 2 | 0 |
| **Java total** | **224** | **232** | **0** |
| Node | 44 | **86** (+24 normalizador, +18 pipeline) | 0 |
| Navegador (Chrome for Testing real) | 197 | **211** (+14 `Studio003InteractionsIT`) | 0 |

Todo evento recebido pelo Studio de laboratório nos testes de navegador é validado contra o contrato, com 0
violações. Do lado do Studio, os testes cruzados com o host real também passam (`RealNativeHostBridgeTest`:
Windows e Linux).

## 11. Evidências reais

- `reports/evidence/S003-login-steps.json`: Preencher Usuário, Preencher Senha, Clicar em Entrar (com
  `FORM_SUBMITTED`); resumo AVAILABLE, 3 confirmadas.
- `S003-consulta-steps.json`: as 15 etapas da consulta, com descartes por motivo (UNTRUSTED, NON_INTERACTIVE…).
- `S003-frames-shadow-steps.json`: etapas de iframe e Shadow DOM com trajetória; cobertura PARTIAL.
- `S003-performance.json`: desempenho (seção 12).
- Homologação real com o Studio de verdade (Windows 11, mouse e teclado do sistema operacional): **29 verificações,
  0 falhas**, com as capturas no repositório do Studio (`reports/studio-003/evidence/`).

## 12. Desempenho

| Medida (Chrome real) | Resultado |
|---|---|
| Correlação do elemento por etapa | p50 11 ms · p95 12 ms · máximo 14 ms (21 amostras) |
| Clique (WebDriver) até a etapa no Studio de laboratório | p50 813 ms · p95 826 ms (inclui a janela de 400 ms do contexto posterior) |
| 300 caracteres digitados | **1** etapa, 301 eventos consolidados |
| 20 cliques consecutivos | 20 etapas, nenhum clique perdido pela página |
| Tamanho do evento | 2.376 bytes em média, 2.381 no máximo (nenhum PageMap) |
| Elementos resolvidos e verificados ao vivo | 21 de 21 |

O Native Host não é bloqueado pelas etapas: a correlação é curta e serializada por gravação, e os comandos de
controle continuam fora da fila do motor (STUDIO-002).

## 13. Falhas encontradas e 14. Correções realizadas

| Falha (teste real) | Correção | Regressão |
|---|---|---|
| `.click()` programático num checkbox gerava CHECK (`change` confiável) | ativação real obrigatória; `PROGRAMMATIC_CHANGE` | Node + navegador |
| Enter: o clique sintético do envio implícito virava CLICK | absorvido; FORM_SUBMITTED vai para o PRESS_KEY | Node + navegador |
| `contenteditable`: texto digitado nos deltas e no log | valor de campo na captura | navegador (`assertNoSecretAnywhere`) |
| Nova base técnica após navegar → `actionCapture` PARTIAL | só limitações de ações | Node |
| Limite de pendentes não contava etapas em correlação | produzidas − confirmadas | Node |
| Descartes do normalizador não chegavam ao resumo | contagens cumulativas por documento | Node + navegador |

## 15. Limitações e 16. Pendências

`docs/studio-003/known-limitations.md`:

- ordem entre documentos pela recepção;
- contexto posterior numa janela de 400 ms;
- perda declarada, não recuperada;
- combobox só com relação comprovada;
- só Enter como tecla;
- arquivos, arrastar e rolagem fora do escopo;
- iframe sem permissão não observado;
- encerramento abrupto do Studio sem recuperação garantida.

Nenhuma pendência bloqueante.

## 17. Preparação para a STUDIO-004

- Cada etapa carrega uma referência completa e verificável:
  - localizadores com validação LIVE ou SNAPSHOT;
  - trajetória documental e contexto;
  - `targetId` (identidade lógica registrada no motor) para reencontro (`target.resolve`).
- `context.after` e as evidências (`FORM_SUBMITTED`, `NAVIGATION_FOLLOWED`) são a base da análise de resultado
  (STUDIO-005).
- O contrato aceita novas ações e evidências por acréscimo (enums fechados, fixtures).

## Anexo — revisão técnica dos PRs (P1 × 3 e P2)

A revisão encontrou três problemas de sincronização (P1) e um de robustez (P2). Os dois primeiros P1 e o P2 estão
neste repositório; o terceiro P1 está no Studio. Primeiro vieram os testes que reproduzem cada defeito, rodados no
código anterior à correção: **9 falharam** (o décimo é o controle da troca de modo confirmada, que já passava). Depois
da correção, os 28 testes de `studio-steps.test.js` passam.

| Achado | Defeito comprovado (antes) | Correção | Regressão |
|---|---|---|---|
| P1 — modo confirmado sem estar ativo | `cs.recorder.mode` recusado ou `{active:false}`: a extensão confirmava CAPTURE_ONLY e o documento seguia em CAPTURE_AND_INTERACT. O mesmo no início com `steps/1` | confirmação obrigatória `{active, epoch, mode}` do sensor principal no início, na retomada, na troca de modo e na troca de rota; sem ela, os sensores são religados no modo anterior e a resposta é `CAPTURE_FAILED`/`SENSOR_NOT_CONFIRMED`; sem restauração, `DEGRADED`; iframe sem confirmação = `PARTIAL_COVERAGE`; a etapa leva o modo informado pelo sensor | `REV-P1a` (7 testes) |
| P1 — evento atrasado na sessão errada | mensagem retida além da espera de 1,5 s recebia a rota nova (`route-b`) | cada época pertence a uma rota; trocar de rota abre uma época nova; a etapa leva a rota da sua época | `REV-P1b` (2 testes) |
| P2 — limite de 100 não rígido | 130 interações seguidas: 130 admitidas | admissão fechada de forma síncrona no limite; as excedentes são recusadas e contadas como perda declarada | `REV-P2` |

Contrato `nekkora-bridge/1` sem mudança: `CAPTURE_FAILED` e o campo `reason` já existiam, e `SENSOR_NOT_CONFIRMED`
é um motivo (texto livre do contrato). As fixtures não mudaram.

Resultados depois da revisão (`scripts/run-tests.ps1 -Suite all`):

| Suíte | Antes da revisão | Depois |
|---|---|---|
| Java (core 175, host 51, laboratório 4, IT unidade 2) | 232 | **232**, 0 falhas (sem mudança em Java) |
| Node | 86 | **96**, 0 falhas |
| Navegador (Chrome for Testing real, inclui `Studio003InteractionsIT` 14/14) | 211 | **211**, 0 falhas |

A homologação real com o Studio, repetida depois das correções, também passou: 29 verificações, 0 falhas
(evidência no repositório do Studio). As evidências `reports/evidence/S003-*.json` foram regeneradas pela suíte com o
código corrigido.

