# STUDIO-005 — Privacidade das evidências

Os campos novos (mensagem, região, campo, título, URL de navegação) **não** foram considerados protegidos pela proteção
existente dos parâmetros. Cada um tem a sua regra, aplicada em **quatro barreiras**.

## Modo estrito do texto das mensagens (padrão do Nekkora Recorder)

Esta é a decisão da revisão final do PR #6. A evidência separa **o que aconteceu** do **texto que a página
apresentou**. O texto livre de uma mensagem da página **nunca atravessa o Bridge**:

| Situação | `message` enviada |
|---|---|
| a região mostrou texto (qualquer texto) | `{availability: "SUPPRESSED", text: null}` + limitação `MESSAGE_SUPPRESSED` |
| a região não tem texto | `{availability: "NONE", text: null}` |

O que continua sendo evidência: o tipo (erro, alerta, status, validação, carregamento…), a classificação, os sinais
(`role=alert`, `aria-invalid`, `invalid` nativo…), a **descrição controlada** da região e do tipo de campo, o
documento (principal/iframe/Shadow DOM), o horário real, a confiança e a correlação com a etapa. O Studio mostra, por
exemplo: *depois de "Entrar", uma mensagem com indicador de erro foi observada (texto omitido por privacidade);
resultado de negócio: não validado*.

**Por que estrito:** nenhuma heurística (regex, formato, comparação com campos) reconhece todo dado pessoal. Uma
frase como `o cliente joão já existe` parece inofensiva para um algoritmo e contém dado pessoal. Com o modo estrito,
a segurança não depende de reconhecer o dado.

**Futuro (não autorizado nesta missão):** um catálogo controlado de mensagens seguras, por correspondência exata e com
um código (ex.: `AUTHENTICATION_INVALID_CREDENTIALS`), traduzido pelo Studio. Ele exigiria uma evolução de contrato
própria.

**Contrato:** o schema `evidence/1` continua aceitando `AVAILABLE` + trecho de até 160 caracteres, porque o contrato não
foi alterado. Nenhuma das quatro barreiras produz ou deixa passar esse caso.

## Modo estrito dos metadados (revisão 3 do PR #6)

A revisão encontrou um segundo caminho para o texto da página: `details.region` era montado com `aria-label` /
`aria-labelledby` da região (e `field` com o nome acessível do campo; `document.title` com o título). Uma região
`aria-label="o cliente joão já existe"` saía como `region: "mensagem de erro o cliente joão já existe"`, reproduzido no
Chrome real (`Studio005EvidenceIT.s005MetadataStrict`, falhando antes da correção).

Agora os metadados textuais são **vocabulário fechado**, igual nas quatro barreiras:

| Campo | Valores possíveis |
|---|---|
| `details.region` | `mensagem de erro`, `mensagem de status`, `alerta`, `região de validação`, `indicador de carregamento`, `região de conteúdo`, `elemento acionado`, `formulário` — ou `null` |
| `details.field` | o **tipo** do controle: `campo de texto`, `campo de e-mail`, `campo de senha`, `campo de telefone`, `campo numérico`, `campo de pesquisa`, `campo de endereço`, `campo de data e hora`, `caixa de seleção`, `opção de escolha`, `lista de seleção`, `área de texto`, `campo editável`, `campo` — ou `null` |
| `document.title` | sempre `null` (o documento é identificado por frame, Shadow DOM e URL sanitizada) |

Qualquer valor fora do vocabulário é descartado (`null`) no service worker, no Native Host e no Studio, mesmo que a
origem seja adulterada. O observador não lê mais `aria-label`, `aria-labelledby`, rótulos, `placeholder`, nome de
formulário nem o título.

Resultado na página `test-lab/pages/studio005/metadados.html`: região com `aria-label` e com `aria-labelledby` contendo
`o cliente joão já existe` → `region: "mensagem de erro"`, `message: SUPPRESSED/null`, limitação `MESSAGE_SUPPRESSED`;
campo com rótulo `telefone do cliente joão` → `field: "campo de telefone"`; título trocado para o texto → `title: null`.
O texto não aparece em nenhum ponto dos eventos de evidência, do `storage.session` da gravação, do Native Host
(`EvidencePrivacyTest.revisaoFinalMetadadosComTextoDaPaginaNaoAtravessamOHost`) nem do Studio
(`RecorderEvidenceTest.revFinalMetadadosComTextoDaPaginaNaoEntramNoStudio`, `EvidenceDomainTest`).

**Fora do canal de evidências (registrado para a CHROME-SECURITY-001):**

- **Canal de ETAPAS (STUDIO-003):** o mesmo teste mostrou que o evento de etapa leva o nome acessível do elemento
  (`element.name`), aqui `"telefone do cliente joão"` — guardado em `reports/evidence/S005-metadata-strict.json`
  (`stepChannelElementNames`). Esse canal não foi alterado nesta revisão (fora do escopo da STUDIO-005) e entra na
  avaliação de todos os canais da CHROME-SECURITY-001.
- **URLs sanitizadas** removem consulta e fragmento sensíveis, mas o **caminho** da URL pode conter texto (ex.:
  `/clientes/joao-silva`). Também fica para a CHROME-SECURITY-001.

## As quatro barreiras

| # | Onde | O quê |
|---|---|---|
| 1 | content script (`NekkoraFeedback.messagePolicy`, origem) | qualquer texto → `SUPPRESSED`; o texto lido (`textContent`) serve só ao hash local que detecta a mudança e nunca sai; **nenhum valor de campo é lido**; região e campo só do vocabulário controlado (nada de `aria-label`, rótulos ou nome acessível); título `null`; URL via `sanitizeUrl` |
| 2 | service worker (`studio-evidence.js`) | revalida todo o payload: tipos, fontes, sinais e limitações fechados (o desconhecido é descartado); qualquer texto de mensagem recebido é removido; região e campo fora do vocabulário → `null`; título → `null`; URLs sanitizadas; campos não previstos (HTML, `value`, `pageMap`) nunca são copiados |
| 3 | Native Host (`EvidencePrivacy.reapply`) | qualquer texto de mensagem é removido (`SUPPRESSED` + `MESSAGE_SUPPRESSED`); região e campo só do vocabulário (`REGION_LABELS`, `FIELD_LABELS`); título → `null`; URLs sanitizadas; depois, validação estrita do schema |
| 4 | Studio (`RecordedEvidence` → `Message.of`, `ObservedEvidence`) | qualquer texto vira `SUPPRESSED`; o construtor público de `Message` recusa `AVAILABLE`; região e campo fora do vocabulário (`EvidenceVocabulary`) viram `null`; título sempre `null` |

## Nunca capturado

Valores digitados, senhas, OTP, cookies, cabeçalhos de autenticação, tokens, conteúdo de campos protegidos, respostas
de APIs, documentos completos, texto e HTML das mensagens e histórico do navegador.

O PageMap não trafega por evento: o contrato não tem campo para ele, e as fixtures inválidas provam a recusa.

## Histórico: os P1 de privacidade da revisão do PR #6

Antes do modo estrito, a política liberava textos "seguros". A revisão encontrou, e um teste reproduziu no Chrome
real, que um valor digitado podia sair quando a página **limpava** o campo antes da mensagem
(`"O código Kq7Segredo expirou."` atravessou com o texto disponível). Também havia os caminhos do campo **removido**, do
campo **em Shadow DOM** e do dado de **terceiro**. A primeira correção (impressões digitais e regra de forma) fechou os
casos reproduzidos, mas não garante todo dado pessoal. O modo estrito encerra a questão no canal de evidências, e os
testes de reprodução foram **mantidos** como regressão:

| Mensagem da página (fictícia) | Sai como |
|---|---|
| `Usuário ou senha inválidos.` | `SUPPRESSED` |
| `Senha <b>incorreta</b>.` | `SUPPRESSED` |
| `CPF 123.456.789-09 já cadastrado.` · `Enviamos o código para ana.souza@exemplo.com.` · `Sessão eyJhbGciOi… expirada.` | `SUPPRESSED` |
| `O código Kq7Segredo expirou.` com o campo **limpo** antes | `SUPPRESSED` |
| `Até logo, zorbalino.` com o campo **removido** antes | `SUPPRESSED` |
| `Palavra pirilampo recusada.` com o campo em **Shadow DOM** | `SUPPRESSED` |
| `Cliente Maria Souza já possui cadastro.` (terceiro) | `SUPPRESSED` |
| `o cliente joão já existe` (terceiro, só minúsculas, nunca digitado) | `SUPPRESSED` |

Nenhum desses textos aparece nos eventos ao Studio, no `storage.session` da extensão, nos arquivos do host ou no Studio
(`Studio005EvidenceIT`, `EvidencePrivacyTest`, `RecorderEvidenceTest`, `EvidenceDomainTest`, homologação real).

## Achado de segurança (fora do canal de evidências) — prioridade alta

Quando a **própria página** escreve um valor digitado no seu texto visível, esse texto aparece na **captura técnica
existente** (CHROME-002/STUDIO-003): deltas de mutação no log local de diagnóstico do service worker (`events.since`)
e `contextTexts` do registro de alvos do motor (`targets/registry.json`). Não atravessa o Bridge e não é evidência.
O modo estrito **não** resolve esse caminho.

**Enquanto a correção não for feita na origem da captura técnica (missão CHROME-SECURITY-001), o Recorder NÃO deve ser
tratado como seguro para gravações com dados reais de clientes.** A correção deve avaliar todos os canais da extensão,
não só as mensagens.
