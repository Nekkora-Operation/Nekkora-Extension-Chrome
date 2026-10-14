# CHROME-003 — desempenho medido

Gerado em 2026-10-14T17:03:08.427139500Z · Chrome for Testing 155.0.8059.39 · Java 21.0.12 · Windows 11 10.0

Páginas geradas `/generated/frames?count=N&depth=D` (mesma origem). Tempos de ida e volta medidos pelo WebDriver (incluem o harness); tempos do motor medidos no Java.

| iframes | depth | frames | nodes | discoveryEndToEndMs | captureMs | graphMs | protectionMs | readinessMs | diagnoseEndToEndMs | accessPathEndToEndMs | resolveMs | navigationUpdateMs | observers | hostHeapUsedMb | pageHeapUsedMb | exportMs | exportBytes |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 1 | 1 | 14 | 46.09 | 6.7 | 0.1 | 0.15 | 0.09 | 11.4 | 10.12 | 21.37 | 60.22 | 2 | 11.3 | 2.9 | 5.99 | 109861 |
| 5 | 1 | 5 | 50 | 62.86 | 7.1 | 0.42 | 0.36 | 0.14 | 15.42 | 9.7 | 24.42 | 54.55 | 6 | 11.5 | 5.1 | 7.63 | 311167 |
| 10 | 1 | 10 | 95 | 76.85 | 9.3 | 0.48 | 0.5 | 0.18 | 10.53 | 10.15 | 24.66 | 60.09 | 11 | 11.7 | 8.1 | 12.1 | 563599 |
| 25 | 1 | 25 | 230 | 99.16 | 18.1 | 2.74 | 1.13 | 0.34 | 15.54 | 14.37 | 30.93 | 74.9 | 26 | 12.0 | 16.0 | 20.65 | 1324569 |
| 5 | 3 | 15 | 90 | 78.05 | 11.3 | 0.85 | 0.77 | 0.3 | 11.6 | 10.6 | 27.61 | 61.56 | 14 | 11.7 | 10.7 | 8.22 | 455955 |
| 3 | 5 | 15 | 80 | 83.72 | 11.8 | 0.97 | 0.8 | 0.22 | 11.71 | 12.1 | 28.62 | 72.01 | 12 | 11.7 | 10.9 | 10.64 | 376452 |
| 10 | 3 | 30 | 175 | 111.45 | 18.9 | 1.49 | 1.56 | 0.38 | 14.42 | 15.17 | 34.84 | 81.09 | 29 | 11.9 | 19.0 | 15.7 | 869998 |

## Comparação com o baseline CHROME-002 (mesmo procedimento do C002-038)

Valores no formato agora / baseline.

| Elementos | Nós | Captura síncrona (ms) | Export anterior (ms) | Bytes export anterior | Ciclo sessão (ms) | Captura cooperativa (ms) | Motor na base (ms) | Seleção (ms) | Export em fluxo (ms) | Bytes export em fluxo | Diagnóstico ida e volta (ms) |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 100 | 92 | 4.5 / 4.5 | 10.98 / 39.64 | 574210 / 567291 | 23.79 / 29.88 | 4.0 / 2.8 | 2.1 / 2.4 | 39.25 / 35.94 | 51.33 / 86.53 | 352176 / 345737 | 14.62 |
| 1000 | 932 | 24.3 / 18.7 | 132.06 / 155.61 | 6165896 / 6135491 | 46.82 / 56.61 | 19.6 / 18.6 | 14.6 / 11.7 | 41.41 / 36.35 | 146.76 / 191.77 | 3679979 / 3656295 | 20.86 |
| 5000 | 4666 | 129.7 / 117.5 | 965.93 / 928.36 | 31156790 / 31022317 | 155.33 / 148.29 | 78.0 / 71.9 | 63.6 / 54.2 | 68.95 / 62.72 | 532.15 / 586.26 | 18606269 / 18506256 | 66.18 |
| 10000 | 9332 | 335.7 / 392.9 | 2683.04 / 2701.91 | 62371010 / 62106568 | 283.33 / 279.51 | 148.7 / 148.3 | 121.5 / 105.0 | 89.13 / 86.29 | 1052.16 / 1067.06 | 37250227 / 37054904 | 106.76 |
