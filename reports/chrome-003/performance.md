# CHROME-003 — desempenho medido

Gerado em 2026-10-14T04:10:43.949497600Z · Chrome for Testing 155.0.8059.39 · Java 21.0.12 · Windows 11 10.0

Páginas geradas `/generated/frames?count=N&depth=D` (mesma origem). Tempos de ida e volta medidos pelo WebDriver (incluem o harness); tempos do motor medidos no Java.

| iframes | depth | frames | nodes | discoveryEndToEndMs | captureMs | graphMs | protectionMs | readinessMs | diagnoseEndToEndMs | accessPathEndToEndMs | resolveMs | navigationUpdateMs | observers | hostHeapUsedMb | pageHeapUsedMb | exportMs | exportBytes |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 1 | 1 | 14 | 45.43 | 4.5 | 0.12 | 0.17 | 0.1 | 9.56 | 10.85 | 21.64 | 62.36 | 2 | 11.3 | 2.7 | 8.33 | 109322 |
| 5 | 1 | 5 | 50 | 60.22 | 7.5 | 0.21 | 0.32 | 0.12 | 9.45 | 8.35 | 21.5 | 59.81 | 6 | 11.5 | 4.8 | 7.55 | 310632 |
| 10 | 1 | 10 | 95 | 84.42 | 10.0 | 0.44 | 0.51 | 0.16 | 11.65 | 9.73 | 23.38 | 54.31 | 11 | 11.6 | 8.1 | 11.26 | 563068 |
| 25 | 1 | 25 | 230 | 101.91 | 17.0 | 1.61 | 1.35 | 0.55 | 16.42 | 15.74 | 35.81 | 91.4 | 26 | 12.0 | 15.5 | 21.67 | 1324027 |
| 5 | 3 | 15 | 90 | 84.83 | 10.3 | 0.51 | 1.06 | 0.34 | 11.41 | 11.0 | 30.89 | 62.53 | 14 | 11.6 | 10.1 | 8.9 | 455417 |
| 3 | 5 | 15 | 80 | 80.3 | 11.9 | 0.53 | 1.24 | 0.27 | 13.69 | 12.87 | 26.6 | 61.37 | 12 | 11.6 | 10.3 | 9.47 | 375910 |
| 10 | 3 | 30 | 175 | 103.77 | 17.7 | 1.5 | 0.9 | 0.52 | 16.42 | 12.31 | 29.19 | 70.82 | 29 | 11.9 | 18.2 | 18.37 | 869463 |

## Comparação com o baseline CHROME-002 (mesmo procedimento do C002-038)

Valores no formato agora / baseline.

| Elementos | Nós | Captura síncrona (ms) | Export anterior (ms) | Bytes export anterior | Ciclo sessão (ms) | Captura cooperativa (ms) | Motor na base (ms) | Seleção (ms) | Export em fluxo (ms) | Bytes export em fluxo | Diagnóstico ida e volta (ms) |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 100 | 92 | 4.4 / 4.5 | 11.28 / 39.64 | 574210 / 567291 | 20.82 / 29.88 | 3.3 / 2.8 | 3.5 / 2.4 | 38.92 / 35.94 | 51.17 / 86.53 | 352179 / 345737 | 14.67 |
| 1000 | 932 | 19.9 / 18.7 | 115.32 / 155.61 | 6165897 / 6135491 | 43.1 / 56.61 | 18.7 / 18.6 | 13.5 / 11.7 | 41.15 / 36.35 | 165.89 / 191.77 | 3679977 / 3656295 | 23.98 |
| 5000 | 4666 | 125.6 / 117.5 | 891.71 / 928.36 | 31156790 / 31022317 | 154.3 / 148.29 | 81.1 / 71.9 | 60.8 / 54.2 | 62.31 / 62.72 | 576.79 / 586.26 | 18606266 / 18506256 | 62.05 |
| 10000 | 9332 | 344.1 / 392.9 | 2621.76 / 2701.91 | 62371004 / 62106568 | 292.48 / 279.51 | 159.5 / 148.3 | 119.9 / 105.0 | 92.12 / 86.29 | 1144.26 / 1067.06 | 37250232 / 37054904 | 110.45 |
