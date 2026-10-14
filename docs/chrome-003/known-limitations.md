# Limitações conhecidas (CHROME-003)

A extensão **não** compreende qualquer iframe nem detecta qualquer proteção antibot. Limites verificados ou
declarados nesta entrega:

## Frames e documentos

1. **Origens sem permissão** (fora de `127.0.0.1`/`localhost`): somente atributos do iframe e metadados do Chrome
   (URL sanitizada, `documentId`); conteúdo nunca inventariado (`PERMISSION_DENIED`).
2. **Sandbox sem `allow-same-origin`**: conteúdo não inspecionado por política (origem opaca).
3. **Shadow roots fechados**: contados e declarados; conteúdo inacessível por definição.
4. **about:blank / srcdoc**: capturados pelo pai; os metadados do Chrome só são associados quando o pai tem
   identidade confirmada no Chrome e a URL é única sob esse pai, tanto no Chrome quanto entre os iframes lógicos
   (D-052) — vários about:blank irmãos, ou um about:blank sob pai sem metadados, ficam sem `browserDocumentId`
   (nunca adivinhado).
5. **Troca de documento de iframe de mesma origem** sem mutação no pai: detectada pelo evento `load` (captura) e por
   verificação periódica de 1 s — latência de até ~1 s se o `load` não for observado.
6. **`FrameInfo.url`** (seção `document.frames` do snapshot, contrato 0.1.0) mantém a URL completa do frame por
   compatibilidade com a CHROME-002; as seções novas (`frameGraph`, `accessPaths`, `protection`) usam URLs
   sanitizadas e somente nomes de parâmetros. Os atributos `src` de iframe/script passam a ter os **valores** de
   parâmetros redigidos.
7. **Limite de 20 frames anexados** por aba (outra origem permitida), herdado da CHROME-002; frames de mesma origem
   não têm esse limite (capturados pelo pai). Medido até 30 frames de mesma origem (D003-001).
8. `ELEMENT_BELONGS_TO_*` é emitido para elementos estruturais; a pertença de cada elemento comum está em
   `elements[].documentId`.
9. Iframes `fenced frame`, `portal` e documentos `prerender`/`bfcache` não foram exercitados no laboratório; o
   `documentLifecycle` do Chrome é registrado quando informado.

## Proteções

1. Detecção **somente** do que é observável no documento: proteções aplicadas no servidor/borda (WAF, bot
   management por cabeçalhos, TLS, reputação) podem não deixar evidência — `NOT_OBSERVED` nunca significa ausência.
2. Assinaturas de **observação de terceiros** (Cloudflare challenge/bot management/bloqueio, Akamai, HUMAN,
   Imperva) têm teto `MEDIUM`/`LOW`; fornecedores mudam estruturas sem aviso — o catálogo tem data de revisão e
   versão e precisa ser revisado periodicamente.
3. Widget real dentro de iframe do fornecedor (origem sem permissão): só a URL do iframe é avaliada; o estado
   interno (marcado/desafio aberto) é desconhecido.
4. Valores de parâmetros não são coletados: produtos que só se distinguem pelo valor (reCAPTCHA v2 explícito × v3)
   ficam com confiança reduzida.
5. `CHALLENGE_COMPLETED` depende de campo de resposta preenchido; desafios sem campo observável nunca são
   declarados concluídos.
6. Todas as validações foram feitas com fixtures **simuladas** (referências inertes); nenhum serviço real foi
   contatado, por exigência da missão. O comportamento contra páginas reais não foi homologado.

## Desempenho

Com o mesmo procedimento do baseline, sessão, seleção e exportação ficam dentro da variação de medição (uma rodada
intermediária mostrou exportação 16–20% mais lenta em 5.000 elementos, não reproduzida na final); os bytes exportados
crescem ≈ 0,5%. Medições de uma única execução por tamanho: variação de ±10–20% entre rodadas é esperada. Ver
`reports/chrome-003/performance.md`.
