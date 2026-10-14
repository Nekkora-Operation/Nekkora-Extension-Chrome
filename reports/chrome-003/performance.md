# CHROME-003 — desempenho medido

Gerado em 2026-10-14T18:14:37.419510700Z · Chrome for Testing 155.0.8059.39 · Java 21.0.12 · Windows 11 10.0

Páginas geradas `/generated/frames?count=N&depth=D` (mesma origem). Tempos de ida e volta medidos pelo WebDriver (incluem o harness); tempos do motor medidos no Java.

| iframes | depth | frames | nodes | discoveryEndToEndMs | captureMs | graphMs | protectionMs | readinessMs | diagnoseEndToEndMs | accessPathEndToEndMs | resolveMs | navigationUpdateMs | observers | hostHeapUsedMb | pageHeapUsedMb | exportMs | exportBytes |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 1 | 1 | 14 | 70.85 | 6.6 | 0.15 | 0.2 | 0.11 | 13.46 | 10.95 | 26.5 | 67.88 | 2 | 11.4 | 2.9 | 6.11 | 109857 |
| 5 | 1 | 5 | 50 | 71.15 | 10.6 | 0.3 | 0.38 | 0.15 | 9.47 | 10.96 | 24.35 | 60.5 | 6 | 11.6 | 5.2 | 7.79 | 311164 |
| 10 | 1 | 10 | 95 | 74.58 | 9.1 | 0.5 | 0.49 | 0.18 | 10.15 | 11.22 | 27.12 | 64.66 | 11 | 11.7 | 8.0 | 11.96 | 563606 |
| 25 | 1 | 25 | 230 | 99.3 | 18.6 | 2.63 | 1.5 | 0.43 | 15.89 | 13.54 | 32.53 | 87.54 | 26 | 12.0 | 16.2 | 23.51 | 1324569 |
| 5 | 3 | 15 | 90 | 83.71 | 12.6 | 0.64 | 0.99 | 0.39 | 12.39 | 16.69 | 31.35 | 67.62 | 14 | 11.7 | 10.9 | 9.36 | 455956 |
| 3 | 5 | 15 | 80 | 75.87 | 10.7 | 1.03 | 1.23 | 0.17 | 13.35 | 12.08 | 34.57 | 85.22 | 12 | 11.7 | 11.1 | 10.04 | 376455 |
| 10 | 3 | 30 | 175 | 115.53 | 24.0 | 1.32 | 0.83 | 0.35 | 16.05 | 14.87 | 31.26 | 73.38 | 29 | 12.0 | 19.3 | 15.21 | 870002 |

## Comparação com o baseline CHROME-002 (mesmo procedimento do C002-038)

Valores no formato agora / baseline.

| Elementos | Nós | Captura síncrona (ms) | Export anterior (ms) | Bytes export anterior | Ciclo sessão (ms) | Captura cooperativa (ms) | Motor na base (ms) | Seleção (ms) | Export em fluxo (ms) | Bytes export em fluxo | Diagnóstico ida e volta (ms) |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 100 | 92 | 4.9 / 4.5 | 13.16 / 39.64 | 574209 / 567291 | 22.0 / 29.88 | 5.0 / 2.8 | 2.3 / 2.4 | 45.21 / 35.94 | 38.41 / 86.53 | 352177 / 345737 | 16.26 |
| 1000 | 932 | 21.3 / 18.7 | 117.23 / 155.61 | 6165895 / 6135491 | 50.3 / 56.61 | 21.9 / 18.6 | 15.5 / 11.7 | 38.68 / 36.35 | 150.9 / 191.77 | 3679980 / 3656295 | 23.07 |
| 5000 | 4666 | 140.5 / 117.5 | 1003.66 / 928.36 | 31156792 / 31022317 | 169.17 / 148.29 | 83.8 / 71.9 | 69.6 / 54.2 | 64.85 / 62.72 | 555.19 / 586.26 | 18606270 / 18506256 | 65.77 |
| 10000 | 9332 | 345.8 / 392.9 | 2661.03 / 2701.91 | 62371009 / 62106568 | 293.59 / 279.51 | 157.6 / 148.3 | 122.4 / 105.0 | 87.75 / 86.29 | 1054.39 / 1067.06 | 37250228 / 37054904 | 101.69 |
