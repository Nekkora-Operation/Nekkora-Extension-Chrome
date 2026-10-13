# Matriz de homologação — execução integration

Gerado automaticamente em 2026-10-13T22:38:49.135456100Z a partir da execução real dos testes.

| ID | Cenário | Resultado | Duração (ms) | Detalhe |
|---|---|---|---|---|
| C-1 | Extensão conecta ao host Java via Native Messaging | APROVADO | 12 |  |
| C-2 | Resposta grande (5 M caracteres) é fragmentada pelo host e remontada pela extensão | APROVADO | 498 |  |
| C-3 | Requisição grande (3 M caracteres) é fragmentada pela extensão e remontada pelo host | APROVADO | 904 |  |
| C-4 | Erros do host chegam à extensão com código estável | APROVADO | 15 |  |
| C-5 | Após desconexão, a extensão reconecta e o host volta a responder | APROVADO | 679 |  |
| C-8 | Contrato real: /lab/simple/form-basics.html | APROVADO | 870 |  |
| C-8 | Contrato real: /lab/intermediate/widgets.html | APROVADO | 617 |  |
| C-8 | Contrato real: /lab/complex/duplicated-ids.html | APROVADO | 1037 |  |
| C-8 | Contrato real: /lab/complex/shadow-iframes.html | APROVADO | 640 |  |
| C-8 | Contrato real: /lab/complex/dropdowns.html | APROVADO | 515 |  |
| C-8 | Contrato real: /clientes?nome=ana&status=ativo | APROVADO | 624 |  |
| C-8 | Contrato real: /generated/large?n=1000 | APROVADO | 1983 |  |
| C-9 | Painel: inspecionar, família, localizadores, reencontro, comparação, exportação e desativação | APROVADO | 2145 |  |
| N-A | Dois botões indistinguíveis: AMBIGUOUS | APROVADO | 365 |  |
| N-B | Elemento removido: NOT_FOUND ou STALE_TARGET | APROVADO | 1341 |  |
| N-C | ID preservado com controle incompatível: IDENTITY_MISMATCH | APROVADO | 785 |  |
| N-D | Elemento em região inacessível: SCOPE_UNAVAILABLE | APROVADO | 879 |  |
| N-E | Contexto insuficiente para comprovar identidade: INSUFFICIENT_EVIDENCE | APROVADO | 833 |  |
| T001 | Input com ID estável: identificação correta | APROVADO | 390 |  |
| T002 | Input sem ID: localizador alternativo | APROVADO | 410 |  |
| T003 | Input sem name: localizador contextual | APROVADO | 1195 |  |
| T004 | ID duplicado: desambiguação ou recusa | APROVADO | 391 |  |
| T005 | IDs dinâmicos: reencontro após renderização | APROVADO | 1402 |  |
| T006 | Texto duplicado: contextualização correta | APROVADO | 355 |  |
| T007 | Botões iguais em tabelas diferentes: família correta | APROVADO | 492 |  |
| T008 | Botões iguais em linhas diferentes: linha correta | APROVADO | 385 |  |
| T009 | Reordenação de tabela: identidade preservada | APROVADO | 1407 |  |
| T010 | Select nativo: opções identificadas | APROVADO | 386 |  |
| T011 | Select com optgroup: grupos identificados | APROVADO | 370 |  |
| T012 | Dropdown personalizado: controle e lista associados | APROVADO | 365 |  |
| T013 | Dropdown com portal: associação lógica correta | APROVADO | 330 |  |
| T014 | Dropdown dinâmico: cobertura declarada | APROVADO | 1108 |  |
| T015 | Modal: contexto correto | APROVADO | 786 |  |
| T016 | Modal fechado e aberto: estado atualizado | APROVADO | 1334 |  |
| T017 | Shadow DOM aberto: inspeção quando acessível | APROVADO | 455 |  |
| T018 | Shadow DOM fechado: limite declarado | APROVADO | 420 |  |
| T019 | Iframe acessível: caminho identificado | APROVADO | 492 |  |
| T020 | Iframe inacessível: limite declarado | APROVADO | 498 |  |
| T021 | Lista virtualizada: não presumir completude | APROVADO | 700 |  |
| T022 | Navegação SPA: contexto atualizado | APROVADO | 805 |  |
| T023 | Alteração de query: parâmetros atualizados | APROVADO | 780 |  |
| T024 | Pesquisa por clique: mudanças observadas | APROVADO | 1448 |  |
| T025 | Elemento removido: referência invalidada | APROVADO | 855 |  |
| T026 | Elemento substituído: identidade revalidada | APROVADO | 932 |  |
| T027 | Botão desabilitado: estado correto | APROVADO | 365 |  |
| T028 | Texto isolado: classificação correta | APROVADO | 569 |  |
| T029 | Label externo: associação correta | APROVADO | 361 |  |
| T030 | Elemento contenteditable: identificação correta | APROVADO | 341 |  |
| T031 | Variação responsiva: reencontro validado | APROVADO | 501 |  |
| T032 | Alvo ambíguo: recusa explícita | APROVADO | 383 |  |
| T033 | Dados sensíveis de teste: ausência na exportação | APROVADO | 603 |  |
| T034 | Destaque rosa: contorno visual correto | APROVADO | 4992 |  |
| T035 | Desativação da inspeção: recursos removidos | APROVADO | 1552 |  |
| T036 | Página grande: desempenho mensurado | APROVADO | 26753 |  |
| T037 | Mutações frequentes: atualização incremental | APROVADO | 5079 |  |
| T038 | Funções locais de teste: resultado verificado | APROVADO | 778 |  |
| T039 | Seleção em modo inspector: nenhuma ação acidental | APROVADO | 2629 |  |
| T040 | Alteração semântica com ID constante: identidade incompatível detectada | APROVADO | 814 |  |

Total: 58 · aprovados: 58 · não aprovados: 0
