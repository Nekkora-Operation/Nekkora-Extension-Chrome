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
(`role=alert`, `aria-invalid`, `invalid` nativo…), a região segura, o campo validado (nome acessível mascarado), o
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

## As quatro barreiras

| # | Onde | O quê |
|---|---|---|
| 1 | content script (`NekkoraFeedback.messagePolicy`, origem) | qualquer texto → `SUPPRESSED`; o texto lido (`textContent`) serve só ao hash local que detecta a mudança e nunca sai; **nenhum valor de campo é lido**; região, campo e título mascarados (`maskText`; `looksSecret` → omitido); URL via `sanitizeUrl` |
| 2 | service worker (`studio-evidence.js`) | revalida todo o payload: tipos, fontes, sinais e limitações fechados (o desconhecido é descartado); qualquer texto de mensagem recebido é removido; região, campo e título mascarados; URLs sanitizadas; campos não previstos (HTML, `value`, `pageMap`) nunca são copiados |
| 3 | Native Host (`EvidencePrivacy.reapply`) | qualquer texto de mensagem é removido (`SUPPRESSED` + `MESSAGE_SUPPRESSED`); título, região e campo pela política Java; URLs sanitizadas; depois, validação estrita do schema |
| 4 | Studio (`RecordedEvidence` → `Message.of`) | qualquer texto vira `SUPPRESSED`; rótulos inseguros viram `null` (`DisplaySafety`) |

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
