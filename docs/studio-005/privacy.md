# STUDIO-005 — Privacidade das evidências

Os campos novos (texto de mensagem, região, campo, título, URL de navegação) **não** foram considerados protegidos pela
proteção existente dos parâmetros. Cada um tem a sua regra, aplicada em **quatro barreiras**.

## A política de mensagem (`NekkoraFeedback.messagePolicy`)

O texto é normalizado (espaços) e:

| Resultado | Quando |
|---|---|
| `NONE` | vazio |
| `SUPPRESSED` | mais de 160 caracteres · `maskText` alteraria algo · `looksSecret` · sequência de 4 ou mais dígitos (conta, documento, telefone, código) · `@`, `://` ou `www.` · token opaco longo (letras e dígitos, 20 ou mais) · **forma de identificador ou nome** (palavra com letras e dígitos, maiúscula interna, ou nome próprio fora do início da frase) · contém um valor presente **agora** nos campos da página, inclusive em shadow roots · bate com a **impressão digital** de um valor digitado antes, mesmo que o campo já tenha sido limpo ou removido |
| `AVAILABLE` | o resto |

**Em caso de dúvida, omite.** Uma regex não identifica todo dado pessoal. Por isso o texto liberado é curto e passa
por quatro barreiras, e a suspeita de valor digitado é tratada por **comparação**, não por padrão.

### Valores dos campos e impressões digitais (revisão do PR #6)

Para impedir que "Usuário anateste não encontrado" ou "O código Kq7Segredo expirou" saiam, o content script usa duas
comparações, **só na memória do content script** e **só para suprimir**:

1. **Valores atuais** dos campos de texto, áreas de texto e editáveis de **todas** as raízes observáveis: o documento,
   os iframes de mesma origem e os shadow roots abertos. São até 80 valores, com até 200 caracteres cada, inclusive
   `password`.
2. **Impressões digitais** dos valores digitados. São hashes de 32 bits com **sal aleatório por documento**, do valor
   inteiro e das palavras de 4 ou mais caracteres. Elas são tiradas **antes** de a página poder limpar ou remover o
   campo: em cada interação entregue (o clique é entregue na captura, antes dos manipuladores da página) e quando um
   campo perde o foco (`blur` na captura, antes do `blur` da própria página). O valor **nunca** é guardado; só o hash
   fica, e só na memória do documento, sem envio nem registro. Há no máximo 300 impressões (as mais antigas saem), e
   elas somem com o documento. Uma colisão só faz omitir mais (fail-closed).

Isso fecha os caminhos encontrados na revisão: campo **limpo** antes da mensagem, campo **removido** da página e campo
**em Shadow DOM**. É uma exceção controlada ao "valores digitados nunca são lidos" da STUDIO-003.

### Dado pessoal de terceiro (nunca digitado)

Nenhuma comparação com campos encontra um dado que ninguém digitou. A regra **fail-closed pela forma** omite o texto
com cara de identificador ou de nome próprio: "Cliente Maria Souza já possui cadastro." sai como `SUPPRESSED`. Ela é
aplicada igualmente no content script, no service worker, no host e no Studio. **Limite declarado**: um dado pessoal
escrito só em minúsculas e nunca digitado nesta página (ex.: "o cliente joão já existe") não é reconhecido por forma.
Se a política precisar de garantia total, a alternativa é nunca enviar o texto (decisão de produto pendente).

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
| `O código Kq7Segredo expirou.` com o campo **limpo** antes (revisão) | `SUPPRESSED` (antes da correção: `AVAILABLE`, reproduzido no Chrome) |
| `Até logo, zorbalino.` com o campo **removido** antes (revisão) | `SUPPRESSED` |
| `Palavra pirilampo recusada.` com o campo em **Shadow DOM** (revisão) | `SUPPRESSED` |
| `Cliente Maria Souza já possui cadastro.` (terceiro, nunca digitado) | `SUPPRESSED` |

Nenhum dos valores aparece nos eventos ao Studio, no `storage.session` da extensão nem nos arquivos do host
(`Studio005EvidenceIT.assertNoSecret…`).

## Achado de segurança (fora do canal de evidências) — prioridade alta

Quando a **própria página** escreve um valor digitado no seu texto visível, esse texto aparece na **captura técnica
existente** (CHROME-002/STUDIO-003): deltas de mutação no log local de diagnóstico do service worker (`events.since`)
e `contextTexts` do registro de alvos do motor (`targets/registry.json`). Não atravessa o Bridge e não é evidência.
O teste separa os dois canais: o canal de evidências é checado sempre, e o log local/registro só antes de a página
repetir o valor.

**Enquanto essa correção não for feita na origem da captura técnica, o Recorder NÃO deve ser tratado como seguro para
gravações com dados reais de clientes.** A correção é uma missão de segurança própria, com prioridade alta e anterior a
qualquer uso com dados reais, conforme a revisão do PR #6.
