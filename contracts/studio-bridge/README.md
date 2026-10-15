# Fixtures do contrato `nekkora-bridge/1`

- `fixtures/valid/`: mensagens que **devem** passar no schema (handshake, comandos, respostas, eventos).
- `fixtures/invalid/`: mensagens que **devem** ser recusadas (protocolo errado, comando fora da lista, script no
  payload, id interno do Chrome, URL completa, PageMap no canal de controle, finalização que diz ter exportado…).

Prefixos: `s003-*` = capacidade `steps/1` (etapas gravadas, STUDIO-003); `s005-*` = capacidade `evidence/1`
(evidências observadas, STUDIO-005: evento `studio.recording.evidence`, comandos `studio.recording.evidence.ack/.sync` e
as recusas de HTML, valor digitado, PageMap, tipo/sinal desconhecido, confiança em percentual e "resultado de negócio").

Geradas por `scripts/gen-studio-bridge-fixtures.py <pasta fixtures>`. Validadas por `BridgeContractTest` (Native Host). O Nekkora Studio
guarda uma cópia e a confere com `BridgeContractFixturesTest`.
