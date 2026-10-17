# STUDIO-010 — Captura visual segura para o PDD (extensão)

A documentação completa da missão fica no Studio (`Nekkora-Studio/docs/studio-010/`: auditoria G0, arquitetura,
política visual, contratos, privacidade, homologação). Aqui fica o que é próprio da extensão e do host.

## Permissões

| Mudança | Justificativa |
|---|---|
| `activeTab` | `chrome.tabs.captureVisibleTab` exige `<all_urls>` ou `activeTab` (experimento G0 E1). `activeTab` só vale depois de um gesto do usuário no Chrome e só para a aba ativa; não gera aviso de instalação |
| `commands.nk-pdd-capturar` (sugerido **Alt+Shift+K**) | é o gesto. O Chrome 155 não atribui Alt+Shift+P (medido). O atalho efetivo volta ao Studio em `PddArmResult.shortcut`; vazio → orientação para `chrome://extensions/shortcuts` |

Nenhuma permissão de host nova. Recusados: `<all_urls>`, `debugger`, captura de tela pelo sistema (ver G0).

## Peças

| Arquivo | Papel |
|---|---|
| `src/background/studio-pdd.js` | máquina de estados: `arm` (só aba LAB; validade 5–120 s; época; uso único) → gesto (`onShortcut`) → plano visual + caixas das etapas (content script) → `captureVisibleTab` → nova ficha do layout → sanitização → autoverificação → evento `studio.pdd.captured`. Navegação, troca de documento, aba fechada e vencimento desarmam com motivo. Trava `studio-pdd` exclusiva com gravação e debug |
| `src/background/visual-sanitizer.js` | puro: `deviceRegions` (px CSS → px do dispositivo; portador arredondado **para dentro**, campo **para fora**), `compose` (tela opaca + portadores + molduras sintéticas), `violations` (todo pixel fora dos portadores tem de ser sintético) |
| `src/content/content-main.js` | `cs.pdd.plan` (viewport, DPR, rolagem, ficha), `cs.pdd.rects` (portadores aprovados pela `nekkora-privacy/2` + molduras de campo; 5 pontos de amostra; sem sobreposição), `cs.pdd.token` (ficha do layout por `MutationObserver`), `cs.pdd.release` |
| `src/background/studio-debug.js` | exporta `descriptorOf` para as caixas das etapas pelo resolvedor somente leitura da STUDIO-009 |
| host `PddVisualPrivacy` | 3ª barreira: reabre o PNG, confere dimensões, política, tamanho e refaz a verificação de pixels antes de repassar ao Studio |
| `contracts/studio-bridge.schema.json` | `PddArmPayload`, `PddArmResult`, `PddCaptureRef`, `PddCancelResult`, `PddCapturedPayload` |

A imagem bruta vive só dentro de `shoot()` no service worker; `close()` e anulação das referências no `finally`.
Nunca vai a `chrome.storage`, log, Native Messaging, disco, área de transferência ou relatório.

## Laboratório

`test-lab/pages/studio010/`: `login.html`, `consulta.html`, `resultado.html`, `sentinelas.html` (`?v=modal`,
`?v=mudanca`) e `frame-sentinela.html`. Todo texto sensível usa a cor-sentinela **#E6007E**; os testes contam pixels
dessa cor nas imagens sanitizadas (detector estreito: exclui franjas de ClearType azul e o rosa dos marcadores do PDF).

## Testes

- `test/studio-pdd.test.js` (8): sanitizador, armação, gesto, uso único, página mudando, regiões incoerentes, navegação.
- `PddVisualPrivacyTest` (6): 3ª barreira.
- `Studio010SafeCaptureIT` (6, Chrome real, gesto pelo teclado do sistema): login, sentinelas por classe, modal,
  página mudando, negativas, zoom 150%. Imagens sanitizadas em `reports/evidence/S010-*-sanitizado.png`.
