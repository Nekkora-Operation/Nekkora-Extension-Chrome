# STUDIO-002 — Native Host: processamento bidirecional sem bloqueio

Decisão D-STUDIO-002-05. Código: `java-core/native-host/.../runtime/` e `.../bridge/`.

## Antes

O `NativeHostMain` era um laço único: lia uma mensagem do stdin, processava, escrevia a resposta e só então lia a
próxima. Funcionava porque toda mensagem nascia de uma requisição da extensão. Com o Studio surgem mensagens
**iniciadas pelo host** (comandos do Studio, avisos do canal) que precisam sair enquanto o motor está ocupado.

## Agora

```
            ┌──────────────────────────────── HostRuntime (coordenador do ciclo de vida) ─┐
 stdin ──►  │ leitor (thread principal)                                                     │
            │   remonta fragmentos, lê só o campo "type" (sem montar a árvore das grandes)  │
            │   ├─ studio.* ──► CONTROLE (1 thread) ──► StudioLink (canal com o Studio) ────┐│
            │   └─ demais   ──► MOTOR (1 thread, ordem preservada) ──► MessageProcessor ───┤│
            │                                                                              ▼│
 stdout ◄── │ ChromeOutboundWriter: ÚNICO escritor; filas CONTROLE (prioritária) e MOTOR   ││
            └───────────────────────────────────────────────────────────────────────────────┘
                                   StudioLink ⇄ Studio (Unix Domain Socket, nekkora-bridge/1)
```

| Componente (proposta da missão) | Implementação |
|---|---|
| ChromeInputReader | `HostRuntime.run()` + `dispatch()` |
| NativeCommandDispatcher | executores MOTOR e CONTROLE do `HostRuntime` |
| ChromeOutboundWriter | `runtime/ChromeOutboundWriter` |
| StudioBridgeClient | `bridge/StudioLink` (com `BridgeDiscovery`, `BridgeAuth`, `BridgeFrames`, `BridgeContract`) |
| LifecycleCoordinator | `HostRuntime.shutdown()` |

## Garantias

1. **O leitor nunca trabalha.** Uma exportação longa ocupa a thread do MOTOR; o leitor continua recebendo e o CONTROLE
   continua respondendo.
2. **O motor continua sequencial.** Todas as operações sobre o estado do motor (sessões, capturas, exportações) correm
   numa única thread, na ordem de chegada. Prioridade de controle **não** significa mutação concorrente.
3. **Escrita atômica por transmissão.** Cada envio é a lista completa de quadros de uma mensagem, ou seja, um quadro ou
   todos os fragmentos `chunk` de uma resposta grande. Só a thread do escritor toca o stdout, e ela escreve a
   transmissão inteira antes de olhar a próxima. Assim **nenhum** envelope cai entre dois fragmentos da mesma mensagem.
   A sincronização de `NativeMessageCodec.write()` por quadro, que já existia, não bastava.
4. **Precedência de controle só entre transmissões.** Uma transmissão iniciada nunca é interrompida.
5. **Mensagens do Studio nunca chegam ao roteador do motor.** Tipos `studio.*` desconhecidos são descartados (com log)
   e nunca executados.
6. **Encerramento.** Quando o Chrome fecha o stdin, o host avisa o Studio (`bridge.disconnected`, motivo
   `CHROME_DISCONNECTED`), espera o motor concluir a operação em andamento (até 30 s), esvazia as filas e sai.

## Comprovação

| Teste | O que mede | Resultado |
|---|---|---|
| `HostConcurrencyTest.s002021…` (S002-021) | 3 respostas de 6 milhões de caracteres (153 fragmentos) disputando o stdout com mensagens de controle contínuas vindas de outra thread | 153 fragmentos, todas as transferências contíguas e em ordem; 361 mensagens de controle entregues, 3 delas logo após o fim de uma transferência (prova de disputa real); **0** intercalações |
| `HostConcurrencyTest.s002022…` (S002-022) | `host.hold` de 6 s ocupando o motor | `bridge.status` 3 ms; repasse do comando do Studio 1 ms; resposta ao Studio 1 ms; ida e volta da pausa 4 ms; `studio.link.status` 1 ms; o `host.ping` enfileirado no motor só terminou depois da operação longa (ordem preservada) |
| `Studio002BridgeIT.s002022…` (Chrome real) | `host.hold` de 8 s pelo harness com uma gravação ativa | `bridge.status` 1 ms; **pausa confirmada em 3 ms**; renovação 1 ms; `engineBusy=true` informado |

Os valores são as medições registradas em `reports/studio-002/` e `reports/evidence/S002-022-*.json`.

## Pausa sem esperar o motor

Pausar, finalizar ou cancelar libera o consumidor do Recorder com `capture.release(…, {detachHost: true})`. Quando o
Recorder era o último consumidor, a sessão de captura termina **localmente** na hora: rastreadores desligados,
consumidor removido. O pedido `capture.session.stop` segue na fila do motor e, se houver uma exportação em curso,
roda depois dela. Por isso a confirmação da pausa não espera a exportação.

Retomar exige uma nova captura-base quando não há outro consumidor, e isso depende do motor. Com o motor ocupado, a
retomada espera, e o Studio mostra "Retomando…". Com outro consumidor ativo (inspeção), a retomada é imediata e reusa a
sessão compartilhada.

## Descoberta e conexão (o host é iniciado pelo Chrome)

- Abrir o Studio não inicia o host. O usuário **liga "Conectar ao Nekkora Studio" no painel** (opt-in persistido em
  `chrome.storage.local`). A partir daí a extensão mantém a porta nativa aberta: o host roda e a porta aberta mantém o
  service worker ativo. Se o host terminar, a extensão reabre a porta por alarme a cada 30 s ou logo após a queda.
- A cada nova porta, a extensão envia `studio.link.configure {enabled:true}`. O host então lê o arquivo de descoberta a
  cada 1 s até encontrar um Studio vivo, conecta, autentica e informa `studio.link {state: CONNECTED}`.
- Sem o opt-in, o host **não procura** o Studio, e a extensão funciona exatamente como antes (teste
  `s002002SemCanalHabilitadoNadaMuda` e regressão completa).
- O Studio mostra "Navegador: aguardando conexão com a extensão" até haver uma conexão confirmada.
