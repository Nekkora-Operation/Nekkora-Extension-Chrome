# Fixtures do contrato `nekkora-bridge/1`

- `fixtures/valid/`: mensagens que **devem** passar no schema (handshake, comandos, respostas, eventos).
- `fixtures/invalid/`: mensagens que **devem** ser recusadas (protocolo errado, comando fora da lista, script no
  payload, id interno do Chrome, URL completa, PageMap no canal de controle, finalização que diz ter exportado…).

Geradas por `scripts/gen-studio-bridge-fixtures.py`. Validadas por `BridgeContractTest` (Native Host). O Nekkora Studio
guarda uma cópia e a confere com `BridgeContractFixturesTest`.
