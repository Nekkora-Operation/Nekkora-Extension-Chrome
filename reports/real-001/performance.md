# NEKKORA-REAL-001 — variabilidade da seleção (frio × aquecido)

Gerado por `Real001PerformanceIT`. 7 execuções independentes por tamanho (página nova a cada execução); 10 seleções aquecidas por execução. Tempos de ponta a ponta (teste → service worker → motor → teste), em ms.

| Métrica | amostras | mediana | p95 | média | desvio | CV | mín. | máx. |
|---|---|---|---|---|---|---|---|---|
| 5000 · início da sessão | 7 | 241.6 | 265.3 | 242.7 | 11.4 | 4.7% | 227.0 | 265.3 |
| 5000 · seleção FRIA (1ª) | 7 | 61.0 | 64.9 | 60.8 | 2.7 | 4.5% | 57.2 | 64.9 |
| 5000 · seleção AQUECIDA | 70 | 46.4 | 88.7 | 50.1 | 13.8 | 27.5% | 38.0 | 99.9 |
| 10000 · início da sessão | 7 | 540.3 | 596.2 | 547.8 | 28.3 | 5.2% | 519.6 | 596.2 |
| 10000 · seleção FRIA (1ª) | 7 | 92.5 | 94.3 | 91.9 | 2.9 | 3.2% | 85.6 | 94.3 |
| 10000 · seleção AQUECIDA | 70 | 65.2 | 143.2 | 79.6 | 30.2 | 38.0% | 55.3 | 165.8 |

Referência: CHROME-003, rodadas únicas do C002-038 (seleção após captura síncrona prévia): 86 ms (baseline CHROME-002), 92 ms e 127 ms (rodadas da CHROME-003).
