# CHROME-SECURITY-001: ponto de controle da fase 2 (decisão de arquitetura)

> **Situação:** proposta aguardando decisão. As correções evidentes e independentes já foram feitas (`6293dc7`).

## Por que há um ponto de controle

O motor Java identifica, nomeia, localiza e reencontra elementos **a partir do texto da página e dos valores de
atributos**. Os localizadores únicos de hoje se apoiam sobretudo em `ID` (224 de 359), `ROLE_NAME` (59), `CONTEXTUAL`
(54) e `ROW_CONTEXT` (15). Tirar o texto e os identificadores da representação transportável **remove uma capacidade
importante**: é exatamente o caso em que o pedido manda apresentar alternativa, impacto e recomendação antes de uma
solução irreversível.

## Medição (Chrome real, 34 páginas do laboratório, 359 elementos interativos)

O teste é o `SemanticCapabilityIT`, com evidência em `reports/evidence/SEC001-semantic-capability.json`. O mesmo
snapshot real é transformado por cada política e analisado pelo motor atual, sem nenhuma adaptação. "Transportável"
significa sem pseudônimo, isto é, pode ir para o Studio e ser executado depois.

| Política | Classificação igual | Nome legível | Localizador transportável único **e estável** | O que passa |
|---|---:|---:|---:|---|
| ATUAL (`nekkora-privacy/1`) | 359 | 350 | **355** | texto e atributos mascarados por formato (heurística) |
| ESTRUTURAL | 359 | 0 | **36** | nenhum texto; atributos de vocabulário fechado; presença de `href`; relações sem valor |
| ESTRUTURAL + gramática de IDs | 359 | 0 | 238 | o anterior + ids "com cara de técnico" (**deixa passar** `cliente-anacleto-prudencio`, `usuario_hermengarda`) |
| **VOCABULÁRIO CONTROLADO** | 359 | **209** | **214** | texto e identificadores **só** se todos os termos forem do vocabulário de interface; os marcadores do laboratório **não passam** |
| TOKENS de sessão (HMAC efêmero) | 359 | 0 legível | 33 | igualdade dentro da sessão; nada transportável |

Outras observações:

- Sem o valor de `href`, os links deixavam de ser links (35 casos). Basta transmitir a **presença** do atributo, sem o
  endereço, e isso já está incorporado às políticas medidas.
- Na política ESTRUTURAL, 323 dos 359 localizadores únicos são **posicionais**, instáveis por definição.
- TOKENS preserva o reconhecimento só **durante** a sessão: inspeção e reencontro funcionam, mas a automação gravada
  não ganha nada, porque um localizador com pseudônimo não é executável depois.

## Decisões

### D1: texto da página (nós, segmentos, título de SVG, `aria-label`, `title`, `placeholder`, `alt`, rótulos, títulos de documento)

| Opção | Como | Impacto | Risco |
|---|---|---|---|
| **B. Vocabulário controlado (recomendada)** | o texto só atravessa se **todos** os termos estiverem em `nekkora-ui-vocabulary/1` (928 termos PT/EN de interface; números de até 2 dígitos). Senão é suprimido **inteiro**, sem supressão parcial. Aplicado no content script e repetido no service worker, no host e no Studio | 60% dos elementos com localizador transportável estável; nomes como "Entrar", "Usuário", "Pesquisar" continuam; "Consultar cliente Bartolomeu Quixadá" vira genérico | lista de permissão, não detector: só palavras públicas de dicionário passam. Residual: um sobrenome que também seja palavra de interface (os comuns foram excluídos de propósito: costa, campos, lima, dias…) |
| A. Estrutural estrita | nenhum texto atravessa | 10% estáveis; etapas sempre genéricas ("Clicar em botão") | o menor risco |
| D. Texto efêmero no host | o texto vai ao motor só em memória; nada com texto é gravado, exportado ou enviado | capacidade de hoje dentro da sessão; as saídas seguem B ou A | contraria o objetivo 1 do pedido ("conteúdos sensíveis não sejam enviados ao Native Host") |
| C. Tokens de sessão | pseudônimo HMAC com chave efêmera | não melhora o que vai ao Studio | hashes, com a ressalva já feita no pedido |

Na opção B, a correção é **na origem**: o motor deixa de receber o texto suprimido. O reconhecimento que dependia de
texto e é feito por expressões (dica "carregar mais", indício de erro ou ajuda, catálogo de proteção) vira **sinal
controlado calculado na página** (ex.: `textHints: ["LOAD_MORE"]`), sem levar o texto.

### D2: identificadores escritos pelo desenvolvedor (`id`, `name`, classe, `data-testid`…) e relações (`for`, `aria-labelledby`, `aria-controls`…)

- **Recomendado:** a **mesma regra do vocabulário** de D1 (`btn-pesquisar` e `username` passam;
  `cliente-anacleto-prudencio` e `usuario_hermengarda` não). As **relações** passam a ser resolvidas **na página**,
  como ids de nó (`labelledByIds`, `controlsIds`…, como `labelIds` já faz), sem hash e sem valor.
- Alternativa estrita: identificador nunca atravessa. Sobram 10% de estáveis, como na opção A.
- **Descartada:** a gramática de "identificador técnico". É heurística, e a medição mostrou que ela deixa passar os
  marcadores.

### D3: URLs (documento, rota, iframe, recurso, requisição, etapas, evidências, PageMap)

- **Recomendado:** **origem** (esquema, host e porta) mais **modelo do caminho**. Um segmento do vocabulário fica, e
  qualquer outro vira `{…}`. Exemplo: `/clientes/joao-oliveira` vira `/clientes/{…}`, e `/lab/chrome-security-001/urls.html`
  vira `/lab/{…}/{…}`. A consulta mantém só os **nomes** de parâmetros do vocabulário, nunca valores, e o fragmento é
  suprimido. O tipo de navegação, a identidade do documento, o frame e a ordem continuam. Um modelo com `{…}` **não é
  executável** e não se passa por URL real.
- Alternativa: **somente a origem**.
- Consequência: estratégias de localização que dependiam de URL (`a[href=...]`) deixam de ser transportáveis, e isso
  é declarado.

### D4: título da aba (listagem de abas e ciclo de vida da gravação no Studio)

- **Recomendado:** o título só segue se passar no vocabulário. Senão o Studio mostra "host · aba N".
- Alternativa: o título nunca segue.

### D5: contratos e dados antigos

- Política nova **`nekkora-privacy/2`**, declarada em cada snapshot, delta, etapa, evidência e PageMap. Ausência ou
  política desconhecida nunca valem como garantia.
- **PageMap 0.3.0:** os campos de texto passam a ser anuláveis, com indicação de supressão. O schema 0.2.0 fica para
  os arquivos antigos. Não se redefine o 0.2.0 em silêncio.
- **RawSnapshot e Bridge:** mudanças **aditivas** (campo `privacy`, relações por id de nó). Um Studio ou host antigo
  continua recebendo payload válido, só com menos texto.
- **Registro de alvos:** passa a ser gravado em `targets/registry-v2.json`. O `registry.json` antigo **não é lido nem
  apagado**, e um procedimento explícito de limpeza fica documentado.

### D6: barreiras do Studio (branch `feat/chrome-security-001-studio-guards`)

O Studio aplica o **mesmo vocabulário** a nomes, localizadores, URLs e títulos. Um evento sem `nekkora-privacy/2`,
vindo de extensão antiga, é **degradado**: nome genérico, localizador descartado se não passar, URL reduzida à origem.
Ele nunca é aceito como se fosse da política nova.

## O que não muda, em qualquer opção

O modo estrito da STUDIO-005 (mensagens `SUPPRESSED`, vocabulário de região e campo, título omitido) continua. Também
continuam a identidade da gravação, a ordem, as rotas, as épocas, o ACK, a deduplicação, a pausa, a finalização, o
cancelamento, os modos CAPTURE_ONLY e CAPTURE_AND_INTERACT e a procedência RECORDED/MANUAL/MANUALLY_REVISED. O
usuário continua podendo nomear etapas e editar localizadores **manualmente** no Studio.
