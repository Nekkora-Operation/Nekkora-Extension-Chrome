# Desempenho medido (T036 / T037)

Gerado em 2026-10-14T04:12:01.562930900Z · Chrome for Testing 155.0.8059.39 · Java 21.0.12 · Windows 11 10.0

| Elementos | Nós | Interativos | Captura (ms) | Maior bloqueio da página (ms) [base] | Snapshot (KB) | Classificação Java (ms) | Ciclo captura (ms) | Seleção ponta a ponta (ms) | Reencontro (ms) | Localizadores média (ms) | Export PageMap (ms) | Heap host (MB) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 100 | 92 | 23 | 3.7 | 6.3 [6.3] | 49 | 0.63 | 27.46 | 52.79 | 1.71 | 0.19 | 25.29 | 262.7 |
| 1000 | 932 | 248 | 16.6 | 20.6 [6.7] | 501 | 2.93 | 58.83 | 110.27 | 7.66 | 0.25 | 197.51 | 290.4 |
| 5000 | 4666 | 1248 | 107.0 | 123.8 [6.2] | 2526 | 14.38 | 244.82 | 250.47 | 29.88 | 0.39 | 884.62 | 455.1 |
| 10000 | 9332 | 2498 | 358.6 | 381.5 [6.5] | 5061 | 17.69 | 562.09 | 370.65 | 48.21 | 0.87 | 2895.29 | 625.3 |

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
  "browserMaxDeltaMs" : 0.6,
  "hostMaxDeltaMs" : 0.66,
  "hostAvgDeltaMs" : 0.56,
  "resyncs" : 0
}
```
