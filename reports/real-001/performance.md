# NEKKORA-REAL-001 — variabilidade da seleção (frio × aquecido)

Gerado por `Real001PerformanceIT`. 7 execuções independentes por tamanho (página nova a cada execução); 10 seleções aquecidas por execução. Tempos de ponta a ponta (teste → service worker → motor → teste), em ms.

| Métrica | amostras | mediana | p95 | média | desvio | CV | mín. | máx. |
|---|---|---|---|---|---|---|---|---|
| 5000 · início da sessão | 7 | 230.9 | 254.1 | 231.5 | 11.3 | 4.9% | 221.6 | 254.1 |
| 5000 · seleção FRIA (1ª) | 7 | 54.9 | 63.3 | 56.0 | 3.7 | 6.6% | 52.7 | 63.3 |
| 5000 · seleção AQUECIDA | 70 | 42.6 | 78.9 | 46.4 | 11.6 | 25.0% | 37.6 | 88.4 |
| 10000 · início da sessão | 7 | 503.0 | 560.9 | 514.6 | 23.8 | 4.6% | 495.0 | 560.9 |
| 10000 · seleção FRIA (1ª) | 7 | 84.6 | 91.7 | 85.7 | 4.4 | 5.1% | 80.1 | 91.7 |
| 10000 · seleção AQUECIDA | 70 | 61.8 | 130.7 | 74.2 | 28.5 | 38.4% | 51.0 | 159.7 |

Referência: CHROME-003, rodadas únicas do C002-038 (seleção após captura síncrona prévia): 86 ms (baseline CHROME-002), 92 ms e 127 ms (rodadas da CHROME-003).
