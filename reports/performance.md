# Desempenho medido (T036 / T037)

Gerado em 2026-10-14T18:15:56.535772300Z · Chrome for Testing 155.0.8059.39 · Java 21.0.12 · Windows 11 10.0

| Elementos | Nós | Interativos | Captura (ms) | Maior bloqueio da página (ms) [base] | Snapshot (KB) | Classificação Java (ms) | Ciclo captura (ms) | Seleção ponta a ponta (ms) | Reencontro (ms) | Localizadores média (ms) | Export PageMap (ms) | Heap host (MB) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 100 | 92 | 23 | 4.2 | 8.5 [6.3] | 49 | 0.46 | 29.04 | 55.08 | 1.77 | 0.22 | 18.15 | 266.4 |
| 1000 | 932 | 248 | 20.8 | 23.9 [6.2] | 501 | 3.41 | 66.89 | 93.42 | 7.81 | 0.2 | 164.35 | 361.9 |
| 5000 | 4666 | 1248 | 118.9 | 133.2 [6.2] | 2526 | 15.58 | 256.97 | 301.18 | 29.72 | 0.42 | 981.75 | 453.4 |
| 10000 | 9332 | 2498 | 333.2 | 358.9 [6.3] | 5061 | 29.19 | 563.46 | 393.17 | 55.81 | 0.97 | 3059.66 | 607.5 |

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
  "hostMaxDeltaMs" : 0.77,
  "hostAvgDeltaMs" : 0.55,
  "resyncs" : 0
}
```
