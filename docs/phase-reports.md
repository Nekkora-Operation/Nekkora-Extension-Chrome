# Relatórios por fase

Cada fase registra o que foi implementado, arquivos, contratos, testes executados e resultados,
falhas encontradas (e corrigidas), limitações e dependências seguintes. Os números de testes são da
execução final registrada em `reports/`.

## FASE 01 — Preparação

- **Implementado**: investigação das referências visuais (Nekkora-lib `src/tokens/themes.css` e
  `foundation.css`; Platform-Web consome os mesmos tokens) — `#C2466D` confirmado como
  `--nk-color-accent` (light) e `--nk-solid-accent` (dark); verificação do GitHub (repositório inexistente;
  SSH autenticado); arquitetura definida (D-001..D-005); Maven multi-módulo Java 21 com wrapper;
  manifesto MV3; `.gitignore`, `.gitattributes`, `LICENSE` (pendente, D-010).
- **Arquivos**: `pom.xml`, `mvnw*`, `.mvn/`, `chrome-extension/manifest.json`, `chrome-extension/src/shared/tokens.js`.
- **Testes**: compilação do esqueleto.
- **Limitações**: Nekkora-lib é `UNLICENSED` → somente valores de tokens reutilizados; nenhum ativo copiado.
- **Commit**: `chore: initialize nekkora chrome extension repository`.

## FASE 02 — Comunicação Chrome/Java

- **Implementado**: host Native Messaging (`NativeMessageCodec` 32 bits LE; limites 1 MiB/64 MiB),
  fragmentação/remontagem (`ChunkCodec`, `native-client.js`), envelope `nekkora-nm/1` com correlação e
  códigos de erro, stdout reservado (redirecionamento de `System.out`; logs em arquivo), reconexão com
  espera progressiva, timeout, scripts de instalação/remoção, id fixo da extensão.
- **Contratos**: `native-protocol.schema.json`.
- **Testes**: `ProtocolTest` (7), `NativeHostTest` (5), `native-client.test.js` (6); C-1..C-5 no Chrome — **todos aprovados**.
- **Falhas encontradas e corrigidas**: laço infinito ao dividir pares substitutos com fragmento de 1
  caractere (JS e Java); instalador quebrava no PowerShell 5.1 (stderr do `java -version`) e gravava
  manifesto com BOM; **Chrome for Testing não lê `Software\Google\Chrome for Testing`** — lê
  `Software\Chromium` (verificado por exclusão de chaves; D-009).
- **Commits**: `feat: implement java native messaging host`, `test: add real-browser integration harness…`.

## FASE 03 — Captura DOM

- **Implementado**: `capture.js` (árvore composta, pré-ordem, estados coexistentes, visibilidade por
  `checkVisibility`, inércia por modal, labels via `element.labels`, métricas de rolagem, iframes,
  shadow roots aberto/fechado, Resource Timing, limite de nós), `node-registry.js` (ids temporários),
  `sensitivity.js`; no Java, `RawSnapshot`/`PageModel`.
- **Contratos**: `raw-snapshot.schema.json` (validado com snapshots reais de 7 páginas).
- **Testes**: T018, T020, T033, C-8 — aprovados.
- **Limitações**: frames de outra origem permitida tratados como inacessíveis; rect relativo ao frame.

## FASE 04 — Classificação semântica

- **Implementado**: `SemanticClassifier` (60 tipos; nativo × ARIA; papel ARIA sem evidência → confiança
  LOW + incerteza), `AccessibleNameCalculator`, mensagens de validação/instrução/descrição, condições.
- **Testes**: `SemanticClassifierTest` (53), `AccessibleNameCalculatorTest` (6); T001–T003, T010, T011, T027–T030.
- **Falha corrigida**: links/contenteditable/widgets ARIA eram marcados com estado desconhecido (sem `:disabled` nativo).

## FASE 05 — Família e contexto

- **Implementado**: `ContextGraph` (pai, filhos, ancestrais/irmãos relevantes, formulário, grupo,
  labels, descrição, região, diálogo, tabela, linha com chave, coluna com cabeçalho, item de lista,
  contêiner lógico e visual, título precedente, textos contextuais, cadeia de escopos, relações ARIA
  incluindo IDREF ambíguo).
- **Testes**: `ContextGraphTest` (8); T006–T008, T015, T029.
- **Falha corrigida (encontrada no navegador)**: `<form role="search">` deixava de ser formulário dos
  controles (papel landmark sobrepunha a semântica de dono).

## FASE 06 — Localizadores

- **Implementado**: AST de localizador avaliável no snapshot e renderizável em CSS/XPath; 10
  estratégias plugáveis; pontuação sem hierarquia rígida; estabilidade heurística × observada;
  verificação ao vivo (`locators.verify`) com contagens reais.
- **Testes**: `LocatorEngineTest` (11); T001–T008; concordância snapshot × DOM real em todos os casos verificados.
- **Falhas corrigidas**: estabilidade observada nunca era promovida (combinação partia de
  `HEURISTIC_STABLE`); recursão em cache de escopo (`ConcurrentModificationException`).
- **Otimização**: exportação de 10.000 elementos de 8,9 s → 3,3 s (planejador índice × subárvore,
  índices por papel e papel+nome).

## FASE 07 — Reencontro

- **Implementado**: `IdentityFactory` (discriminadores contra parecidos), `FeatureComparator`,
  `TargetResolver` com os 7 resultados, sonda de endereço (id/name), histórico de atributos voláteis,
  reavaliação de localizadores, reencontro automático do alvo destacado.
- **Testes**: `TargetResolverTest` (15); T005, T009, T025, T026, T031, T032, T040, N-A..N-E — aprovados.
- **Falhas corrigidas**: chaves estruturais comparadas por similaridade parcial (D-014); substituição
  de input por botão com o mesmo id não era detectada (localizador por id incluía a tag).

## FASE 08 — Componentes complexos

- **Implementado**: `OptionSetAnalyzer` (nativo, optgroup, ARIA controls/owns, portal, dinâmico,
  virtualizado, observadas × materializadas, completude), escopos de shadow root e iframe nos
  localizadores e no resolvedor.
- **Testes**: `OptionSetAnalyzerTest` (7); T010–T021 — aprovados.
- **Falhas corrigidas**: lista irmã marcada como portal; lista vazia observada antes não contava como
  histórico (0 → N não sinalizava carregamento dinâmico).

## FASE 09 — Inspeção visual

- **Implementado**: overlay rosa (shadow root fechado), modos hover/seleção, bloqueio de ações,
  desativação completa, painel lateral e harness.
- **Testes**: T034 (geometria e cor exatas, acompanhamento em scroll/movimento/redimensionamento/janela/
  re-renderização), T035, T039, C-9 — aprovados; evidências em `reports/evidence/`.

## FASE 10 — Contexto e interações

- **Implementado**: `UrlContextParser` (observado × inferido, com ressalva), `NavigationChange`,
  detecção de rota SPA (`webNavigation`), `InteractionAnalyzer` (URL, consulta da interface, requisições
  via Resource Timing, regiões de resultado, instrumentação separada), leitura de hooks restrita.
- **Testes**: `UrlContextParserTest` (3); T022–T024, T038 — aprovados.
- **Falha encontrada (no teste)**: a espera por "Maria" era satisfeita por "Mariana" antes da nova
  consulta; o motor corretamente relatou "nenhuma requisição nova". O teste foi corrigido para esperar
  o estado exato.

## FASE 11 — Homologação

- **Executado**: suíte completa via `scripts/run-tests.ps1` a partir de relatórios limpos.
- **Resultado**: 58/58 casos de homologação no Chrome real; 140 testes sem navegador (113 core, 12
  host, 3 laboratório, 2 contrato, 10 extensão). Nenhum teste reprovado ou ignorado.
- **Desempenho**: dentro do orçamento provisório em todas as métricas ([`reports/performance.md`](../reports/performance.md)).
- **Instalação limpa**: host reinstalado pelo script oficial a cada execução; dados isolados.
- **Relatório final**: [`reports/final-report.md`](../reports/final-report.md).
- **Dependências seguintes**: decisão de licença; validação Linux/macOS; consumo do PageMap pelo Nekkora Studio.

---

# NEKKORA-CHROME-002 — relatórios por fase

Branch `feat/nekkora-chrome-002` (base `00b34c0`). Números da execução final registrada em
`reports/` e no [relatório final](../reports/chrome-002-final-report.md).

## FASE 01 — Auditoria e baseline

- **Implementado**: auditoria do código da CHROME-001; reprodução da suíte (58/58) e da linha de base de
  desempenho arquivada em `reports/chrome-002/baseline/` (10k: captura 316,9 ms, bloqueio 339 ms,
  seleção 380 ms, exportação 3.560 ms, heap 827,5 MB); porta do laboratório de testes isolada (18765) para
  não colidir com o laboratório manual (8765).
- **Commit**: `ac32131`.

## FASE 02 — Botão único e estados da inspeção

- **Implementado**: `inspection.js` (INACTIVE/ACTIVATING/ACTIVE/DEACTIVATING/DEGRADED/ERROR, exclusão
  mútua por aba, estado persistido), painel com botão único (`aria-pressed`, `#C2466D`, textos de
  transição), bloco de status (Captura/Elementos/Última atualização/Deltas), área DEV.
- **Testes**: C002-001..005, 012, 013; `PanelUiIT` adaptado.
- **Falhas encontradas e corrigidas**: o overlay permanecia no documento após desativar (passou a ser
  destruído); a falha de origem não registrava `ERROR` (a validação passou para dentro da ativação).

## FASE 03 — Ciclo automático de captura

- **Implementado**: `capture-session.js` (consumidores, captura-base cooperativa em partes, deltas
  sequenciados, barreira), rastreador com modo BUFFER, `confirmBase`, pausa e reconciliação;
  `HostCaptureSession` (época/seq por fonte, recusas explícitas); `capture.session.*` no host.
- **Testes**: C002-006..011, 016; `SessionCommandsTest`.
- **Falhas encontradas e corrigidas**: deltas de fontes de frame trocavam o documento principal e
  "soltavam" a raiz do frame (corrigido em `DeltaApplier`/`Sources`).
- **Commits**: `ba555ef`, `593c3d3`, `c1a43bd`, `0cd545f`.

## FASE 04 — Sessões por aba e recuperação

- **Implementado**: restauração após reinício do service worker (`storage.session` + reconciliação),
  reconexão do motor com espera progressiva + `chrome.alarms`, ressincronização limitada (5/30 s),
  documento substituído/recarga, aba fechada, rastreadores órfãos.
- **Testes**: C002-014, 015, 017, 018, 019, 020, 042.
- **Falha de ambiente identificada**: o Chrome for Testing também procura o host em
  `HKCU\Software\Google\Chrome` (fallback) — o teste de host ausente remove as duas chaves.

## FASE 05 — Reutilização do snapshot e contrato de exportação

- **Implementado**: seleção/reencontro/comparação pela barreira + `capture.pin` (referência fora do
  LRU); `recording.js` (started/finished/cancelled); `ExportService` idempotente, em fluxo, atômico,
  com SHA-256 e validação do schema; modo DEV/PRD (`build-mode.js`, `build.ps1 -Mode PRD`).
- **Testes**: C002-021..025, 048, 049, 050; `SessionCommandsTest` (idempotência, falha não memorizada,
  simultaneidade, equivalência com a exportação anterior).
- **Falha encontrada e corrigida**: duas gravações finalizadas na mesma sessão gravavam o mesmo arquivo
  (o caminho/SHA-256 da primeira deixava de valer) — um arquivo por (sessão, operação) (`f8c1566`).

## FASE 06 — Componentes dinâmicos: Shadow DOM e iframes

- **Implementado**: observação incremental de shadow roots novos, reconciliação de `attachShadow()`
  tardio (1 s), `all_frames` + handshake + `capture.session.attachFrame` para iframes de outra origem
  permitida, poda de frames/shadow roots cujo hospedeiro sumiu; laboratório com porta +1 e `127.0.0.2`.
- **Testes**: C002-026..032; `FrameSourcesTest`.

## FASE 07 — Semântica (AccName 1.2)

- **Implementado**: `aria-labelledby` múltiplo/recursivo/circular/oculto, autorreferência (2C),
  `aria-description`, ícones, controles embutidos, confiança por fonte; `textSegments` preserva a ordem
  texto/elemento (corrige a limitação "Mostrar itens 20").
- **Testes**: C002-033..037 (17 nomes comparados com o Chrome, todos concordantes);
  `AccNameConformanceTest` (11).
- **Correção de expectativa**: a autorreferência em `aria-labelledby` era ignorada por um teste desta
  missão; a especificação (e o Chrome) incluem o próprio nó — teste corrigido.

## FASE 08 — Desempenho, memória e longa duração

- **Implementado**: montagem do PageMap em blocos paralelos com ordem preservada e escrita em fluxo;
  geometria atualizada em rolagem/redimensionamento e na barreira; varredura do registro em sessões longas.
- **Testes**: C002-038..041, 045 (critérios definidos antes das medições no Javadoc de
  `Chrome002PerformanceIT`).
- **Falha encontrada e corrigida**: a equivalência incremental × completa falhava por geometria defasada
  (`OUT_OF_VIEWPORT`) após deslocamentos de layout — atualização de geometria (D-027).

## FASE 09 — Homologação no navegador

- **Implementado**: painel lateral **nativo** homologado (`chrome.sidePanel.open` com gesto real,
  `getContexts(SIDE_PANEL)`, estado lido do painel, captura de tela do sistema), homologação no **Google
  Chrome convencional** via CDP `Extensions.loadUnpacked`, contratos novos validados.
- **Testes**: C002-046, C002-047, `BrandedChromeIT`; suíte completa (CHROME-001 + CHROME-002).

## FASE 10 — Documentação, relatório e publicação

- **Implementado**: README, `docs/chrome-002/*` (ciclo de captura, inspeção, exportação, iframes/Shadow
  DOM/AccName, desempenho) com fluxogramas Mermaid, decisões D-018..D-031, limitações, plano de testes,
  contratos, arquitetura, `reports/chrome-002-final-report.md`; publicação da branch e PR.

---

# NEKKORA-CHROME-003 — relatórios por fase

Branch `feat/nekkora-chrome-003` (base `47d7a16`). Números da execução final em `reports/` e no
[relatório final](../reports/chrome-003-final-report.md).

## FASE 01 — Exame da CHROME-002 e baseline

- **Implementado**: execução completa da `main` arquivada em `reports/chrome-003/baseline/` (117/117 no navegador,
  desempenho de 100 a 20.000 elementos).

## FASE 02 — Frame Intelligence e grafo documental

- **Implementado**: `UrlSanitizer`, `FrameInfo` com título/`readyState`, `FrameGraphBuilder` (status, `documentKind`,
  origem efetiva/opaca, relações §6), `BrowserFrameMetadata` e associação TOP/HANDSHAKE/URL_MATCH.
- **Testes**: `FrameIntelligenceTest`; F003-001..003, F003-012..015.

## FASE 03 — Identidade de frame e reencontro entre documentos

- **Implementado**: `FrameIdentity`/`FrameIdentities`, `TargetIdentity.frameChain`, salto de frame por identidade no
  `TargetResolver`, diagnósticos `FRAME_*`, `SHADOW_ROOT_CLOSED`, `TARGET_AMBIGUOUS`.
- **Testes**: F003-008..011, N003-F.

## FASE 04 — Element Access Path

- **Implementado**: `ElementAccessPath`, `AccessPathBuilder` com explicação legível; trajetória em `element.inspect`,
  `element.accessPath` e no PageMap.
- **Testes**: F003-004..007, F003-016.

## FASE 05 — Protection Detection Engine

- **Implementado**: catálogo versionado com schema, `SignatureCatalog`, `ProtectionDetector` passivo, estados,
  confiança por canais, localização na hierarquia; fornecedores conferidos com a documentação atual.
- **Testes**: `ProtectionDetectorTest`; P003-001..015.

## FASE 06 — Automation Diagnostics Engine

- **Implementado**: `AutomationDiagnostics`, `AutomationObstacle`, `AutomationReadinessAssessment` (obstáculos,
  dependências de interação, recomendações, status e confiança por cobertura).
- **Testes**: `AutomationDiagnosticsTest`; P003, I003.

## FASE 07 — Contratos e PageMap 0.2.0

- **Implementado**: seções `frameGraph`, `accessPaths`, `protection`, `readiness`; `elements[].documentId`; 0.1.0
  arquivado; comandos novos no protocolo; `StreamingPageMapWriter`.
- **Testes**: `FrameCommandsTest`, F003-020, contratos.

## FASE 08 — Integração no navegador e painel

- **Implementado**: `background/diagnostics.js` (metadados do Chrome, comandos, destaque do iframe), handshake no dono
  da captura, detecção de troca de documento de iframe, recursos observáveis e redação de URLs, painel com
  Documentos/Iframes/Shadow Roots, TRAJETÓRIA e DIAGNÓSTICO.
- **Testes**: F003-017..019.

## FASE 09 — Laboratório e matrizes

- **Implementado**: `test-lab/pages/chrome003/` (A–J), `protection/` (LAB-P01..P11), geradores `/generated/frames` e
  `/generated/slow-frame`; matrizes F003, P003, N003 e I003.
- **Correções encontradas pelas matrizes**: handshake aninhado (D-039), troca de documento (D-042), about:blank/srcdoc
  (D-038/D-041), sombreamento v2×v3 (D-045), evidência fraca (D-046), POSSIBLE na prontidão (D-049), redação de URLs
  (D-048), limite de frames (D-043), relações estruturais (D-040).

## FASE 10 — Desempenho e memória

- **Implementado**: D003-001 (1/5/10/25 iframes, profundidades 1–5) e D003-002 (baseline CHROME-002 pelo mesmo
  procedimento); relatório `reports/chrome-003/performance.md`.

## FASE 11 — Documentação, regressão completa e publicação

- **Implementado**: `docs/chrome-003/*` (Frame Intelligence, identidade, Access Path, proteções, confiança, obstáculos,
  limitações) com fluxogramas Mermaid; README, arquitetura, contratos, decisões D-037..D-051, limitações, plano de
  testes; regressão completa (CHROME-001 + CHROME-002 + CHROME-003); `reports/chrome-003-final-report.md`; branch e PR.

