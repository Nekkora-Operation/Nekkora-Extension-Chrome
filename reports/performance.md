# Desempenho medido (T036 / T037)

Gerado em 2026-10-16T01:54:29.796983100Z · Chrome for Testing 155.0.8059.39 · Java 21.0.12 · Windows 11 10.0

| Elementos | Nós | Interativos | Captura (ms) | Maior bloqueio da página (ms) [base] | Snapshot (KB) | Classificação Java (ms) | Ciclo captura (ms) | Seleção ponta a ponta (ms) | Reencontro (ms) | Localizadores média (ms) | Export PageMap (ms) | Heap host (MB) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 100 | 92 | 23 | 4.8 | 6.4 [6.4] | 52 | 0.17 | 27.77 | 56.18 | 0.7 | 0.02 | 7.37 | 276.4 |
| 1000 | 932 | 248 | 22.8 | 28.8 [6.9] | 528 | 1.49 | 65.15 | 93.78 | 2.1 | 0.05 | 55.82 | 314.5 |
| 5000 | 4666 | 1248 | 134.9 | 153.3 [6.6] | 2655 | 5.85 | 275.77 | 277.97 | 16.77 | 0.15 | 358.85 | 388.2 |
| 10000 | 9332 | 2498 | 378.0 | 412.0 [6.4] | 5315 | 15.27 | 615.03 | 430.46 | 39.39 | 0.29 | 1095.92 | 696.1 |

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
  "hostMaxDeltaMs" : 0.49,
  "hostAvgDeltaMs" : 0.24,
  "resyncs" : 0
}
```
