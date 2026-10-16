# CHROME-SECURITY-001: capacidade do motor e desempenho (antes × depois)

## Método

As medições "antes" e "depois" usam o **mesmo código de teste**, o mesmo corpus e a mesma máquina (Windows 11,
Chrome for Testing 155.0.8059.39, Java 21.0.12), uma após a outra:

- **antes:** worktree da `main` `8f3326b` (`nekkora-privacy/1`). A classe `CapabilityMeasureIT` foi copiada sem
  alteração, usando só comandos que existem nas duas versões, e as 12 páginas do laboratório `chrome-security-001`
  também foram copiadas (são HTML estático);
- **depois:** branch `feat/chrome-security-001` (`nekkora-privacy/2`);
- as classes de desempenho que já existiam (`PerformanceIT`, `Chrome002PerformanceIT`, `Chrome003PerformanceIT`,
  `Real001PerformanceIT`, `Studio003InteractionsIT`, `Studio005EvidenceIT`) rodaram nos dois builds. As evidências
  ficam em `reports/performance.md`, `reports/chrome-002|chrome-003|real-001/performance.md` e
  `reports/evidence/SEC001-capability-{antes,depois}.json`.

Nenhum número foi fixado como meta: a comparação é com a linha de base medida.

## Capacidade do motor (34 páginas, `CapabilityMeasureIT`)

| Métrica | Antes | Depois | Observação |
|---|---:|---:|---|
| Elementos classificados | 925 | 925 | a classificação estrutural é a mesma |
| Interativos | 359 | 359 | |
| Com nome legível | 350 | **181** | o nome sai só se for do vocabulário |
| Localizador principal único | 359 | 351 | 8 passam a ter o principal declarado `AMBIGUOUS_MATCH` |
| Localizador principal verificável (CSS ou XPath) | 297 | **330** | menos estratégias só do motor (`ROLE_NAME` por texto) |
| Não verificáveis (principal só do motor) | 62 | 29 | |
| Sem nenhum localizador único (ambíguos) | 0 | 0 | sempre há ao menos um único, mesmo que posicional e instável |
| **Alvos resolvidos na página inalterada** | **355** | **178** | 143 `AMBIGUOUS` e 38 `INSUFFICIENT_EVIDENCE` (antes: 4 `AMBIGUOUS`). Nenhum resolvido para outro elemento |
| Tempo de captura (mediana das páginas, ms) | 9,1 | 9,9 | soma das medianas: 337 → 349 ms |
| Tamanho do snapshot (soma, bytes) | 678 798 | 694 973 | +2,4% (`textPresent`, `textSuppressed`, `textRevision`, `refs`) |
| Tamanho do PageMap (soma, bytes) | 4 057 282 | 3 253 591 | −19,8% |

### A queda de reidentificação (178 de 359)

É a principal regressão semântica da missão, e ela não é apresentada como aprovação automática. A resolução do motor
**reidentifica** o alvo pela identidade lógica (assinatura). Por desenho anterior (`NegativeCasesIT.caseA`), ela
nunca usa o id temporário do nó como identidade: um alvo indistinguível no registro dá `AMBIGUOUS` mesmo com a página
inalterada. Sem o texto da página, muitos alvos deixaram de ter característica própria: opções e itens de lista,
botões repetidos ("Excluir" em cada linha), campos sem id do vocabulário. As maiores perdas estão em
`complex/dropdowns.html` (33 → 6), `intermediate/widgets.html` (24 → 10), `simple/form-basics.html` (26 → 13),
`intermediate/forms-groups.html` (16 → 4), `complex/duplicated-ids.html` (14 → 2) e `intermediate/tables.html` (9 → 0).

**Uso do Recorder:** a gravação de etapas não depende dessa reidentificação. Cada etapa leva os localizadores
verificados no momento do clique (351 de 359 com principal único), e a homologação real grava o processo completo. O
que fica mais fraco é reencontrar automaticamente o alvo destacado depois de mudanças na página e reproduzir o alvo em
outra sessão quando só o texto o distinguia.

**Alternativa para a revisão (não implementada por mudar um contrato do motor):** aceitar a **continuidade técnica do
nó** como evidência de identidade dentro da mesma sessão de página. O id temporário do content script é um contador
monotônico por elemento e nunca é reutilizado na sessão, então mesmo id e mesma sessão significam o mesmo nó do DOM.
Isso recuperaria a resolução na página inalterada sem nenhum texto, mas muda a semântica hoje testada pelo
`caseA` ("indistinguível ⇒ AMBIGUOUS"). Fica como decisão da revisão.

## Desempenho

### Captura e motor (`PerformanceIT`, páginas sintéticas)

| Elementos | Captura antes (ms) | Captura depois (ms) | Snapshot antes/depois (KB) | Classificação Java antes/depois (ms) | Seleção ponta a ponta antes/depois (ms) |
|---:|---:|---:|---:|---:|---:|
| 100 | 5,6 | 4,8 | 49 / 52 | 0,17 / 0,17 | 79,4 / 56,2 |
| 1 000 | 25,7 | 22,8 | 501 / 528 | 1,2 / 1,49 | 99,0 / 93,8 |
| 5 000 | 138,1 | 134,9 | 2 526 / 2 655 | 10,9 / 5,9 | 277,9 / 278,0 |
| 10 000 | 399,4 | 378,0 | 5 061 / 5 315 | 24,8 / 15,3 | 489,6 / 430,5 |

### Sessão contínua e exportação (`Chrome002PerformanceIT`)

| Nós | Captura síncrona antes/depois (ms) | Captura cooperativa antes/depois (ms) | Maior fatia antes/depois (ms) | Exportação em fluxo antes/depois (ms) |
|---:|---:|---:|---:|---:|
| 92 | 5,1 / 6,5 | 3,1 / 3,1 | 1,6 / 1,6 | 59,5 / 49,1 |
| 932 | 22,9 / 32,0 | 19,9 / 20,2 | 8,0 / 8,2 | 145,3 / 125,9 |
| 4 666 | 127,7 / 142,2 | 77,8 / 103,0 | 8,2 / 10,8 | 565,3 / 320,6 |
| 9 332 | 345,9 / 380,1 | 150,5 / 174,0 | 9,7 / 9,1 | 1 019,4 / 739,9 |
| 18 666 | 1 101,7 / 1 238,7 | 346,1 / 342,5 | 9,9 / 9,7 | 2 562,1 / 1 494,9 |

A captura cooperativa, usada na sessão contínua, mantém fatias abaixo de 11 ms (a página não trava). A síncrona
ficou até 12% mais lenta nas páginas grandes.

### Frames, proteção e exportação (`Chrome003PerformanceIT`)

| Nós | Captura síncrona antes/depois (ms) | Motor na base antes/depois (ms) | Exportação em fluxo antes/depois (ms) | Bytes exportados antes/depois |
|---:|---:|---:|---:|---:|
| 92 | 4,5 / 4,9 | 2,4 / 2,8 | 86,5 / 54,5 | 345 737 / 250 021 |
| 932 | 18,7 / 26,9 | 11,7 / 21,4 | 191,8 / 80,3 | 3 656 295 / 2 599 432 |
| 4 666 | 117,5 / 141,4 | 54,2 / 81,6 | 586,3 / 316,1 | 18 506 256 / 12 162 951 |
| 9 332 | 392,9 / 362,2 | 105,0 / 142,8 | 1 067,1 / 591,8 | 37 054 904 / 23 922 301 |

O "motor na base" cresce porque o host **reaplica** a política a todo snapshot (defesa em profundidade: a extensão pode
ser antiga), e ali não há cache de propósito, para nenhum texto ficar retido no host. Com 30 frames, a descoberta ponta
a ponta caiu (125 → 104 ms).

### Seleção em página externa (`Real001PerformanceIT`, mediana)

| Métrica | Antes (ms) | Depois (ms) |
|---|---:|---:|
| 5 000 · início da sessão | 271,6 | 263,6 |
| 5 000 · seleção fria | 77,6 | 51,9 |
| 5 000 · seleção aquecida | 69,2 | 39,3 |
| 10 000 · início da sessão | 607,8 | 579,1 |
| 10 000 · seleção fria | 114,5 | 77,1 |
| 10 000 · seleção aquecida | 87,1 | 52,1 |

### Etapas e evidências (Recorder ⇄ Studio)

| Métrica | Antes | Depois |
|---|---:|---:|
| Clique → etapa no Studio, p50 / p95 (ms, S003) | 815 / 825 | 818 / 826 |
| Tamanho médio do evento de etapa (bytes) | 2 376 | 2 069 |
| Correlação no SW, p50 (ms) | 11 | 13 |
| Clique → etapa com evidências, p50 (ms, S005) | 809 | 815 |
| Maior evento de evidência (bytes) | 695 | 681 |
| Etapas perdidas | 0 | 0 |

A latência de entrega das etapas é dominada pela janela de silêncio do Recorder e não mudou.

### Latência da normalização (política JS, sem cache, Node, máquina ociosa)

| Operação | µs por chamada |
|---|---:|
| `interfaceText` | 1,63 |
| `urlTemplate` | 4,23 |
| `attribute` | 1,47 |
| `expressionSafe` | 2,11 |

## Investigação da degradação de captura

Na primeira medição completa (sem cache), a captura ficou até 30% mais lenta: 10 mil elementos de 399 para 453 ms, e
captura cooperativa de 18,7 mil nós de 346 para 450 ms. A causa é a política avaliada para cada nó e cada atributo,
com os mesmos rótulos, ids e classes repetidos milhares de vezes. A correção foi uma memória de decisões
**só no content script**: limitada a 8 192 entradas, esvaziada ao encher e só para valores de até 256 caracteres.
Os valores já estão na memória da própria página. No service worker e no host não há cache, para nenhum texto ficar
retido lá. Depois dela, a captura voltou à linha de base (378 ms para 10 mil elementos; 342 ms na cooperativa).

## Memória e host

- Heap do host na sessão contínua: 90/376 MB usados/comprometidos com 18,7 mil nós (antes: 109,8/428 MB).
- RSS do host no maior caso: a coluna "RSS host" de `reports/chrome-002/performance.md`.
- Studio: a interface recebe eventos menores. Não foi observada mudança de latência no Floating Recorder (homologação).
