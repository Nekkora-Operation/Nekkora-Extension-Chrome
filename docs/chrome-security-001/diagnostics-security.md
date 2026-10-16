# CHROME-SECURITY-001: segurança de logs e diagnósticos

| Superfície | Antes | Agora |
|---|---|---|
| `eventLog` do service worker (`events.since`, `sw.diagnostics`) | payload inteiro de cada difusão (nós, mutações com texto, inspeção, URLs) | lista fechada de campos: ids, códigos, estados, números e booleanos; listas viram contagens |
| Evento de navegação difundido | URL pela política anterior | modelo |
| `frames.list` (DEV) | URLs cruas do navegador, também em PRD | **só DEV** (bloqueado em PRD): diagnóstico de teste |
| `testHooks.read` e `interaction.observe` com `readHooks` | valores que a página expõe em `window.nekkoraTestHooks` (qualquer página pode declarar o marcador), também em PRD | **só DEV** (bloqueado em PRD, `config-mode.test.js`): instrumentação do laboratório |
| `errorInfo` difundido | `message` | ao vivo, para as páginas da extensão; no log retido, só `code`/`reason`/`where` |
| Log do Native Host | `LOG.log(..., e)` com mensagem e causas; `BridgeContract.problems` com mensagens do validador | `LogSafety.redacted(e)`: tipo, pilha e causas **sem mensagens**; `problemSummary`: caminho e regra violada |
| Resposta de erro `BAD_JSON` | `getOriginalMessage()` do Jackson (cita o token lido) | linha e coluna |
| Resposta `INTERNAL_ERROR` | tipo + mensagem | tipo + "(mensagem omitida)" |
| Identificadores nas mensagens de erro | eco do valor recebido (`Captura <valor> não está disponível`) | identificador fora da forma fechada é recusado **sem eco** |

Continua disponível para diagnóstico: código, componente, operação, instante, identificadores técnicos, estado e pilha.

Testes: `LogSafetyTest` (resposta e log sem o trecho recebido, para JSON malformado, payload incompatível e identificador com texto) e `ChromeSecurity001IT` (`events.since` sem nenhum marcador).
