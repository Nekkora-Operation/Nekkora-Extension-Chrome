# STUDIO-005 — Desempenho

## Medição real (`Studio005EvidenceIT.s005Performance`, Chrome for Testing, Windows)

15 cliques consecutivos na mesma página, com e sem `evidence/1` (arquivo `reports/evidence/S005-performance.json`):

| Medida | Sem `evidence/1` | Com `evidence/1` |
|---|---|---|
| clique → etapa no Studio, p50 | 811 ms | 810 ms |
| clique → etapa no Studio, p95 | 849 ms | 817 ms |
| clique → etapa no Studio, máximo | 910 ms | 835 ms |
| eventos de evidência | — | 16 (15 conclusões de janela + 1 aviso ambíguo) |
| maior evento de evidência | — | 716 bytes |
| latência de emissão no service worker (p50/p95/máx) | — | 1 / 2 / 2 ms |
| `MutationObserver` ativos em repouso | — | 0 |

A diferença está dentro do ruído. O tempo até a etapa é dominado pela janela de 400 ms do contexto posterior
(STUDIO-003) e pela correlação no motor Java. A evidência sai depois da etapa e **não a atrasa**.

## Custos por desenho

- **Sem `MutationObserver` permanente**: só com janela aberta (no máximo 8), desligado em seguida.
- **Varredura limitada**: até 40 regiões, seletor fechado, debounce de 100 ms e leitura de texto truncada em 2.000
  caracteres para o hash local.
- **Limites**: 12 evidências por janela, 40 por etapa à espera, 200 pendentes por gravação, 300 vínculos retidos e
  `latencyMs` com as últimas 200 amostras.
- **Sem captura de tela, sem leitura de HTML e sem PageMap por evento.**

## Regressão

A suíte do navegador (`*IT`, exceto `*LiveWebIT`/`*ManualIT`) passou em 6 grupos: 224/224 (211 anteriores + 13 novos).
Os testes de desempenho existentes (CHROME-002/003, REAL-001 e STUDIO-003) continuam dentro dos limites.
