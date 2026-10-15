# STUDIO-002 — Gravação técnica controlada pelo Studio (lado da extensão)

Código: `chrome-extension/src/background/studio-recording.js` (fábrica testável), `studio-recorder.js` (instância com as
dependências reais), `studio-link.js` (canal) e ligações em `service-worker.js`.

## O que é (e o que não é)

Gravar, nesta versão, é manter um **consumidor próprio** (`studio-recorder:<recordingId>`) inscrito na CaptureSession
da aba. A página é observada de verdade: captura-base e deltas aplicados no motor Java. Mas **nenhuma ação vira etapa**:
cliques, digitação e seleção são da STUDIO-003 (D-STUDIO-002-11). Finalizar **não** exporta PageMap
(D-STUDIO-002-03); `recording.session.*`, o contrato que exporta, não foi tocado.

## Estados (autoritativos)

```
start ─► RECORDING ─pause─► PAUSED ─resume─► RECORDING
            │  ▲               │
            │  └─resume (recovered)── DEGRADED ◄─ aba fechada · permissão revogada · captura em erro/encerrada
            ▼
finish ─► COMPLETED     cancel ─► CANCELLED     concessão vencida ─► EXPIRED          (terminais)
```

| Operação | Efeito técnico verificável |
|---|---|
| `start` | valida payload, aba (`tabRef` → aba existente), uma gravação do Studio por aba, política de acesso (LAB/AUTHORIZED) e então `capture.acquire(tab, "studio-recorder:<id>")`. A captura-base criada pelo Recorder conta no primeiro trecho |
| `pause` | `capture.release(tab, consumidor, "STUDIO_RECORDER_PAUSED", {detachHost:true})`. Se o Recorder era o único consumidor, a sessão de captura termina (`lastEnded.reason = STUDIO_RECORDER_PAUSED`). Se havia outro (inspeção), a sessão **continua** e `otherConsumers > 0` informa isso |
| `resume` | revalida a aba e a permissão e readquire o consumidor (abre um novo trecho). Com sessão compartilhada ativa, reaproveita a base consistente; sem ela, estabelece uma nova base |
| `mode` | grava `CAPTURE_ONLY` ou `CAPTURE_AND_INTERACT`, confirmado e devolvido. **Nenhum sensor muda**: a diferença de comportamento é da STUDIO-003 |
| `finish` / `cancel` | libera o consumidor e devolve o resumo técnico (`pageMapExported: false`, `actionCapture: NOT_AVAILABLE`) |
| `status` | lista somente as gravações do controlador que pergunta |
| `renew` | renova a concessão; fica fora da fila, então nunca espera outra operação |

## Garantias

- **Lista explícita**: só os comandos da tabela acima. Payload validado (campos, tipos, padrões, nenhum campo extra) e
  controlador obrigatório (`NOT_CONTROLLER` para outra instância do Studio).
- **Serialização**: todas as mutações passam por uma fila única (`withLock('studio-recorder')`). Finalizar e cancelar
  ao mesmo tempo produzem um único desfecho; o perdedor recebe `RECORDING_ENDED`.
- **Idempotência**: o mesmo `operationId` devolve o mesmo resultado (`duplicate: true`) sem repetir efeitos. A mesma
  gravação nunca é iniciada duas vezes. Pausar ou continuar duas vezes não tem efeito adicional. Repetir a finalização
  devolve o mesmo resumo.
- **Concessão** (D-STUDIO-002-07): definida no início (`leaseMs` entre 5 s e 300 s; o Studio usa 30 s e renova a cada
  10 s). Sem renovação, a gravação vai para `EXPIRED` e o consumidor é liberado, inclusive com o Studio travado ou
  derrubado. Isso é verificado por temporizador e, se o service worker reiniciar, pelo alarme `nekkora.studio.lease`.
  O prazo é **absoluto**. Uma renovação que chega depois do vencimento é recusada com `RECORDING_ENDED` e motivo
  `LEASE_EXPIRED`, sem prolongar o prazo, mesmo que o temporizador ainda não tenha rodado. Ela também é recusada
  enquanto a expiração está em andamento (marca `expiring`, definida dentro da fila antes de liberar o consumidor). A
  rotina de expiração encerra a gravação uma única vez e libera o consumidor. A renovação continua fora da fila e
  responde na hora. Ao receber a recusa, o Studio consulta o estado real.
- **Degradação** sem operação do Studio (evento com `operationId: null`): `TAB_CLOSED`, `PERMISSION_REVOKED`,
  `ORIGIN_NOT_ALLOWED` (navegou para fora), `CAPTURE_ERROR`, `CAPTURE_ENDED`. Uma gravação degradada nunca fica
  "gravando" sem consumidor.
- **Reinício do service worker**: o estado mínimo (ids, estados, contadores e referências de abas; nada da página) fica
  em `chrome.storage.session`. Ele é restaurado e reconciliado depois da reconciliação da CaptureSession.
- **Privacidade**: abas por `tabRef` opaco (`t-` + 14 caracteres aleatórios); o id do Chrome não sai da extensão. Título
  mascarado (`NekkoraPrivacy.maskText`, até 120 caracteres) e somente o host, nunca a URL. Páginas sem acesso aparecem
  sem título.

## Cobertura (iframes)

Calculada a partir de `webNavigation.getAllFrames` e do estado dos frames da sessão. Frames de origem não autorizada, em
sandbox ou com falha de anexação contam como **indisponíveis** e deixam a cobertura `PARTIAL`. Documentos locais do pai
(`about:`, `srcdoc`) e frames capturados pelo pai contam como cobertos. A permissão da página principal não autoriza
iframes de outras origens: a política existente foi preservada.

## Painel

Seção "Nekkora Studio" com o interruptor **Conectar ao Nekkora Studio** e o estado do canal: Desligada, Aguardando o
Nekkora Studio, Conectada, ou Recusada (sem proteção adequada, versão incompatível ou autenticação). O Studio **não**
concede acesso a sites: a autorização continua no próprio painel ("Autorizar este site"), com o diálogo do Chrome.

## Testes

| Nível | Arquivo | Cobertura |
|---|---|---|
| Node (14) | `test/studio-recording.test.js` | lista de abas sanitizada, recusa sem autorização, consumidor próprio e idempotência, coexistência, modo, finalizar × cancelar, controlador, lista explícita, concessão, aba fechada, permissão revogada, reinício do service worker, concessão vencida (renovação atrasada e renovação concorrente com a expiração) |
| Node (4) | `test/native-client-studio.test.js` | roteamento por tipo, nunca confundido com resposta, descarte de desconhecidos, remontagem de fragmentos, `post` e ouvintes de reconexão |
| Chrome (11) | `integration-tests/.../Studio002BridgeIT.java` | cadeia completa Studio de laboratório ⇄ host real ⇄ extensão ⇄ CaptureSession, em S002-002/003/006/007/008/009/010/011/012/013/014/016/017/018/020/022/027/028 |
