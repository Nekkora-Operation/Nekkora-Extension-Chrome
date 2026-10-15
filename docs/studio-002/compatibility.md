# STUDIO-002 — Compatibilidade (D-STUDIO-002-12)

## O que foi preservado

| Recurso | Situação | Evidência |
|---|---|---|
| Inspeção manual (botão único) | inalterada; coexiste com o Recorder, porque são consumidores independentes | regressão completa; `Studio002BridgeIT.s002009Coexistencia` |
| PageMap 0.2.0 | **sem alteração** no schema | `contracts/page-map.schema.json` intocado; contrato e regressão |
| Captura incremental, Frame Intelligence, diagnóstico de proteções | inalterados | regressão completa (186 testes de navegador) |
| Permissões opcionais por origem | inalteradas; o Studio não concede nada | `s002007…`, `s002027…` |
| Política de privacidade | reutilizada (títulos mascarados, URLs nunca enviadas) | Node e IT |
| Native Messaging `nekkora-nm/1` | só **acréscimos**: `studio.*` e `host.hold` | `BridgeContractTest.mensagensStudioDoNativeMessagingSaoAditivas` |
| Exportações existentes e gravações legadas (`recording.session.*`) | inalteradas; `recording.session.finished` continua sincronizando e exportando, e o Studio **não** o usa | regressão (Chrome002ExportIT, Chrome002LifecycleIT) |

## Mudanças internas (sem efeito para quem já usava)

- `NativeHostMain`: o laço único virou leitor + motor (1 thread, mesma ordem) + controle + escritor único. As respostas
  do motor saem na mesma ordem de antes. As únicas mensagens novas no stdout são as `studio.*`, e só depois do opt-in.
- `NativeClient`: mensagens que não são `response` eram silenciosamente ignoradas. Agora tipos com manipulador
  registrado (`studio.command`, `studio.link`) são roteados, e os demais continuam ignorados (contados em
  `stats.ignored`).
- `capture.release(…, {detachHost})`: novo parâmetro opcional. Sem ele, o comportamento é idêntico ao anterior.
- `MessageProcessor`: novos `success()`/`failure()` públicos, que reaproveitam o envelope existente.
- `manifest.json`: versão 0.4.0. Nenhuma permissão nova.

## Sem Studio / sem Chrome

- **Extensão sem Studio**: sem o opt-in no painel, o host nem procura o Studio. Com o opt-in e o Studio fechado, o
  canal fica "Aguardando o Nekkora Studio", e inspeção, captura e exportação funcionam normalmente
  (`s002002SemStudio`).
- **Studio sem Chrome**: o Studio abre, cria automações e etapas manuais e mostra "Navegador: aguardando conexão com a
  extensão"; só a gravação fica indisponível (Studio S002-001).

## Suítes

| Suíte | Antes (3c25a77) | Depois |
|---|---|---|
| Java (core, host, laboratório, contrato) | 208 | ver o relatório final |
| Node | 26 | 42 |
| Navegador (regressão) | 186 | 186 + 11 (`Studio002BridgeIT`) |

Os números finais medidos estão em [`reports/studio-002-bridge-final-report.md`](../../reports/studio-002-bridge-final-report.md).
