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

## Como executar

```powershell
.\scripts\run-tests.ps1                       # tudo
.\scripts\run-tests.ps1 -Suite unit           # sem navegador
.\scripts\run-tests.ps1 -Suite integration    # Chrome for Testing
.\scripts\run-tests.ps1 -Suite integration -Test "ResolutionIT" -Headless
```

Propriedades úteis: `-Dnekkora.headless=true`, `-Dnekkora.lab.port=8765`, `-Dnekkora.cft.version=<versão>`,
`-Dnekkora.skipHostInstall=true` (usa o registro existente).
