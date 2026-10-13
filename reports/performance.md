# Desempenho medido (T036 / T037)

Gerado em 2026-10-13T21:27:37.358977200Z · Chrome for Testing 155.0.8059.39 · Java 21.0.12 · Windows 11 10.0

| Elementos | Nós | Interativos | Captura (ms) | Maior bloqueio da página (ms) [base] | Snapshot (KB) | Classificação Java (ms) | Ciclo captura (ms) | Seleção ponta a ponta (ms) | Reencontro (ms) | Localizadores média (ms) | Export PageMap (ms) | Heap host (MB) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 100 | 92 | 23 | 4.0 | 8.8 [6.9] | 49 | 0.42 | 29.7 | 48.94 | 1.45 | 0.19 | 19.98 | 70.9 |
| 1000 | 932 | 248 | 16.2 | 20.8 [6.3] | 501 | 2.8 | 58.41 | 96.12 | 10.09 | 0.29 | 192.14 | 469.6 |
| 5000 | 4666 | 1248 | 110.8 | 123.5 [6.2] | 2526 | 13.64 | 245.21 | 246.91 | 32.45 | 0.45 | 1090.96 | 445.2 |
| 10000 | 9332 | 2498 | 318.6 | 340.2 [6.2] | 5061 | 18.54 | 531.83 | 368.51 | 56.43 | 0.88 | 3282.18 | 544.0 |

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
  "hostMaxDeltaMs" : 0.98,
  "hostAvgDeltaMs" : 0.74,
  "resyncs" : 0
}
```
