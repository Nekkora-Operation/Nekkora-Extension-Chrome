# NEKKORA-REAL-001 — variabilidade da seleção (frio × aquecido)

Gerado por `Real001PerformanceIT`. 7 execuções independentes por tamanho (página nova a cada execução); 10 seleções aquecidas por execução. Tempos de ponta a ponta (teste → service worker → motor → teste), em ms.

| Métrica | amostras | mediana | p95 | média | desvio | CV | mín. | máx. |
|---|---|---|---|---|---|---|---|---|
| 5000 · início da sessão | 7 | 263.6 | 285.2 | 267.1 | 11.0 | 4.1% | 253.3 | 285.2 |
| 5000 · seleção FRIA (1ª) | 7 | 51.9 | 61.9 | 53.2 | 4.0 | 7.5% | 50.0 | 61.9 |
| 5000 · seleção AQUECIDA | 70 | 39.3 | 69.9 | 42.6 | 9.6 | 22.5% | 34.3 | 73.1 |
| 10000 · início da sessão | 7 | 579.1 | 591.2 | 573.7 | 13.1 | 2.3% | 554.4 | 591.2 |
| 10000 · seleção FRIA (1ª) | 7 | 77.1 | 81.3 | 77.7 | 2.9 | 3.7% | 74.4 | 81.3 |
| 10000 · seleção AQUECIDA | 70 | 52.1 | 124.9 | 65.1 | 28.0 | 43.0% | 44.5 | 145.0 |

Referência: CHROME-003, rodadas únicas do C002-038 (seleção após captura síncrona prévia): 86 ms (baseline CHROME-002), 92 ms e 127 ms (rodadas da CHROME-003).
