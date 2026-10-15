# STUDIO-002 — Segurança do Bridge e dos comandos do Studio

## Ameaças consideradas

| Ameaça | Mitigação | Verificação |
|---|---|---|
| Outro computador acessa o Studio pela rede | Unix Domain Socket local. A alternativa TCP fica **só** em loopback (`127.0.0.1`/`::1`), nunca em `0.0.0.0`. O host recusa um endereço TCP fora do loopback no arquivo de descoberta | `BridgeServerTest.tcpSomenteNoLoopback…`; `BridgeDiscovery.assertLoopback` |
| Outro usuário do Windows lê o segredo ou fala com o socket | Diretório com ACL DEFINIDA (não herdada): uma única entrada, do dono, herdável. Socket e arquivo de descoberta nascem restritos, e a ACL EFETIVA é relida e conferida. Se a restrição não puder ser garantida, a publicação **falha** (Studio sem gravação) e o host **recusa** o arquivo (estado `INSECURE`) | sonda real no Windows 11 (abaixo); `StudioLinkTest.arquivoDeDescobertaInseguroNaoEhUsado`; `BridgeServerTest.aclComOutroPrincipalEhDetectada` |
| Processo se passando pelo host | Desafio-resposta HMAC-SHA256 com o segredo efêmero (32 bytes por inicialização) | `BridgeServerTest` (credencial inválida → `AUTH_FAILED`) |
| Processo se passando pelo Studio | O Studio também prova conhecer o segredo (`bridge.welcome.proof`), e a `instanceId` do desafio precisa ser a do arquivo | `StudioLinkTest.autenticacaoMutuaStudioFalsoRecusado` |
| Repetição de uma prova capturada | O nonce do servidor muda a cada conexão | `BridgeServerTest.recusaCredencialInvalidaProtocoloIncompativelERepeticao` |
| Credencial reaproveitada depois que o Studio fecha | Ao fechar: arquivo de descoberta e socket apagados, segredo zerado em memória. Arquivo deixado por uma queda tem `pid` morto: o host não conecta, e a próxima instância gera um novo segredo | `BridgeServerTest.encerramentoLimpa…`; homologação 18.1 |
| Proxy genérico para o motor | Lista EXPLÍCITA no host (`BridgeContract`) e na extensão (`COMMANDS`); todo comando validado pelo schema. `page.capture`, `host.ping` e qualquer comando do motor recebem `UNKNOWN_COMMAND` sem chegar à extensão | `StudioLinkTest.listaExplicitaEValidacaoDosComandos`; Node "lista explícita e validação" |
| Executar script, ler cookies, histórico ou credenciais, manipular arquivos, rodar comandos | Não existe nenhum comando para isso: o vocabulário só controla a gravação e lista abas com título mascarado e host. Campos extras (ex.: `script`) são recusados | fixture inválida `script-injection.json` |
| Contornar a permissão de origem | O Studio não concede nada: `start` e `resume` passam por `access-policy.assertAllowed`. Autorizar continua exigindo o gesto do usuário na extensão e o diálogo do Chrome | `Studio002BridgeIT.s002007…`, `s002027…` |
| Vazamento de dados pelo canal de controle | O host valida TAMBÉM as respostas e os eventos que repassa: fora do contrato (URL completa, PageMap, segredo) vira `INTERNAL_ERROR` e nada é repassado | `StudioLinkTest.respostaForaDoContratoNaoVaza`; `RealNativeHostBridgeTest` (Studio) |
| Identificadores internos expostos | `tabRef` opaco no lugar do id do Chrome; ids de gravação e operação são técnicos e não aparecem na interface do Studio | `S002-026` (Studio) |
| Segredo em log | O segredo nunca é registrado; logs só têm códigos | `BridgeServerTest.publicaComPermissoes…SemSegredoNosLogs` |
| Outra instância do Studio assume a gravação | Comandos carregam o controlador (`instanceId` autenticado, anexado pelo host); `NOT_CONTROLLER` para outros | Node "controlador" |

## Limite declarado

A autenticação por segredo em arquivo **não é proteção absoluta contra um processo malicioso executado pelo MESMO
usuário**: esse processo pode ler o arquivo de descoberta, como lê qualquer outro arquivo do usuário. O Bridge protege
contra outros usuários, a rede e processos que não leem o arquivo. Contra um processo malicioso do próprio usuário, a
proteção é a do sistema operacional.

## Validação real do Unix Domain Socket no Windows 11 (D-STUDIO-002-04)

Sonda executada antes da escolha do transporte (Windows 11 Pro 10.0.26200, Microsoft OpenJDK 21.0.12):

| Verificação | Resultado |
|---|---|
| Criação e conexão (`UnixDomainSocketAddress`) | ✅ eco de ida e volta |
| ACL do diretório com uma entrada do dono **sem** herança | o socket recebeu as entradas padrão (Administradores, SISTEMA). Por isso a entrada passou a ser herdável |
| ACL do diretório com entrada do dono **herdável** (`OI`/`CI`) | ✅ socket nasce só com o dono |
| ACL efetiva aplicada pelo sistema (socket sem permissão para o usuário) | ✅ `connect` recusado: "Permission denied" |
| Limpeza | fechar o canal **não** apaga o arquivo; um órfão recusa conexão e impede um novo `bind`. O Studio apaga o arquivo ao fechar e, com a trava de instância em mãos, remove um órfão antes do `bind` |
| Reconexão | ✅ várias conexões sucessivas no mesmo endpoint |
| Limite do caminho | cerca de 108 caracteres (`Unix domain path too long`). Acima de 100, o Studio usa TCP local |

Na homologação real, o Bridge foi publicado por Unix Domain Socket (`transport=unix`) em
`%LOCALAPPDATA%\Nekkora\Studio\bridge`.
