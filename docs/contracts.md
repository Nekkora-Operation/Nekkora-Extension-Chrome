# Contratos

Três contratos versionados em `contracts/`, todos em JSON Schema 2020-12 e validados automaticamente:

| Schema | Produzido por | Validado em |
|---|---|---|
| `page-map.schema.json` (PageMap **0.1.0**) | host Java (`pagemap.export`) | `PageMapContractTest` (sem navegador) e `ContractIT` (7 páginas reais) |
| `raw-snapshot.schema.json` | content script (`cs.capture`) | `PageMapContractTest` (fixture) e `ContractIT` (snapshots reais) |
| `native-protocol.schema.json` (nekkora-nm/1) | extensão e host | `PageMapContractTest` (requisições e respostas reais do host) |

Classes Java correspondentes: `com.nekkora.extension.core.*` (records). O mapeamento JSON segue os
nomes dos componentes dos records (Jackson), sem anotações no domínio.

## Identificadores

| Identificador | Natureza | Validade |
|---|---|---|
| `nodeId` (`n123`) | **temporário**, atribuído pelo content script a um nó DOM | enquanto o nó existir na sessão de página |
| `pageSessionId` (`ps-…`) | sessão de documento (nova a cada carga; SPA mantém) | uma carga de documento |
| `captureId` (`cap-…`) | captura completa ou incremental | sessão do host (6 capturas recentes por aba) |
| `targetId` (`tgt-…`) | **identidade lógica** do alvo | persistida; nunca ligada permanentemente a um nó |

## PageMap 0.1.0

```text
PageMap
├── schema = "nekkora.pagemap", version = "0.1.0", generatedAt, generator
├── snapshot: PageSnapshot        captureId, capturedAt, pageSessionId, mode (FULL|INCREMENTAL), stats
├── document: DocumentContext     url, title, navigation { observed, inferred }, frames, shadowRoots, observedRequests
├── coverage: CoverageReport      contagens, visíveis/ocultos, baixa confiança, frames/shadow roots, truncamento, limitações
├── elements[]: ElementDescriptor kind, role, confidence, text{own, descendant, accessibleName, contextual}, attributes,
│                                  conditions[], sensitive, rect, family (ElementFamily), locators (LocatorSet), evidence, uncertainties
├── relations[]: ContextRelation  CONTROLS, OWNS, LABELS, LABELLED_BY, DESCRIBED_BY, FORM_OWNER, OPTION_OF, HOSTS_* …
├── optionSets[]                  opções observadas × materializadas, completude COMPLETE|INCOMPLETE|UNKNOWN
├── targets[]: TargetIdentity     assinatura, discriminadores, localizadores, histórico de atributos voláteis
├── resolutions[]: ResolutionResult
├── mutations[]: MutationRecord
├── evidence[]: InspectionEvidence (com fonte: DOM, ENGINE, BROWSER_LIVE, RESOURCE_TIMING, TEST_INSTRUMENTATION)
└── metrics                       tempos de captura, indexação, classificação, família, localizadores
```

### LocatorDescriptor

Exemplo real (botão "Excluir" da Maria, tabela `clientes-inativos`, id duplicado na página):

```json
{
  "strategy": "ROW_CONTEXT",
  "css": "table#clientes-inativos tr[data-client=\"202\"] button#excluir",
  "xpath": "//table[@id=\"clientes-inativos\"]//tr[@data-client=\"202\"]//button[@id=\"excluir\"]",
  "semantic": "table {id=clientes-inativos} >> tr {data-client=202} >> button {id=excluir}",
  "query": { "steps": [ { "axis": "DESCENDANT", "tag": "table", "predicates": [ { "type": "ATTR_EQUALS", "name": "id", "value": "clientes-inativos" } ] }, "…" ] },
  "scope": { "type": "MAIN_DOCUMENT", "scopeId": "doc:f0", "frameId": "f0", "chain": [] },
  "matchCount": 1,
  "matchesTarget": true,
  "stability": "HEURISTIC_STABLE",
  "stabilityBasis": "HEURISTIC",
  "expected": { "kind": "BUTTON", "role": "button", "name": "Excluir", "table": "#clientes-inativos", "rowKey": { "data-client": "202" }, "heading": "Clientes inativos" },
  "warnings": ["DUPLICATE_ID: id \"excluir\" aparece 2 vezes no escopo"],
  "live": { "cssCount": 1, "xpathCount": 1, "cssMatchesTarget": true, "xpathMatchesTarget": true, "error": null }
}
```

- `query` é o localizador **estruturado**: o motor o reexecuta no snapshot; `css`/`xpath` são renderizações com a mesma semântica (verificadas na DOM real em `live`).
- `stabilityBasis` diferencia inferência (`HEURISTIC`) de comprovação por múltiplas capturas (`OBSERVED`).
- `expected` descreve o alvo; **a identidade não depende do seletor**.
- Estratégias: `TEST_ATTRIBUTE, ID, NAME, LABEL, ROLE_NAME, CONTEXTUAL, ROW_CONTEXT, ATTRIBUTE, TEXT, XPATH_RELATIVE, POSITIONAL`. Pontuação = peso × estabilidade × unicidade × verificabilidade (sem hierarquia rígida).

### ResolutionResult

| `status` | Quando |
|---|---|
| `RESOLVED` | exatamente um candidato confirmado (ou vantagem ≥ 0,15 sobre o segundo e alvo distinguível no registro) |
| `AMBIGUOUS` | dois ou mais candidatos igualmente plausíveis |
| `INSUFFICIENT_EVIDENCE` | candidato plausível sem comprovar discriminadores (ex.: linha sem chave e sem textos) |
| `IDENTITY_MISMATCH` | o id/name registrados agora apontam para elemento de outro tipo ou outro nome |
| `STALE_TARGET` | o nó registrado foi removido nesta sessão de página e não há substituto confirmável |
| `NOT_FOUND` | nenhum elemento compatível (ex.: após recarga, sem candidato) |
| `SCOPE_UNAVAILABLE` | o escopo do alvo (iframe/shadow root) deixou de ser inspecionável |

Cada candidato traz `verdict` (`CONFIRMED`, `VIABLE`, `DISQUALIFIED`, `INCOMPATIBLE`), `score` e a
comparação de cada característica (`MATCH`, `PARTIAL`, `MISMATCH`, `ABSENT`, `NOT_APPLICABLE`).

## Protocolo nekkora-nm/1

```json
{ "protocol": "nekkora-nm/1", "id": "x12-…", "type": "target.resolve", "payload": { "tabKey": "tab-7", "targetId": "tgt-…" } }
{ "protocol": "nekkora-nm/1", "id": "x12-…", "type": "response", "requestType": "target.resolve", "ok": true, "payload": { … }, "error": null, "timing": { "hostMs": 3.1 } }
{ "protocol": "nekkora-nm/1", "type": "chunk", "transferId": "h-4", "index": 0, "count": 17, "data": "{\"protocol\":…" }
```

- Quadro: inteiro de 32 bits little-endian + JSON UTF-8 (definição do Chrome).
- Host → navegador ≤ 1 MiB por mensagem: acima de 900 KiB a resposta é fragmentada (120.000 caracteres por fragmento, sem separar pares substitutos).
- Navegador → host: fragmentado acima de 4 MiB (configurável); remontagem limitada a 64 MiB, 16 transferências simultâneas, expiração de 120 s.
- Erros: `UNSUPPORTED_PROTOCOL, BAD_REQUEST, BAD_JSON, UNKNOWN_COMMAND, UNKNOWN_CAPTURE, UNKNOWN_NODE, UNKNOWN_TARGET, FRAME_TOO_LARGE, BAD_CHUNK, DUPLICATE_CHUNK, TOO_MANY_TRANSFERS, MESSAGE_TOO_LARGE, INTERNAL_ERROR`; do lado da extensão: `HOST_UNAVAILABLE, HOST_DISCONNECTED, TIMEOUT`.

## Evolução

Mudanças incompatíveis exigem nova versão (`0.2.0`) do schema e de `PageMap.VERSION`. Campos novos
opcionais exigem atualizar o schema (os objetos principais usam `additionalProperties: false` para
detectar divergência entre código e contrato).
