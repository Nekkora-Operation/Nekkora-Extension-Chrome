# CHROME-SECURITY-001: inventário dos fluxos de dados (fase 1)

Este inventário cobre todo produtor de dado da página, cada transformação e cada destino real. Ele foi feito de duas
formas complementares:

1. **Auditoria do código:** leitura dos produtores (content scripts), das transformações (service worker, motor Java,
   Native Host) e dos consumidores (Studio, arquivos, armazenamento, logs).
2. **Medição no Chrome real:** o teste `ChromeSecurity001IT` grava as 12 páginas do laboratório `chrome-security-001`
   pela cadeia completa e procura os marcadores fictícios em todos os destinos. Na base `8f3326b`, os **12 cenários
   falharam por exposição** (`reports/evidence/SEC001-exposure-baseline.json`).

Legenda dos destinos medidos: **SNAP** snapshot transportado ao motor · **MOTOR** resposta do motor à inspeção ·
**REG** registro de alvos (memória e `targets/registry.json`) · **PM** PageMap exportado (resposta e
`pagemaps/*.json`) · **LOG-SW** log de eventos do service worker (`events.since`) · **SES** `chrome.storage.session` ·
**LOC** `chrome.storage.local` · **STUDIO** eventos ao Studio · **LOG-HOST** `logs/native-host-*.log`.

## 1. Exposições reproduzidas no Chrome real (base `8f3326b`)

| Caso | Superfície (marcador fictício) | Destinos onde apareceu |
|---|---|---|
| SEC001-001/022 | texto próprio sem formato ("o cliente joão já existe", "Ximena Tavolaro") | SNAP, MOTOR, REG, PM, LOG-SW, SES |
| SEC001-023 | texto descendente ("Irineu Calabouço") | SNAP, PM |
| SEC001-024 | segmentos de texto entre filhos ("Severino Pitanguy") | SNAP, MOTOR, PM, LOG-SW, SES |
| SEC001-021 | `<title>` de SVG ("Excluir Doralice Mondego") | SNAP, MOTOR, REG, PM, LOG-SW, SES, **STUDIO** |
| SEC001-002/013 | `aria-label` ("Consultar cliente Bartolomeu Quixadá") | SNAP, MOTOR, REG, PM, LOG-SW, **STUDIO** |
| SEC001-002/014 | `aria-labelledby` ("telefone do cliente Godofredo Ubirajara") | SNAP, MOTOR, REG, PM, LOG-SW, **STUDIO** |
| SEC001-015 | `placeholder` | SNAP, MOTOR, REG, PM, LOG-SW, **STUDIO** |
| SEC001-016 | `title` | SNAP, MOTOR, REG, PM, LOG-SW, **STUDIO** |
| SEC001-002 | texto de `<label>` e de link | SNAP, MOTOR, REG, PM, LOG-SW, SES, **STUDIO** |
| SEC001-017 | `data-*` ("Eustáquio Marangoni") | SNAP, MOTOR, PM, LOG-SW |
| SEC001-018 | `id` (`cliente-anacleto-prudencio`) | SNAP, MOTOR, REG, PM, LOG-SW, **STUDIO** (`htmlId`, localizador) |
| SEC001-019 | `name` (`usuario_hermengarda`) | SNAP, MOTOR, REG, PM, LOG-SW, **STUDIO** (`htmlName`) |
| SEC001-020/081/082/083/084 | atributo personalizado, classe, `aria-description`, `aria-valuetext`, `alt` | SNAP, PM (e `alt` também MOTOR, REG) |
| SEC001-004 | **valor de pesquisa digitado** ("Quirino Valadares") | SNAP (`exposedValue`) |
| SEC001-005/033 | valor digitado repetido pela página depois de remover o campo | SNAP, LOG-SW |
| SEC001-032 | valor digitado repetido pela página depois de limpar o campo | PM, LOG-SW (delta) |
| SEC001-011 | opção selecionada ("Zéfiro Quintanilha (contrato 7731)") | SNAP, MOTOR, REG, PM, LOG-SW, **STUDIO** (`parameters.option`) |
| SEC001-012 | opção de combobox ("Ondina Carrasqueira") | SNAP, MOTOR, PM, LOG-SW, **STUDIO** |
| SEC001-025 | Shadow DOM (texto e rótulo) | SNAP, REG, PM, **STUDIO** |
| SEC001-026 | conteúdo de iframe autorizado | SNAP, REG, PM, **STUDIO** |
| SEC001-029/030/034 | texto alterado depois da captura ("Ulisses Paranhos") | SNAP, MOTOR, REG, PM, LOG-SW (`MutationRecord.textAfter`), SES |
| SEC001-031 | atributo alterado depois da captura | SNAP, PM, LOG-SW (`attributeChanges`) |
| SEC001-037/038 | células, identificador da linha, legenda da tabela | SNAP, REG (`rowTexts`, `contextTexts`), PM, LOG-SW, SES, **STUDIO** (localizador `ROW_CONTEXT`) |
| SEC001-039 | âncora de contexto (seção, diálogo), nome de formulário | SNAP, MOTOR, REG, PM, LOG-SW, SES, **STUDIO** (localizador `CONTEXTUAL`) |
| SEC001-003 | caminho de rota (`/clientes/joao-oliveira`) | MOTOR, REG, PM, LOG-SW, SES, **STUDIO** (`context.url`, evidência) |
| SEC001-049/050/085 | caminho, parâmetro e fragmento de URL (`ataulfo-marcondes.html?cliente=Ataulfo#ficha-ataulfo`) | SNAP, MOTOR, REG, PM, LOG-SW, SES, **STUDIO** |
| SEC001-051 | URL de iframe (`ficha-gervasio-barros.html`) | SNAP, REG, PM, LOG-SW, SES, **STUDIO** |
| SEC001-052/053 | URL de recurso e de requisição (caminho e parâmetro) | SNAP, PM |
| SEC001-086 | título do documento ("Ficha de Belmira Saraiva") | SNAP, MOTOR, PM, LOG-SW, SES, **STUDIO** (título da aba no ciclo de vida da gravação) |
| SEC001-061..063 | página de recuperação (nome acessível) | SNAP, REG, **STUDIO** |

Não apareceram em nenhum destino: senha, e-mail, telefone, número e conteúdo de contenteditable digitados (SEC001-006
a 010). As proteções anteriores (STUDIO-003) continuam efetivas para os valores desses campos. `chrome.storage.local`
e os logs do host ficaram limpos nesse cenário. Os logs, porém, tinham caminhos de exceção com mensagens (ver §3).

## 2. Produtores, transformações e destinos

| # | Origem | Transformação (arquivo) | Destino | Risco | Política proposta |
|---|---|---|---|---|---|
| 1 | texto de nós | `capture.js::ownText()` → `maskText` | `RawNode.ownText` → motor, PageMap, registro, Studio | frase livre ("o cliente joão já existe") | **decisão D1** |
| 2 | texto entre filhos | `capture.js::textSegments()` | `RawNode.textSegments` → nome acessível | idem | **D1** |
| 3 | `<title>` de SVG | `capture.js::svgTitle()` | `ownText` do SVG → nome do botão | idem | **D1** |
| 4 | valor de campo de pesquisa | `capture.js` (`isSearchField`) → `exposedValue` | `RawNode.exposedValue` → nome, interação | valor do usuário | **eliminado** (fase 1, `6293dc7`) |
| 5 | contenteditable | `capture.js` (só `hasValue`/tamanho) | estado | já protegido (STUDIO-003) | manter |
| 6 | atributos HTML | `capture.js::attributes()` → `sanitizeAttribute` | `RawNode.attributes` → localizadores, nome, PageMap | `id`, `name`, `data-*`, classe, ARIA textual, `href`/`src`/`action` | **D1/D2** |
| 7 | relações `for`/`aria-*` por id | `attributes()` (valor = ids) | motor (nome acessível, opções) | o id pode conter dado | **D2** (relação por id de nó) |
| 8 | título do documento e de iframes | `capture.js::documentInfo()`, `rootFrameInfo()` → `maskText` | snapshot, PageMap, frame graph | texto livre | **D1** |
| 9 | URL do documento, iframes, recursos, requisições | `sanitizeUrl` / `redactUrlValues` | snapshot, PageMap, registro, Studio, evidências | caminho com nome | **D3** |
| 10 | mutações | `mutation-tracker.js` → `capture.partial()` | delta → motor | o mesmo dos itens 1–9, depois da captura inicial | igual à captura (D1–D3) |
| 11 | comparação de capturas | `SnapshotComparator` → `NodeChange` | `MutationRecord` → broadcast `delta`, PageMap, LOG-SW | texto antes/depois, valores de atributos | **corrigido** (fase 1: `[omitido]`, nome do atributo e estados fechados ficam) |
| 12 | resolução de alvo | `TargetResolver` → `ResolutionResult.attributeChanges` | broadcast `resolution`, PageMap | valores antigos/novos de atributos | **corrigido** (fase 1) |
| 13 | interação | `InteractionAnalyzer.valueOf` | `InteractionObservation` | texto da opção, `exposedValue` | **corrigido** (posição da opção) |
| 14 | nome acessível | `AccessibleNameCalculator` | classificação → localizadores, inspeção, Studio | texto e atributos da página | consequência de D1 |
| 15 | assinatura de identidade | `SignatureExtractor` → `IdentitySignature` (`name`, `text`, `label`, `form`, `rowTexts`, `contextTexts`, `containerHeading`…) | registro em memória e **`targets/registry.json` persistido** | texto retido entre sessões | consequência de D1 + **D5** (dados antigos) |
| 16 | localizadores | `LocatorEngine` (estratégias `LABEL`, `ROLE_NAME`, `TEXT`, `CONTEXTUAL`, `ROW_CONTEXT`, `ID`, `NAME`, `ATTRIBUTE`) | inspeção, PageMap, registro, **Studio** (`css`, `xpath`, `description`) | texto e identificadores embutidos | consequência de D1/D2 |
| 17 | caminho de acesso e contexto | `AccessPathBuilder`, `ContextGraph` | Studio (`accessPath.label`, `context.name`) | texto da página | consequência de D1 |
| 18 | descritor da etapa | `interaction-recorder.js::descriptor()` (`aria-label`, `<label>`, texto, `value`, `placeholder`, `title`) | etapa sem resolução no motor → Studio (`element.name`) | nome acessível privado | consequência de D1 |
| 19 | referência da etapa | `studio-steps.js::elementRef()` (`maskText`, `looksSecret`) | Studio (`name`, `htmlId`, `htmlName`, localizadores, `accessPath`, `context`) | heurística insuficiente | consequência de D1/D2 |
| 20 | opção escolhida | `studio-steps.js::build()` (`option.label`, `option.value`) | Studio (`parameters.option`) | valor escolhido pelo usuário | consequência de D1 (opção vira posição ou nada) |
| 21 | contexto da etapa | `build()` (`context.url`, `context.title`, `after.url`) | Studio | URL e título | D3/D4 |
| 22 | aba | `browser.tabs.list`, ciclo de vida (`tab.title`, `host`) | Studio (seleção e Floating Recorder) | título da página | **D4** |
| 23 | evidências (STUDIO-005) | `studio-evidence.js` (`document.url`, `navigation.url`) | Studio | caminho de URL | D3 (o resto já é estrito) |
| 24 | log de eventos do SW | `runtime.js::broadcast()` → `eventLog` | `events.since`, `sw.diagnostics` | qualquer payload difundido | **corrigido** (lista fechada de campos) |
| 25 | difusão ao vivo | `broadcast()` → páginas da extensão (painel, harness) | memória das páginas da extensão | o que o motor devolve | consequência de D1 |
| 26 | estado da inspeção | `inspection.js` → `storage.session['inspection:<aba>']` | SES | resultado completo da inspeção (nome, localizadores) | consequência de D1 (guardar só o mínimo) |
| 27 | fila da gravação | `studio-recording.js` → `storage.session['nk.srec.<id>']` | SES | etapas e evidências como enviadas ao Studio | consequência de D1 |
| 28 | sessão de captura | `session-store.js` | SES | ids e estado (sem conteúdo) | manter |
| 29 | metadados de frames | `diagnostics.js` (`originAndPath`) | frame graph, painel | caminho de URL | D3 |
| 30 | erros | `errorInfo()` → `broadcast('error')` | LOG-SW, painel | mensagem de exceção | LOG-SW corrigido; mensagem ao vivo depende do host |
| 31 | erros do host | `MessageProcessor`, `HostRuntime` | resposta ao SW | mensagem do Jackson e de exceções internas; **eco de identificadores** | **corrigido** (posição, tipo e forma fechada de ids) |
| 32 | log do host | `LOG.log(..., e)` em 15 pontos; `BridgeContract.problems` | `logs/native-host-*.log` | mensagens de exceção e de validação citando conteúdo | **corrigido** (`LogSafety.redacted`, `problemSummary`) |
| 33 | exportação | `ExportService` → `StreamingPageMapWriter` | `pagemaps/*.json` | todo o PageMap | consequência de D1–D3 + **D5** (versão do PageMap) |
| 34 | registro persistido | `TargetRegistry` → `targets/registry.json` | disco, entre sessões | assinaturas com texto | D1 + D5 |
| 35 | Bridge | `StudioLink` (`StepPrivacy`, `EvidencePrivacy`) | Studio | heurística (`maskText`) nos passos | 3ª barreira com a mesma política |
| 36 | Studio | `RecordedStep`, `RecordedStepDrafts`, `ElementReference`, `DisplaySafety` | domínio, tela, (STUDIO-006: persistência) | aceita o que chega | 4ª barreira + versão de política (D6) |
| 37 | painel e realce | `panel.js`, `highlighter.js` (rótulo `kind · nome`) | tela do próprio usuário, dentro da página | exibição local | consequência de D1 |

## 3. Caminhos encontrados durante a auditoria (fora da matriz inicial)

- **Eco de identificadores nas respostas de erro do host:** um comando com `captureId` contendo texto respondia
  `Captura <texto> não está disponível`. Foi encontrado pelo `LogSafetyTest` e corrigido: identificadores têm forma
  fechada e são recusados sem eco.
- **Mensagens do Jackson** (`getOriginalMessage`) citam o token lido. Agora a resposta leva só linha e coluna.
- **Validação do Bridge no log** (`BridgeContract.problems`): a mensagem do validador pode citar o valor. O log passou
  a registrar só o caminho e a regra.
- **Título da aba no ciclo de vida da gravação:** canal próprio para o Studio, independente das etapas (decisão D4).
- **`InteractionAnalyzer`** levava o texto da opção selecionada e o valor de pesquisa (corrigido).
- **`storage.session['inspection:<aba>']`** guarda a inspeção completa (consequência de D1).
- **Classe CSS, `aria-description`, `aria-valuetext`, `alt`, `href`/`action`/`formaction`** viraram os casos
  SEC001-081 a 085, e o título do documento virou o SEC001-086.
- **Hooks de teste em PRD (fase 13):** `testHooks.read`, e `interaction.observe` com `readHooks`, liam os valores que a
  página expõe em `window.nekkoraTestHooks` também em PRD, e qualquer página pode declarar o marcador `__nekkoraLab`.
  Os dois passaram a ser recusados fora de DEV (`MODE_RESTRICTED`, `config-mode.test.js`).

- **Revisão dos PRs #7 (P1-01):** valores de atributos estruturais (`role`, `autocomplete`, `type`, `target`, `rel`,
  `lang`, `aria-*`, numéricos) só eram verificados pela forma técnica, e `role="gumercindo-tavares"` ou um número de 11
  dígitos atravessavam até o snapshot, motor, PageMap, registro e `storage.session` (13 exposições reproduzidas na página
  13, `SEC001-exposure-p1-reproducao.json`). No mesmo levantamento apareceu o **papel ARIA das etapas**, que saía cru do
  descritor do recorder (`getAttribute('role')`) sem passar por nenhuma barreira.
- **Revisão dos PRs #7 (P1-02):** literais só de dígitos eram aceitos em qualquer comprimento em localizadores
  (`input[type="48291736455"]` chegou a ser gerado).

## 4. Estado final na branch `feat/chrome-security-001`

Medição final: `ChromeSecurity001IT` **13/13 cenários sem nenhuma exposição** (12 originais + página 13 da revisão) nos destinos SNAP, MOTOR, REG, PM, LOG-SW,
SES, LOC, STUDIO e LOG-HOST, com busca por transformações (sem acento, minúsculas, hífen, `%20`, entidades, escapes e
trechos). A linha de base (`8f3326b`) tinha 12/12 cenários com exposição. Cobertura total não é prometida: a busca
encontra só os marcadores plantados e as transformações conhecidas.

| # | Fluxo | Tratamento final |
|---|---|---|
| 1–3 | texto próprio, segmentos, `<title>` de SVG | só atravessa metadado de interface do vocabulário dentro de um portador (botão, link, rótulo, legenda, papel interativo). O resto vai como `textPresent`/`textSuppressed`, sem conteúdo |
| 4 | valor de pesquisa | eliminado (`exposedValue` só `null`) |
| 5 | contenteditable | inalterado (presença e tamanho) |
| 6 | atributos | categorias fechadas: estruturais com valor fechado; identificadores (`id`, `name`, `data-testid`…, `slot`, `part`, `is`) só do vocabulário; nomeadores pela política de texto; URLs como presença ou modelo (`src` de frame e script); `srcdoc`/`download` só presença; `data-*` e atributos personalizados só presença, e só se o nome passar |
| 7 | relações por id | resolvidas na página em ids de nó temporários (`refs`). Nenhum valor de id privado nem hash atravessa |
| 8 | títulos | documento e frame só se forem do vocabulário (D4) |
| 9 | URLs | origem + modelo do caminho; segmentos fora do vocabulário viram `{…}`; nomes de parâmetro só do vocabulário, sem valores; sem fragmento (D3) |
| 10 | mutações | a mesma política da captura (o delta é uma captura parcial) |
| 11–13 | comparação, resolução, interação | sem textos nem valores (`[omitido]`, nome do atributo, estados fechados, posição da opção) |
| 14 | nome acessível | calculado sobre entrada filtrada; nome incompleto não sai parcial (vazio, com as fontes preservadas como relação) |
| 15, 34 | assinatura e registro persistido | só o que passou pela política; `targets/registry-v2.json`, sem ler nem apagar o antigo (procedimento em `storage-and-retention.md`) |
| 16 | localizadores | toda expressão passa por `expressionSafe`; sem expressão segura, `SAFE_LOCATOR_UNAVAILABLE`/`LOCATORS_WITHHELD` |
| 17 | caminho de acesso e contexto | rótulos pela política; frames sem título viram modelo de URL |
| 18–21 | descritor, referência, opção e contexto da etapa | `StepPrivacy` (SW e host): nome, ids, localizadores, contexto e URL pela política; `OPTION_WITHHELD` |
| 22 | aba | título só do vocabulário; o Studio identifica a aba pelo host |
| 23 | evidências | URL de documento e de navegação como modelo |
| 24, 30 | log do SW e erros | lista fechada de campos por tipo de evento |
| 25–27 | difusão, inspeção e fila em `storage.session` | só recebem o que já passou pela política. Nenhum texto é guardado no SW |
| 29 | metadados de frames | modelo de URL; `frames.list` (URLs cruas) só em DEV |
| 31–32 | erros e log do host | forma fechada de ids, linha/coluna no lugar do token, `LogSafety` |
| 33 | exportação | PageMap 0.3.0 com `privacy` declarada; 0.2.0 congelado |
| 35 | Bridge | 3ª barreira (`StepPrivacy`/`EvidencePrivacy` no host com a política v2); `client.privacy` no `hello` |
| 36 | Studio | 4ª barreira (`RecordedStep.guard`, `CapturePrivacy`): revalida tudo e avisa quando a extensão não declara a v2 |
| 37 | painel e realce | mostram só o que o motor recebeu |
| 38 | hooks de teste | só DEV |
| 39 | papel ARIA das etapas | regra fechada do `role` no recorder, no service worker, no host e no Studio |
| 40 | valores de atributos estruturais | regra fechada por atributo (`nekkora-structural-attributes/1`); fora dela, só presença |
| 41 | números em localizadores | valor só até 2 dígitos; posição aceita; verificação também na representação estruturada (`LocatorPrivacy`) |
| 42 | continuidade técnica (D7) | âncora sem texto (época do registro, frame, escopo, tipo, revisões); resultado separado da identidade lógica |
