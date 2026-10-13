# Matriz de homologação — execução integration

Gerado automaticamente em 2026-10-13T21:27:43.137260600Z a partir da execução real dos testes.

| ID | Cenário | Resultado | Duração (ms) | Detalhe |
|---|---|---|---|---|
| C-1 | Extensão conecta ao host Java via Native Messaging | APROVADO | 10 |  |
| C-2 | Resposta grande (5 M caracteres) é fragmentada pelo host e remontada pela extensão | APROVADO | 427 |  |
| C-3 | Requisição grande (3 M caracteres) é fragmentada pela extensão e remontada pelo host | APROVADO | 722 |  |
| C-4 | Erros do host chegam à extensão com código estável | APROVADO | 13 |  |
| C-5 | Após desconexão, a extensão reconecta e o host volta a responder | APROVADO | 670 |  |
| C-8 | Contrato real: /lab/simple/form-basics.html | APROVADO | 798 |  |
| C-8 | Contrato real: /lab/intermediate/widgets.html | APROVADO | 689 |  |
| C-8 | Contrato real: /lab/complex/duplicated-ids.html | APROVADO | 1089 |  |
| C-8 | Contrato real: /lab/complex/shadow-iframes.html | APROVADO | 608 |  |
| C-8 | Contrato real: /lab/complex/dropdowns.html | APROVADO | 440 |  |
| C-8 | Contrato real: /clientes?nome=ana&status=ativo | APROVADO | 617 |  |
| C-8 | Contrato real: /generated/large?n=1000 | APROVADO | 1752 |  |
| C-9 | Painel: inspecionar, família, localizadores, reencontro, comparação, exportação e desativação | APROVADO | 2096 |  |
| N-A | Dois botões indistinguíveis: AMBIGUOUS | APROVADO | 405 |  |
| N-B | Elemento removido: NOT_FOUND ou STALE_TARGET | APROVADO | 1254 |  |
| N-C | ID preservado com controle incompatível: IDENTITY_MISMATCH | APROVADO | 792 |  |
| N-D | Elemento em região inacessível: SCOPE_UNAVAILABLE | APROVADO | 866 |  |
| N-E | Contexto insuficiente para comprovar identidade: INSUFFICIENT_EVIDENCE | APROVADO | 776 |  |
| T001 | Input com ID estável: identificação correta | APROVADO | 388 |  |
| T002 | Input sem ID: localizador alternativo | APROVADO | 412 |  |
| T003 | Input sem name: localizador contextual | APROVADO | 1031 |  |
| T004 | ID duplicado: desambiguação ou recusa | APROVADO | 397 |  |
| T005 | IDs dinâmicos: reencontro após renderização | APROVADO | 1392 |  |
| T006 | Texto duplicado: contextualização correta | APROVADO | 327 |  |
| T007 | Botões iguais em tabelas diferentes: família correta | APROVADO | 470 |  |
| T008 | Botões iguais em linhas diferentes: linha correta | APROVADO | 375 |  |
| T009 | Reordenação de tabela: identidade preservada | APROVADO | 1407 |  |
| T010 | Select nativo: opções identificadas | APROVADO | 374 |  |
| T011 | Select com optgroup: grupos identificados | APROVADO | 383 |  |
| T012 | Dropdown personalizado: controle e lista associados | APROVADO | 341 |  |
| T013 | Dropdown com portal: associação lógica correta | APROVADO | 329 |  |
| T014 | Dropdown dinâmico: cobertura declarada | APROVADO | 1149 |  |
| T015 | Modal: contexto correto | APROVADO | 810 |  |
| T016 | Modal fechado e aberto: estado atualizado | APROVADO | 1291 |  |
| T017 | Shadow DOM aberto: inspeção quando acessível | APROVADO | 453 |  |
| T018 | Shadow DOM fechado: limite declarado | APROVADO | 369 |  |
| T019 | Iframe acessível: caminho identificado | APROVADO | 454 |  |
| T020 | Iframe inacessível: limite declarado | APROVADO | 416 |  |
| T021 | Lista virtualizada: não presumir completude | APROVADO | 707 |  |
| T022 | Navegação SPA: contexto atualizado | APROVADO | 774 |  |
| T023 | Alteração de query: parâmetros atualizados | APROVADO | 776 |  |
| T024 | Pesquisa por clique: mudanças observadas | APROVADO | 1393 |  |
| T025 | Elemento removido: referência invalidada | APROVADO | 825 |  |
| T026 | Elemento substituído: identidade revalidada | APROVADO | 930 |  |
| T027 | Botão desabilitado: estado correto | APROVADO | 375 |  |
| T028 | Texto isolado: classificação correta | APROVADO | 512 |  |
| T029 | Label externo: associação correta | APROVADO | 330 |  |
| T030 | Elemento contenteditable: identificação correta | APROVADO | 370 |  |
| T031 | Variação responsiva: reencontro validado | APROVADO | 421 |  |
| T032 | Alvo ambíguo: recusa explícita | APROVADO | 377 |  |
| T033 | Dados sensíveis de teste: ausência na exportação | APROVADO | 583 |  |
| T034 | Destaque rosa: contorno visual correto | APROVADO | 4995 |  |
| T035 | Desativação da inspeção: recursos removidos | APROVADO | 1424 |  |
| T036 | Página grande: desempenho mensurado | APROVADO | 24274 |  |
| T037 | Mutações frequentes: atualização incremental | APROVADO | 5127 |  |
| T038 | Funções locais de teste: resultado verificado | APROVADO | 760 |  |
| T039 | Seleção em modo inspector: nenhuma ação acidental | APROVADO | 2657 |  |
| T040 | Alteração semântica com ID constante: identidade incompatível detectada | APROVADO | 789 |  |

Total: 58 · aprovados: 58 · não aprovados: 0
