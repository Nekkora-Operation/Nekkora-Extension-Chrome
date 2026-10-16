# CHROME-SECURITY-001: segurança dos localizadores

## Regra

**Nenhum localizador gerado automaticamente atravessa com texto arbitrário da página** (SEC001-041 a 046).

1. **Na origem:** o motor gera localizadores sobre o modelo já filtrado, então os literais possíveis são metadado do vocabulário, identificadores do vocabulário e valores estruturais.
2. **Na saída:** `expressionSafe()` (JS, Java e Studio, mesmos vetores) extrai **todos** os literais de CSS (`#id`, `.classe`, `[attr=valor]`), de XPath (`'…'`, `"…"`) e da descrição semântica (`chave=valor`, `"…"`). Cada literal precisa ser vazio, número ou valor do vocabulário. Senão o localizador inteiro é **descartado**, nunca reescrito.
3. **Declaração:** quando há descarte, a etapa leva `LOCATORS_WITHHELD`. Sem localizador seguro e único, leva `SAFE_LOCATOR_UNAVAILABLE`: "Elemento identificado parcialmente. Não foi possível produzir um localizador seguro e único."

Aplicado no service worker (`studio-steps.js`), no host (`StepPrivacy`, também no `accessPath.locator`) e no Studio (`RecordedStep.guard`).

## Exemplos (vetores)

| Expressão | Transportável |
|---|---|
| `button[aria-label='Consultar']`, `#pesquisar`, `form > p:nth-of-type(3) > input`, `input[type=submit]`, `//input[@id=//label[normalize-space(.)="Nome"]/@for]` | sim |
| `button[aria-label='Consultar cliente Bartolomeu Quixadá']`, `#cliente-anacleto-prudencio`, `input[name="usuario_hermengarda"]`, `//button[text()='Consultar João Oliveira']`, `[data-customer='joao-oliveira']`, `a[href='/clientes/{…}']` | não |

## Estratégias

`ROW_CONTEXT` (chave ou texto da linha) e `TEXT`/`LABEL`/`ROLE_NAME` com nome fora do vocabulário deixam de existir para esses elementos. O motor escolhe `CONTEXTUAL`/`ID`/`NAME` com identificadores do vocabulário ou cai para `XPATH_RELATIVE`/`POSITIONAL`, que é instável e assim declarado. A estabilidade não é inventada: um localizador posicional continua `HEURISTIC_UNSTABLE`. A validação ao vivo (`cs.verifyLocators`) continua igual.

## Localizadores que dependem de URL

Um `a[href=...]` com caminho real não é transportável: o atributo `href` vai só como presença e a URL só como modelo `{…}`. Não se gera URL artificial que pareça executável.
