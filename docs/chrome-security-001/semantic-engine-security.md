# CHROME-SECURITY-001: motor semântico sob a política

O motor Java continua decidindo classificação, família, localizadores e resolução, como pede a arquitetura. A diferença é que agora ele **não recebe** texto da página fora do vocabulário. A política aprovada na fase 2 foi escolhida por preservar o máximo de capacidade sem transmitir texto privado.

## Mudanças no motor

| Ponto | Antes | Agora |
|---|---|---|
| Presença de texto | `!ownText.isBlank()` | `RawNode.hasText()` = texto que atravessou **ou** `textPresent` (texto retido continua sendo texto: TEXT × contêiner, inclusão no PageMap, dicas de mensagem) |
| Relações (`for`, `aria-*`, `form`, `headers`) | resolvidas pelo valor do `id` | `PageModel.refTargets()`: `refs` (ids de nó resolvidos na página); valor do atributo só para entradas antigas e testes |
| Nome acessível | composto de qualquer texto | composto só do que atravessou; se alguma fonte teve texto retido, o nome **inteiro** é retido (as fontes continuam) |
| Família | rótulos a partir das fontes do nome | rótulos também de `labelIds` e de `aria-labelledby`, mesmo com o nome retido |
| Referências cruzadas (`aria-describedby`/`aria-errormessage`) | por `escopo|id` | por id de nó |
| URL (tela, filtros, paginação) | caminho real | modelo; segmento omitido nunca vira nome de tela |
| `exposedValue` | valor de pesquisa | nunca |
| Valor de interação | texto da opção | posição da opção (`#2`) |

## Correção de segurança encontrada na regressão (SEC001-078)

Sem texto, uma identidade podia ficar só com contexto ou tipo, e a pontuação, que é uma razão sobre as características aplicáveis, chegava a 0,75 com evidência mínima. No Chrome real, ao trocar o campo de e-mail por um botão com o mesmo `id`, **o campo "Nome" era confirmado como o alvo**. Agora `TargetResolver` só confirma com ao menos uma característica do **próprio elemento** (id, `name`, atributo de teste, nome, rótulo, texto, linha ou coluna). Contexto, contêiner e tipo, sozinhos, deixam o candidato "plausível sem comprovação". O teste de unidade reproduz o defeito sem a correção.

## Capacidade medida (Chrome real, 34 páginas, 359 elementos interativos)

Ver `performance.md` e `reports/evidence/SEC001-semantic-capability.json` (medição da fase 2, com o motor de antes da correção e a política simulada). A comparação final, com a implementação real, está no relatório final.
