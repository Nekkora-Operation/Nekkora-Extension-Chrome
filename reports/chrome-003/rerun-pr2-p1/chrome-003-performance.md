# CHROME-003 — desempenho medido

Gerado em 2026-10-14T13:02:42.611389Z · Chrome for Testing 155.0.8059.39 · Java 21.0.12 · Windows 11 10.0

Páginas geradas `/generated/frames?count=N&depth=D` (mesma origem). Tempos de ida e volta medidos pelo WebDriver (incluem o harness); tempos do motor medidos no Java.

| iframes | depth | frames | nodes | discoveryEndToEndMs | captureMs | graphMs | protectionMs | readinessMs | diagnoseEndToEndMs | accessPathEndToEndMs | resolveMs | navigationUpdateMs | observers | hostHeapUsedMb | pageHeapUsedMb | exportMs | exportBytes |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 1 | 1 | 14 | 52.7 | 5.4 | 0.2 | 0.17 | 0.09 | 10.62 | 12.09 | 27.68 | 61.55 | 2 | 11.3 | 2.7 | 6.49 | 109322 |
| 5 | 1 | 5 | 50 | 78.57 | 9.4 | 0.3 | 0.34 | 0.13 | 9.35 | 11.1 | 25.54 | 53.7 | 6 | 11.3 | 4.8 | 8.56 | 310631 |
| 10 | 1 | 10 | 95 | 85.39 | 10.3 | 0.37 | 0.49 | 0.17 | 10.23 | 9.54 | 26.54 | 60.09 | 11 | 11.4 | 8.1 | 11.87 | 563069 |
| 25 | 1 | 25 | 230 | 109.65 | 17.4 | 1.07 | 1.13 | 0.38 | 13.19 | 13.01 | 30.08 | 73.55 | 26 | 11.8 | 15.5 | 24.04 | 1324032 |
| 5 | 3 | 15 | 90 | 85.17 | 14.2 | 0.4 | 0.77 | 0.33 | 11.27 | 10.49 | 28.27 | 68.12 | 14 | 11.5 | 10.2 | 9.27 | 455417 |
| 3 | 5 | 15 | 80 | 90.91 | 12.4 | 0.75 | 1.37 | 0.28 | 15.56 | 12.63 | 30.09 | 63.79 | 12 | 11.4 | 10.3 | 8.69 | 375918 |
| 10 | 3 | 30 | 175 | 115.99 | 22.2 | 1.58 | 1.08 | 0.37 | 16.44 | 13.31 | 28.17 | 74.03 | 29 | 11.7 | 18.2 | 15.22 | 869462 |

## Comparação com o baseline CHROME-002 (mesmo procedimento do C002-038)

Valores no formato agora / baseline.

| Elementos | Nós | Captura síncrona (ms) | Export anterior (ms) | Bytes export anterior | Ciclo sessão (ms) | Captura cooperativa (ms) | Motor na base (ms) | Seleção (ms) | Export em fluxo (ms) | Bytes export em fluxo | Diagnóstico ida e volta (ms) |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 100 | 92 | 4.7 / 4.5 | 12.59 / 39.64 | 574208 / 567291 | 23.45 / 29.88 | 2.8 / 2.8 | 3.7 / 2.4 | 37.24 / 35.94 | 45.51 / 86.53 | 352177 / 345737 | 15.47 |
| 1000 | 932 | 18.2 / 18.7 | 118.12 / 155.61 | 6165898 / 6135491 | 45.48 / 56.61 | 19.0 / 18.6 | 14.8 / 11.7 | 39.4 / 36.35 | 157.42 / 191.77 | 3679976 / 3656295 | 18.83 |
| 5000 | 4666 | 131.7 / 117.5 | 932.48 / 928.36 | 31156789 / 31022317 | 170.07 / 148.29 | 82.6 / 71.9 | 72.7 / 54.2 | 66.52 / 62.72 | 582.27 / 586.26 | 18606268 / 18506256 | 82.15 |
| 10000 | 9332 | 356.2 / 392.9 | 3163.62 / 2701.91 | 62371006 / 62106568 | 323.9 / 279.51 | 166.0 / 148.3 | 138.8 / 105.0 | 126.53 / 86.29 | 1025.75 / 1067.06 | 37250230 / 37054904 | 127.92 |
