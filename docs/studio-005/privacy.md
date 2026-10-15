# STUDIO-005 — Privacidade das evidências

Os campos novos (texto de mensagem, região, campo, título, URL de navegação) **não** foram considerados protegidos pela
proteção existente dos parâmetros. Cada um tem a sua regra, aplicada em **quatro barreiras**.

## A política de mensagem (`NekkoraFeedback.messagePolicy`)

O texto é normalizado (espaços) e:

| Resultado | Quando |
|---|---|
| `NONE` | vazio |
| `SUPPRESSED` | mais de 160 caracteres · `maskText` alteraria algo · `looksSecret` · sequência de 4 ou mais dígitos (conta, documento, telefone, código) · `@`, `://` ou `www.` · token opaco longo (letras e dígitos, 20 ou mais) · contém um valor presente nos campos da página (3 ou mais caracteres, sem diferenciar maiúsculas) |
| `AVAILABLE` | o resto |

**Em caso de dúvida, omite.** Uma regex não identifica todo dado pessoal. Por isso o texto liberado é curto e passa
por quatro barreiras, e a suspeita de valor digitado é tratada por **comparação**, não por padrão.

### Valores dos campos

Para impedir que "Usuário anateste não encontrado" ou "O código Kq7Segredo expirou" saiam, o content script lê, **na
memória do content script**, os valores atuais dos campos de texto do documento (até 50, até 200 caracteres cada,
inclusive `password`). Ele só os compara com a mensagem. Esses valores **nunca** são guardados, enviados, registrados
ou comparados fora do content script. É uma exceção controlada ao "valores digitados nunca são lidos" da STUDIO-003,
usada exclusivamente para **suprimir**.

## As quatro barreiras

| # | Onde | O quê |
|---|---|---|
| 1 | content script | política de mensagem com os valores da página; região, campo e título mascarados (`maskText`, `looksSecret` → omitido); URL via `sanitizeUrl` |
| 2 | service worker (`studio-evidence.js`) | revalida todo o payload: tipos, fontes, sinais e limitações fechados (o desconhecido é descartado); mensagem de novo pela política; região, campo e título mascarados; URLs sanitizadas; campos não previstos (HTML, `value`, `pageMap`) nunca são copiados |
| 3 | Native Host (`EvidencePrivacy.reapply`) | política Java (`Privacy.maskText`, `looksSecret`, dígitos ≥ 4, ≤ 160) e URLs sanitizadas; depois, validação estrita do schema |
| 4 | Studio (`RecordedEvidence` + domínio) | `DisplaySafety` (e-mail, CPF, CNPJ, cartão, JWT, Bearer, identificadores internos) e dígitos ≥ 4 no trecho; rótulos inseguros viram `null`; o construtor do domínio recusa texto fora da política |

## Nunca capturado

Valores digitados, senhas, OTP, cookies, cabeçalhos de autenticação, tokens, conteúdo de campos protegidos,
respostas de APIs, documentos completos, HTML bruto de mensagens e histórico do navegador.

O PageMap não trafega por evento: o contrato não tem campo para ele, e as fixtures inválidas provam a recusa.

## Homologação (Chrome real)

| Mensagem da página (fictícia) | Saiu como |
|---|---|
| `CPF 123.456.789-09 já cadastrado.` | `SUPPRESSED` |
| `Enviamos o código para ana.souza@exemplo.com.` | `SUPPRESSED` |
| `Sessão eyJhbGciOi… expirada.` | `SUPPRESSED` |
| `Olá, Joana Prado!` (repete o campo) | `SUPPRESSED` |
| `O código Kq7-Segredo expirou.` (repete o campo senha) | `SUPPRESSED` |
| texto longo dos termos (mais de 160) | `SUPPRESSED` |
| `Senha <b>incorreta</b>.` | `AVAILABLE`: `Senha incorreta.` (sem HTML) |

Nenhum dos valores aparece nos eventos ao Studio, no `storage.session` da extensão nem nos arquivos do host
(`Studio005EvidenceIT.assertNoSecret…`).

## Achado (fora do canal de evidências)

Quando a **própria página** escreve um valor digitado no seu texto visível, esse texto aparece na **captura técnica
existente** (CHROME-002/STUDIO-003): deltas de mutação no log local de diagnóstico do service worker (`events.since`)
e `contextTexts` do registro de alvos do motor (`targets/registry.json`). Não atravessa o Bridge e não é evidência.
O teste separa os dois canais: o canal de evidências é checado sempre, e o log local/registro só antes de a página
repetir o valor. Está registrado em `known-limitations.md` como pendência para uma missão de privacidade da captura técnica.
