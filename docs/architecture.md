# Arquitetura

## Visão geral

Dois ambientes, com uma fronteira explícita:

| Ambiente | Responsabilidades | Por quê |
|---|---|---|
| **Chrome** (content scripts + service worker, JS puro) | ler a DOM, observar mutações, executar localizadores na DOM real, destacar, interceptar eventos de inspeção, ler hooks de teste declarados, falar com o host | dependem de APIs do navegador; extensões não executam bytecode Java |
| **Java 21** (processo local via Native Messaging) | classificar, montar a família, gerar e pontuar localizadores, registrar identidades, resolver alvos, comparar capturas, aplicar deltas, interpretar URL, analisar interações, montar e persistir o PageMap | núcleo determinístico, testável sem navegador, reutilizável pelo Nekkora Studio |

O JavaScript nunca decide classificação, família, estabilidade ou resolução.

## Módulos Java

```mermaid
flowchart LR
  core["nekkora-extension-core<br/>(domínio, sem dependências)"]
  host["nekkora-native-host<br/>(Jackson, protocolo, sessão, persistência)"]
  lab["nekkora-lab-server<br/>(JDK HttpServer)"]
  it["nekkora-integration-tests<br/>(Selenium, CfT, JSON Schema)"]
  host --> core
  it --> host
  it --> lab
```

### `nekkora-extension-core` — pacotes

| Pacote | Responsabilidade (SRP) |
|---|---|
| `snapshot` | modelo de entrada (`RawSnapshot`, `RawNode`, `NodeState`) e `PageModel` indexado (ordem DFS composta, subárvores, escopos, índices por id/tag) |
| `text` | normalização em camadas (`normalize` preserva identidade; `fold`/`maskDigits` só para comparação) |
| `semantic` | `SemanticClassifier` (nativo × ARIA, evidências, incertezas), `AccessibleNameCalculator`, `SensitivityPolicy`, condições |
| `context` | `ContextGraph`: família (`ElementFamily`), relações declaradas/inferidas (`ContextRelation`) |
| `locator.query` | localizador estruturado (`LocatorQuery` → passos → predicados), avaliador no snapshot, renderização CSS/XPath |
| `locator.strategy` | estratégias plugáveis (`LocatorStrategy`) e fábrica de passos |
| `locator` | `LocatorEngine` (pontuação), estabilidade heurística × observada, descritores |
| `identity` | assinatura, discriminadores, comparação de características, `TargetIdentity` |
| `resolve` | `TargetResolver` e `ResolutionResult` |
| `options` | conjuntos de opções (select, combobox, listbox, portal, dinâmico, virtualizado) |
| `navigation` | contexto de URL (observado × inferido) e diferenças |
| `mutation` | deltas incrementais, comparação de capturas, `MutationRecord` |
| `interaction` | antes/depois de interações com fonte de cada fato (URL, DOM, Resource Timing, instrumentação) |
| `coverage` | cobertura e limites declarados |
| `pagemap` | montagem do PageMap 0.1.0 |
| `analysis` | fachada `PageAnalyzer` (snapshot → análise; reanálise incremental) |

### SOLID na prática

- **S** — cada pacote acima tem uma razão para mudar; o host separa protocolo (`protocol`), mapeamento JSON (`json`), estado (`session`), persistência (`persistence`) e comandos (`commands`, um grupo por assunto).
- **O** — nova estratégia de localização = nova classe que implementa `LocatorStrategy` registrada em `LocatorEngine.defaultStrategies()`; novo comando = novo `CommandGroup`. Motor e roteador não mudam.
- **L** — todas as estratégias respeitam o contrato "propor apenas localizadores que incluem o alvo; não decidir o melhor"; o motor descarta propostas que violam o contrato.
- **I** — interfaces pequenas: `LocatorStrategy`, `CommandHandler`, `CommandGroup`, `StabilityObservations`.
- **D** — o domínio não conhece Chrome, Jackson, Selenium nem banco: anotações de polimorfismo JSON vivem em *mix-ins* no host (`JsonCodec`); a extensão só conhece o protocolo.

## Extensão (MV3)

| Arquivo | Papel |
|---|---|
| `content/capture.js` | DOM Capture Engine: árvore composta em pré-ordem; estados (`checkVisibility`, `:disabled`, `:modal`, inércia); shadow roots abertos inventariados, fechados detectados via `chrome.dom.openOrClosedShadowRoot`; iframes acessíveis percorridos, inacessíveis declarados |
| `content/node-registry.js` | ids temporários por `WeakMap`/`WeakRef` (sem reter nós destruídos) e `pageSessionId` |
| `content/sensitivity.js` | política de privacidade aplicada no navegador |
| `content/mutation-tracker.js` | `MutationObserver` por raiz observável, lotes (100 ms / máx. 500 ms), filtro do overlay, deltas encadeados por captura base |
| `content/locator-runner.js` | executa CSS/XPath na DOM real, atravessando a cadeia de escopos |
| `content/highlighter.js` | overlay com shadow root fechado; acompanha por `requestAnimationFrame` só enquanto há destaque; detecta desconexão do alvo |
| `content/inspector.js` | modos OFF/INSPECT; bloqueio de `pointerdown/mousedown/mouseup/click/submit…` em fase de captura |
| `background/native-client.js` | cliente nekkora-nm/1: correlação, fragmentação, timeout, reconexão com espera progressiva |
| `background/service-worker.js` | fluxos (captura, seleção, registro, verificação, reencontro, deltas, SPA, hooks de teste) |
| `ui/panel.*` | painel lateral mínimo |
| `ui/harness.*` | página interna usada pelos testes automatizados |

## Sequência de uma seleção

```mermaid
sequenceDiagram
  participant U as Usuário
  participant CS as Content script
  participant SW as Service worker
  participant H as Host Java
  U->>CS: clique (modo inspeção)
  CS->>CS: preventDefault + stopImmediatePropagation
  CS->>SW: element-selected {nodeId}
  SW->>CS: cs.capture
  CS-->>SW: RawSnapshot
  SW->>H: snapshot.submit
  SW->>H: element.inspect {nodeId}
  H-->>SW: classificação + família + localizadores
  SW->>H: target.register {nodeId}
  H-->>SW: TargetIdentity (targetId lógico)
  SW->>CS: cs.verifyLocators
  CS-->>SW: contagens reais (CSS/XPath)
  SW->>H: locators.verify
  H-->>SW: concordância snapshot × DOM real
  SW->>CS: cs.highlight (contorno rosa)
  SW-->>U: painel atualizado
```

## Atualização incremental

```mermaid
flowchart LR
  MO["MutationObserver<br/>(documento, frames, shadow roots)"] --> B["lote 100 ms<br/>(máx. 500 ms)"]
  B --> F["filtra overlay e<br/>atributos internos"]
  F --> D["delta: nós alterados<br/>(+ subárvore se class/style/hidden/open)<br/>+ ids removidos"]
  D --> H["host: DeltaApplier →<br/>reanálise incremental"]
  H --> M["MutationRecord INCREMENTAL<br/>+ alvos afetados reencontrados"]
  M --> HL["destaque segue o<br/>novo nó, se RESOLVED"]
```

## Persistência

Arquivos JSON locais (`%LOCALAPPDATA%\Nekkora\ExtensionChrome` ou `NEKKORA_DATA_DIR`):
`targets/registry.json`, `pagemaps/<captura>.json`, `logs/native-host-*.log`. PostgreSQL não é usado
(decisão D-006).
