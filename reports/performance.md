# Desempenho medido (T036 / T037)

Gerado em 2026-10-14T01:38:17.870361200Z · Chrome for Testing 155.0.8059.39 · Java 21.0.12 · Windows 11 10.0

| Elementos | Nós | Interativos | Captura (ms) | Maior bloqueio da página (ms) [base] | Snapshot (KB) | Classificação Java (ms) | Ciclo captura (ms) | Seleção ponta a ponta (ms) | Reencontro (ms) | Localizadores média (ms) | Export PageMap (ms) | Heap host (MB) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 100 | 92 | 23 | 3.5 | 7.0 [6.5] | 49 | 0.39 | 28.27 | 51.34 | 1.73 | 0.23 | 19.69 | 152.7 |
| 1000 | 932 | 248 | 16.8 | 21.7 [6.2] | 501 | 3.91 | 59.03 | 84.68 | 9.07 | 0.18 | 167.05 | 75.5 |
| 5000 | 4666 | 1248 | 108.3 | 121.3 [6.6] | 2526 | 14.03 | 226.65 | 225.08 | 35.42 | 0.45 | 984.28 | 249.0 |
| 10000 | 9332 | 2498 | 310.2 | 333.0 [6.4] | 5061 | 23.32 | 532.66 | 391.53 | 43.93 | 1.06 | 3471.25 | 1053.4 |

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
  "hostMaxDeltaMs" : 0.62,
  "hostAvgDeltaMs" : 0.51,
  "resyncs" : 0
}
```
