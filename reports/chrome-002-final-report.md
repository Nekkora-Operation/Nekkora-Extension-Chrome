# Relatório final — NEKKORA-CHROME-002

**Componente**: Nekkora Extension Chrome · **Data**: 2026-10-13/14 · **Branch**: `feat/nekkora-chrome-002` (base `00b34c0`)
**Ambiente**: Windows 11 · OpenJDK 21.0.12 · Chrome for Testing 155.0.8059.39 · **Google Chrome 155.0.8059.40** (convencional) · Selenium 4.51 · Node 24.19

## 1. Resumo

A extensão passou a **capturar automaticamente, de forma incremental e contínua**, enquanto a inspeção
(ou uma gravação) estiver ativa. O par "Ativar/Desativar inspeção" virou **um botão único** com estados
e `aria-pressed`; a captura manual virou **Forçar captura completa** na área DEV (inexistente em PRD);
a seleção **reutiliza o snapshot sincronizado**; a exportação do PageMap ocorre na **finalização da
gravação** (`recording.session.finished`, simulado), de forma idempotente, atômica e validada. Iframes
de outra origem local permitida, Shadow DOM criado depois da base e AccName 1.2 foram incorporados. O
Nekkora Studio **não** foi iniciado (somente contratos de eventos/ciclo de vida).

| Indicador | Resultado |
|---|---|
| Homologação no navegador (execução final, suíte completa, após as duas revisões do PR #1) | **117/117** — 58 da CHROME-001 + 51 da CHROME-002 + 8 regressões das revisões ([matriz](test-matrix-integration.md)) |
| Matriz C002-001..050 | **50/50** casos cobertos e aprovados (+ C002-RAP alterações rápidas, + §30 Chrome convencional) |
| Sessões prolongadas (C002-041) | 5, 15 e 30 min aprovadas |
| Testes sem navegador | **171/171** (core 129, host 25, laboratório 3, contrato 2, extensão 12) |
| Revisões do PR #1 | 1ª: 4 P1 + 2 pontos adicionais; 2ª: 2 pontos residuais — todos corrigidos, cada um com teste de regressão que falha no código anterior (seções 13 e 14) |
| Testes da CHROME-001 removidos ou afrouxados | **nenhum** (`PanelUiIT` adaptado ao botão único, mesmas verificações) |
| Limites de desempenho da CHROME-001 | mantidos e atendidos (`PerformanceIT`) |

## 2. Linha de base (CHROME-001, reproduzida antes das alterações)

58/58 aprovados; 10.000 elementos: captura 316,9 ms, bloqueio da página 339 ms, ciclo 534 ms, seleção
380 ms, reencontro 52,9 ms, exportação 3.560 ms, heap do host 827,5 MB (sem GC). Arquivo:
[`chrome-002/baseline/`](chrome-002/baseline/).

## 3. Alterações

| Área | O que mudou |
|---|---|
| Painel | botão único (`INACTIVE/ACTIVATING/ACTIVE/DEACTIVATING/DEGRADED/ERROR`, `aria-pressed`, `#C2466D`), status (Captura, Elementos, Última atualização, Deltas), área DEV removida do DOM em PRD |
| Service worker | dividido em `runtime`, `config`, `session-store`, `capture-session`, `frames`, `inspection`, `recording` (+ roteamento); comandos novos e legados |
| Content script | sessão (BUFFER → `confirmBase` → deltas sequenciados), captura cooperativa em partes, reconciliação de shadow roots, geometria, fontes de frame, handshake, `textSegments` |
| Motor Java | `HostCaptureSession` (época/seq por fonte, recusas), `capture.session.*`, `capture.pin`, `export.session` (`ExportService` idempotente, fluxo, atômico, SHA-256, schema), `host.memory`, `FrameMerger`, poda de escopos desconectados, PageMap em blocos paralelos, AccName 1.2 |
| Contratos | `capture-session.schema.json` (novo); `native-protocol` e `raw-snapshot` com extensões aditivas; **PageMap continua 0.1.0** |
| Laboratório | escutas na porta +1 e em `127.0.0.2`; 8 páginas novas (`test-lab/pages/chrome002/`), SPA com fragmento |
| Construção | `build.ps1 -Mode PRD` → `dist/prd` (sem harness, `BUILD_MODE='PRD'`) |
| Manifest | 0.2.0; `all_frames: true`, `alarms` — **sem** novas origens |

Commits: `ac32131`, `ba555ef`, `593c3d3`, `c1a43bd`, `0cd545f`, `0f49538`, `fffff35`, `895cb01`, `f8c1566`, `5ae07e5` e o commit de fechamento.

## 4. Arquitetura

Ver [docs/architecture.md](../docs/architecture.md#visão-chrome-002) e os documentos específicos:
[ciclo de captura](../docs/chrome-002/capture-lifecycle.md) · [inspeção](../docs/chrome-002/inspection.md) ·
[exportação](../docs/chrome-002/export.md) · [iframes/Shadow DOM/AccName](../docs/chrome-002/frames-shadow-accname.md) ·
[desempenho](../docs/chrome-002/performance.md). Regras de análise e de consistência continuam **somente
em Java**; o JavaScript faz apenas o que depende das APIs do navegador.

## 5. Testes

| Classe | Casos | Resultado |
|---|---|---|
| `Chrome002LifecycleIT` | C002-001..020 | 19/19 (012 e 013 no mesmo teste) |
| `Chrome002ExportIT` | 021–025, 048–050 | 8/8 |
| `Chrome002DynamicIT` | 026–037, 043, 044 | 14/14 |
| `Chrome002PerformanceIT` | 038–042, 045, RAP | 7/7 |
| `Chrome002ContractIT` | 046 | 1/1 |
| `Chrome002NativePanelIT` | 047 (painel lateral **nativo**) | 1/1 |
| `BrandedChromeIT` | §30 Chrome convencional | 1/1 |
| Suíte CHROME-001 (11 classes) | T001–T040, N-A..N-E, C-1..C-9 | 58/58 |

Unitários novos: `SessionCommandsTest` (8: deltas sequenciados, exportação atômica/idempotente/simultânea,
falha não memorizada, equivalência com a exportação anterior, um arquivo por operação, frame sem
anexação, conformidade com o protocolo), `FrameSourcesTest` (4), `AccNameConformanceTest` (11),
equivalência paralelo × sequencial no `PageMapAssemblerTest`, `config-mode.test.js` (DEV e construção PRD).

## 6. Regressões

Nenhuma regressão na suíte da CHROME-001 (58/58 na execução final). Defeitos encontrados **durante** a
missão e corrigidos: overlay residual após desativar; deltas de frame trocando o documento principal;
falha de origem sem estado `ERROR`; geometria defasada no estado incremental; expectativa incorreta de
autorreferência no AccName (teste corrigido conforme a especificação); **duas gravações na mesma sessão
sobrescreviam o mesmo arquivo exportado** (corrigido: um arquivo por sessão + operação).

## 7. Desempenho (antes × depois, mesma execução)

| 10.000 elementos | CHROME-001 | CHROME-002 |
|---|---|---|
| Maior bloqueio contínuo da página na captura | 356,4 ms | **27,5 ms** (maior fatia 8,2 ms) |
| Seleção | 350 ms (captura completa por clique) | **85,0 ms** (snapshot reutilizado) |
| Exportação do PageMap | 2.702,0 ms | **1.096,8 ms** |
| 20.000: bloqueio / exportação | 1.073,4 ms / 12.167 ms | **26,3 ms / 2.447,1 ms** |

(Execução final após a revisão; a rodada anterior mediu 358,9→21,5 ms, 79,2 ms e 2.410,5→1.070,1 ms — mesma ordem de grandeza.)

Alterações a 10/50/100 mutações/s: 9–10 deltas em 5 s, 0 ressincronizações, máx. 6,6 ms no host.
Detalhes e critérios (definidos antes): [docs/chrome-002/performance.md](../docs/chrome-002/performance.md).

## 8. Memória

Heap após GC do processo Java: ocioso 9,0 MB; 1 aba de 10k 18,4 MB; **três abas de 10k 37,2 MB**
(comprometido 160 MB, RSS 199 MB); após liberar as sessões 9,1 MB e **0 capturas retidas**. Sessões de
5/15/30 min a 10 mutações/s: heap 7,8 → 8,0 MB, 0 ressincronizações, 1 captura completa, registro de nós
limitado (331 para 271 nós iniciais após 18.011 mutações).

## 9. Exportação

`recording.session.finished` → barreira (deltas pendentes + geometria) → `export.session` → escrita em
fluxo em `.tmp` → validação do schema → movimento atômico → `RecordingExportResult` (status, schemaVersion,
captureId, elementCount, path, complete, coverage, syncState, validation, errors, sha256). Idempotente por
gravação; falhas explícitas (`EXPORT_WRITE_FAILED`, `SNAPSHOT_MISSING`, `RECORDING_UNKNOWN`) e não
memorizadas; desativar a inspeção nunca exporta; valores sensíveis alterados por mutação não aparecem.

## 10. Limitações

Lista completa em [docs/limitations.md](../docs/limitations.md). Principais: Shadow DOM fechado não
inventariado; iframes sem permissão e `sandbox` não inspecionados; `attachShadow()` tardio detectado em
≈ 1,07 s (reconciliação, sem monkeypatch); geometria atualizada só em rolagem/redimensionamento e na
exportação; com a inspeção ativa, cliques são seleções; reinício do service worker implica nova base;
somente o contrato de gravação (sem gravador/Studio/Platform); instalação do host validada só no Windows;
licença pendente.

## 11. Evidências

- Matriz da execução final: [test-matrix-integration.md](test-matrix-integration.md) (109/109), [test-matrix-unit.md](test-matrix-unit.md).
- Desempenho: [chrome-002/performance.md](chrome-002/performance.md) / `.json`.
- Painel: [inativo](evidence/C002-001-panel-inactive.png), [ativo](evidence/C002-002-panel-active.png),
  [falha na ativação](evidence/C002-005-panel-activation-failure.png), [reaberto](evidence/C002-013-panel-reopened.png),
  [PRD sem DEV](evidence/C002-048-panel-prd.png), [**painel lateral nativo**](evidence/C002-047-native-side-panel.png).
- Iframe de outra origem com destaque: [C002-030](evidence/C002-030-iframe-other-origin-highlight.png), [frames](evidence/C002-030-frames.json).
- Destaque após re-renderização: [C002-043](evidence/C002-043-highlight-after-rerender.png).
- Resultado de exportação: [C002-022](evidence/C002-022-export-result.json).
- AccName × Chrome e latência de `attachShadow` tardio: [C002-dynamic-evidence.json](evidence/C002-dynamic-evidence.json).
- Chrome convencional: [C002-branded-chrome.json](evidence/C002-branded-chrome.json), [página](evidence/C002-030B-branded-page.png).

## 12. Recomendações

1. Nekkora Studio: consumir `capture-session.schema.json` (eventos `recording.session.*` e `RecordingExportResult`) e o PageMap 0.1.0; não depender do harness.
2. Decidir a licença (D-010) antes de qualquer distribuição; PRD continua restrito ao laboratório local.
3. Avaliar a pausa temporária do inspector (ex.: tecla) para interagir com a página durante a inspeção.
4. Validar instalação do host em Linux/macOS se houver demanda.
5. Considerar `ResizeObserver` seletivo para elementos selecionados/registrados se a geometria em tempo real se tornar requisito.

## 13. Rodada de correções da revisão do PR #1

| Ponto | Correção | Regressão (falha no código anterior, passa no atual) |
|---|---|---|
| P1 — sincronização concluída após erro | `synchronize()` exige confirmação de **todas** as fontes obrigatórias (documento + frames anexados); erro de flush, rastreador desligado ou envio recusado ⇒ `INCOMPLETE` com `unconfirmed`; frame removido (verificado por `webNavigation.getFrame`) deixa de ser obrigatório | C002-R01 |
| P1 — exportação incompleta memorizada | finalização exige `SYNCHRONIZED` no navegador (`SYNC_INCOMPLETE`) **e** no motor (`SYNC_NOT_CONFIRMED`); somente `EXPORTED` completo é memorizado; truncado/diagnóstico ⇒ `PARTIAL` em arquivo `…-partial.json`, nunca memorizado (`mode: DIAGNOSTIC`) | C002-R02; `SessionCommandsTest.finalExportRequiresConfirmedSynchronizationAndPartialIsNeverMemorized` |
| P1 — gravação perdida no reinício do service worker | registro das gravações persistido em `chrome.storage.session` e restaurado antes de qualquer mensagem; finalização interrompida volta a `RECORDING` | C002-R03 |
| P1 — mistura de alvos entre abas | cada alvo tem dono (aba + sessão de captura) no registro do motor; exportação da sessão, exportação manual e reencontro automático filtram pelo dono | C002-R04; `SessionCommandsTest.targetsAreIsolatedBetweenTabsWithTheSameUrl` |
| Finalização × cancelamento | eventos de uma gravação serializados por exclusão mútua: um único desfecho; cancelar após exportar não desfaz | C002-R05 (6 rodadas, ordens alternadas) |
| Limite de 50 registros | a retenção descarta somente registros encerrados; gravações ativas nunca saem | C002-R06 (55 gravações simultâneas) |

Validação de que os testes são regressões reais: com o código Java anterior os dois testes unitários
novos falham; com os módulos anteriores do service worker, C002-R01, R02, R03, R05 e R06 falham (R04
depende do motor e é coberto pelo teste unitário). Contrato `capture-session.schema.json` atualizado
(`PARTIAL`, `INCOMPLETE`, `EXPORTED` ⇒ `complete=true` e `SYNCHRONIZED`); decisões D-032..D-035.
Limitação nova declarada: alvos registrados antes desta versão, sem dono, não entram em exportações de sessão.

## 14. Segunda revisão do PR #1 — ajustes finais

| Ponto | Correção | Regressão (falha no código anterior, passa no atual) |
|---|---|---|
| A — reencontro explícito sem validar o dono | `target.resolve` recusa alvo de outra aba com `TARGET_NOT_OWNED` (a identidade não é resolvida nem atualizada); registros antigos sem dono podem ser resolvidos mas nunca atualizados. O reencontro automático passou a usar os alvos da **sessão do delta** (`ownedBySession`), não mais todos os da aba | `explicitResolutionOfAnotherTabsTargetIsRefused`, `autoResolutionOnlyConsidersTargetsOfTheCurrentSession`; C002-R07, C002-R08 |
| B — exportação normal truncada com caminho de arquivo completo | o nome do arquivo é decidido **depois** de conhecer o resultado: completo → `session-<s>-<op>.json`; parcial (truncado ou diagnóstico) → `…-partial-<instante>-<n>.json`, único, nunca sobrescrito por uma exportação completa posterior | `truncatedFinalExportIsPartialAndNeverOverwritten` |

O evento `delta` passou a informar os alvos reencontrados automaticamente (`autoResolved`), o que permite
verificar o isolamento no navegador. Execução final: 117/117 no navegador, 171/171 sem navegador;
desempenho na mesma ordem (10k: bloqueio 349 → 25 ms, seleção 76 ms, exportação 2.488 → 1.008 ms).
Decisões D-035 (ampliada) e D-036.
