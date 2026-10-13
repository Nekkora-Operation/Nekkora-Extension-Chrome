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
