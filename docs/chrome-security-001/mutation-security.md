# CHROME-SECURITY-001: segurança de deltas e MutationRecord

## Deltas (SEC001-028 a 033)

- O `mutation-tracker.js` envia os nós alterados descritos por `capture.partial()`, com **a mesma política** da captura completa, inclusive em rebase, recuperação, reenvio, Shadow DOM e navegação. Um texto que aparece depois da captura inicial ("Cliente Ulisses Paranhos") também é retido.
- Na entrada do host, `SnapshotPrivacy.apply(delta, base)` reaplica a política. O contexto (portador de nome, ancestrais) vem do próprio delta e, na falta, da captura-base.

## MutationRecord / NodeChange (SEC001-034)

| Campo | Regra |
|---|---|
| `textBefore` / `textAfter` | `[omitido]` quando o texto mudou; `null` quando não mudou (nunca uma mudança inventada) |
| `attributeChanges` | o **nome** do atributo fica; o valor fica só nos estruturais (`aria-expanded: false → true`); nos demais vira `[omitido]`; ausência continua `null` |
| `stateChanges` | inalterado (estados são estruturais) |

A barreira está no **construtor** de `NodeChange` e `ResolutionResult`: nenhum produtor (comparação, delta, reconciliação, resolução) consegue fazer o conteúdo sair.

## Texto retido que muda

Um texto retido nos dois lados (vazio antes e depois) não permitiria perceber a mudança. Por isso a página mantém, em memória, uma **revisão** por elemento: um contador que sobe quando o texto próprio muda. Só o número atravessa (`textRevision`). O comparador registra a mudança como `[omitido]`. Não há hash nem resumo do texto fora da página.

## Log do service worker (SEC001-056)

O `broadcast()` mantém a difusão ao vivo para as páginas da extensão, mas o `eventLog` (`events.since`, `sw.diagnostics`) **retém** só campos de lista fechada: identificadores, códigos, estados, números e booleanos. Listas viram contagens. Nenhum payload (nós, mutações, nomes, localizadores, URLs, mensagens) é guardado.
