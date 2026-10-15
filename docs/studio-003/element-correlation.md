# STUDIO-003 — Correlação do elemento (referência técnica de cada etapa)

A extensão continua sendo a fonte de inteligência dos elementos web. O Studio **não** recalcula localizadores
(D-STUDIO-003-08).

## Fluxo, por etapa (no service worker, em ordem)

1. **Barreira curta**: `capture.synchronize(tab)` (até 2,5 s) para que o nó da interação esteja no estado do motor.
   Se a página já mudou, usa a última captura conhecida.
2. **`recorder.element.describe`** (Native Host, uma chamada) devolve, sem PageMap:
   - classificação (`kind`, `role`) e nome acessível mascarado;
   - `tag`, e `htmlId`/`htmlName` só quando não são sensíveis nem parecem segredo;
   - até 5 localizadores (estratégia, descrição, CSS, XPath, correspondências na captura, estabilidade);
   - o índice do primeiro localizador **único** na captura;
   - a trajetória documental (`MAIN_DOCUMENT` → `IFRAME` → `SHADOW_ROOT` → `ELEMENT`, com restrições);
   - âncoras de contexto (`IN_DIALOG`, `IN_ROW`, `IN_LIST_ITEM`, `IN_FORM`);
   - elementos parecidos e notas (`NO_UNIQUE_LOCATOR`, `INDISTINGUISHABLE_LOOKALIKES`, `ACCESS_RESTRICTED`);
   - registro da **identidade lógica** (`targetId`), como `target.register`.
3. **Verificação ao vivo** no documento: `cs.verifyLocators` executa os localizadores (com escopo de iframe e de
   shadow) e `recorder.element.verify` marca `liveUnique` só para o que encontrou exatamente o alvo.
4. **Montagem** (segunda barreira de privacidade) do `ElementRef` do contrato. Cada localizador recebe uma validação:
   - `LIVE`: único na página real;
   - `SNAPSHOT`: único só na captura;
   - `NONE`: não validado.

   O preferencial é o primeiro LIVE ou, na falta dele, o único da captura.

## Quando não dá para correlacionar

Exemplos: nó inexistente na captura, motor indisponível, documento já substituído.

- `resolution: UNRESOLVED`, com o descritor **observado** pelo sensor (tag, papel, nome mascarado, id e name
  seguros);
- **nenhum** localizador e nenhuma identidade (`correlation` ausente), nota `ELEMENT_UNRESOLVED`;
- no Studio, a etapa fica com o obstáculo "elemento não correlacionado" para revisão. Nunca se escolhe o primeiro
  elemento parecido.

Quando o documento some antes da verificação ao vivo, a validação fica `SNAPSHOT`, com a nota
`LIVE_CHECK_UNAVAILABLE`.

## Medições (Chrome real, `S003-performance.json`)

- Correlação por etapa: p50 de 11 ms, p95 de 12 ms, máximo de 14 ms (21 amostras).
- 21 de 21 elementos resolvidos e verificados ao vivo.
- Evento da etapa: cerca de 2,4 KB.

## Testes

- Host: `RecorderCommandsTest` (5 testes):
  - referência compacta, localizador único e trajetória;
  - senha sem id e sem name, e-mail mascarado;
  - ids duplicados sem identidade inventada, com contexto de linha;
  - nó inexistente recusado;
  - verificação ao vivo.
- Node: `studio-steps.test.js` cobre `UNRESOLVED` e a validação ao vivo indisponível.
- Chrome real: `Studio003InteractionsIT` confere `resolution`, o localizador preferencial, a trajetória com IFRAME e
  SHADOW_ROOT e o `targetId`.
