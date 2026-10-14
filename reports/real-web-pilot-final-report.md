# NEKKORA-REAL-001 — Real Web Pilot: relatório final

Branch `feat/nekkora-real-web-pilot` (base `5bcc108`, CHROME-003 integrada). Data: 2026-10-14.

> **Resultado**: a extensão interpreta páginas http/https **autorizadas pelo usuário**, sem depender do laboratório
> local, mantendo isolamento, privacidade, integridade e segurança das missões anteriores. Isso autoriza **testes
> exploratórios controlados**; **não** é homologação para produção.

## 1. Resumo

| Item | Resultado |
|---|---|
| Homologação no navegador (suíte padrão, Chrome for Testing 155) | **186/186** em 10 min 10 s (Chrome for Testing 155.0.8059.39, Java 21.0.12, Windows 11) |
| — linha de base (CHROME-001/002/003, sem remoções nem afrouxamentos) | 174/174 |
| — novos (R001-01..11 em hosts "externos" de teste, RP-01 desempenho) | 12/12 |
| Diálogo nativo de permissão, Chrome 155 convencional, aprovação humana (R001-12) | **1/1** |
| Página real autorizada (Wikipedia, `-Suite real`) | **5/5** (RW-01..05) em 38 s; matriz em [test-matrix-real-web.md](test-matrix-real-web.md) |
| Unitários Java core / host / laboratório · contrato · extensão (Node) | 175 / 27 / 4 · 2 · 26 — todos aprovados |

## 2. Permissões

| Campo | Antes | Agora |
|---|---|---|
| `host_permissions` | `http://127.0.0.1/*`, `http://localhost/*` | inalterado (laboratório) |
| `optional_host_permissions` | — | `https://*/*`, `http://*/*` (teto; nada concedido na instalação) |
| `permissions` | `nativeMessaging, sidePanel, storage, scripting, webNavigation, alarms` | **inalterado** |

Nenhuma permissão de cookies, histórico (`history`/`tabs`), credenciais ou interceptação de tráfego. Autorização
por site ("Autorizar este site") ou ampla ("Autorizar todos os sites…", com segunda confirmação), sempre pelo
diálogo do Chrome e no clique do usuário; revogação pelo painel ou por `chrome://extensions`. Detalhes:
[docs/real-001/site-access.md](../docs/real-001/site-access.md).

## 3. Alterações

| Área | Alteração |
|---|---|
| Política de acesso | `background/access-policy.js` substitui as seis verificações de `localhost` (runtime, inspection, capture-session, frames, diagnostics, service worker, content script). Recusas: `ORIGIN_NOT_ALLOWED` + `reason` (`PERMISSION_REQUIRED`, `BROWSER_INTERNAL`, `BROWSER_RESTRICTED`, `UNSUPPORTED_SCHEME`, …) |
| Injeção | `background/injection.js`: registro dinâmico dos content scripts nas origens autorizadas (mesma lista/ordem, todos os frames, laboratório excluído); injeção nas abas já abertas só nos frames sem o script |
| Revogação | sessão e inspeção encerradas (`PERMISSION_REVOKED`); frame revogado vira `PERMISSION_DENIED` numa nova base; navegação para origem não autorizada encerra a sessão |
| Painel | seção "Acesso ao site" (estado, autorizar, autorização ampla com confirmação, revogar) |
| Privacidade | política `nekkora-privacy/1` no navegador **e** no motor (vetores compartilhados); `document.frames[].url` sem URL completa; textos/atributos mascarados; alvos de sites externos só em memória |
| Captura em páginas reais | atualizações compactas de geometria; varredura de visibilidade em subárvores grandes; PageMap > 48 MiB (JSON compacto) só em arquivo |
| Homologação | construção de teste com origens concedidas na instalação; hosts `*.nekkora.test`; suíte `-Suite real`; matriz `test-matrix-real-web` |

Decisões D-053..D-064 em [docs/decisions.md](../docs/decisions.md).

## 4. Segurança e privacidade

- URLs: credenciais removidas; segmentos de caminho com identificadores/segredos ⇒ `:redacted`; valores de
  parâmetros sensíveis ou com aparência de segredo/dado pessoal ⇒ `REDACTED` (nomes mantidos); `mailto:/tel:` e
  `data:/blob:` omitidos. Aplicado à URL do documento, frames, requisições, recursos, atributos de URL, eventos de
  navegação e estado persistido.
- Textos e atributos de texto livre: e-mail, CPF, CNPJ, cartão (Luhn), telefone, JWT/Bearer mascarados.
- Valores redigidos/mascarados nunca viram localizadores.
- Persistência: nada de sites externos é gravado em disco sem exportação explícita; logs sem conteúdo de página.
- **R001-10**: 20 valores fictícios plantados (tokens em consulta/fragmento/caminho/`data-*`, URL assinada, `fetch`
  com chave, `sid` de iframe, e-mail/CPF/telefone/cartão e — após a revisão do PR #3 — senha curta em `data-password`,
  URL com token em `data-reset-url`, chave de API e PIN em JSON de `data-config`, `x-session-token`) — **nenhum**
  encontrado no snapshot bruto, no PageMap exportado, na sessão, nos eventos, no grafo nem em qualquer arquivo do motor
  (inclusive o log). Evidência:
  [R001-10-sensitive-data-check.json](evidence/R001-10-sensitive-data-check.json).
- Diagnóstico de proteções continua estritamente passivo; nenhuma recomendação de contorno; nada de CAPTCHA,
  fingerprint ou simulação humana.

Pendência de segurança da revisão do PR #2 (`document.frames[].url` com URL completa): **resolvida**.

## 5. Homologação (§8)

| # | Cenário | Caso(s) | Resultado |
|---|---|---|---|
| 1 | Página HTTP/HTTPS externa autorizada | R001-01, RW-01 | aprovado (site "externo" de teste e Wikipedia) |
| 2 | Página externa sem autorização | R001-02, RW-02 | aprovado (`PERMISSION_REQUIRED`, `BROWSER_INTERNAL`, `UNSUPPORTED_SCHEME`; `pt.wikipedia.org` sem autorização) |
| 3 | Iframe de outra origem sem permissão | R001-03 | aprovado (`PERMISSION_DENIED`, permissão não ampliada) |
| 4 | Iframe de outra origem autorizada | R001-04 | aprovado (anexado, trajetória MAIN_DOCUMENT → IFRAME → SHADOW_ROOT → ELEMENT, reencontro RESOLVED) |
| 5 | SPA e atualização dinâmica | R001-05, R001-11, RW-03 | aprovado (rota SPA com `ref=REDACTED`, linha nova por delta; rolagem/classe do body sem recaptura, local e na Wikipedia) |
| 6 | Shadow DOM aberto | R001-06 | aprovado |
| 7 | Tecnologia de proteção observável | R001-07, RW-04 | aprovado com fixture simulada em origem externa (reCAPTCHA v2 DETECTED; falso positivo NOT_OBSERVED); na Wikipedia nenhuma tecnologia observada e a página de criação de conta é de outra origem (§6) |
| 8 | Desativação e reativação | R001-08, RW-05 | aprovado (ouvintes/observadores = 0; nova sessão) |
| 9 | Revogação durante a sessão | R001-09, RW-05 | aprovado (frame ⇒ PERMISSION_DENIED; página ⇒ sessão e inspeção encerradas com PERMISSION_REVOKED; reconcessão restabelece) |
| 10 | Exportação sem informações sensíveis | R001-10, RW-01 | aprovado (nenhum dos 20 valores plantados; nenhum e-mail no PageMap da Wikipedia) |

### Página real — Wikipedia (autorizada pela usuária para o teste)

| Caso | Observado |
|---|---|
| RW-01 `en.wikipedia.org/wiki/Brazil` | acesso `AUTHORIZED`; nenhum observador antes da ativação; base de **16.628** nós (1,5 s); `#firstHeading` → HEADING "Brazil"; `#searchInput` → SEARCH_INPUT "Search Wikipedia", localizador `input#searchInput`, DOM real = snapshot; PageMap com **13.136** elementos (≈ 146 MB em disco, omitido da mensagem: `TOO_LARGE_FOR_MESSAGE`); `document.url` = `https://en.wikipedia.org/wiki/Brazil`; nenhum e-mail no PageMap |
| RW-02 `pt.wikipedia.org` | `PERMISSION_REQUIRED` (padrão a pedir `https://pt.wikipedia.org/*`); sem content script |
| RW-03 rolagem | `SYNCHRONIZED`, 1 captura completa, 2 deltas (antes das correções: recaptura completa por rolagem) |
| RW-04 `Special:CreateAccount` | redireciona para `https://auth.wikimedia.org` ⇒ `PERMISSION_REQUIRED` (autorização não se estende) |
| RW-05 | desativar/reativar; revogação pelo controle do Chrome ⇒ `PERMISSION_REVOKED`; acesso restaurado ao fim |

Evidências: `reports/evidence/RW-0*.json` e capturas `RW-01-wikipedia-article.png`, `RW-04-wikipedia-create-account.png`.
Prontidão do artigo: `REQUIRES_SPECIAL_HANDLING` por `AMBIGUOUS_ELEMENTS` (1.577 links de mesmo nome — citações,
"edit"), observação correta, não falha.

## 6. Proteções em páginas reais

| Página | Resultado | Comparação com a evidência visual |
|---|---|---|
| Wikipedia — artigo | `NOT_OBSERVED`, nenhum indicador genérico; ressalva "não comprova ausência" | a página não exibe CAPTCHA nem desafio: **sem falso positivo** |
| Wikipedia — criação de conta | não inspecionada (outra origem: `auth.wikimedia.org`) | a verificação humana da Wikimedia (quando existe) fica nessa origem: **não avaliável** sem autorização dela |
| Fixtures do laboratório servidas por origem externa (`site-a.nekkora.test`) | reCAPTCHA v2 `DETECTED`/`CHALLENGE_PRESENT` (evidência marcada como simulada); falso positivo `NOT_OBSERVED` | iguais aos resultados da CHROME-003 em 127.0.0.1 |

Falsos negativos possíveis e não medidos em páginas reais: verificações aplicadas no servidor/borda, CAPTCHAs fora do
catálogo (ex.: FancyCaptcha do MediaWiki) e verificações carregadas só após interação. Nenhum fornecedor foi
declarado homologado por assinatura.

## 7. Desempenho (§7)

Medição repetida (RP-01, dentro da regressão completa, JVM do motor já aquecida): 7 execuções independentes por
tamanho, página nova a cada execução; 1ª seleção = fria, 10 seguintes = aquecidas.

| Métrica | amostras | mediana | p95 | média | desvio | CV | mín. | máx. |
|---|---|---|---|---|---|---|---|---|
| 5000 · início da sessão | 7 | 241.6 | 265.3 | 242.7 | 11.4 | 4.7% | 227.0 | 265.3 |
| 5000 · seleção FRIA (1ª) | 7 | 61.0 | 64.9 | 60.8 | 2.7 | 4.5% | 57.2 | 64.9 |
| 5000 · seleção AQUECIDA | 70 | 46.4 | 88.7 | 50.1 | 13.8 | 27.5% | 38.0 | 99.9 |
| 10000 · início da sessão | 7 | 540.3 | 596.2 | 547.8 | 28.3 | 5.2% | 519.6 | 596.2 |
| 10000 · seleção FRIA (1ª) | 7 | 92.5 | 94.3 | 91.9 | 2.9 | 3.2% | 85.6 | 94.3 |
| 10000 · seleção AQUECIDA | 70 | 65.2 | 143.2 | 79.6 | 30.2 | 38.0% | 55.3 | 165.8 |

Rodada isolada (JVM do motor recém-iniciada, [performance-isolated-jvm-cold.md](real-001/performance-isolated-jvm-cold.md)):
a **primeira** execução é o ponto fora da curva —

| Métrica | amostras | mediana | p95 | média | desvio | CV | mín. | máx. |
|---|---|---|---|---|---|---|---|---|
| 5000 · início da sessão | 7 | 246.6 | 1064.1 | 366.8 | 307.8 | 83.9% | 238.9 | 1064.1 |
| 5000 · seleção FRIA (1ª) | 7 | 54.8 | 302.8 | 93.4 | 92.7 | 99.2% | 52.9 | 302.8 |

(máximo = 1ª execução: início 1.064 ms e 1ª seleção 303 ms, contra ≈ 240 ms e ≈ 55 ms nas seguintes).

**Conclusão sobre a oscilação 86 × 127 ms (CHROME-003)**: em 10.000 elementos a seleção fria é estável (CV ≈ 5%,
86–94 ms), mas a aquecida tem cauda longa (mediana ≈ 65 ms, p95 ≈ 143 ms, máx. ≈ 166 ms; CV ≈ 38%) e a primeira
medição após iniciar a JVM é várias vezes mais lenta. Os valores 86, 92 e 127 ms medidos uma vez cada estão dentro
dessa distribuição: **não há evidência de regressão**, e uma única medição não permite concluir nada.

Comparação com o baseline (D003-002 na mesma rodada, procedimento C002-038, 10.000 elementos, agora / CHROME-002):
sessão 294 / 280 ms, seleção 88 / 86 ms, exportação em fluxo 1.054 / 1.067 ms — dentro da variação.

## 8. Defeitos encontrados e corrigidos

1. **PageMap acima do limite de mensagens** (página real): o artigo "Brazil" gera ~146 MB; o retorno ao painel/harness
   ultrapassava 64 MiB. Agora só o arquivo da exportação explícita acima de 48 MiB (JSON compacto) — D-062.
2. **Rolagem ⇒ recaptura completa** (página real): o retângulo é relativo ao viewport; rolar uma página com > 5.000
   elementos redescrevia todos e estourava o delta. Atualizações compactas de geometria — D-060.
3. **Classe do `body` trocada ao rolar** (Wikipedia: 17.362 descendentes) redescrevia a subárvore inteira. Varredura
   de visibilidade — D-061.
4. **Regressão introduzida e corrigida durante a missão**: as atualizações compactas não reclassificavam as condições
   (`OUT_OF_VIEWPORT`) — detectado pelo C002-045 (incremental × completo); os nós da geometria entram na reanálise.
5. **Regressão introduzida e corrigida durante a missão**: o primeiro limite (32 MiB medido sobre o arquivo formatado)
   omitia o PageMap de 10.000 elementos do laboratório — detectado pelo T036; passou a medir a resposta real.
6. Criação de conta da Wikipedia redireciona para `auth.wikimedia.org`: comportamento correto (a autorização não se
   estende a outra origem), registrado como limitação.

## 9. Mudança de teste existente (requisito, não afrouxamento)

`chrome-extension/test/content-policy.test.js` › "only loopback origins are allowed" codificava a restrição exclusiva
ao laboratório que a missão manda remover (§2). O teste passou a verificar que o content script só roda em http/https
(rejeita `file:`, `chrome:`, `data:`), e a recusa de `example.com` **sem autorização** passou para a política central
(`access-policy.test.js`), com verificações adicionais (outro subdomínio, outro esquema, revogação, página interna,
loja do Chrome). Nenhum outro teste foi alterado ou removido.

## 10. Limitações

[docs/real-001/known-limitations.md](../docs/real-001/known-limitations.md). Principais: somente a Wikipedia como
página real; diálogo de permissão verificado manualmente; dados pessoais sem formato reconhecível não detectados;
detecção de proteções de fornecedores reais não homologada; PageMaps de páginas reais grandes muito volumosos.

## 11. Pendência para a usuária

- ~~Verificação manual do diálogo do Chrome~~ — **concluída** (§13, R001-12): aprovação humana no Google Chrome
  155.0.8059.40 convencional.
- Licença do repositório: não modificada.
- Nekkora Studio: **não** iniciado.

## 12. Reprodução

```powershell
.\scripts\run-tests.ps1 -Suite all                 # unitários + homologação completa (~11 min)
.\scripts\run-tests.ps1 -Suite integration -Test "Real001PilotIT,Real001PerformanceIT"
.\scripts\run-tests.ps1 -Suite real                # Wikipedia (internet)
```

## 13. Revisão do PR #3

### P1 — atributos personalizados podiam expor segredos (corrigido)

`data-*` só era redigido com valor ≥ 16 caracteres com aparência de segredo: `data-password="hunter2"` e
`data-reset-url="https://example.test/reset?token=abc123"` passavam. Correção (D-065), idêntica no navegador
(`privacy.js › sanitizeAttribute`) e no motor (`AttributePrivacy`), conferida pelos vetores compartilhados (35 casos de
atributos): todo atributo **não estrutural** — `data-*` e personalizados — tem nome sensível ⇒ `[redacted]`; URL
inteira ou embutida ⇒ política de URLs; pares `chave=valor`/JSON com chave sensível ⇒ valor `REDACTED`; segredo longo
⇒ `[redacted]`; dados pessoais ⇒ mascarados. Atributos genéricos de componentes (`data-state="open"`) e os do
laboratório continuam intactos. Testes: `PrivacyTest` (+1), `privacy.test.js` (+1), vetores, R001-10 ampliado para 20
valores e verificação do snapshot bruto.

Durante a correção, um bloco removido do `capture.js` levou junto a constante que preserva `#frame=challenge` do
hCaptcha; a regressão completa acusou (P003-003, P003-014, N003) e a constante foi restaurada antes da rodada final.

Também corrigido: "Revogar autorização ampla" removia só o padrão que casava com a aba (`http://*/*` ou
`https://*/*`); agora remove o par.

### Diálogo nativo de permissão (verificado com aprovação humana)

`Real001PermissionDialogManualIT` (R001-12, fora da suíte padrão) no **Google Chrome 155.0.8059.40 convencional** com a
extensão **publicada** (nada concedido na instalação); a automação só clica nos botões do painel, a usuária aprovou os
diálogos:

| Passo | Resultado |
|---|---|
| Antes | painel "Não autorizado" (`en.wikipedia.org`) |
| "Autorizar este site" → diálogo do Chrome → aprovado | "Autorizado (este site)"; inspeção ativada na aba aberta ANTES da autorização (injeção nos frames que faltavam); `#firstHeading` → HEADING "Brazil" |
| "Revogar acesso a este site" | permissão removida; inspeção encerrada com `PERMISSION_REVOKED`; painel "Não autorizado" |
| "Autorizar todos os sites…" | segunda confirmação exibida e nada concedido só com o 1º clique |
| "Confirmar autorização ampla" → diálogo → aprovado | "Autorizado (autorização ampla)" em `pt.wikipedia.org`; inspeção ativa |
| "Revogar autorização ampla" | `https://*/*` e `http://*/*` removidos; inspeção encerrada; "Não autorizado" |

Evidências: [R001-12-permission-dialog-manual.json](evidence/R001-12-permission-dialog-manual.json) e capturas
`R001-12-panel-*.png`. Observação prática: no Windows a janela do Chrome de teste pode abrir atrás das demais — o
diálogo fica dentro dela (as duas primeiras tentativas esgotaram a espera por isso).

