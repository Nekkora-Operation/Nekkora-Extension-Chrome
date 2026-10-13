# Limitações conhecidas

Limites **declarados** (comportamento correto é reconhecer e reportar a impossibilidade) e limitações
de implementação desta primeira fase, com a evidência correspondente.

## Por projeto (comportamento correto)

| Limite | Comportamento | Evidência |
|---|---|---|
| Shadow DOM **fechado** | detectado (`chrome.dom.openOrClosedShadowRoot`), declarado na cobertura, **não** inventariado | T018 |
| Iframe cross-origin / sandbox | declarado inacessível com motivo (`CROSS_ORIGIN`, `SANDBOXED`); alvo nele → `SCOPE_UNAVAILABLE` | T020, N-D |
| Opções não materializadas | completude `UNKNOWN`/`INCOMPLETE`; nunca presumidas inexistentes | T014, T021 |
| Alvo indistinguível no registro | nunca resolvido depois (`AMBIGUOUS`/`INSUFFICIENT_EVIDENCE`) | T032 |
| Páginas não locais | a extensão não atua fora de `127.0.0.1`/`localhost` | `ORIGIN_NOT_ALLOWED` |

## Limitações de implementação

1. **Plataforma**: instalação automatizada do host validada somente no **Windows 11** (PowerShell,
   registro HKCU). Em Linux/macOS o host Java funciona, mas o manifesto precisa ser copiado
   manualmente para `~/.config/google-chrome/NativeMessagingHosts/` (Linux) ou
   `~/Library/Application Support/Google/Chrome/NativeMessagingHosts/` (macOS) e o launcher adaptado
   (shell script) — **não validado** nesta missão.
2. **Chrome de marca**: o registro `HKCU\Software\Google\Chrome\…` é criado e a extensão pode ser
   carregada manualmente em `chrome://extensions`; toda a automação de testes rodou no **Chrome for
   Testing 155** (o Chrome de marca não aceita `--load-extension` desde a 137).
3. **Iframes**: content scripts não são injetados em frames (`all_frames: false`); frames de mesma
   origem são percorridos pelo documento principal. Iframes de **outra origem permitida**
   (ex.: `localhost` dentro de `127.0.0.1`) são tratados como inacessíveis. O inspector intercepta
   eventos apenas no documento principal e em frames acessíveis.
4. **Nome acessível**: implementação simplificada do AccName 1.2 (um nível de `aria-labelledby`, sem
   conteúdo de pseudo-elementos CSS, sem `aria-hidden` recursivo além da visibilidade computada).
5. **Heurísticas rotuladas**: mensagens de erro/instrução por `class`/`id`, portal por ancestral comum,
   virtualização por `aria-setsize` e altura rolável, títulos de diálogo sem rótulo. Sempre registradas
   em `evidence` como heurística.
6. **XPath × motor**: `normalize-space()` do XPath só colapsa espaços ASCII; o motor normaliza também
   NBSP. Textos com NBSP podem gerar contagens diferentes — a verificação ao vivo (`locators.verify`)
   detecta a divergência.
7. **`ROLE_NAME`** é verificável somente pelo motor (sem equivalente CSS/XPath); marcado `ENGINE_ONLY`.
8. **Rastreamento incremental**: shadow roots e iframes criados **depois** de ligar o rastreamento só
   entram na cobertura (e são observados) após a próxima captura completa (`rebase`).
9. **Resource Timing**: observa apenas URL e tempos de `fetch`/`XHR` do documento principal (sem
   corpo, método ou cabeçalhos; buffer padrão de 250 entradas).
10. **Assinatura fixa**: a identidade registrada não "aprende" mudanças legítimas de contexto (ex.:
    tabela renomeada) — preferimos recusar a arriscar correspondência falsa.
11. **Sensibilidade**: detecção por tipo/nome/autocomplete; campos sensíveis com nomes inócuos não são
    marcados — mas valores de campos (exceto pesquisa) nunca são exportados de qualquer forma.
12. **Desempenho**: exportação do PageMap completo de 10.000 elementos leva ~3,3 s (localizadores para
    2.498 elementos interativos); o heap do host chega a ~550 MB porque mantém 6 capturas por aba e o
    GC é preguiçoso com heap padrão. A captura síncrona bloqueia a thread da página por ~340 ms em
    10.000 elementos (medido por batimento no mundo da página).
13. **Painel lateral**: o Chrome exige gesto do usuário para abrir o side panel; o teste de interface
    (C-9) usa a mesma página do painel aberta como aba vinculada (`panel.html?tabId=`).
14. **Exploração de opções**: a extensão não abre dropdowns automaticamente (requisito: captura inicial
    somente leitura; exploração em etapa separada). A exploração foi feita por cliques reais nos testes.
15. **Licença** pendente de decisão da Nekkora (ver `LICENSE`, D-010).
