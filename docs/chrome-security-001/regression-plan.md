# CHROME-SECURITY-001: plano e resultado da regressão

## O que é executado

| Suíte | Comando oficial | Linha de base (`8f3326b`) |
|---|---|---:|
| Java (núcleo + host) | `scripts/run-tests.ps1 -Suite unit` (`mvnw install`) | 238 |
| Node (extensão) | `cd chrome-extension && npm test` | 134 |
| Chrome real (integração) | `scripts/run-tests.ps1 -Suite integration` (Chrome for Testing, extensão real, Native Host instalado) | 227 |
| Studio, Windows | `./mvnw clean verify` | 338 |
| Studio, Linux | `tools/golden-linux.sh verify` (Docker, ambiente fixado) | 338 |

A suíte do Chrome real foi executada em **7 grupos** de classes, cada um com um Chrome novo, para isolar falhas e
medir o tempo. Depois de cada grupo, só o processo do Chrome for Testing é encerrado:

| Grupo | Classes |
|---|---|
| G1 | BrandedChrome, CaptureClassification, Chrome002Contract, Chrome002Dynamic, Chrome002Export |
| G2 | Chrome002Lifecycle, Chrome002NativePanel, Chrome002Performance, Chrome002Review, Chrome003Frame |
| G3 | Chrome003Integrated, Chrome003Negative, Chrome003Performance, Chrome003Protection, ComplexComponents |
| G4 | Contract, FamilyAndLocators, HostCommunication, InspectionVisual, NavigationInteraction |
| G5 | NegativeCases, PanelUi, Performance, Real001Performance, Real001Pilot, Resolution |
| G6 | Studio002Bridge, Studio003Interactions, Studio005Evidence |
| G7 | ChromeSecurity001 (matriz SEC001 ponta a ponta), SemanticCapability, CapabilityMeasure (antes × depois) |

## Regras seguidas

- **Nenhum teste removido.** Os testes que verificavam um texto da página passaram a exigir o resultado **exato** da
  política (`support/Policy.name/url/id`, o mesmo código da extensão) e uma verificação estrutural equivalente: o
  elemento certo localizado pela estrutura (iframe hospedeiro, frame filho, único botão no shadow root), nunca por
  texto que deixou de atravessar.
- **Nenhuma asserção enfraquecida para "qualquer resultado".** Onde o texto era o único diferencial, o teste exige o
  resultado honesto (`AMBIGUOUS`/`INSUFFICIENT_EVIDENCE`/`IDENTITY_MISMATCH`, sem `resolvedNodeId`) **e** que o motor
  nunca resolva para o elemento errado (outra linha, outra filial, outro painel).
- **O corpus da medição não foi alterado:** os textos das páginas antigas do laboratório continuam os mesmos, para a
  comparação antes × depois valer.
- O script de testes passou a **falhar** se o build falhar (antes seguia em silêncio com JARs antigos).

## Adaptações por tipo (rodadas 1 a 3)

| Tipo | Exemplos | Tratamento |
|---|---|---|
| Nome igual ao texto da página | `"Aplicar cupom"`, `"Mudar plano"`, `"País de entrega"` | `Policy.name(texto)`: o nome sai se for do vocabulário, senão vazio |
| Frame localizado pelo título | `"Filial Sul"`, `"Gráficos"`, `"Frame 1 nível 1"` | frame hospedado pelo iframe real (`frameHostedBy`) ou filho único (`onlyChildFrame`) |
| Localizador com id privado | `iframe#f-contas`, `nk-open-card#cartao-aberto` | `expressionSafe` e ausência do id; o localizador usa só o que atravessou |
| Linhas/filiais só distinguíveis por texto | `ROW_CONTEXT` com `data-client="202"`, filiais Norte/Sul | resultado honesto (`AMBIGUOUS`/`INSUFFICIENT_EVIDENCE`), nunca outra linha/filial |
| URLs | `aba=inicio`, `view=clientes`, `/lab/spa/pedidos` | `Policy.url`: `aba={…}`, segmentos fora do vocabulário como `{…}` |
| Máscaras antigas | `[email]`, `token=REDACTED`, `:redacted` | não existem mais: o texto não atravessa e as URLs são modelo |
| Proteção (CAPTCHA) | menção textual, desafio ativo do hCaptcha por fragmento | limitação declarada (`known-limitations.md` 9 e 15); `render` acrescentado ao vocabulário com revisão manual |
| Hooks de teste | `instrumentation` | só em DEV (correção de segurança da fase 13) |

## Resultado

Preenchido em `performance.md` (métricas) e no relatório final (§20), com as contagens da 3ª rodada completa.

## 2ª revisão dos PRs #7 (commit `a240333`)

Mesma estratégia oficial: grupos sequenciais, em primeiro plano. Entre os grupos, só os processos de teste são encerrados
(Chrome for Testing, chromedriver, JVMs do surefire e do Maven, Native Host aberto pela extensão de teste) e a memória é
conferida (8,9 a 9,1 GB livres). O G7 inclui `TechnicalContinuityIT`.

| Suíte | Resultado |
|---|---|
| Java (núcleo 207 + host 64) | 271/271 |
| Node | 148/148 |
| Chrome real, G1–G7 | 251/251 (34, 55, 47, 23, 27, 41, 24) |
| Studio Windows (`1391360`) | 344/344 |
| Studio Linux (`1391360`) | 344/344, golden screenshots preservados |

Casos novos: SEC001-090 (operadores CSS e expressão não analisável, vetores e testes adversariais nas barreiras),
SEC001-091 (página 15: nomes de componente, ponta a ponta) e SEC001-092 (`page.nodeInfo`). Asserções ajustadas ao
resultado exato da nova política: `LocatorEngineTest.scopedLocatorsForShadowAndFrames` e `ComplexComponentsIT.openShadow`
(o hospedeiro deixa de ser citado pelo nome `nk-card`/`nk-open-card` e passa a ser citado pela posição real, continuando
único e concordante ao vivo). Fixtures de etapa com um formato de descrição que nenhum produtor emite (`id "entrar"`,
`botão "Entrar"`, `campo "Senha"`, `texto`) passaram ao formato real do motor (`button {id=entrar}`,
`* {role=button} {name="Entrar"}`, `input {label="Senha"}`, `button {text="Entrar"}`), sem mudar o que cada teste verifica.
