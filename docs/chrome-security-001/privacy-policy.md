# CHROME-SECURITY-001: política `nekkora-privacy/2`

Política aprovada no ponto de controle da fase 2 (decisões D1 a D5, em 2026-10-15). Ela vale para **tudo o que sai da página**: snapshot e deltas ao motor, etapas e evidências ao Studio, PageMap, registro de alvos, diagnósticos e armazenamento da extensão.

## Princípio

**Nenhum conteúdo textual arbitrário da página atravessa por depender de detecção de dado pessoal.** O que não pertence a uma lista fechada não sai. Não há expressão regular "de CPF" decidindo o que passa: a máscara heurística da `nekkora-privacy/1` (`maskText`, `looksSecret`) deixou de ser critério de saída.

## Três categorias

| Categoria | Exemplos | Regra |
|---|---|---|
| Estrutural | tag, papel ARIA, estado (`aria-expanded`, `disabled`, `checked`), tipo do campo, posição, hierarquia, frame, Shadow DOM, presença de texto, presença de atributo | atravessa |
| Texto da página | nome de controle, rótulo, título, aviso, célula, parágrafo, opção | só o **metadado de interface elegível** cujo valor **inteiro** está no vocabulário controlado; o resto vira presença (`textPresent`/`textSuppressed`) |
| Valor do usuário | texto digitado, termo pesquisado, opção escolhida, conteúdo editável | nunca atravessa (no máximo `hasValue`/tamanho, e não em campo sensível) |

## Vocabulário controlado (`nekkora-ui-vocabulary/1`)

- Fonte única em `contracts/privacy/ui-vocabulary.json`: 1064 termos PT/EN de interface (1062 na CHROME-SECURITY-001; +"excel" e +"cloud" na NEKKORA-E2E-001, ver o histórico do arquivo). São os 928 aprovados no ponto de controle, mais termos técnicos acrescentados à mão e documentados no próprio arquivo: papéis ARIA, tipos de campo, extensões e segmentos de caminho, termos públicos de fornecedores de proteção do catálogo fechado e termos de estado de interface usados pelas dicas do motor.
- **Fechado.** Muda só por revisão versionada. Não há expansão automática nem exceção por cliente.
- **Exclusão de propósito:** palavras que também são sobrenomes frequentes (costa, campos, lima, dias, oliveira…) ficam fora, mesmo sendo palavras comuns.
- **Regra de um valor:**
  - NFC e espaços Unicode colapsados;
  - só caracteres do conjunto seguro: letras latinas (com acento), dígitos, espaço e `-_.:/()',!?`. Largura zero, controle, largura total, outros alfabetos e símbolos recusam o valor inteiro;
  - termos separados por pontuação, hífen, sublinhado, camelCase e fronteira letra/dígito;
  - **todos** os termos precisam estar no vocabulário ou ser números de até 2 dígitos;
  - até 8 termos e 80 caracteres.
- **Sem supressão parcial:** "Consultar cliente Bartolomeu Quixadá" não vira "Consultar cliente", vira vazio.

## Elegibilidade (antes do vocabulário)

O vocabulário só se aplica a **metadado de interface elegível**. Conteúdo de negócio, tabelas, mensagens e texto livre ficam retidos **mesmo** quando feitos só de palavras do vocabulário: uma célula com "Ativo" não sai.

| Elegível | Não elegível |
|---|---|
| texto **dentro** de um portador de nome: `button`, `a[href]`, `summary`, `label`, `legend`, papéis `button/link/tab/menuitem*/checkbox/radio/switch/treeitem`, alvo de `aria-labelledby` | parágrafo, título de seção, célula, legenda de tabela, item de lista, opção, alerta, status, diálogo, texto solto |
| atributos de nome: `aria-label`, `title`, `placeholder`, `alt`, `aria-roledescription`, `aria-placeholder`, `value` de `input` botão | `aria-description`, `aria-valuetext`, `label` (opção/grupo), `value` de campo ou opção |
| identificadores do desenvolvedor: `id`, `name`, classes (cada uma), `data-testid`/`data-test`/`data-qa`/`data-cy` | `data-*` e atributos personalizados (só a **presença**, se o nome do atributo passar) |
| título da aba e do documento (D4) | |

O texto do portador é verificado **inteiro**, sem as subárvores de valor (`select`, `option`, `textarea`, contenteditable). Se a verificação falhar, nenhum pedaço dele sai. O mesmo vale para o nome acessível composto de várias fontes: se alguma fonte teve texto retido, o nome inteiro é retido e as fontes (estrutura) continuam.

### Atributos estruturais: regra fechada por atributo (revisão dos PRs #7, P1-01)

A forma técnica do valor **não basta**. `role="cliente-joao"` ou `autocomplete="joao oliveira"` têm forma técnica e
mesmo assim são texto arbitrário. Cada atributo estrutural tem uma regra **fechada** no contrato versionado
`contracts/privacy/structural-attributes.json` (`nekkora-structural-attributes/1`, 100 atributos, gerado por
`scripts/gen-structural-attributes.py`), a mesma em JS, Java e Studio:

| Tipo de regra | Exemplos | Valor aceito |
|---|---|---|
| booleano | `disabled`, `required`, `checked`, `open` | vazio, o próprio nome, `true`/`false` |
| enumeração | `type`, `inputmode`, `target`, `aria-current`, `aria-haspopup` | um valor da lista do atributo |
| tokens | `role`, `autocomplete`, `rel`, `sandbox`, `accept` | cada item da lista do atributo |
| inteiro | `tabindex`, `colspan`, `maxlength`, `aria-setsize` | até 5 dígitos |
| decimal | `min`, `max`, `step`, `aria-valuenow` | até 5 dígitos inteiros e 4 decimais |
| dimensão | `width`, `height` | até 5 dígitos, `px`/`%` opcionais |
| idioma | `lang`, `hreflang`, `srclang` | código ISO 639-1 (lista fechada) + escrita/região opcionais |
| só presença | `allow` | nunca o valor |

Fora da regra, o atributo vai **só como presença** (valor vazio). O papel ARIA das etapas (recorder, service worker,
host e Studio) passa pela mesma regra do `role`. Antes, ele saía cru do descritor do recorder.

### Nomes de elemento (2ª revisão dos PRs #7)

O nome de um elemento personalizado (`<ficha-cliente-joao>`) ou desconhecido (`<joao>`) é escolhido pelo autor da
página e pode carregar dado de negócio. Regra fechada, a mesma em JS, Java e Studio (`tagName`):

| Nome | Sai |
|---|---|
| do catálogo `contracts/privacy/html-elements.json` (`nekkora-html-elements/1`, 235 nomes HTML, incluindo os obsoletos, SVG e MathML, gerado por `scripts/gen-html-elements.py`) | o nome, em minúsculas (só ASCII; sem dobra Unicode) |
| personalizado (com `-`) cujo valor **inteiro** é identificador do vocabulário (`painel-cliente`) | o nome |
| qualquer outro (`ficha-cliente-teodolinda`, `nk-open-card`, `eustaquio`, `custom-element`) | o marcador neutro `custom-element` |

Aplicada na captura (1ª barreira), no descritor do recorder, nas etapas do service worker (2ª), no `SnapshotPrivacy` e
no `StepPrivacy` do host (3ª) e no `RecordedStep.guard` do Studio (4ª). O motor continua sabendo que existe um elemento
naquela posição. Shadow root, classificação e nome acessível não dependem do nome do componente. Os localizadores usam
`*` e a posição real (`locator-security.md`).

### Fonte de nome retida

Quando um atributo de nome (`aria-label`, `title`, `placeholder`, `alt`, `aria-roledescription`, `aria-placeholder`,
`value` de botão) existe mas o valor não passa, ele atravessa só como **presença marcada**: o valor vira `{…}`, o mesmo
sinal de lacuna usado nas URLs. Um valor vazio continua vazio (`alt=""` decorativo é semântica legítima). A marca
existe para o motor **não inventar um nome**: sem ela, um botão `aria-label="Consultar cliente Bartolomeu Quixadá"` com
o conteúdo "Consultar" seria nomeado "Consultar" por uma fonte de prioridade menor, e esse não é o nome acessível real.
Com a marca, o nome fica retido inteiro (fonte `ARIA_LABEL`, texto vazio). O mesmo vale para rótulos (`<label>`,
`aria-labelledby`) cujo texto foi retido. A marca nunca vira literal de localizador nem título de frame.

## Relações entre elementos

`for`, `aria-labelledby`, `aria-describedby`, `aria-controls`, `aria-owns`, `aria-activedescendant`, `aria-errormessage`, `list`, `form`, `headers`: a página resolve a relação em **ids temporários de nó** (`refs`). O valor do atributo (ids da página) não atravessa, e o atributo fica presente com valor vazio. Não há hash nem pseudônimo.

## URLs (D3)

- Formato: origem + **modelo do caminho**. Segmento do vocabulário fica; os demais viram `{…}`.
- Consulta: só os **nomes** de parâmetro do vocabulário, sempre `nome={…}`. Fragmento nunca sai.
- Outros esquemas: só `esquema:{…}`. `about:blank`/`about:srcdoc` passam como estão.
- O modelo não é executável e não se passa por URL real.
- Exemplo: `https://sistema/clientes/joao-oliveira?cliente=Ana#ficha` vira `https://sistema/clientes/{…}?cliente={…}`.

## Mudanças (deltas, MutationRecord)

O fato fica e o conteúdo não:
- texto alterado: `[omitido]` antes e depois. Uma mudança de texto já retido é detectada pela **revisão** do texto, um contador mantido na página, sem conteúdo nem resumo;
- atributo: o **nome** e, nos estruturais, o valor (`aria-expanded: false → true`). Os demais valores viram `[omitido]`.

## Versionamento

| Artefato | Antes | Agora |
|---|---|---|
| Política | `nekkora-privacy/1` (máscara heurística) | `nekkora-privacy/2`, declarada no snapshot, no PageMap e no `hello` do Bridge |
| PageMap | 0.2.0 | **0.3.0** (`privacy`, `text.suppressed`); 0.2.0 congelado em `contracts/v0.2.0/` |
| RawSnapshot | campos de texto livres | aditivo: `privacy`, `textPresent`, `textSuppressed`, `textRevision`, `refs`; `exposedValue` sempre `null` |
| Bridge (`nekkora-bridge/1`) | — | aditivo: `client.privacy`; notas `OPTION_WITHHELD`, `LOCATORS_WITHHELD`, `SAFE_LOCATOR_UNAVAILABLE` |
| Registro de alvos | `targets/registry.json` | `targets/registry-v2.json` (o antigo não é lido nem apagado) |

Uma política ausente ou desconhecida nunca vale como garantia: o host e o Studio reaplicam a v2 a tudo o que recebem.

## Barreiras

| # | Onde | Implementação |
|---|---|---|
| 1 | content script | `capture-policy.js` (`capture.js`, `interaction-recorder.js`, `feedback-observer.js`) |
| 2 | service worker | `studio-steps.js`, `studio-evidence.js`, `studio-recording.js`, `diagnostics.js`, `frames.js`, `runtime.js` (log) |
| 3 | Native Host | `SnapshotPrivacy` (entrada de snapshots, deltas e frames), `StepPrivacy`, `EvidencePrivacy`, `StudioLink.titles`, `BrowserFrameMetadata`, `LogSafety` |
| 4 | Studio | `CapturePrivacy` (domínio), `RecordedStep.guard`, títulos de aba, URLs de evidência, `ExtensionInfo.privacy` |

As implementações JS (`capture-policy.js`) e Java (`CapturePrivacyPolicy`, `CapturePrivacy` do Studio) são verificadas pelos **mesmos vetores** (`contracts/privacy/privacy-v2-vectors.json`). Os vetores já pegaram duas divergências reais: `String.trim()` do Java removendo `U+0000` e o par `@id=//label[...]` de XPath.
