# Desempenho medido (T036 / T037)

Gerado em 2026-10-14T13:04:02.855682800Z · Chrome for Testing 155.0.8059.39 · Java 21.0.12 · Windows 11 10.0

| Elementos | Nós | Interativos | Captura (ms) | Maior bloqueio da página (ms) [base] | Snapshot (KB) | Classificação Java (ms) | Ciclo captura (ms) | Seleção ponta a ponta (ms) | Reencontro (ms) | Localizadores média (ms) | Export PageMap (ms) | Heap host (MB) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 100 | 92 | 23 | 3.7 | 6.2 [6.2] | 49 | 0.49 | 29.32 | 62.04 | 1.8 | 0.24 | 21.2 | 259.7 |
| 1000 | 932 | 248 | 18.1 | 22.4 [6.5] | 501 | 3.03 | 59.78 | 91.12 | 9.54 | 0.21 | 193.39 | 290.7 |
| 5000 | 4666 | 1248 | 128.3 | 149.2 [6.4] | 2526 | 17.73 | 272.97 | 272.37 | 34.4 | 0.5 | 1058.58 | 445.6 |
| 10000 | 9332 | 2498 | 320.2 | 342.5 [6.6] | 5061 | 18.7 | 534.89 | 364.09 | 46.09 | 0.97 | 3295.44 | 760.1 |

## Mutações frequentes (relógio a cada 20 ms por 3 s)

```json
{
  "clockTicks" : 170,
  "rawMutations" : 516,
  "batches" : 170,
  "deltasSent" : 7,
  "deltaEvents" : 7,
  "upsertedNodes" : 26,
  "ignoredRecords" : 0,
  "browserMaxDeltaMs" : 0.7,
  "hostMaxDeltaMs" : 0.71,
  "hostAvgDeltaMs" : 0.56,
  "resyncs" : 0
}
```
