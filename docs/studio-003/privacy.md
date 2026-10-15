# STUDIO-003 — Privacidade das etapas gravadas

Regra central: o Recorder registra **que** houve um preenchimento, nunca **o que** foi digitado. Não existe, no
contrato, campo para valor digitado (`parameters.valueCaptured` é a constante `false`).

## Três barreiras (D-STUDIO-003-09)

| Barreira | Onde | O que faz |
|---|---|---|
| 1 | content script (`interaction-recorder.js`) | nunca lê `.value` de campos de texto nem o conteúdo de editores `contenteditable`. A opção de um select passa pela política (`maskText` e `sanitizeAttribute`); em campo sensível, a opção não sai. O descritor observado leva só tag, tipo, papel, nome mascarado e `id`/`name` não sensíveis |
| 2 | service worker (`studio-steps.js`) | remonta o evento só com campos do contrato, reaplica `maskText` a nomes, títulos, rótulos e opções, sanitiza URLs (`sanitizeUrl`), descarta `id`/`name` com aparência de segredo ou dado pessoal e aplica limites de tamanho e enums fechados |
| 3 | Native Host (`StepPrivacy`, `BridgeContract`) | reaplica a política **Java** a textos e URLs da etapa e valida contra o schema estrito. Etapa fora do contrato não sai; o log registra só "fora do contrato", sem o conteúdo |

## Correção feita nesta missão: conteúdo de `contenteditable`

O teste no Chrome real (`Studio003InteractionsIT.s003Consulta`) encontrou o texto digitado num editor
`contenteditable` no delta de mutação da captura técnica (`textAfter`) e no log de eventos do service worker. Isso
é anterior a esta missão, mas o Recorder o expõe.

Correção em `capture.js`: o conteúdo de um editor `contenteditable` passa a ser **valor de campo**, como o de um
`input`, conforme a política já declarada em `sensitivity.js` ("valores de campos não saem da página"). O hospedeiro
informa só `hasValue` e `valueLength`. O nome acessível do editor continua vindo do rótulo (T030 continua aprovado).

## Senhas, tokens, documentos

- Campo sensível (`type=password`, `autocomplete` de senha, cartão ou código único, nomes como senha, token, cpf,
  cartão etc.): `sensitive: true`, sem `htmlId` e sem `htmlName` no Studio. Um valor literal é recusado pelo domínio
  (`LITERAL_ON_SENSITIVE_TARGET`).
- URLs: parâmetros sensíveis e segredos são redigidos (`token=REDACTED`, segmentos `:redacted`).
- Textos: e-mail, CPF, CNPJ, cartão (Luhn), telefone, JWT e Bearer são mascarados.

## O que nunca sai

Valores de campos, códigos OTP, tokens, cookies, credenciais, arquivos selecionados (o input `file` nem é
registrado) e histórico não relacionado à gravação. PageMaps e snapshots também não saem: o evento é compacto, com
cerca de 2,4 KB.

## Verificação

- Chrome real: `assertNoSecretAnywhere` em `Studio003InteractionsIT` procura os textos digitados em:
  - eventos recebidos pelo Studio de laboratório;
  - `chrome.storage.session`;
  - log de eventos do service worker;
  - arquivos do host.

  Nada encontrado no login (usuário e senha) nem na consulta (observações, nota rápida, valor programático).
- Host: `StepPrivacyTest` (e-mail, token na URL, CPF no título, id JWT, campo sensível).
- Cruzado no Studio: `RealNativeHostBridgeTest.etapasPeloHostReal` mostra o host real mascarando o e-mail e
  sanitizando a URL; uma etapa com campo `value` não atravessa e não aparece no log do host.
- Node: `studio-steps.test.js` (senha, e-mail, CPF e token na URL).
