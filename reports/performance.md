# Desempenho medido (T036 / T037)

Gerado em 2026-10-14T17:04:26.016024800Z · Chrome for Testing 155.0.8059.39 · Java 21.0.12 · Windows 11 10.0

| Elementos | Nós | Interativos | Captura (ms) | Maior bloqueio da página (ms) [base] | Snapshot (KB) | Classificação Java (ms) | Ciclo captura (ms) | Seleção ponta a ponta (ms) | Reencontro (ms) | Localizadores média (ms) | Export PageMap (ms) | Heap host (MB) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 100 | 92 | 23 | 3.9 | 8.8 [5.8] | 49 | 0.4 | 30.09 | 54.94 | 1.71 | 0.2 | 16.25 | 265.5 |
| 1000 | 932 | 248 | 17.7 | 21.1 [6.6] | 501 | 3.27 | 59.96 | 91.79 | 7.91 | 0.2 | 186.85 | 299.4 |
| 5000 | 4666 | 1248 | 115.5 | 131.1 [5.7] | 2526 | 13.85 | 253.63 | 241.18 | 38.75 | 0.41 | 857.41 | 447.2 |
| 10000 | 9332 | 2498 | 317.0 | 338.7 [6.3] | 5061 | 16.83 | 537.0 | 379.68 | 42.68 | 0.9 | 2771.73 | 981.8 |

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
  "browserMaxDeltaMs" : 0.7,
  "hostMaxDeltaMs" : 0.65,
  "hostAvgDeltaMs" : 0.5,
  "resyncs" : 0
}
```
