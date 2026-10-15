# STUDIO-005 — Detecção de mensagens e validação

## Princípio

A extensão detecta **sinais de interface**, não significados. A classificação vem do papel semântico da região (ARIA ou
validação nativa), nunca do texto e nunca só de classe CSS.

| Classificação | Origem | O que **não** significa |
|---|---|---|
| `ERROR_INDICATOR` | alerta (`role=alert`/`alertdialog`/`aria-live=assertive`) com indício de erro, ou região referenciada por `aria-errormessage` | que a operação de negócio falhou |
| `STATUS_INDICATOR` | `role=status`/`aria-live=polite` | que a operação deu certo |
| `VALIDATION_INDICATOR` | campo `aria-invalid=true` ou evento nativo `invalid` | que o valor está errado para o negócio |
| `UNKNOWN_FEEDBACK` | alerta sem indício de erro; ambíguo | — |

## Mensagens dinâmicas em região existente

- A região é acompanhada pela identidade do elemento (WeakMap local, sem efeito na página) e pela assinatura.
- Ela vira evidência quando passa a visível com texto, ou quando o texto muda (hash diferente) enquanto está visível.
- Não há duplicata: a chave é `região + hash` por janela. Reapresentar o mesmo texto na mesma janela não gera nova evidência.
- Uma região ocupada (`aria-busy=true`) não gera avisos intermediários. Ao terminar, sai `LOADING_FINISHED` e, se o
  conteúdo mudou, `CONTENT_CHANGED`. O conteúdo da lista nunca é enviado: no cenário de pesquisa, "Contrato Alfa" não sai da página.

## Validação

- **Da aplicação**: o campo passou a `aria-invalid=true`. A mensagem vem do elemento indicado por `aria-errormessage`,
  quando visível, ou de `validationMessage`, sempre pela política de mensagem. O campo é identificado pelo nome acessível
  (`aria-label`, `aria-labelledby`, `<label>`, `placeholder` ou `title`, mascarado).
- **Nativa**: o evento `invalid` que o **próprio navegador** dispara quando recusa o envio. É escutado na captura, e
  nada é disparado pela extensão: nada de `reportValidity()` ou `checkValidity()`. Limitação: o `invalid` não atravessa
  shadow roots.
- Homologado: "CEP inválido." (`ARIA_INVALID` + `ARIA_ERRORMESSAGE`, `DIRECTLY_OBSERVED` da etapa "Preencher CEP") e
  "E-mail" obrigatório (`NATIVE_INVALID`, associado ao clique em "Cadastrar"). O formulário não foi enviado, e a
  observação não o enviou.

## Texto da mensagem

O texto passa pela política de mensagem (`privacy.md`) **antes de sair do content script**. O resultado é sempre um destes:

- `AVAILABLE` + trecho de até 160 caracteres;
- `SUPPRESSED` + `null` (com a limitação `MESSAGE_SUPPRESSED`);
- `NONE` (região sem texto).

HTML nunca é lido para envio. No cenário "Marcação" (`Senha <b>incorreta</b>.`) só sai o texto `Senha incorreta.`.
