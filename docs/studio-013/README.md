# NEKKORA-STUDIO-013 — captura visual do PDD em sites autorizados (extensão + Native Host)

Decisões: [`decisions.md`](decisions.md). Resultados das execuções: [`RESULTADOS.md`](RESULTADOS.md). Lado do Studio:
`Nekkora-Studio/docs/studio-013/README.md`.

## O que mudou

| Antes (E2E-001 / PDD-01) | Agora (0.6.0) |
|---|---|
| A captura fora do laboratório só valia para origens compiladas em `BENCHMARK_ORIGINS` (`https://rpachallenge.com`) | Qualquer origem http/https com **acesso do Chrome** e **consentimento PDD próprio**, concedido pela interface, por origem exata, sem editar código |
| O consentimento era implícito (estar na lista) | O consentimento é explícito: sessão (padrão) ou "lembrar este site". Revogável pelo Studio ou pelo painel, e apagado quando o Chrome deixa de permitir o site |
| Fora do laboratório valia a `nekkora-visual/2`, que copia pixels reais dos rótulos | Fora do laboratório valem a `nekkora-visual/3` (descrição sintética, sem `captureVisibleTab`), a **`/4`** (print real com o texto de dados mascarado, o que o Studio pede; D-S013-08) e a **`/5`** (print integral, só num site com o nível `FULL`; D-S013-09). A v2 continua fora |
| Revogar a permissão do Chrome não derrubava uma captura armada | Revogar a permissão do Chrome ou o consentimento derruba a armação e a captura em curso |

## Arquivos

| Arquivo | Papel |
|---|---|
| `chrome-extension/src/background/pdd-consent.js` | Consentimento `nekkora-pdd-consent/1`: forma canônica da origem, sessão em `storage.session`, sites lembrados no IndexedDB da extensão, revogação, limpeza pela permissão do Chrome |
| `chrome-extension/src/background/visual-capture-profile.js` | Perfil `/2`: `LAB` e `AUTHORIZED_SITE`, políticas por perfil e `denialReason` |
| `chrome-extension/src/background/studio-pdd.js` | Comandos `studio.pdd.consent.*`. A armação guarda origem, perfil e política. `sameAuthorization` confere em cada etapa. `onAccessChanged` trata a revogação. `describeV3` monta a descrição sem screenshot |
| `chrome-extension/src/background/visual-sanitizer.js` | `validateV3`: 1ª barreira da v3 (tipos, campos por tipo, ordem, tetos, geometria inteira, barras quantizadas, rótulo pelo vocabulário) |
| `chrome-extension/src/content/pdd-plan.js` | Plano v3 no content script: shadow roots abertas e fechadas, campos sempre vazios, barras no lugar de texto, rótulos só do vocabulário, nada de dentro de tabela |
| `chrome-extension/src/background/service-worker.js`, `src/ui/panel.*` | Seção "Captura do PDD" do painel (autorizar, lembrar, revogar, lista de sites) e ligação das revogações do Chrome |
| `java-core/native-host/.../PddVisualPrivacy.java` | `checkV3`: 3ª barreira, com a política Java `CapturePrivacyPolicy.interfaceText`. `checkV4`/`checkV5`: repintura das máscaras (a v5 só aceita `FIELD` e `BLOCK`) |
| `chrome-extension/src/background/native-client.js`, `java-core/native-host/.../BridgeFrames.java` | Exceção única ao teto de controle de 256 KiB: só o evento `studio.pdd.captured` v4 ou v5, até 12 MiB |
| `java-core/native-host/.../BridgeContract.java`, `contracts/studio-bridge.schema.json` | Comandos `studio.pdd.consent.status/grant/revoke/list`, campos `origin`/`pddConsent` das abas, `origin` na armação e payload v3. Fixtures `s013-*` |
| `manifest.json` | 0.6.0, descrições atualizadas. **Nenhuma permissão nova**: `host_permissions` continua só com o laboratório |

## Revisões da usuária: print mascarado (v4) e print integral (v5)

- **`nekkora-visual/4`** (D-S013-08): a imagem é a tela capturada. Campos, linhas de texto de dados, tabelas, frames,
  canvas, vídeo, imagens grandes, fundos com imagem e pseudo-elementos com texto saem cobertos por máscaras lisas;
  rótulos do vocabulário, logotipo e layout ficam reais. É o que o Studio pede por padrão.
- **`nekkora-visual/5`** (D-S013-09): print integral. A tela sai como está, com os dados. Só os campos de senha saem
  cobertos, sempre, inclusive em shadow roots, no "mostrar senha" e, se algum frame mostrar senha ou não responder, nos
  frames inteiros. Exige o nível `FULL` no consentimento (`nekkora-pdd-consent/2`), escolhido de forma explícita e
  confirmada pela interface confiável. Rebaixar o nível derruba uma v5 armada ou em curso.
- **Depois do teste da usuária** (D-S013-10): o print integral passou a ser o **padrão** ao autorizar um site, sem
  segundo clique (um aviso fixo de uma linha fica na escolha); o mascarado continua como opção, senhas sempre cobertas.
- **Painel da extensão:** a seção "Captura do PDD" tem a escolha "Print integral (padrão)" ou "Print mascarado", com o
  aviso de uma linha, e o botão para trocar o nível de um site autorizado.
- **Verificação:** v4 e v5 têm a mesma pintura e a mesma verificação pixel a pixel das máscaras na extensão, no Native
  Host e no Studio. Plano incompleto falha (`TOO_MANY_REGIONS`) em vez de sair um print parcial.

A seção a seguir descreve a v3, que continua no contrato.

## Captura sanitizada para PDD × screenshot real (v3)

A imagem do PDD na v3 **não é uma captura de tela**. É um desenho do Studio a partir de uma descrição que leva só:

- geometria e tipos;
- classe de tom e de tinta (nunca a cor real);
- textos de interface que o vocabulário controlado aprova.

Todo o resto da página fica de fora da descrição: textos, valores, placeholders, tabelas, imagens, canvas, vídeo e o
conteúdo de frames. Os campos aparecem vazios e os textos que não são rótulos viram barras neutras. A paleta e a fonte
são do Studio.

Isso **não é anonimização absoluta**. A descrição revela:

- a estrutura da tela (onde há superfícies, campos, botões e mídia);
- o comprimento aproximado de cada linha de texto (barras em múltiplos de 16 px CSS);
- a quantidade de linhas de texto.

Os testes adversariais com dados sintéticos provam o escopo ensaiado, não a ausência universal de vazamento.

## Riscos residuais e limites

- **Texto do vocabulário que vira dado:** um rótulo de interface cujo texto INTEIRO é do vocabulário (ex.: um link
  "Ativo") aparece se estiver num portador (`button`, `label`, `legend`, `summary`, `a[href]`, papéis de controle). O
  conteúdo de tabela nunca vira rótulo. Itens de menu e links vocabulares fora de tabela aparecem. Esse é o mesmo critério
  da `nekkora-privacy/2` para os metadados das etapas.
- **Geometria:** a posição e o tamanho das regiões são reais (arredondados para px CSS inteiros).
- **Abrangência do plano:** até 6.000 elementos e 4.000 nós de texto. Acima disso, `PAGE_TOO_LARGE`/`TEXT_SIMPLIFIED`,
  com menos regiões e nunca mais conteúdo.
- **Pseudo-elementos e glifos de fonte de ícones:** não aparecem.
- **Shadow roots fechadas:** são percorridas no mundo isolado da extensão (`chrome.dom.openOrClosedShadowRoot`).
- **Esquema https em teste:** a distinção http/https foi provada nos testes unitários. O Chrome de homologação só tem
  servidores http locais.
- **Permissão ampla do Chrome no navegador real:** conceder `https://*/*` exige o diálogo nativo, que a homologação não
  automatiza. A regra foi provada com a política de acesso real em Node (`studio-pdd-013-access.test.js`).
- **Validação em ambiente de cliente real:** fica separada. Esta missão não a executou e não usou dados de clientes.

## Versões mínimas

- **Captura em site:** extensão **0.6.0**, Native Host desta branch e Studio desta branch.
- **Studio anterior com a extensão 0.6.0:** o laboratório continua funcionando. Em site é recusado com
  `POLICY_REQUIRES_V3`.
- **Extensão 0.5.x com o Studio novo:** a captura fica restrita ao laboratório (v2) e os sites aparecem como
  indisponíveis.
