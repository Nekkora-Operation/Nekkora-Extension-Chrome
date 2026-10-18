# STUDIO-012 · Adendo PDD-01 — política `nekkora-visual/2` (extensão e host)

A documentação completa do adendo fica no Studio (`Nekkora-Studio/docs/studio-012/pdd-01/`: diagnóstico, política,
compatibilidade, segurança, homologação, relatório). Aqui fica o que é próprio da extensão e do Native Host.

## O que muda e o que não muda

- **Não muda:** permissões (`activeTab` + atalho), gesto obrigatório, origem LAB/BENCHMARK (lista fechada), conferência
  da identidade da aba, ficha de layout antes/depois, trava exclusiva, `nekkora-privacy/2` para decidir o que é rótulo,
  imagem bruta só dentro de `shoot()`. Sem pedido de política, a extensão faz **exatamente** a v1 (mesma resposta, mesmo
  evento).
- **Muda:** com `policy: "nekkora-visual/2"` no `studio.pdd.capture.arm`, a imagem é a **representação documental**:
  fundo claro e estrutura redesenhada com paleta fechada; só os portadores trazem pixels reais.

## Plano visual v2 (`src/content/pdd-plan.js`, via `cs.pdd.plan {policy}`)

Só geometria e tipos, na ordem de pintura:

| Tipo | Origem no DOM | Regras |
|---|---|---|
| `SURFACE` + `tone` | `div`, `section`, `header`, `footer`, `nav`, `main`, `aside`, `article`, `form`, `fieldset`, `table`, `dialog`, `details`, `ul`, `ol` e papéis de região/diálogo/menu/barra | visível, ≥ 96×32 px CSS, com fundo próprio (luminância diferente do fundo de trás) ou borda; tabela = 1 superfície, nada de dentro dela; `tone` = LIGHT/MID/DARK pela luminância, **nunca a cor**; até 48 (as maiores, na ordem do documento) |
| `MEDIA` | `img`, `svg`, `canvas`, `video`, `picture` | ≥ 16×16; só o lugar; até 16 |
| `FRAME` | `iframe`, `frame`, `object`, `embed` | só o lugar; conteúdo nunca lido; até 16; limitação `FRAMES_HIDDEN` |
| `CONTROL` | botão cujo rótulo não pode ser portador | só o formato; até 32 |
| `FIELD` | campo editável visível | moldura vazia (igual à v1) |
| `CARRIER` | regras da v1 **+** `input[type=submit\|button\|reset]` com `value` inteiro do vocabulário | recusado (vira `CONTROL`, se for botão) com imagem de fundo **própria ou de ancestral**, fora do viewport, alto/largo demais, ou tocando campo, frame ou mídia; coberto = recusado |

O `input[type=submit]` era a causa do botão "Entrar" vazio no PDF da vistoria.

## Sanitizador (`src/background/visual-sanitizer.js`)

Funções puras novas: `deviceRegionsV2` (px CSS → px do dispositivo; portador para dentro, sintéticas para fora; ordem,
tipos, tons, tetos e sobreposições), `expectedV2` (a tela esperada), `composeV2` (tela esperada + pixels reais só nos
portadores) e `violationsV2` (cada pixel fora dos portadores tem de ser **exatamente** a cor esperada naquele ponto).

Paleta: página `#FFFFFF`; superfícies `#F4F6F8` (LIGHT), `#E6EAEE` (MID) e `#5B6675` (DARK), com borda `#C9D1D9`; mídia
`#EDEFF2`/`#C9D1D9`; frame `#F4F6F8`/`#8A9099`; controle `#E6EAEE`/`#8A9099`; campo `#FFFFFF`/`#8A9099`.

## Host (`PddVisualPrivacy`, 3ª barreira)

`policy = nekkora-visual/2` → `checkV2`: decodifica, confere dimensões, ordem, tetos, tom, sobreposição de portador e
repinta a tela esperada. Qualquer divergência vira `FAILED / SANITIZATION_CHECK_FAILED` sem imagem. A v1 segue com a
regra dela.

## Contrato (`contracts/studio-bridge.schema.json`)

- `PddArmPayload.policy` (opcional, `nekkora-visual/1` ou `/2`); `PddArmResult.policy` só quando foi pedida.
- `PddCapturedPayload.policy` passou a ser enum (v1 ou v2); `regions` vai até 256 itens, cada um `oneOf`: tipo sem tom ou
  `SURFACE` com `tone` obrigatório.
- Códigos novos de falha: `REGION_ORDER`, `CARRIER_OVERLAPS_CONTENT`.

Versão da extensão: **0.5.0**. O Studio só pede a v2 a partir dessa versão; com 0.4.x, continua pedindo a v1.

## Laboratório e testes

- `test-lab/pages/studio012/pdd-login.html`: réplica sintética da tela da vistoria (faixa colorida, logotipo, cartão,
  rótulos, `input type=submit` escuro, rodapé com ícones; texto sensível na cor-sentinela).
- `test-lab/pages/studio012/pdd-sensiveis.html`: menu com item sensível, tabela de registros, iframe de outra origem,
  rótulo sobre gradiente, botão grande demais, senha preenchida e cartão de status.
- `test/studio-pdd.test.js`: +6 testes da v2.
- `PddVisualPrivacyV2Test` (8): vazamento dentro de superfície, região declarada e não desenhada, tom trocado, cinza da v1,
  ordem e tom ausente, portador sobre frame e sobre mídia, v1 intacta.
- `Studio012PddVisualIT` (5, Chrome real, gesto pelo teclado do sistema): antes × depois na réplica, sensíveis, sentinelas
  da STUDIO-010 sob a v2, zoom de 150%, capturas inválidas (página mudando, troca de aba).
