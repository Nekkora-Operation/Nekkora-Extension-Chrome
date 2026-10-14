# Desempenho medido (T036 / T037)

Gerado em 2026-10-14T00:51:02.894472800Z · Chrome for Testing 155.0.8059.39 · Java 21.0.12 · Windows 11 10.0

| Elementos | Nós | Interativos | Captura (ms) | Maior bloqueio da página (ms) [base] | Snapshot (KB) | Classificação Java (ms) | Ciclo captura (ms) | Seleção ponta a ponta (ms) | Reencontro (ms) | Localizadores média (ms) | Export PageMap (ms) | Heap host (MB) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 100 | 92 | 23 | 5.0 | 7.1 [6.4] | 49 | 0.48 | 29.23 | 53.09 | 1.62 | 0.23 | 24.88 | 309.1 |
| 1000 | 932 | 248 | 17.3 | 23.8 [6.4] | 501 | 3.86 | 63.46 | 89.51 | 10.17 | 0.21 | 165.92 | 434.2 |
| 5000 | 4666 | 1248 | 109.8 | 122.3 [6.6] | 2526 | 14.74 | 229.2 | 224.61 | 28.72 | 0.42 | 972.99 | 447.1 |
| 10000 | 9332 | 2498 | 318.2 | 335.7 [6.5] | 5061 | 19.19 | 512.01 | 383.31 | 52.18 | 0.88 | 3270.87 | 616.6 |

## Mutações frequentes (relógio a cada 20 ms por 3 s)

```json
{
  "clockTicks" : 168,
  "rawMutations" : 510,
  "batches" : 168,
  "deltasSent" : 7,
  "deltaEvents" : 7,
  "upsertedNodes" : 26,
  "ignoredRecords" : 0,
  "browserMaxDeltaMs" : 0.6,
  "hostMaxDeltaMs" : 0.77,
  "hostAvgDeltaMs" : 0.51,
  "resyncs" : 0
}
```
