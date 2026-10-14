# NEKKORA-REAL-001 — variabilidade da seleção (frio × aquecido) — rodada ISOLADA (JVM do motor recém-iniciada)

Gerado por `Real001PerformanceIT`. 7 execuções independentes por tamanho (página nova a cada execução); 10 seleções aquecidas por execução. Tempos de ponta a ponta (teste → service worker → motor → teste), em ms.

| Métrica | amostras | mediana | p95 | média | desvio | CV | mín. | máx. |
|---|---|---|---|---|---|---|---|---|
| 5000 · início da sessão | 7 | 246.6 | 1064.1 | 366.8 | 307.8 | 83.9% | 238.9 | 1064.1 |
| 5000 · seleção FRIA (1ª) | 7 | 54.8 | 302.8 | 93.4 | 92.7 | 99.2% | 52.9 | 302.8 |
| 5000 · seleção AQUECIDA | 70 | 42.2 | 84.8 | 49.7 | 22.2 | 44.6% | 34.4 | 180.7 |
| 10000 · início da sessão | 7 | 516.5 | 539.1 | 518.9 | 14.0 | 2.7% | 501.0 | 539.1 |
| 10000 · seleção FRIA (1ª) | 7 | 80.6 | 83.3 | 80.5 | 1.7 | 2.1% | 78.4 | 83.3 |
| 10000 · seleção AQUECIDA | 70 | 55.9 | 132.0 | 69.6 | 28.7 | 41.2% | 48.3 | 143.8 |

Referência: CHROME-003, rodadas únicas do C002-038 (seleção após captura síncrona prévia): 86 ms (baseline CHROME-002), 92 ms e 127 ms (rodadas da CHROME-003).
