# NEKKORA-STUDIO-013 — decisões

Captura visual do PDD em sites autorizados. Base: `main` da extensão `ab281e2` (PDD-01, PR #12) e do Studio `fceaa4a`
(PR #16). Os dois PRs foram integrados em 19/10/2026 às 14:00 UTC, antes do início da missão: não foi preciso empilhar.

## D-S013-01 — fim da lista compilada; duas autorizações independentes

`BENCHMARK_ORIGINS` saiu de `visual-capture-profile.js`. O contrato do perfil passou a ser
`nekkora-visual-capture-profile/2`, com os perfis abaixo.

| Perfil | Quando vale | Políticas aceitas |
|---|---|---|
| `LAB` | `127.0.0.1` ou `localhost`, como antes | `nekkora-visual/1`, `/2` e `/3` |
| `AUTHORIZED_SITE` | Permissão de site do Chrome **e** consentimento PDD da origem exata | Só `nekkora-visual/3` |
| `BENCHMARK` | Mantido no enum do contrato para clientes antigos. A partir da 0.6.0 não é mais produzido | — |

- **Permissão do Chrome:** autoriza inspeção e gravação, como antes.
- **Consentimento PDD:** autoriza só a captura de tela para o PDD. É uma decisão à parte, por origem exata (esquema +
  host + porta), e vale mesmo quando a extensão tem a permissão ampla `https://*/*`.
- **Sem permissão nova:** `host_permissions` continua só com o laboratório. Nenhuma permissão nova foi pedida.
- **`rpachallenge.com`:** passa a seguir a regra geral. Captura com o consentimento e não depende mais da lista.

## D-S013-02 — consentimento `nekkora-pdd-consent/1`

- **Registro:** guarda só `origin`, `scope`, `actor` (`DEVELOPER` | `TEST_AGENT`), `via` (`STUDIO` | `EXTENSION`) e
  `grantedAt`. Nunca guarda caminho, query, título, credencial ou identificador de sessão. A origem é aceita só na forma
  canônica de `URL.origin`, sem barra final, credenciais nem porta padrão explícita. O laboratório não aceita registro.
- **Escopo `SESSION` (padrão):** fica em `chrome.storage.session`, que content scripts não leem. Vale até o navegador
  fechar.
- **Escopo `REMEMBERED` (escolha explícita):** fica no IndexedDB da origem da extensão, fora do alcance de content
  scripts e da página. Escolher "só nesta sessão" depois de "lembrar" apaga o lembrado.
- **Quem concede e revoga:** só a interface confiável.
  - Painel da extensão: comandos `pdd.consent.*` do roteador de páginas da extensão.
  - Studio: comandos `studio.pdd.consent.status|grant|revoke|list`, pelo Bridge autenticado e pela lista explícita do
    Native Host.
  - Content scripts: o roteador encaminha mensagens deles só para `onContentEvent`, que não tem comando de consentimento.
- **Concessão pelo Studio:** manda a origem que mostrou ao usuário. Se a aba estiver em outra origem, a extensão recusa
  com `ORIGIN_CHANGED`. Só vale para site já permitido pelo Chrome.
- **Remoção da permissão no Chrome:** `permissions.onRemoved` apaga o consentimento das origens que deixaram de ser
  permitidas.

## D-S013-03 — revogação e revalidação

- **Na armação:** a extensão guarda origem, perfil e política.
- **Em cada conferência da aba:** `sameTab()` revalida tudo antes e depois do plano. Aba ativa e janela continuam como na
  STUDIO-010. Agora também `sameAuthorization`: mesma origem, Chrome ainda permite e, fora do laboratório, consentimento
  ainda presente.
- **Revogação (`onAccessChanged`):** revogar o consentimento ou a permissão do Chrome derruba a armação. A falha publicada
  é `PDD_CONSENT_REVOKED` ou `PERMISSION_REVOKED`. Uma captura em curso termina sem nada publicado.
- **Navegação durante a captura:** login SSO ou redirecionamento resultam em `ORIGIN_CHANGED` ou `PAGE_CHANGED`. A
  sequência A→B→A, nova aba e cancelamento continuam invalidando a captura.

## D-S013-04 — `nekkora-visual/3`: representação inteiramente sintética (gate de privacidade)

**Problema.** A v2 copia os pixels reais dos portadores de rótulo. A verificação de pixels fora deles não prova nada
sobre o que está dentro. Numa aplicação real, um rótulo aprovado pelo texto pode exibir outra coisa nos pixels:

- uma fonte web que troca glifos;
- um elemento sobreposto com `pointer-events:none`, que `elementFromPoint` ignora;
- sombra ou transformação de vizinhos.

Com dados de pacientes, esse risco não é aceitável.

**Decisão.** Foi o caminho que a missão prefere: modo inteiramente sintético.

- **Sem captura de tela:** na v3 a extensão **não chama `captureVisibleTab`** e **não envia imagem**.
- **O que a extensão envia:** uma descrição fechada.
  - Geometria em px CSS inteiros e tipos.
  - Classes de tom e de tinta, nunca a cor real.
  - Barras `TEXT` com largura em múltiplos de 16 px. O comprimento exato do texto não sai.
  - Em `LABEL`, só o texto INTEIRO aprovado por `interfaceText` (vocabulário controlado da `nekkora-privacy/2`).
- **O que o Studio faz:** desenha a imagem com a fonte Inter e uma paleta fechada.
- **Quem revalida o texto:** o service worker (`validateV3`), o Native Host (`PddVisualPrivacy.checkV3`) e o Studio
  (`SyntheticRenderer.normalize` e `VisualVerifier.verifyV3`, com `CapturePrivacy.interfaceText`). Os três podem
  revalidar o texto, coisa impossível com pixels.
- **Verificação da imagem no Studio (4ª barreira, de novo antes de cada exportação):**
  - fora dos rótulos, repintura exata;
  - dentro deles, cada pixel tem de ser uma mistura entre o fundo esperado e a tinta (texto em tons de cinza);
  - os rótulos têm de ser disjuntos;
  - a geometria tem de estar em múltiplos da escala;
  - o sha-256 é conferido.
- **Versões:** a v2 continua existindo e válida só no laboratório. Nada foi reclassificado.

**Alternativa recusada.** Endurecer a v2 com mais heurística de cobertura. Não há como provar de ponta a ponta que os
pixels de um rótulo mostram só o texto aprovado.

**Arquivada, nunca publicada.** A variante local "print real com campos mascarados", decidida no PDD-01 em 19/10/2026,
contraria este gate: copiaria nomes, números e tabelas das telas reais. Ela foi arquivada em uma branch só local
(`archive/pdd01-v3-print-real-local`), sem push.

## D-S013-05 — o que a v3 mostra e o que não mostra

| Tipo | Na imagem |
|---|---|
| `SURFACE` | Contêiner com fundo ou borda: tom LIGHT/MID/DARK, nunca a cor. Uma tabela é uma superfície só: nada de dentro dela é descrito (`TABLE_CONTENT_HIDDEN`), como na v2 |
| `MEDIA` | `img`, `svg`, `canvas`, `video` e fundo `url()` médio: só o lugar |
| `FRAME` | `iframe`, `frame`, `object` e `embed`: só o lugar. O conteúdo nunca é lido |
| `CONTROL` | Formato do botão |
| `FIELD` | Moldura vazia. Valor, placeholder, autofill e estado de seleção nunca entram. Inclui contenteditable e os papéis textbox/searchbox/combobox/spinbutton |
| `TEXT` | Barra neutra no lugar de texto que não é rótulo de interface |
| `LABEL` | Texto de interface do vocabulário (`isCarrier` da v1/v2 e `input` de botão) |

Shadow roots abertas e fechadas são percorridas com as mesmas regras (`chrome.dom.openOrClosedShadowRoot`).
Pseudo-elementos não aparecem.

## D-S013-06 — compatibilidade e versões mínimas

| Combinação | Resultado |
|---|---|
| Extensão 0.6.0 + Studio novo | v3 em tudo (laboratório e site). Rascunho 1.2.0 só quando há imagem v3 |
| Extensão 0.6.0 + Studio antigo (pede v1/v2) | Funciona no laboratório. Em site é recusado com `POLICY_REQUIRES_V3`; abas `AUTHORIZED_SITE` são ignoradas pelo Studio antigo (fail-closed) |
| Extensão 0.5.x + Studio novo | v2 só no laboratório. Sites aparecem como "captura indisponível" com aviso para atualizar. Nenhum comando novo é enviado |
| Rascunhos 1.0.0 e 1.1.0 | Continuam legíveis e exportáveis. Um 1.1.0 que contenha v3 é recusado |

Versões mínimas para captura em site: **extensão 0.6.0 + Native Host desta branch + Studio desta branch**.

## D-S013-07 — trilha de decisão

O Studio manda `actor` = `StudioEnvironment.decisionActor()`. `TEST_AGENT` só existe em modo de laboratório explícito
(propriedade ou variável de ambiente); sem isso, vale `DEVELOPER`. O painel da extensão sempre registra `DEVELOPER`.
O registro é mostrado ao usuário: "decidido por desenvolvedor/agente de teste, no Studio/na extensão".

## D-S013-08 — `nekkora-visual/4`: print real com o texto de dados mascarado (decisão da usuária, 19/10/2026)

**Contexto.** A usuária validou a v3 e aprovou o resultado, mas pediu uma imagem fiel, "quase um print": quem valida o PDD
reconhece a própria tela. As opções apresentadas foram:

- print real com o texto de dados mascarado (recomendada);
- print real sem máscara, como opção por site;
- print real sempre.

A escolha foi a primeira.

**O que a v4 é.** A imagem é a tela capturada (`captureVisibleTab`, com o mesmo gesto e as mesmas conferências de aba,
origem e autorização da v1/v2). Por cima, máscaras lisas de cor fechada:

| Máscara | O que cobre |
|---|---|
| `BLOCK` | Tabela inteira; iframe, frame, object e embed; canvas; vídeo; imagem com mais de 240 px num lado ou quebrada (o texto alternativo apareceria); SVG com `<text>`/`<foreignObject>`; fundo `url()` com mais de 64 px; elemento com pseudo-elemento de texto; botão `input` cujo `value` não é do vocabulário |
| `FIELD` | Todo campo, com moldura vazia: valor, placeholder e autofill nunca aparecem |
| `TEXT` | Cada linha de texto que não é rótulo de interface, com 2 px de folga, numa faixa da cor da classe do fundo |

**O que fica real:** fundos, cores, bordas, logotipo e ícones pequenos, botões e rótulos de interface cujo texto inteiro é
do vocabulário controlado. As shadow roots abertas e fechadas são percorridas.

**Na dúvida, cobre.** Um rótulo encoberto ou cortado vira texto mascarado. Se o plano não couber nos tetos (6.000+
elementos, 600 linhas, 128 campos, 96 blocos), a captura **falha** com `TOO_MANY_REGIONS`, antes de qualquer
`captureVisibleTab`. Nunca sai um print parcial.

**Verificação.**
- **Extensão, Native Host e Studio:** repintam as máscaras e exigem cada pixel delas exato.
- **Composição:** é zerada depois de codificar.
- **Bridge:** exceção única ao teto de controle, só para o evento `studio.pdd.captured` com `nekkora-visual/4` (até
  12 MiB). No Studio, ela vale só depois do handshake autenticado; qualquer outro quadro grande derruba a conexão.

**Limite declarado (é a troca aceita pela usuária).**
- **Pixels reais fora das máscaras:** a proteção depende de o plano mapear toda a página. A prova é empírica, com a
  cor-sentinela: todo dado da página adversarial tem a cor #E6007E, e nenhum pixel dela chega ao print, embora a tela
  bruta (só em memória) tenha centenas.
- **Riscos residuais:**
  - texto desenhado sem nó de texto e fora dos casos cobertos (ex.: fonte que troca glifos num rótulo do vocabulário,
    imagem pequena com texto);
  - tooltips nativos (não entram no print);
  - conteúdo que mude entre o plano e a captura (a ficha de layout recusa).
- **Compartilhar:** o PDF pede revisão das imagens antes de compartilhar.

**Política pedida pelo Studio.** O Studio pede a v4 à extensão 0.6.0, no laboratório e em site autorizado. A v3 continua
no contrato (`POLICIES_BY_PROFILE`: o site autorizado aceita v3 e v4) e a v2 continua fora dos sites.
