# Desempenho medido (T036 / T037)

Gerado em 2026-10-14T01:57:10.003361400Z · Chrome for Testing 155.0.8059.39 · Java 21.0.12 · Windows 11 10.0

| Elementos | Nós | Interativos | Captura (ms) | Maior bloqueio da página (ms) [base] | Snapshot (KB) | Classificação Java (ms) | Ciclo captura (ms) | Seleção ponta a ponta (ms) | Reencontro (ms) | Localizadores média (ms) | Export PageMap (ms) | Heap host (MB) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 100 | 92 | 23 | 3.5 | 6.2 [6.4] | 49 | 0.88 | 26.89 | 50.34 | 1.56 | 0.22 | 21.3 | 134.6 |
| 1000 | 932 | 248 | 16.7 | 19.3 [6.3] | 501 | 2.7 | 56.5 | 82.95 | 10.1 | 0.18 | 163.55 | 23.5 |
| 5000 | 4666 | 1248 | 110.9 | 124.5 [6.3] | 2526 | 16.54 | 245.25 | 228.95 | 29.03 | 0.41 | 937.94 | 439.9 |
| 10000 | 9332 | 2498 | 311.3 | 332.1 [6.2] | 5061 | 20.98 | 515.07 | 342.66 | 60.29 | 0.86 | 2797.73 | 1033.8 |

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
  "hostMaxDeltaMs" : 0.65,
  "hostAvgDeltaMs" : 0.51,
  "resyncs" : 0
}
```
