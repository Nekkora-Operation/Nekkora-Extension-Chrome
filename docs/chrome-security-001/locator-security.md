# CHROME-SECURITY-001: segurança dos localizadores

## Regra

**Nenhum localizador gerado automaticamente atravessa com texto arbitrário da página** (SEC001-041 a 046).

1. **Na origem:** o motor gera localizadores sobre o modelo já filtrado, então os literais possíveis são metadado do vocabulário, identificadores do vocabulário e valores estruturais.
2. **Na representação estruturada (motor):** `LocatorPrivacy` julga cada predicado pelo papel sintático **antes** de
   gerar CSS/XPath. `NthOfType` é posição estrutural (qualquer número). `AttrEquals` é valor, pela regra da categoria do
   atributo: estrutural fechada, identificador do vocabulário, nome de interface; os demais seguem o literal da
   política. Texto, nome e rótulo seguem o vocabulário, e `RoleIs` segue a regra do `role`. Um candidato reprovado não
   vira localizador (revisão dos PRs #7, P1-02).
3. **Na saída:** `expressionSafe()` (JS, Java e Studio, mesmos vetores) **analisa a expressão inteira** pela gramática
   (CSS, XPath ou notação semântica; ver "Análise integral" abaixo). Todo nome de elemento, nome de atributo e literal
   (depois de decodificar os escapes) precisa passar na política. Um literal precisa ser vazio, `{…}`, número **de até 2
   dígitos** ou valor do vocabulário. O que a gramática não reconhece é **recusado**. Senão o localizador inteiro é
   **descartado**, nunca reescrito. As posições (`:nth-of-type(123)`, `[123]` do XPath, `{nth-of-type 123}`) não são literais e continuam aceitas; um número longo como **valor** (`//button[@data-customer='12345678900']`, `input[type="48291736455"]`) não é.
4. **Declaração:** quando há descarte, a etapa leva `LOCATORS_WITHHELD`. Sem localizador seguro e único, leva `SAFE_LOCATOR_UNAVAILABLE`: "Elemento identificado parcialmente. Não foi possível produzir um localizador seguro e único."

Aplicado no service worker (`studio-steps.js`), no host (`StepPrivacy`, também no `accessPath.locator`) e no Studio (`RecordedStep.guard`).

## Exemplos (vetores)

| Expressão | Transportável |
|---|---|
| `button[aria-label='Consultar']`, `#pesquisar`, `form > p:nth-of-type(3) > input`, `input[type=submit]`, `//input[@id=//label[normalize-space(.)="Nome"]/@for]` | sim |
| `button[aria-label='Consultar cliente Bartolomeu Quixadá']`, `#cliente-anacleto-prudencio`, `input[name="usuario_hermengarda"]`, `//button[text()='Consultar João Oliveira']`, `[data-customer='joao-oliveira']`, `a[href='/clientes/{…}']` | não |
| `button:nth-child(12)`, `//html/body/div[123]/button[1]`, `html >> body >> div {nth-of-type 123}`, `input[maxlength='12']` | sim (posição; número curto) |
| `//button[@data-customer='12345678900']`, `input[type="48291736455"]`, `[data-customer=12345678900]` | não (valor numérico longo) |
| `button[data-client^=joao-oliveira]`, `[data-client~=…]`, `[…\|=…]`, `[…$=…]`, `input[aria-label*=gumercindo-tavares]` | não (2ª revisão: operadores além de `=`) |
| `#\6a oao-oliveira` (escape de "j"), `button {aria-label=Consultar Joao Oliveira}` (valor sem aspas), `button[data-joao-oliveira]` (nome de atributo) | não |
| `ficha-cliente-joao > button`, `//ficha-cliente-joao/button`, `ficha-cliente-joao >> button` (nome de elemento) | não |
| `button:not(…)`, `button, a`, `button /* … */`, `button::after`, `//button[contains(…)]`, `//a \| //b`, `[data-client` | não (construção não reconhecida ou incompleta) |
| `input[aria-label*='Consultar']`, `button[data-client="Consultar" i]`, `painel-cliente > button`, `html > body > :nth-child(3) > button`, `* {nth-child 3}` | sim |

## Estratégias

`ROW_CONTEXT` (chave ou texto da linha) e `TEXT`/`LABEL`/`ROLE_NAME` com nome fora do vocabulário deixam de existir para esses elementos. O motor escolhe `CONTEXTUAL`/`ID`/`NAME` com identificadores do vocabulário ou cai para `XPATH_RELATIVE`/`POSITIONAL`, que é instável e assim declarado. A estabilidade não é inventada: um localizador posicional continua `HEURISTIC_UNSTABLE`. A validação ao vivo (`cs.verifyLocators`) continua igual.

## Localizadores que dependem de URL

Um `a[href=...]` com caminho real não é transportável: o atributo `href` vai só como presença e a URL só como modelo `{…}`. Não se gera URL artificial que pareça executável.

## Análise integral da expressão (2ª revisão dos PRs #7)

Antes, `expressionSafe()` procurava literais por expressão regular e aprovava a expressão quando **todos os literais
encontrados** passavam. Uma construção que o padrão não reconhecia (`[data-client^=joao-oliveira]`, um escape CSS, um
comentário, um nome de elemento) não produzia literal. Com a lista vazia, a expressão era aprovada. A reprodução está em
`reports/evidence/SEC001-r2-reproducao.txt`: 29 expressões aceitas indevidamente, nas três implementações.

Agora a expressão é aprovada só se uma das gramáticas abaixo a **reconhecer por inteiro** e cada elemento passar na
política. Não há tentativa de "consertar" a expressão.

| Gramática | Reconhecido | Verificação |
|---|---|---|
| CSS | compostos com `>`, `+`, `~` ou espaço; tipo ou `*`; `#id`, `.classe`; `[atributo]` e `[atributo op valor]` com `=`, `~=`, `\|=`, `^=`, `$=`, `*=` e marcador `i`/`s`; `:nth-child`, `:nth-last-child`, `:nth-of-type`, `:nth-last-of-type` (`odd`, `even`, `an+b`); pseudo-classes estruturais/de estado sem argumento | nome de elemento pelo catálogo `nekkora-html-elements/1` ou nome inteiro do vocabulário (nunca o marcador neutro); nome de atributo conhecido pela política ou do vocabulário; valor, id e classe como literal, **depois** de decodificar escapes (`\6a ` vira "j") |
| XPath (subconjunto do motor) | `/` e `//` com tipo ou `*`; predicados de posição (`[3]`), `@atributo`, `@atributo=valor`, `normalize-space(.)`/`normalize-space()`/`text()` = literal, `concat(...)`, caminho relativo com `ancestor::` e caminho absoluto dentro do predicado (`@id=//label[...]/@for`) | número **comparado** é valor (até 2 dígitos); número sozinho é posição; `concat` é julgado pela concatenação inteira |
| Notação semântica | `tag {predicado} … >> tag {…}`; predicados `nth-of-type n`, `nth-child n`, `has atributo`, `has child <tag> "texto"`, `chave=valor` e `chave="texto"` | o valor sem aspas é julgado **inteiro**, até o `}` |

Recusados por construção: vírgula, comentário, namespace, `::`, `:not`/`:is`/`:has`/`:contains`, funções XPath fora da
lista, `|` do XPath, caractere de controle ou separador de linha, expressão incompleta ou com mais de 4000 caracteres.

**Sem corte.** Uma expressão cortada deixa de ser analisável. Por isso o host envia a descrição semântica inteira (até
500 caracteres) ou vazia, e o service worker descarta, em vez de cortar, CSS e XPath acima do limite do contrato (500).

**Equivalência das três implementações.** Os vetores compartilhados (`privacy-v2-vectors.json`: 97 expressões e 14
nomes de elemento) passam em JS, Java e Studio. Um teste diferencial com 200.007 entradas aleatórias (operadores,
escapes, Unicode, controles) deu **0 divergências** entre JS e Java e nenhuma exceção.

## Elemento com nome retido (2ª revisão dos PRs #7)

O nome de um componente fora do catálogo e do vocabulário chega ao motor como o marcador neutro `custom-element`
(`privacy-policy.md`). O motor nunca usa o marcador num localizador: o passo vira `*`. Como `:nth-of-type` depende do
nome, a posição usada é `:nth-child(k)` (XPath `*[k]`, notação `{nth-child k}`), com `k` informado pela **página**
(`childPositions`: posição real entre todos os filhos elemento, inclusive os que a captura não descreve, como `script`).
O motor não recalcula essa posição, porque a sua contagem divergiria da do navegador. Sem a posição informada (captura
antiga), nenhuma é inventada: o passo fica só `*`.

Exemplo: o hospedeiro de um shadow root `<nk-open-card>` era `html > body > section:nth-of-type(1) > nk-open-card:nth-of-type(1)`;
agora é `html > body > section:nth-of-type(1) > :nth-child(2)`. A concordância ao vivo (`ComplexComponentsIT.openShadow`)
e a resolução continuam.
