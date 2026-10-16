# CHROME-SECURITY-001: Native Host (3ª barreira)

O host não confia no content script nem no service worker. Toda entrada e toda saída passam pela política:

| Ponto | Classe |
|---|---|
| `snapshot.submit`, `capture.session.start`, `capture.session.attachFrame` | `EngineServices.snapshot()` → `SnapshotPrivacy.apply` |
| `snapshot.delta`, `capture.session.delta` | `EngineServices.privacy()` → `SnapshotPrivacy.apply(delta, base)` |
| Metadados de frames do navegador | `BrowserFrameMetadata` (URL como modelo) |
| `exposedValue` | `RawNode`: sempre `null` |
| Mudanças e resoluções | `NodeChange`, `ResolutionResult` (`[omitido]`) |
| Etapas ao Studio | `StepPrivacy` |
| Evidências ao Studio | `EvidencePrivacy` (modo estrito da STUDIO-005 + URL como modelo) |
| Títulos de aba ao Studio (eventos e respostas) | `StudioLink.titles` |
| Declaração ao Studio | `hello.client.privacy = nekkora-privacy/2` (verdadeira mesmo com extensão antiga, porque o host reaplica a política) |
| Identificadores recebidos | `Payloads`: forma fechada, recusa sem eco |
| Logs e erros | `LogSafety`, `BridgeContract.problemSummary` |
| PageMap | 0.3.0 com `privacy`; escrita em fluxo inclui a declaração |
| Registro de alvos | `targets/registry-v2.json` |

## Testes adversariais

- `SnapshotPrivacyTest`: snapshot de produtor antigo com texto cru, atributos crus e valor de pesquisa. Verifica idempotência, delta com contexto da base e motor sem localizador inseguro.
- `StepPrivacyTest.sec001MetadadosDaPaginaNaoAtravessamOHostMesmoQueAExtensaoFalhe`: etapa com nome, ids, caminho, contexto, localizador, opção, URL e título com dados de pessoas fictícios.
- `LogSafetyTest`.
- `NativeHostTest.sec001RegistroAntigoNaoELidoNemApagado`.
- `NativeHostTest.engineRoundTripOverProtocol`: verifica a chave de linha retida e o localizador seguro e único.
