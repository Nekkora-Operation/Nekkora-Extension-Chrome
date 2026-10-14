# Desempenho e memória (CHROME-002)

Medições reais no Chrome for Testing 155 + Java 21.0.12, Windows 11 (`Chrome002PerformanceIT`).
Dados completos: [`reports/chrome-002/performance.md`](../../reports/chrome-002/performance.md) e `.json`;
linha de base da CHROME-001: [`reports/chrome-002/baseline/`](../../reports/chrome-002/baseline/).

## Critérios (definidos antes da medição)

Os limites da CHROME-001 (`PerformanceIT`) **não foram aumentados** e continuam verificados. Novos:

| Critério | Limite |
|---|---|
| Maior fatia contínua da captura-base cooperativa (10k e 20k) | ≤ 50 ms |
| Bloqueio medido na página (batimento de 5 ms) com captura cooperativa | menor que o da captura síncrona (≥ 10k) |
| Ciclo da captura-base com sessão (10k) | ≤ 8.000 ms |
| Seleção com snapshot reutilizado (10k) | ≤ 1.500 ms, sem captura completa |
| Exportação em fluxo (10k) | ≤ exportação CHROME-001 medida na mesma execução × 1,10 |
| Memória após liberar sessões + GC | heap ≤ inicial + 64 MB; capturas retidas = 0 |
| Três abas grandes | acréscimo de heap ≤ 3,5 × o de uma aba grande |
| Alterações a 10/50/100 por s | 0 ressincronizações; deltas agrupados; processamento no host ≤ 200 ms; estado = captura completa |
| Sessão prolongada (5/15/30 min, 10 mutações/s) | sempre ACTIVE; 0 ressincronizações e 0 recusas; heap no fim ≤ heap no 1º min + 64 MB; registro de nós limitado |

## Antes × depois (execução da homologação)

| Elementos | Bloqueio síncrono (ms) | Bloqueio cooperativo (ms) | Maior fatia (ms) | Seleção anterior* (ms) | Seleção reutilizada (ms) | Export anterior (ms) | Export em fluxo (ms) |
|---|---|---|---|---|---|---|---|
| 100 | 6,2 | 6,7 | 1,5 | 46 | 35,7 | 40,1 | 94,7 |
| 1.000 | 25,4 | 19,1 | 8,3 | 89 | 41,1 | 156,3 | 186,7 |
| 5.000 | 141,3 | 22,2 | 8,4 | 222 | 61,2 | 897,3 | 522,2 |
| 10.000 | 358,9 | 21,5 | 8,3 | 350 | 79,2 | 2.410,5 | 1.070,1 |
| 20.000 | 1.105,6 | 26,1 | 8,9 | — | 166,5 | 13.344,3 | 2.243,7 |

\* seleção da CHROME-001 (captura completa a cada clique), de `reports/performance.md` na mesma
máquina. Em páginas pequenas a diferença é ruído (dezenas de ms); a partir de 5.000 elementos a
reutilização do snapshot e a captura cooperativa dominam.

Memória do processo Java (heap após GC, `host.memory`; RSS por `Get-Process`):

| Cenário | Heap usado (MB) | Heap comprometido (MB) | RSS (MB) |
|---|---|---|---|
| Ocioso | 9,0 | — | 106,4 |
| 1 aba, 100 elementos | 9,1 | 56 | 111,2 |
| 1 aba, 1.000 | 10,0 | 40 | 92,8 |
| 1 aba, 10.000 | 18,4 | 108 | 143,0 |
| 3 abas de 10.000 | 37,2 | 160 | 199,4 |
| Após liberar as sessões | 9,1 | — | 154,9 |

Heap máximo da JVM: 7.992 MB (padrão = ¼ da RAM). A CHROME-001 reportava ~550–830 MB de heap
**usado sem GC** com 6 capturas por aba; o número comparável aqui é o heap após GC (o que de fato é retido).

## Trade-offs

- **Captura cooperativa** troca latência total por responsividade: o tempo total da base é parecido ou
  menor (10k: 142 ms de trabalho em 11 fatias), mas a página nunca fica bloqueada mais de ~9 ms seguidos.
  Em troca, a base não é um instante único (política `BASE_PLUS_DELTA`).
- **Referência fixada** retém uma captura a mais por aba para que comparações sobrevivam aos deltas.
- **Reconciliação de shadow roots a cada 1 s** custa uma varredura `querySelectorAll('*')` por segundo
  enquanto a sessão está ativa (medida dentro do orçamento nas páginas de 10k); detecta `attachShadow()`
  tardio sem monkeypatch.
- **Geometria** só é atualizada em rolagem/redimensionamento e na exportação (não a cada quadro).
- **Exportação paralela** usa `núcleos − 1` threads por padrão (`parallelism`), configurável.

## Alterações rápidas (5 s por taxa, 50 linhas)

| Taxa | Mutações | Registros brutos | Deltas | Ressincronizações | Maior delta no navegador (ms) | Maior / média no host (ms) |
|---|---|---|---|---|---|---|
| 10/s | 50 | 103 | 9 | 0 | 0,6 | 6,55 / 2,02 |
| 50/s | 250 | 511 | 10 | 0 | 1,0 | 3,11 / 2,10 |
| 100/s | 501 | 1.023 | 10 | 0 | 2,0 | 2,63 / 2,17 |

## Longa duração (10 mutações/s, página `rapid.html`)

| Duração | Mutações | Deltas | Ressinc. | Recusas | Capturas completas | Heap 1º min → fim (MB) | Registro máx. / inicial |
|---|---|---|---|---|---|---|---|
| 5 min | 3.051 | 510 | 0 | 0 | 1 | 9,2 → 9,3 | 281 / 271 |
| 15 min | 9.162 | 1.531 | 0 | 0 | 1 | 7,8 → 7,9 | 306 / 271 |
| 30 min | 18.011 | 3.014 | 0 | 0 | 1 | 7,8 → 8,0 | 331 / 271 |

Ao fim de cada sessão o estado incremental tinha o mesmo número de nós que uma captura completa.
