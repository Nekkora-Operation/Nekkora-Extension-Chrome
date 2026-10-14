# Desempenho medido (T036 / T037)

Gerado em 2026-10-13T22:38:43.210024800Z · Chrome for Testing 155.0.8059.39 · Java 21.0.12 · Windows 11 10.0

| Elementos | Nós | Interativos | Captura (ms) | Maior bloqueio da página (ms) [base] | Snapshot (KB) | Classificação Java (ms) | Ciclo captura (ms) | Seleção ponta a ponta (ms) | Reencontro (ms) | Localizadores média (ms) | Export PageMap (ms) | Heap host (MB) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 100 | 92 | 23 | 4.2 | 8.5 [6.5] | 49 | 0.37 | 28.88 | 54.59 | 2.45 | 0.31 | 22.38 | 72.7 |
| 1000 | 932 | 248 | 16.5 | 21.9 [6.7] | 501 | 2.73 | 59.24 | 102.26 | 8.09 | 0.26 | 213.41 | 21.1 |
| 5000 | 4666 | 1248 | 116.2 | 130.6 [6.2] | 2526 | 16.76 | 267.27 | 279.03 | 30.55 | 0.49 | 1081.45 | 450.3 |
| 10000 | 9332 | 2498 | 316.9 | 339.0 [6.2] | 5061 | 20.44 | 534.28 | 379.92 | 52.89 | 1.1 | 3560.29 | 827.5 |

## Mutações frequentes (relógio a cada 20 ms por 3 s)

```json
{
  "clockTicks" : 169,
  "rawMutations" : 513,
  "batches" : 169,
  "deltasSent" : 7,
  "deltaEvents" : 7,
  "upsertedNodes" : 26,
  "ignoredRecords" : 0,
  "browserMaxDeltaMs" : 0.5,
  "hostMaxDeltaMs" : 1.05,
  "hostAvgDeltaMs" : 0.76,
  "resyncs" : 0
}
```
