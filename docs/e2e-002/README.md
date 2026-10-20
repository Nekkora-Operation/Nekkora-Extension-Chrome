# NEKKORA-E2E-002 — Extensão: ler texto não interativo e reconhecer campos recriados

Missão: provar pela interface do Nekkora Studio a cadeia Wikipédia → PDD → IA → Python → Excel sobre quatro biografias
públicas, sem scraper específico de site. Esta parte cobre o que a extensão precisou ganhar. O relatório completo e as
evidências estão em `Nekkora-Studio/docs/e2e-002/`.

## O que mudou

| Commit | Mudança |
|---|---|
| `af8410b` | **Selecionar dado** (`SELECT_DATA`): o clique não executa nada; o alvo é o contêiner de dado mais próximo (célula, termo/descrição, título, parágrafo, item, legenda), realçado; vira a ação `EXTRACT`. Localizadores de leitura sem posição: `ROW_CONTEXT` `//table//tr[th[normalize-space(.)="Rótulo"]]//td`, `dt → dd`, região semântica (`header`, `role=search`…). Cabeçalho de linha (`th`/`dt`) é portador de âncora só com 100% vocabulário (D-E2E2-01: +morte, +nacionalidade, +ocupacao). Contrato do Bridge com fixtures `e2e002-*`. |
| `ea6b1ea` | Marcadores `(s)`, `(es)`, `(a)`… colados a uma palavra não são termo (D-E2E2-12). |
| `8c4aa10` | (homologação real) item de lista/parágrafo dentro de célula cuja linha tem `th` propõe a CÉLULA; `input role=combobox` é **text-entry** (a busca troca searchbox → combobox ao receber foco); região sem id/nome tem chave `landmark:<tag>`; `DescriptorIdentity` no núcleo (identidade transitória respeita a região do localizador contextual gravado); debug: empate entre candidatos de identidade confirmada decidido pelo localizador contextual gravado quando ele seleciona exatamente um (`DISAMBIGUATED_BY_RECORDED_LOCATOR`, novo código no contrato). |
| `9e4687e` | `E2e002SelectDataIT`: no Chrome real, título + célula com link + item de lista em linha → 3 leituras `EXTRACT` das CÉLULAS por `ROW_CONTEXT`, o link não navega, nenhum texto da página no descritor. Página de laboratório fictícia `test-lab/pages/e2e002/ficha.html`. |

## Testes

- Núcleo Java 223, Native Host 98, extensão (Node) 221/221.
- Novos: `TargetResolverTest` (combobox editável = mesmo campo do searchbox; `select` continua outra família),
  `DescriptorIdentityTest` (região separa a busca do cabeçalho da cópia; sem região não decide; região que seleciona dois
  não decide; chave `landmark:<tag>`), `E2e002SelectDataIT`.
- Mutações conferidas à mão: sem a promoção para a célula, `E2e002SelectDataIT` falha (a etapa vira item de lista).
- ITs da suíte padrão: **40/40 classes, 305 testes, 0 falhas** — 129 da rodada completa (interrompida pelo sistema por
  memória baixa) + 176 em 8 grupos sequenciais de até 3 classes, com memória amostrada ([c12-matriz.md](c12-matriz.md)).
  Defeito PRÉ-EXISTENTE (também na base `main`) corrigido: `Studio013SitesIT` S013-06/S013-09 dependiam de a captura
  demorar mais que 1,2 s; agora a página é ocupada antes do gesto (cenário "durante a captura" determinístico).

## Privacidade

Nada do texto da página sai: o descritor leva tipo, localizadores e caminho de acesso; o rótulo da linha só como
âncora de vocabulário. O motivo novo do debug é um código fechado (sem frase livre).
