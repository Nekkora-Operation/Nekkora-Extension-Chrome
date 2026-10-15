# NEKKORA-STUDIO-005 — Relatório final da extensão (evidências observadas)

Branch `feat/nekkora-studio-005-evidence` (base `8b251c5`). PR separado do Studio e **sem merge**. Dados de teste
fictícios. Nada aqui executa automações, valida negócio, usa IA, persiste em definitivo ou tira capturas de tela.

## 1. Baseline

| Suíte | Commit `8b251c5` (antes) | Branch (depois) |
|---|---|---|
| Java (core 175, host 51, laboratório 4, contrato 2) | 232/232 | **237/237** (host +5: `EvidencePrivacyTest` 4, `HostConcurrencyTest` +1) |
| Node | 96/96 | **133/133** (+37: `feedback-rules` 10, `studio-evidence` 27) |
| Navegador (`*IT` sem `*LiveWebIT`/`*ManualIT`, 6 grupos) | 211/211 (28 classes) | **226/226** (29 classes, +15 `Studio005EvidenceIT`) |

Números depois da revisão do PR #6 (anexo no fim).

Nenhum teste foi removido nem afrouxado.

## 2. Commits utilizados

| Commit | Conteúdo |
|---|---|
| `1d6eab6` | contrato `evidence/1` (schema, fixtures), encaminhamento no host e `EvidencePrivacy` (3ª barreira) |
| `b5e81d3` | `feedback-rules`, `feedback-observer`, `studio-evidence`, integração no sensor e no `studio-recording`, laboratório `studio005`, `Studio005EvidenceIT` |
| `d2e11a2` | laboratório: aviso ambíguo 2 s depois do primeiro clique (determinístico também com cliques reais do Robot) |
| `793fad3` | rótulos na matriz oficial S005, degradação e revogação (S005-041/046), Shadow DOM (S005-044) |
| último | documentação, relatório e fixtures S005 no gerador oficial (`scripts/gen-studio-bridge-fixtures.py`) |

## 3. Arquitetura

```
página ── interaction-recorder (STUDIO-003) ──interaction──┐
   │            └─ open(i, el) ─► feedback-observer ──feedback──┤
   │                 (janelas, varredura, invalid, estado)       ▼
   │                                              service worker
   │                     studio-steps (etapa) ──► studio-evidence (2ª barreira, vínculo, fila por etapa,
   │                                               FORM/ROUTE/DOCUMENT, outbox, ack/sync, cortes)
   │                                                        │ studio.recording.evidence
   ▼                                                        ▼
 nada é disparado na página                   Native Host (EvidencePrivacy + schema) ─► Bridge ─► Studio
```

### Auditoria: necessidade × recurso existente × evolução

| Necessidade | Recurso existente | Evolução na STUDIO-005 |
|---|---|---|
| saber quando uma ação aconteceu | sensor de interações e normalizador (STUDIO-003), `deliver()` na captura | a entrega passa a abrir a janela de observação |
| envio de formulário | código `FORM_SUBMITTED` na etapa e `interaction-evidence` tardio | evidência estruturada `FORM_SUBMITTED` (DIRECT, AT_ACTION), também a tardia |
| navegação | `lastNav` e `context.after` da etapa (janela de 400 ms) | `ROUTE_CHANGED`/`DOCUMENT_CHANGED` com janela de 3 s e ambiguidade explícita |
| mudanças na página | `mutation-tracker` (deltas da captura técnica, sessão inteira) | **não** reaproveitado: observação delimitada por janela, seletor fechado, sem texto para fora |
| raízes observáveis (iframes de mesma origem, shadow) | `NS.capture.observableRoots()` / `frameWindows()` | reutilizados pelo observador |
| privacidade de texto | `NekkoraPrivacy.maskText/looksSecret/sanitizeUrl`, `Privacy` no host | nova política de mensagem e barreiras específicas para os campos novos |
| entrega confiável | outbox/ack/sync/épocas das etapas | o mesmo modelo, com sequência própria e perda declarada sem degradar |
| contrato | `nekkora-bridge/1` + `steps/1` | acréscimo `evidence/1` (capacidade, evento, 2 comandos, estados, resumo) |

## 4. Contratos

Detalhado em `docs/studio-005/bridge-contract.md`. Só acréscimos ao `nekkora-bridge/1`: capacidade `evidence/1`
(exige `steps/1`), evento `studio.recording.evidence`, comandos `evidence.ack/.sync`, `EvidenceState` em
`Recording`/`RenewResult`, `EvidenceSummary` e as limitações `EVIDENCE_UNCONFIRMED`/`EVIDENCE_LOST`. Fixtures: 14 válidas
e 11 inválidas `s005-*` (total 56/35), geradas pelo script oficial byte a byte. PageMap 0.2.0 inalterado.
Compatibilidade nos dois sentidos testada (Studio antigo × extensão nova; o Studio testa a extensão antiga).

## 5. Modelo de evidência

`EvidenceEventPayload` tem identidade (`evidenceId`), sequência própria, vínculo (`eventId`/`stepSequence`, `null` na
ambígua), `observedAt` real, tipo (14, fechado), fonte, relação temporal, confiança sem percentuais, classificação,
documento, detalhes sanitizados (região, mensagem, campo, estado, navegação, sinais, candidatas, sinais na janela) e
limitações. Não tem campo de resultado de negócio.

## 6. Regras de observação

Em `docs/studio-005/evidence-observer.md` e `message-detection.md`: janela de 3 s (até 8 s com carregamento),
`MutationObserver` só com janela aberta, seletor fechado de regiões ARIA, `invalid` nativo sem `reportValidity`,
estado do elemento acionado, linha de base que exclui o que já estava na tela, sem duplicatas, classe CSS sozinha não
é evidência e toda janela termina COMPLETE ou INCOMPLETE.

## 7. Regras de correlação

Em `docs/studio-005/evidence-correlation.md`: mesmo documento; só ações que operam a página (FILL só a validação do
próprio campo); dentro da janela; uma candidata = `TEMPORALLY_ASSOCIATED`; várias = `AMBIGUOUS_FEEDBACK` sem etapa;
navegação com candidatas de até 3 s; vínculo por `epoch|frameId|localSeq`; evidência sai depois da etapa.

## 8. Políticas de privacidade

Em `docs/studio-005/privacy.md`: política de mensagem ("em caso de dúvida, omite") com comparação local aos valores dos
campos, quatro barreiras (content script, service worker, host e Studio), nada de HTML, valores, cookies, tokens ou
PageMap. A homologação no Chrome mostrou só `Senha incorreta.` liberado entre 7 mensagens sensíveis.

## 9. Integração com o domínio

A extensão entrega e o Studio registra pela `EvidenceIntake` (relatório do Studio). O ACK só vem depois do domínio.
As recusas do domínio voltam em `rejected[]` e são contadas.

## 10. Compatibilidade com o editor

A extensão não conhece o editor. A evidência leva o `eventId` da etapa, e é por ele que o Studio encontra a etapa
mesmo depois de ela ser movida, editada ou ter a sessão trocada.

## 11. Undo/Redo

Não se aplica à extensão. A retransmissão (`sync`) reemite a **mesma** identidade, então o Studio não duplica nem
"reaplica" evidências.

## 12. Máquina de estados

Em `docs/studio-005/event-delivery.md`. START, PAUSE (`WINDOW_CUT_BY_PAUSE`), RESUME (época nova), MODE e ROUTE
(`PARTIAL_COVERAGE`), documento substituído (`DOCUMENT_CHANGED` + `WINDOW_CUT_BY_NAVIGATION`), DEGRADED
(`WINDOW_CUT_BY_STOP`, observações posteriores `STALE_EPOCH`), FINISH (drena e espera ACK por 3 s) e CANCEL (descarta).
`CAPTURE_ONLY` não liga o observador.

## 13. Testes

| Arquivo | Testes | Cobre (matriz oficial) |
|---|---|---|
| `test/feedback-rules.test.js` | 8 | 011, 013, 014, 018, 021, 035, 036, 042, 047, 048, 049 |
| `test/studio-evidence.test.js` | 25 | 003, 004, 006, 007, 008, 010, 019, 021, 022, 023, 024, 025, 026, 027, 035, 036, 037, 038, 039, 040, 041, 042, 044, 045, 046, 047, 051, 052, 053, 054, 064 (todo payload validado contra o schema) |
| `EvidencePrivacyTest` (host) | 3 | 047, 048, 049 |
| `Studio005EvidenceIT` (Chrome real) | 13 | 004, 006–022, 024, 025, 034–038, 042–045, 047–049, 051 e desempenho |

## 14. Homologação real

`Studio005EvidenceIT` no Chrome for Testing com a extensão real, o Native Host e um Studio de laboratório, pela cadeia
completa (todas as mensagens validadas contra o contrato: `contractViolations` vazio). Cenários: login com erro, mensagem
tardia, ambígua, validação, pesquisa com carregamento, SPA, iframe/Shadow DOM, dados sensíveis, pausa, `CAPTURE_ONLY`,
Studio antigo, reconexão e desempenho. Resultado: **13/13**. Também passou a homologação com o Studio real e
mouse/teclado do sistema (`Studio005Homologation`, 50/0), descrita no relatório do Studio.

## 15. Evidências

`reports/evidence/S005-login-error-evidence.json`, `S005-validation-evidence.json`, `S005-search-evidence.json`,
`S005-sensitive-evidence.json` e `S005-performance.json` (geradas na última execução da suíte). As capturas da
homologação com o Studio estão no repositório do Studio (`reports/studio-005/evidence/`).

## 16. Desempenho

Em `docs/studio-005/performance.md`. Clique → etapa p50 811/814 ms com e sem evidências (regressão depois da revisão); emissão em 1–2 ms; maior evento
716 bytes; nenhum `MutationObserver` em repouso.

## 17. Falhas encontradas

| Falha | Onde | Diagnóstico |
|---|---|---|
| valor repetido pela página aparecia no log local | IT login/sensível | o texto visível da página entra na captura técnica existente (deltas de mutação) |
| idem em `targets/registry.json` | IT login | `contextTexts` do motor (STUDIO-003) |
| `DOCUMENT_CHANGED` não aparecia | IT SPA | novo documento só chega por `onDocumentReplaced` (a navegação SPA chega como `SPA_ROUTE`/`FRAGMENT`) |
| teste de desempenho sem etapas | IT desempenho | duas abas com o mesmo título confundem a escolha da aba no "Studio" de laboratório |
| aviso não ambíguo com cliques reais | homologação | o clique do Robot leva cerca de 1,2 s; o aviso de 800 ms chegava antes do segundo clique |

## 18. Correções

- `onDocumentReplaced(rec, data)` passa a URL do documento novo e emite a navegação **antes** de cortar as janelas.
- O teste de privacidade separa o canal de evidências (eventos, armazenamento e arquivos do host, sempre) da captura
  técnica (verificada antes de a página repetir o valor). O achado está registrado como limitação, sem ser escondido.
- O IT fecha a página entre as rodadas de desempenho.
- O aviso ambíguo do laboratório passa a sair 2 s depois do primeiro clique (dentro das duas janelas).
- Os rótulos dos testes foram renumerados para a matriz oficial (S005-001..064), e foram acrescentados os testes de degradação/revogação e de Shadow DOM.
- As fixtures S005 foram incorporadas ao gerador oficial.

## 19. Limitações

Em `docs/studio-005/known-limitations.md`: janela fixa, ambiguidade com ações rápidas, novo documento lento, shadow
roots tardios, iframes sem permissão, avisos sem papel ARIA, política conservadora, leitura local dos valores para
comparação, achado da captura técnica, persistência só em `storage.session` e ordem entre documentos.

## 20. Preparação para STUDIO-006

- Persistência: o payload já é o formato estável (identidade, sequência, vínculo por `eventId`), pronto para gravar com
  a automação; `storage.session` continua só como outbox.
- Reconciliação depois de reinício: com persistência, os vínculos `epoch|frame|localSeq → eventId` podem ser
  reconstruídos e as evidências antigas não precisam mais ser descartadas.
- Missão recomendada: privacidade da captura técnica (texto visível em deltas e `contextTexts`).
- Janelas configuráveis por automação e evidências de rede **não** estão no escopo atual e dependem de decisão de produto.

## Anexo — revisão técnica do PR #6 (P1 × 2, P2 × 1, achado de segurança)

Procedimento da revisão: **primeiro os testes que reproduzem, depois a correção localizada, depois a regressão
completa**, nas mesmas branches.

### P1-a — valor digitado que some do campo antes da mensagem

- **Reprodução, antes da correção:**
  - testes Node `REV-P1a` falhando (`messagePolicy` sem as impressões e sem a regra de forma);
  - `Studio005EvidenceIT.s005SensitiveFieldGone` falhando no **Chrome real**: com o campo limpo pela página,
    `"O código Kq7Segredo expirou."` atravessou extensão → host → Studio de laboratório com `availability: AVAILABLE`.
  - Casos cobertos: campo limpo, campo removido da DOM, campo em Shadow DOM e dado de terceiro nunca digitado.
- **Correção:**
  - impressões digitais salgadas dos valores digitados (só hashes, só na memória do documento), tiradas antes da reação
    da página;
  - comparação com os valores atuais percorrendo os shadow roots;
  - regra **fail-closed pela forma** (letras com dígitos, maiúscula interna, nome próprio fora do início) nas quatro
    barreiras: content script, service worker, host (`EvidencePrivacy.looksIdentifying`) e Studio (`Message.of`).
- **Depois da correção:** as 4 mensagens ficam `SUPPRESSED` e nenhum dos valores aparece no evento, no
  `storage.session`, nos arquivos do host ou no Studio. A homologação real continua 50/0.
  Limite declarado: dado pessoal de terceiro só em minúsculas e nunca digitado não é reconhecido por forma (ver `privacy.md`).

### P1-b — reinício do service worker com janela aberta

- **Reprodução:** o teste Node `REV-P1b` (instância nova sobre o mesmo `storage.session`) emitia
  `OBSERVATION_COMPLETE` com `eventId: null`, fora do contrato. Era exatamente o cenário da revisão.
- **Correção:**
  - a janela persiste ao abrir o vínculo mínimo **exato** (`eventId`/`sequence`) e a marca "etapa emitida", gravada na
    emissão da etapa;
  - depois do reinício, o vínculo é reconstruído só desse registro, nunca por proximidade temporal;
  - etapa não emitida → **perda declarada** (`lost`, `EVIDENCE_LOST`), sem evento;
  - invariante em `emit`: sem etapa, só `AMBIGUOUS_FEEDBACK` sai.
- **Provas:**
  - 2 testes Node: etapa emitida e etapa perdida, com a fila seguindo depois;
  - IT `s005ServiceWorkerRestartDuringWindow` com **reinício real** do service worker no Chrome
    (`ServiceWorker.stopAllWorkers`) no meio da janela. Todas as evidências chegaram com o `eventId` exato; a janela
    terminou uma única vez e declarada (`OBSERVATION_INCOMPLETE`/`PARTIAL_COVERAGE`, porque os sensores voltam numa
    época nova); a fila foi reconciliada (`pending = 0`). Arquivo: `reports/evidence/S005-rev-sw-restart.json`.

### P2 — prioridade do Native Host com a fila de evidências (S005-055)

`HostConcurrencyTest.s005055RajadaDeEvidenciasNaoPrendeOControle`. O motor ficou ocupado por `host.hold` de 6 s.
A extensão enviou uma rajada contínua de 300 evidências (1,8 s), e a pausa e a finalização do Studio foram enviadas no
meio dela. Resultado medido:

- repasse da pausa em 1 ms e ida e volta em 2 ms;
- repasse da finalização em 0 ms;
- os dois comandos atendidos **antes do fim da rajada** e com o motor ainda ocupado;
- 300/300 evidências entregues dentro do contrato, com a 3ª barreira aplicada.

O limite de 500 ms das asserções é o mesmo da S002-022. O teste está em `target/studio-002/S005-055-evidence-burst-control.json`.
O caso S005-055 passa a estar **coberto**.

### Achado de segurança — captura técnica antiga

Ele é mantido **separado** da STUDIO-005, como pediu a revisão, e está explícito em `privacy.md` e
`known-limitations.md`: até a correção na origem da captura técnica, **o Recorder não deve ser usado com dados reais de
clientes**. A recomendação é uma missão de segurança de prioridade alta antes de qualquer uso com dados reais.

### Regressão completa depois das correções

| Suíte | Resultado |
|---|---|
| Extensão Java | 237/237 |
| Extensão Node | 133/133 |
| Extensão navegador (6 grupos) | 226/226 (34 + 55 + 47 + 23 + 27 + 40) |
| Studio Windows | 337/337 |
| Studio Linux (Docker, contra a extensão `b9d4e06`) | 337/337 |
| Homologação real `Studio005Homologation` | 50/0 |
