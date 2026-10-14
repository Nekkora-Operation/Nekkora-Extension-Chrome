# Privacidade para páginas reais (NEKKORA-REAL-001)

Política `nekkora-privacy/1`, implementada **duas vezes** e verificada contra os mesmos vetores
(`contracts/privacy/privacy-vectors.json`):

1. no navegador (`chrome-extension/src/shared/privacy.js`), antes de qualquer dado sair da página ou do service
   worker — primeira barreira;
2. no motor Java (`com.nekkora.extension.core.privacy`), nos construtores dos registros do snapshot
   (`DocumentInfo`, `FrameInfo`, `ObservedRequest`, `ResourceRef`, `RawNode`) — segunda barreira; vale para
   capturas-base, deltas, frames anexados e capturas avulsas.

A política é **idempotente** e **conservadora**: na dúvida, o valor é redigido.

## URLs

| Parte | Regra |
|---|---|
| Credenciais (`usuário:senha@`) | removidas |
| Segmento de caminho | `:redacted` se tiver aparência de identificador ou segredo: UUID, hexadecimal ≥ 16, JWT, opaco ≥ 24 com letras e dígitos, ≥ 6 dígitos seguidos, e-mail, ou dado pessoal reconhecível; parâmetros de matriz (`;jsessionid=…`) têm o valor redigido |
| Parâmetro de consulta | **nome mantido**; valor `REDACTED` se o nome for sensível (token, session, sid, code, state, key, secret, password, signature, email, cpf, phone, user, redirect, url, … — por palavra ou trecho) ou se o valor tiver > 64 caracteres, aparência de segredo ou dado pessoal |
| Fragmento | rotas (`#/…`) seguem as regras de caminho/consulta; pares `chave=valor` seguem as de consulta; âncoras curtas são mantidas |
| `mailto:`, `tel:`, `sms:` | `esquema:[redacted]` |
| `data:`, `blob:`, outros esquemas | conteúdo omitido |
| `about:blank`, `about:srcdoc`, `javascript:void(0)` | mantidos |

A **forma** da URL é preservada (absoluta, relativa à raiz ou relativa): quando nada precisa ser redigido o valor é
idêntico ao original, e localizadores continuam válidos. Valores produzidos pela política (`REDACTED`, `:redacted`,
`[email]`…) **não** são usados como localizadores, porque não correspondem à página real (`StepFactory.nonBlank`).

Onde se aplica: URL do documento (`document.url`, `document.frames[].url` — antes completa por compatibilidade
com o contrato 0.1.0), requisições observadas (`requests[].url`), recursos (`resources[].url`), metadados de frames
do Chrome, atributos `href`, `src`, `action`, `formaction`, `poster`, `data`, `cite`, `srcset`, `ping` e afins,
eventos de navegação SPA, o estado mínimo persistido em `chrome.storage.session` e as mensagens dos content scripts.

## Textos e atributos

Dados pessoais e segredos com **formato reconhecível** são mascarados no texto visível (`ownText`, segmentos de
texto, `<title>` de SVG), em títulos de documento e frame, no valor de campos de pesquisa e nos atributos de texto
livre (`title`, `alt`, `aria-label`, `aria-description`, `aria-valuetext`, `label`, `content`, `value`, …):

| Dado | Máscara |
|---|---|
| e-mail | `[email]` |
| CPF / CNPJ formatados | `[cpf]` / `[cnpj]` |
| número de cartão (13–19 dígitos, Luhn válido) | `[cartão]` |
| telefone em formato explícito (`(11) 9…`, `+55 …`) | `[telefone]` |
| JWT, `Bearer …`, `Basic …` | `[token]` |

Atributos `data-*` com aparência de segredo (≥ 16 caracteres) viram `[redacted]`. `placeholder` não é mascarado
(é dica da interface, ex.: `(11) 99999-9999`). As regras anteriores continuam: valores de campos nunca saem da
página (exceto pesquisa), senhas/cartões/documentos são sensíveis, `data-sitekey` é redigido, hooks da página só no
laboratório.

**Limite declarado**: nomes de pessoas, endereços e outros dados sem formato reconhecível **não** são detectáveis
por regra; o texto visível da página continua sendo coletado para a análise semântica. Ver
[known-limitations.md](known-limitations.md).

## Persistência

| Onde | O que |
|---|---|
| `chrome.storage.session` (memória do navegador, apagada ao fechar) | estado mínimo da sessão (URL **sanitizada**), estado da inspeção, último resultado de inspeção exibido no painel |
| Diretório do motor (`%LOCALAPPDATA%\Nekkora\ExtensionChrome`) | **laboratório**: registro de alvos como antes. **Sites externos**: alvos ficam **somente em memória** — nada é gravado sem ação explícita |
| Exportação (`pagemaps/`) | somente por ação explícita: botão "Exportar mapa" (DEV) ou finalização de uma gravação pela plataforma |
| Logs do motor | apenas início/encerramento do processo e erros; nenhum conteúdo de página (verificado em R001-10) |

Nenhum dado é enviado a serviços externos. Acima de 48 MiB (JSON compacto) o PageMap não trafega pelas mensagens internas do
Chrome: só o arquivo da exportação explícita é gerado (`pageMapOmitted: TOO_LARGE_FOR_MESSAGE`).

## Verificação

`R001-10` planta 15 valores fictícios (tokens em consulta, fragmento, caminho, `data-*`, URL de imagem assinada,
`fetch` com chave, e-mail/CPF/telefone/cartão em texto e atributos, iframe com `sid`) e verifica que **nenhum**
aparece no PageMap exportado, no estado da sessão, nos eventos, no grafo documental nem em qualquer arquivo do
diretório do motor (inclusive o log, lido após encerrar o processo).
