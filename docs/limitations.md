# Limitações conhecidas

Limites **declarados** (comportamento correto é reconhecer e reportar a impossibilidade) e limitações
de implementação, com a evidência correspondente. Atualizado na CHROME-002 (itens resolvidos estão
marcados no fim).

## Por projeto (comportamento correto)

| Limite | Comportamento | Evidência |
|---|---|---|
| Shadow DOM **fechado** | detectado (`chrome.dom.openOrClosedShadowRoot`), declarado na cobertura, **não** inventariado | T018, C002-028 |
| Iframe de origem **sem permissão** (ex.: `127.0.0.2`, sites externos) | sem content script; declarado `CROSS_ORIGIN`; alvo nele → `SCOPE_UNAVAILABLE` | T020, N-D, C002-032 |
| Iframe com `sandbox` | não anexado por política (`POLICY_SANDBOX`), declarado `SANDBOXED` | T020, C002-032 |
| Opções não materializadas | completude `UNKNOWN`/`INCOMPLETE`; nunca presumidas inexistentes | T014, T021 |
| Alvo indistinguível no registro / ambíguo após mutação | nunca resolvido; destaque removido | T032, C002-044 |
| Páginas não locais | a extensão não atua fora de `127.0.0.1`/`localhost` (qualquer porta), em DEV e em PRD | `ORIGIN_NOT_ALLOWED`, C002-005 |
| Inspeção ativa bloqueia ações da página | cliques viram seleção (requisito CHROME-001); a captura contínua acompanha mudanças provocadas pela página (temporizadores, rede, gravação) | T039, docs/chrome-002/inspection.md |
| Captura-base não é um instante único | política declarada `BASE_PLUS_DELTA`: base cooperativa + 1º delta com o que mudou durante a travessia | C002-039 |

## Limitações de implementação

1. **Plataforma**: instalação automatizada do host validada somente no **Windows 11** (PowerShell,
   registro HKCU). Linux/macOS exigem cópia manual do manifesto e launcher em shell — **não validado**.
2. **Chrome convencional**: homologado na CHROME-002 com o Google Chrome 155 de marca carregando a
   extensão sem compactação por CDP `Extensions.loadUnpacked` (o Chrome ≥ 137 ignora
   `--load-extension`); a suíte completa roda no Chrome for Testing 155. O CfT procura o host em
   `HKCU\Software\Chromium` e, na ausência, em `HKCU\Software\Google\Chrome`.
3. **`attachShadow()` em host já conectado**: detectado pela reconciliação periódica, com latência
   de até ~1 s (medido 1.067 ms) — não há evento DOM para isso e não se usa monkeypatch.
4. **Geometria**: retângulos e "dentro do viewport" são atualizados em rolagem/redimensionamento
   (300 ms) e na barreira de exportação. Mudanças de layout sem rolagem/redimensionamento (ex.:
   animação CSS, fonte carregada) entre duas barreiras podem deixar retângulos defasados no estado
   incremental; a seleção e o destaque usam a DOM real.
5. **Visibilidade por CSS sem mutação** (media query, `:hover`): só é reavaliada quando o elemento ou
   um ancestral sofre mutação, ou numa nova base.
6. **Nome acessível**: AccName 1.2 com os casos da tabela em `docs/chrome-002/frames-shadow-accname.md`;
   fora do escopo: conteúdo de pseudo-elementos CSS, `aria-owns`, valor de controles de intervalo
   (`aria-valuetext`) como nome embutido. Não se declara conformidade integral.
7. **Heurísticas rotuladas**: mensagens de erro/instrução por `class`/`id`, portal por ancestral comum,
   virtualização por `aria-setsize` e altura rolável. Sempre registradas em `evidence` como heurística.
8. **XPath × motor**: `normalize-space()` do XPath só colapsa espaços ASCII; o motor normaliza também
   NBSP (a verificação ao vivo detecta a divergência).
9. **`ROLE_NAME`** é verificável somente pelo motor; marcado `ENGINE_ONLY`.
10. **Resource Timing**: somente URL e tempos de `fetch`/`XHR` do documento principal.
11. **Assinatura fixa**: a identidade registrada não "aprende" mudanças legítimas de contexto.
12. **Sensibilidade**: detecção por tipo/nome/autocomplete; valores de campos (exceto pesquisa) nunca são
    exportados de qualquer forma, inclusive quando alterados por mutação (C002-050).
13. **Reinício do service worker**: o processo do motor termina junto com a porta nativa; a sessão é
    restaurada com **nova base** (não há continuidade de deltas através do reinício).
14. **Exploração de opções**: a extensão não abre dropdowns sozinha (captura inicial somente leitura).
15. **Frames**: no máximo 20 frames anexados por aba; iframes de outra origem dentro de shadow roots
    fechados não são localizáveis pelo handshake.
16. **Gravação**: somente o **contrato** de eventos foi implementado (simulado pelo harness); não há
    gravador, Studio nem integração com a Platform nesta missão.
17. **Alvos registrados antes da CHROME-002** (sem dono no registro persistido) continuam resolvíveis por
    `targetId` (sem atualização da identidade), mas não entram em exportações de sessão nem no reencontro
    automático (isolamento, D-035). Uma identidade só é reencontrada automaticamente na sessão de captura em
    que foi registrada; em uma nova sessão da mesma aba o reencontro é explícito.
18. **Licença** pendente de decisão da Nekkora (ver `LICENSE`, D-010).

## Resolvidas na CHROME-002

| Limitação anterior | Solução | Evidência |
|---|---|---|
| Iframes de outra origem permitida tratados como inacessíveis | content script no frame + anexação | C002-030/031 |
| Shadow roots/iframes criados depois de ligar o rastreamento só entravam após nova captura completa | observação incremental + reconciliação | C002-026/028/031 |
| AccName com um nível de `aria-labelledby`; ordem texto/elemento perdida ("Mostrar itens 20") | AccName 1.2 (múltiplo, recursivo, circular, oculto, autorreferência) + `textSegments` | C002-033..037, `AccNameConformanceTest` |
| Captura síncrona bloqueava ~340 ms em 10.000 elementos | captura cooperativa (maior fatia ≈ 9 ms; bloqueio medido ≈ 22 ms) | C002-038/039 |
| Exportação 10k ~3,3 s; heap ~550–830 MB (6 capturas por aba) | exportação em fluxo (≈ 1,1 s); 3 capturas + referência; liberação ao encerrar | C002-038/040 |
| Painel lateral testado somente como aba | painel nativo homologado | C002-047 |
| Automação somente no Chrome for Testing | homologação no Chrome convencional | BrandedChromeIT |
