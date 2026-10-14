# Plano de testes

## Camadas

| Camada | Ferramenta | Onde | Navegador | Quantidade |
|---|---|---|---|---|
| Unitários do motor | JUnit 5 + fixtures HTML (jsoup só em teste) | `java-core/core/src/test` | não | 113 |
| Unitários do host (protocolo, fragmentação, comandos, persistência) | JUnit 5 | `java-core/native-host/src/test` | não | 12 |
| Unitários do laboratório | JUnit 5 + `HttpClient` | `java-core/lab-server/src/test` | não | 3 |
| Contrato sem navegador | JUnit 5 + networknt json-schema-validator | `integration-tests/.../PageMapContractTest` | não | 2 |
| Unitários da extensão | `node --test` (sem dependências) | `chrome-extension/test` | não | 10 |
| **Homologação** | JUnit 5 + Selenium 4.51 + Chrome for Testing 155 | `integration-tests/.../*IT` | **sim, real** | 58 |

A homologação usa o caminho real completo: página do laboratório servida em `127.0.0.1` → content
script → service worker → Native Messaging → processo Java instalado pelo script oficial → resposta →
destaque/painel. Interações de página usam **ações de ponteiro e teclado do WebDriver** (eventos
confiáveis), nunca `element.click()` nem chamadas diretas de funções da página.

## Matriz obrigatória → testes

| ID | Teste | Página |
|---|---|---|
| T001 | `CaptureClassificationIT.stableId` | simple/form-basics |
| T002 | `CaptureClassificationIT.withoutId` | simple/form-basics |
| T003 | `CaptureClassificationIT.withoutName` | simple/form-basics |
| T004 | `FamilyAndLocatorsIT.duplicatedId` | complex/duplicated-ids |
| T005 | `ResolutionIT.dynamicIds` | complex/dynamic-ids |
| T006 | `FamilyAndLocatorsIT.duplicatedText` | complex/duplicated-ids |
| T007 | `FamilyAndLocatorsIT.sameButtonsDifferentTables` | complex/duplicated-ids |
| T008 | `FamilyAndLocatorsIT.sameButtonsDifferentRows` | complex/duplicated-ids |
| T009 | `ResolutionIT.reorder` | complex/duplicated-ids |
| T010 | `CaptureClassificationIT.nativeSelect` | simple/form-basics |
| T011 | `CaptureClassificationIT.optgroups` | simple/form-basics |
| T012 | `ComplexComponentsIT.customDropdown` | complex/dropdowns |
| T013 | `ComplexComponentsIT.portalDropdown` | complex/dropdowns |
| T014 | `ComplexComponentsIT.dynamicDropdown` | complex/dropdowns |
| T015 | `ComplexComponentsIT.modalContext` | intermediate/widgets |
| T016 | `ComplexComponentsIT.modalStates` | intermediate/widgets |
| T017 | `ComplexComponentsIT.openShadow` | complex/shadow-iframes |
| T018 | `ComplexComponentsIT.closedShadow` | complex/shadow-iframes |
| T019 | `ComplexComponentsIT.accessibleIframe` | complex/shadow-iframes |
| T020 | `ComplexComponentsIT.inaccessibleIframes` | complex/shadow-iframes |
| T021 | `ComplexComponentsIT.virtualizedList` | complex/dropdowns |
| T022 | `NavigationInteractionIT.spaNavigation` | spa/app |
| T023 | `NavigationInteractionIT.queryChange` | spa/app |
| T024 | `NavigationInteractionIT.searchByClick` | /clientes |
| T025 | `ResolutionIT.removed` | complex/rerender |
| T026 | `ResolutionIT.replaced` | complex/rerender |
| T027 | `CaptureClassificationIT.disabled` | simple/form-basics |
| T028 | `CaptureClassificationIT.isolatedText` | simple/form-basics |
| T029 | `CaptureClassificationIT.externalLabel` | simple/form-basics |
| T030 | `CaptureClassificationIT.contentEditable` | simple/form-basics |
| T031 | `ResolutionIT.responsive` | complex/responsive |
| T032 | `NegativeCasesIT.ambiguousTarget` | complex/duplicated-ids |
| T033 | `CaptureClassificationIT.sensitiveDataNeverExported` | simple/form-basics |
| T034 | `InspectionVisualIT.pinkHighlight` | complex/responsive |
| T035 | `InspectionVisualIT.disableRemovesEverything` | complex/responsive |
| T036 | `PerformanceIT.largePages` | /generated/large?n=100…10000 |
| T037 | `PerformanceIT.frequentMutations` | complex/rerender |
| T038 | `NavigationInteractionIT.testHooks` | /clientes, frame-inner |
| T039 | `InspectionVisualIT.noAccidentalActions` | inspector/actions |
| T040 | `ResolutionIT.semanticChangeSameId` | complex/dynamic-ids |

Casos negativos (§23): `NegativeCasesIT` — N-A `AMBIGUOUS`, N-B `STALE_TARGET` e `NOT_FOUND` (após
recarga), N-C `IDENTITY_MISMATCH`, N-D `SCOPE_UNAVAILABLE`, N-E `INSUFFICIENT_EVIDENCE`.
Comunicação: `HostCommunicationIT` (C-1..C-5). Contrato: `PageMapContractTest` (C-6, C-7) e
`ContractIT` (C-8). Interface: `PanelUiIT` (C-9).

Critério: um caso só é aprovado se a captura **e** o comportamento posterior (reencontro, recusa,
destaque, desativação…) forem verificados. Nenhum teste negativo foi removido ou afrouxado para passar.

## CHROME-002 — matriz C002-001..050

Todos os testes da CHROME-001 foram mantidos (somente `PanelUiIT` foi adaptado ao botão único, mudança
exigida pela missão; as verificações foram preservadas). Nos cenários com a inspeção ativa, os cliques
reais são seleções; as ações do cenário são acionadas pelo manipulador do botão (`onclick()`).

| ID | Caso | Teste |
|---|---|---|
| C002-001 | Botão inicialmente inativo | `Chrome002LifecycleIT.initiallyInactive` |
| C002-002 | Ativação (captura inicial, rastreamento, ouvintes, texto) | `Chrome002LifecycleIT.activation` |
| C002-003 | Desativação | `Chrome002LifecycleIT.deactivation` |
| C002-004 | Clique duplo | `Chrome002LifecycleIT.doubleClick` |
| C002-005 | Falha na ativação (origem e motor ausente) | `Chrome002LifecycleIT.activationFailure` |
| C002-006 | Captura inicial automática | `Chrome002LifecycleIT.initialCaptureAutomatic` |
| C002-007 | Sem recapturas desnecessárias | `Chrome002LifecycleIT.noUnnecessaryRecaptures` |
| C002-008 | Mutação simples | `Chrome002LifecycleIT.simpleMutation` |
| C002-009 | Múltiplas mutações / agrupamento | `Chrome002LifecycleIT.batchedMutations` |
| C002-010 | Seleção após mutação (barreira) | `Chrome002LifecycleIT.selectionAfterMutation` |
| C002-011 | Dez seleções sem dez capturas | `Chrome002LifecycleIT.tenSelections` |
| C002-012/013 | Fechar e reabrir o painel | `Chrome002LifecycleIT.closeAndReopenPanel` |
| C002-014 | Abas independentes | `Chrome002LifecycleIT.independentTabs` |
| C002-015 | Recarga | `Chrome002LifecycleIT.reload` |
| C002-016 | SPA (rota, query, fragmento) | `Chrome002LifecycleIT.spa` |
| C002-017 | Perda de delta (limite de ressincronização) | `Chrome002LifecycleIT.deltaLoss` |
| C002-018 | Reinício do motor Java | `Chrome002LifecycleIT.hostRestart` |
| C002-019 | Reinício do service worker | `Chrome002LifecycleIT.serviceWorkerRestart` |
| C002-020 | Desativação remove observadores | `Chrome002LifecycleIT.deactivationRemovesObservers` |
| C002-021 | Exportação manual DEV | `Chrome002ExportIT.manualExportDev` |
| C002-022 | Exportação PRD na finalização | `Chrome002ExportIT.prdExportOnFinish` |
| C002-023 | Finalização duplicada/simultânea idempotente | `Chrome002ExportIT.duplicateFinalization` |
| C002-024 | Finalização com delta pendente | `Chrome002ExportIT.finalizationWithPendingDelta` |
| C002-025 | Falha de exportação e nova tentativa | `Chrome002ExportIT.exportFailure` |
| C002-026 | Novo shadow root (aninhado, alterado, slots) | `Chrome002DynamicIT.newShadowRoot` |
| C002-027 | Shadow root removido | `Chrome002DynamicIT.removedShadowRoot` |
| C002-028 | attachShadow em host existente (latência medida) | `Chrome002DynamicIT.shadowAttachedToExistingHost` |
| C002-029 | Iframe de mesma origem | `Chrome002DynamicIT.sameOriginIframe` |
| C002-030 | Iframe de outra origem local (aninhado, shadow, ids duplicados) | `Chrome002DynamicIT.otherOriginIframe` |
| C002-031 | Iframe dinâmico (inserido, removido, substituído) | `Chrome002DynamicIT.dynamicIframes` |
| C002-032 | Iframe sem permissão e sandbox | `Chrome002DynamicIT.iframeWithoutPermission` |
| C002-033 | AccName recursivo | `Chrome002DynamicIT.accnameRecursive` |
| C002-034 | aria-labelledby múltiplo | `Chrome002DynamicIT.accnameMultiple` |
| C002-035 | Referência circular | `Chrome002DynamicIT.accnameCircular` |
| C002-036 | Oculto referenciado | `Chrome002DynamicIT.accnameHiddenReferenced` |
| C002-037 | Botão com ícone + HTML-AAM (comparação com o Chrome) | `Chrome002DynamicIT.accnameIconButtonsAndHtmlAam` |
| C002-038 | 100 a 20.000 elementos / export 10k | `Chrome002PerformanceIT.sizes` |
| C002-039 | Captura cooperativa (base + delta) | `Chrome002PerformanceIT.sizes, cooperativeConsistency` |
| C002-040 | Memória: pequena, média, grande, três grandes | `Chrome002PerformanceIT.memoryProfiles` |
| C002-041 | Sessão prolongada (5/15/30 min) | `Chrome002PerformanceIT.longSession` |
| C002-042 | Descarte de sessão | `Chrome002PerformanceIT.sessionDiscard` |
| C002-043 | Destaque após re-renderização | `Chrome002DynamicIT.highlightAfterRerender` |
| C002-044 | Ambiguidade após mutação | `Chrome002DynamicIT.ambiguityAfterMutation` |
| C002-045 | PageMap incremental × completo | `Chrome002PerformanceIT.incrementalEqualsFull` |
| C002-046 | Contrato v0.1.0 e contratos novos | `Chrome002ContractIT.contracts` |
| C002-047 | Painel lateral nativo | `Chrome002NativePanelIT.nativeSidePanel` |
| C002-048 | Recursos DEV não expostos em PRD | `Chrome002ExportIT.prdHidesDevFeatures` |
| C002-049 | Fim da inspeção sem gravação não exporta | `Chrome002ExportIT.inspectionEndWithoutRecordingDoesNotExport` |
| C002-050 | Conteúdo sensível em mutações não exportado | `Chrome002ExportIT.sensitiveMutationsNotExported` |
| C002-R01 | Revisão: erro de flush nunca resulta em SYNCHRONIZED (documento e frame) | `Chrome002ReviewIT.synchronizationPropagatesFlushErrors` |
| C002-R02 | Revisão: finalização sem sincronização confirmada falha sem memorizar | `Chrome002ReviewIT.finishRequiresConfirmedSynchronization` |
| C002-R03 | Revisão: gravação sobrevive ao reinício do service worker | `Chrome002ReviewIT.recordingSurvivesServiceWorkerRestart` |
| C002-R04 | Revisão: alvos isolados entre abas na mesma página | `Chrome002ReviewIT.targetsIsolatedBetweenTabs` |
| C002-R05 | Revisão: finalização × cancelamento simultâneos | `Chrome002ReviewIT.finishAndCancelConcurrently` |
| C002-R06 | Revisão: retenção nunca descarta gravações ativas | `Chrome002ReviewIT.retentionNeverDropsActiveRecordings` |
| C002-R07 | Revisão 2: reencontro explícito de alvo de outra aba recusado | `Chrome002ReviewIT.explicitResolutionAcrossTabsRefused` |
| C002-R08 | Revisão 2: reencontro automático ignora alvos de sessão anterior da mesma aba | `Chrome002ReviewIT.autoResolutionIgnoresPreviousSessionTargets` |
| §30 | Chrome convencional (§30) | `BrandedChromeIT.brandedChromeHomologation` |

Páginas novas (`test-lab/pages/chrome002/`): `continuous.html`, `rapid.html` (10/50/100 mutações/s),
`shadow-dynamic.html`, `iframes.html` (+ `frame-child.html`, `frame-nested.html`; porta +1 e
`127.0.0.2`), `accessible-names.html`, `recording.html`; SPA com fragmento (`spa/app.html`).

Critérios de desempenho da CHROME-002 (definidos antes da medição) estão no Javadoc de
`Chrome002PerformanceIT` e em [`docs/chrome-002/performance.md`](chrome-002/performance.md).

## Desempenho

Orçamento provisório definido antes das medições (asserções em `PerformanceIT`):

| Métrica (página de 10.000 elementos) | Orçamento | Medido (execução final) |
|---|---|---|
| Captura no navegador | ≤ 3.000 ms | 318,6 ms |
| Classificação Java | ≤ 2.000 ms | 18,5 ms |
| Ciclo de captura (navegador + transporte + host) | ≤ 8.000 ms | 531,8 ms |
| Reencontro | ≤ 1.500 ms | 56,4 ms |
| Geração de localizadores (média por elemento interativo) | ≤ 25 ms | 0,88 ms |
| Processamento de delta no host (T037) | ≤ 200 ms | 7,6 ms (máx.) |

Valores completos em [`reports/performance.md`](../reports/performance.md). O orçamento foi mantido
(não afrouxado); a exportação completa do PageMap de 10.000 elementos (3,3 s) não tem orçamento
definido e é registrada como ponto de atenção.

## CHROME-003

| Matriz | Classe | Casos | Conteúdo |
|---|---|---|---|
| F003-001..020 | `Chrome003FrameIT` | 20 | grafo documental, ids do Chrome × lógicos, URL declarada × efetiva × origem, cenários A–J, sandbox, outra origem aninhada, carregamento lento, observação contínua, painel, PageMap 0.2.0 |
| P003-001..015 | `Chrome003ProtectionIT` | 15 | LAB-P01..P11, estados, confiança, localização, passividade, recomendações, catálogo e determinismo |
| N003-A..J | `Chrome003NegativeIT` | 10 | origem sem permissão, nó inexistente, handshake forjado, destaque sem iframe, interior inacessível, documento trocado, shadow fechado, frame sem content script, página inalterada, sem captura |
| I003-01..10 | `Chrome003IntegratedIT` | 10 | cenários integrados (captura, hierarquia, identificação, família, localizadores, trajetória, reencontro, diagnóstico, evidências, limitações) |
| D003-001..002 | `Chrome003PerformanceIT` | 2 | 1/5/10/25 iframes e profundidades 1–5; comparação com o baseline da CHROME-002 |
| unitários | `FrameIntelligenceTest`, `ProtectionDetectorTest`, `AutomationDiagnosticsTest`, `FrameCommandsTest`, `LabServerTest` | 14 + 17 + 5 + 2 + 1 | regras do motor sem navegador |

Baseline: `reports/chrome-003/baseline/` (CHROME-002 na `main`: 117/117 no navegador). Todos os testes anteriores
continuam na suíte; nenhum foi removido ou afrouxado (as asserções de versão `0.1.0` → `0.2.0` acompanham a mudança
intencional de contrato — D-037).

## Como executar

```powershell
.\scripts\run-tests.ps1                       # tudo
.\scripts\run-tests.ps1 -Suite unit           # sem navegador
.\scripts\run-tests.ps1 -Suite integration    # Chrome for Testing
.\scripts\run-tests.ps1 -Suite integration -Test "ResolutionIT" -Headless
```

Propriedades úteis: `-Dnekkora.headless=true`, `-Dnekkora.lab.port=8765`, `-Dnekkora.cft.version=<versão>`,
`-Dnekkora.skipHostInstall=true` (usa o registro existente), `-Dnekkora.long.minutes=15` (C002-041),
`-Dnekkora.branded.chrome=<chrome.exe>` (homologação no Chrome convencional; ignorada se ausente).
