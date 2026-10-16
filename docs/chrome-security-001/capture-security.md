# CHROME-SECURITY-001: segurança da captura técnica

Arquivo principal: `chrome-extension/src/content/capture.js`. É a 1ª barreira, e a mesma descrição de nó serve à captura completa, à cooperativa e aos deltas (`capture.partial`).

## Texto próprio, segmentos e título de SVG (SEC001-001, 021 a 024)

| Antes | Agora |
|---|---|
| `ownText` = texto dos nós filhos com `maskText` (formato) | texto cru usado só para decidir; sai **somente** se o elemento estiver dentro de um portador de nome (botão, link, rótulo, legenda, aba, item de menu ou alvo de `aria-labelledby`) cujo texto inteiro, sem subárvores de valor, passe no vocabulário |
| `textSegments` mascarados | só para portadores aprovados; senão ausentes |
| `<title>` de SVG mascarado | mesma regra (o título conta no texto do portador) |
| — | `textPresent`, `textSuppressed` (presença sem conteúdo) e `textRevision` (contador de mudanças mantido só na memória da página) |

O cache de aprovação dos portadores é por passada de captura (`newPass()`), na captura completa, na cooperativa e na parcial.

## Valores de formulário (SEC001-004 a 012)

- `exposedValue` é sempre `null`. A exceção dos campos de pesquisa (`isSearchField`) foi removida, e o host também força `null` (`RawNode`), de modo que nem uma extensão antiga consegue fazer o valor passar.
- O contenteditable continua tratado como valor (STUDIO-003): só `hasValue`/tamanho.
- A opção escolhida nunca sai. O gravador nem lê o texto dela, só registra que houve escolha.

## Atributos (SEC001-013 a 020, 081 a 085)

`CapturePolicy.attribute()` decide pela categoria do atributo: estrutural, identificador, classe, relação, nome, `value`, descritivo, URL ou personalizado (ver `privacy-policy.md`). URLs em atributos viram presença (`""`). Para frames e recursos, a URL vai como modelo nas listas próprias (frames, recursos, requisições).

## Relações

`refs` com ids temporários de nó, resolvidos com `getElementById` no escopo do elemento (documento ou shadow root). Para `label[for]`, vale o `label.control` do próprio navegador.

## Documento, frames, recursos e requisições

URL como modelo (`CapturePolicy.urlTemplate`). Título só do vocabulário. Recursos levam só nomes de parâmetro do vocabulário.

## Declaração

Snapshot (completo e cooperativo) e delta carregam `privacy: "nekkora-privacy/2"`. O host **não confia** nessa declaração e reaplica a política (`SnapshotPrivacy`).
