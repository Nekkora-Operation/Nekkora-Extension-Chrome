# CHROME-003 — desempenho medido

Gerado em 2026-10-16T01:54:09.074245Z · Chrome for Testing 155.0.8059.39 · Java 21.0.12 · Windows 11 10.0

Páginas geradas `/generated/frames?count=N&depth=D` (mesma origem). Tempos de ida e volta medidos pelo WebDriver (incluem o harness); tempos do motor medidos no Java.

| iframes | depth | frames | nodes | discoveryEndToEndMs | captureMs | graphMs | protectionMs | readinessMs | diagnoseEndToEndMs | accessPathEndToEndMs | resolveMs | navigationUpdateMs | observers | hostHeapUsedMb | pageHeapUsedMb | exportMs | exportBytes |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 1 | 1 | 14 | 49.39 | 5.1 | 0.12 | 0.13 | 0.08 | 12.19 | 8.88 | 32.56 | 86.05 | 2 | 19.8 | 3.5 | 6.55 | 111117 |
| 5 | 1 | 5 | 50 | 57.45 | 8.1 | 0.21 | 0.21 | 0.12 | 18.34 | 9.28 | 34.02 | 67.82 | 6 | 19.9 | 6.5 | 6.39 | 328031 |
| 10 | 1 | 10 | 95 | 63.57 | 10.0 | 0.7 | 0.47 | 0.18 | 12.27 | 9.18 | 36.31 | 74.76 | 11 | 20.1 | 10.0 | 9.02 | 599457 |
| 25 | 1 | 25 | 230 | 104.89 | 19.0 | 0.69 | 0.66 | 0.36 | 12.25 | 12.12 | 47.37 | 94.23 | 26 | 20.4 | 13.0 | 17.55 | 1416164 |
| 5 | 3 | 15 | 90 | 77.28 | 11.7 | 0.47 | 0.51 | 0.25 | 11.05 | 11.15 | 37.45 | 84.45 | 14 | 20.1 | 13.3 | 10.3 | 483985 |
| 3 | 5 | 15 | 80 | 74.93 | 12.9 | 0.67 | 0.63 | 0.19 | 10.98 | 10.46 | 37.23 | 84.71 | 12 | 20.1 | 13.4 | 8.59 | 399753 |
| 10 | 3 | 30 | 175 | 104.18 | 16.3 | 0.82 | 0.67 | 0.39 | 16.69 | 12.11 | 41.25 | 96.66 | 29 | 20.3 | 23.6 | 14.6 | 925509 |

## Comparação com o baseline CHROME-002 (mesmo procedimento do C002-038)

Valores no formato agora / baseline.

| Elementos | Nós | Captura síncrona (ms) | Export anterior (ms) | Bytes export anterior | Ciclo sessão (ms) | Captura cooperativa (ms) | Motor na base (ms) | Seleção (ms) | Export em fluxo (ms) | Bytes export em fluxo | Diagnóstico ida e volta (ms) |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 100 | 92 | 4.9 / 4.5 | 6.68 / 39.64 | 410090 / 567291 | 19.06 / 29.88 | 3.3 / 2.8 | 2.8 / 2.4 | 35.17 / 35.94 | 54.47 / 86.53 | 250021 / 345737 | 10.55 |
| 1000 | 932 | 26.9 / 18.7 | 65.52 / 155.61 | 4369794 / 6135491 | 55.67 / 56.61 | 21.2 / 18.6 | 21.4 / 11.7 | 45.93 / 36.35 | 80.34 / 191.77 | 2599432 / 3656295 | 17.28 |
| 5000 | 4666 | 141.4 / 117.5 | 369.57 / 928.36 | 20415539 / 31022317 | 191.79 / 148.29 | 96.4 / 71.9 | 81.6 / 54.2 | 61.32 / 62.72 | 316.05 / 586.26 | 12162951 / 18506256 | 44.5 |
| 10000 | 9332 | 362.2 / 392.9 | 1043.46 / 2701.91 | 40146246 / 62106568 | 339.84 / 279.51 | 181.3 / 148.3 | 142.8 / 105.0 | 83.97 / 86.29 | 591.8 / 1067.06 | 23922296 / 37054904 | 77.97 |
