# CHROME-SECURITY-001: compatibilidade e versionamento (decisão D5)

| Combinação | Comportamento |
|---|---|
| Extensão nova + host novo + Studio novo | política v2 nas quatro barreiras; `hello.client.privacy` declarado |
| **Extensão antiga** + host novo | o host reaplica a v2 a snapshots, deltas, etapas, evidências e títulos (`SnapshotPrivacy`, `StepPrivacy`…). Relações por valor de id são convertidas em `refs` antes de os valores serem retidos. O host declara a v2 porque é ele quem a garante |
| Extensão nova + **host antigo** | o snapshot continua válido no contrato antigo (campos novos aditivos; `exposedValue` `null`; textos vazios). O motor antigo funciona com menos texto, e o que ele devolver já veio de entrada filtrada. A 2ª barreira (service worker) reaplica a política a etapas e evidências |
| Studio novo + extensão/host **antigos** (sem `client.privacy`) | **não é aceito em silêncio**: o Studio reaplica a v2 a tudo o que recebe (`RecordedStep.guard`, títulos, URLs) e mostra "extensão sem a política de privacidade atual; o Studio revalida nomes, localizadores e endereços". Referências marcadas com a política anterior não são exibidas como seguras |
| **Studio antigo** + extensão nova | eventos continuam válidos no `nekkora-bridge/1`: campo novo no `hello` (o Studio antigo lê o handshake por chave e ignora o desconhecido), notas novas (lidas como texto), `option: null`, `title: null`, nomes vazios. Nada inválido sem tratamento |

## Contratos

| Contrato | Mudança | Tipo |
|---|---|---|
| `nekkora-privacy` | 1 → **2** (semântica nova, nova versão; a 1 não foi redefinida) | versão nova |
| PageMap | 0.2.0 → **0.3.0** (`privacy`, `text.suppressed`); 0.2.0 congelado | versão nova |
| RawSnapshot (`nekkora-nm/1`) | `privacy`, `textPresent`, `textSuppressed`, `textRevision`, `refs`; `exposedValue` só `null`; `document.title` anulável | aditivo |
| `nekkora-bridge/1` | `client.privacy`; notas `OPTION_WITHHELD`, `LOCATORS_WITHHELD`, `SAFE_LOCATOR_UNAVAILABLE` | aditivo |
| `nekkora-ui-vocabulary` | 1 (novo) | novo |
| `nekkora-structural-attributes` | 1 (novo, revisão dos PRs #7): regra fechada por atributo estrutural | novo |
| RawSnapshot `frames[].registryEpoch` | época do registro de nós (D7) | aditivo |
| PageMap 0.3.0 `targets[].anchor`, `resolutions[].continuity`, `document.frames[].registryEpoch` | âncora técnica e continuidade técnica (D7), separadas da identidade lógica | aditivo (antes da integração da 0.3.0) |

Sem downgrade silencioso: uma política ausente ou desconhecida nunca vale como garantia. O host e o Studio aplicam a v2 independentemente da declaração.
