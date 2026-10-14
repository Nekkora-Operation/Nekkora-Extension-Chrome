# Matriz de homologação — execução integration

Gerado automaticamente em 2026-10-14T00:51:08.589313100Z a partir da execução real dos testes.

| ID | Cenário | Resultado | Duração (ms) | Detalhe |
|---|---|---|---|---|
| C-1 | Extensão conecta ao host Java via Native Messaging | APROVADO | 8 |  |
| C-2 | Resposta grande (5 M caracteres) é fragmentada pelo host e remontada pela extensão | APROVADO | 431 |  |
| C-3 | Requisição grande (3 M caracteres) é fragmentada pela extensão e remontada pelo host | APROVADO | 723 |  |
| C-4 | Erros do host chegam à extensão com código estável | APROVADO | 12 |  |
| C-5 | Após desconexão, a extensão reconecta e o host volta a responder | APROVADO | 799 |  |
| C-8 | Contrato real: /lab/simple/form-basics.html | APROVADO | 608 |  |
| C-8 | Contrato real: /lab/intermediate/widgets.html | APROVADO | 561 |  |
| C-8 | Contrato real: /lab/complex/duplicated-ids.html | APROVADO | 981 |  |
| C-8 | Contrato real: /lab/complex/shadow-iframes.html | APROVADO | 591 |  |
| C-8 | Contrato real: /lab/complex/dropdowns.html | APROVADO | 421 |  |
| C-8 | Contrato real: /clientes?nome=ana&status=ativo | APROVADO | 607 |  |
| C-8 | Contrato real: /generated/large?n=1000 | APROVADO | 1669 |  |
| C-9 | Painel: inspecionar, família, localizadores, reencontro, comparação, exportação e desativação | APROVADO | 2018 |  |
| C002-001 | Botão inicialmente inativo | APROVADO | 733 |  |
| C002-002 | Ativação: captura inicial, rastreamento, ouvintes e texto 'Desativar inspeção' | APROVADO | 812 |  |
| C002-003 | Desativação: ouvintes, destaques e sessão liberados | APROVADO | 1306 |  |
| C002-004 | Clique duplo: uma única ativação | APROVADO | 2429 |  |
| C002-005 | Falha na ativação nunca indica ativo | APROVADO | 3181 |  |
| C002-006 | Captura inicial automática | APROVADO | 399 |  |
| C002-007 | Sem recapturas desnecessárias (página parada) | APROVADO | 4370 |  |
| C002-008 | Mutação simples gera delta (sem captura completa) | APROVADO | 450 |  |
| C002-009 | Múltiplas mutações agrupadas em poucos deltas | APROVADO | 471 |  |
| C002-010 | Seleção logo após mutação usa o estado atualizado (barreira) | APROVADO | 461 |  |
| C002-011 | Dez seleções sem mudanças não geram dez capturas completas | APROVADO | 666 |  |
| C002-012 / C002-013 | Fechar e reabrir o painel preserva o estado e a captura | APROVADO | 1210 |  |
| C002-014 | Mudança de aba: sessões independentes | APROVADO | 817 |  |
| C002-015 | Recarga da página: nova base, inspeção preservada | APROVADO | 625 |  |
| C002-016 | SPA: rota, query e fragmento sem perder o estado válido | APROVADO | 1109 |  |
| C002-017 | Perda de delta: ressincronização controlada e limitada | APROVADO | 3485 |  |
| C002-018 | Reinício do motor Java: degradado, reconexão e nova base | APROVADO | 2314 |  |
| C002-019 | Reinício do service worker: estado restaurado sem observadores duplicados | APROVADO | 1275 |  |
| C002-020 | Desativação remove todos os observadores (inclusive shadow roots) | APROVADO | 2051 |  |
| C002-021 | Exportação manual em DEV (sincronizada, contrato válido) | APROVADO | 1135 |  |
| C002-022 | PRD: recording.session.finished sincroniza, consolida, valida e persiste | APROVADO | 512 |  |
| C002-023 | Finalização duplicada e simultânea: idempotente | APROVADO | 455 |  |
| C002-024 | Finalização com delta pendente: mutação incluída | APROVADO | 431 |  |
| C002-025 | Falha de exportação explícita e nova tentativa | APROVADO | 799 |  |
| C002-026 | Novo shadow root (inclusive aninhado e conteúdo alterado) incorporado automaticamente | APROVADO | 776 |  |
| C002-027 | Shadow root removido: nós e observador descartados | APROVADO | 588 |  |
| C002-028 | attachShadow() em host já conectado: detectado pela reconciliação (latência medida) | APROVADO | 1541 |  |
| C002-029 | Iframe de mesma origem: capturado pelo documento principal, sem duplicidade | APROVADO | 573 |  |
| C002-030 | Iframe de outra origem local permitida (porta +1), aninhado e com Shadow DOM | APROVADO | 884 |  |
| C002-031 | Iframe dinâmico: inserido, removido e documento substituído | APROVADO | 1012 |  |
| C002-032 | Iframe sem permissão (127.0.0.2) e sandbox: não inspecionados, limite declarado | APROVADO | 550 |  |
| C002-033 | AccName: aria-labelledby recursivo (referência não é seguida em segundo nível) | APROVADO | 418 |  |
| C002-034 | AccName: aria-labelledby múltiplo e autorreferência | APROVADO | 482 |  |
| C002-035 | AccName: referência circular termina | APROVADO | 398 |  |
| C002-036 | AccName: elemento oculto referenciado por aria-labelledby | APROVADO | 371 |  |
| C002-037 | AccName: botões com ícone, title, controles e HTML-AAM (comparação com o Chrome) | APROVADO | 1141 |  |
| C002-038 / C002-039 | 100 a 20.000 elementos: captura cooperativa, seleção e exportação (antes × depois) | APROVADO | 32502 |  |
| C002-039 | Captura cooperativa: mutações durante a travessia entram no primeiro delta (base + delta) | APROVADO | 2220 |  |
| C002-040 | Memória: página pequena, média, grande e três grandes (heap usado/comprometido/máximo e RSS) | APROVADO | 7687 |  |
| C002-041 | Sessão prolongada com mutações contínuas (10/s): estável e sem acúmulo | APROVADO | 305683 |  |
| C002-042 | Descarte de sessão: aba fechada libera navegador e motor | APROVADO | 641 |  |
| C002-043 | Destaque acompanha a re-renderização (reencontro automático comprovado) | APROVADO | 592 |  |
| C002-044 | Ambiguidade após mutação: sem destaque e sem escolha silenciosa | APROVADO | 496 |  |
| C002-045 | PageMap incremental × completo: equivalência semântica | APROVADO | 1604 |  |
| C002-046 | Contrato v0.1.0 preservado e contratos de sessão/inspeção/finalização/exportação | APROVADO | 1296 |  |
| C002-047 | Painel lateral nativo: abertura, botão único, captura automática e desativação | APROVADO | 2657 |  |
| C002-048 | Recursos DEV não expostos em PRD | APROVADO | 959 |  |
| C002-049 | Término da inspeção sem gravação não exporta | APROVADO | 498 |  |
| C002-050 | Conteúdo sensível alterado por mutação não é exportado | APROVADO | 461 |  |
| C002-HOM | Chrome convencional (§30): host, extensão sem compactação, painel nativo, captura automática, seleção, destaque, exportação e liberação | APROVADO | 2589 |  |
| C002-RAP | Alterações rápidas a 10, 50 e 100 mutações/s: agrupadas, sem ressincronização, estado equivalente | APROVADO | 16486 |  |
| N-A | Dois botões indistinguíveis: AMBIGUOUS | APROVADO | 374 |  |
| N-B | Elemento removido: NOT_FOUND ou STALE_TARGET | APROVADO | 1313 |  |
| N-C | ID preservado com controle incompatível: IDENTITY_MISMATCH | APROVADO | 768 |  |
| N-D | Elemento em região inacessível: SCOPE_UNAVAILABLE | APROVADO | 887 |  |
| N-E | Contexto insuficiente para comprovar identidade: INSUFFICIENT_EVIDENCE | APROVADO | 780 |  |
| T001 | Input com ID estável: identificação correta | APROVADO | 390 |  |
| T002 | Input sem ID: localizador alternativo | APROVADO | 399 |  |
| T003 | Input sem name: localizador contextual | APROVADO | 1049 |  |
| T004 | ID duplicado: desambiguação ou recusa | APROVADO | 366 |  |
| T005 | IDs dinâmicos: reencontro após renderização | APROVADO | 1437 |  |
| T006 | Texto duplicado: contextualização correta | APROVADO | 338 |  |
| T007 | Botões iguais em tabelas diferentes: família correta | APROVADO | 457 |  |
| T008 | Botões iguais em linhas diferentes: linha correta | APROVADO | 361 |  |
| T009 | Reordenação de tabela: identidade preservada | APROVADO | 1374 |  |
| T010 | Select nativo: opções identificadas | APROVADO | 352 |  |
| T011 | Select com optgroup: grupos identificados | APROVADO | 340 |  |
| T012 | Dropdown personalizado: controle e lista associados | APROVADO | 360 |  |
| T013 | Dropdown com portal: associação lógica correta | APROVADO | 310 |  |
| T014 | Dropdown dinâmico: cobertura declarada | APROVADO | 1166 |  |
| T015 | Modal: contexto correto | APROVADO | 816 |  |
| T016 | Modal fechado e aberto: estado atualizado | APROVADO | 1290 |  |
| T017 | Shadow DOM aberto: inspeção quando acessível | APROVADO | 407 |  |
| T018 | Shadow DOM fechado: limite declarado | APROVADO | 409 |  |
| T019 | Iframe acessível: caminho identificado | APROVADO | 484 |  |
| T020 | Iframe inacessível: limite declarado | APROVADO | 420 |  |
| T021 | Lista virtualizada: não presumir completude | APROVADO | 705 |  |
| T022 | Navegação SPA: contexto atualizado | APROVADO | 804 |  |
| T023 | Alteração de query: parâmetros atualizados | APROVADO | 779 |  |
| T024 | Pesquisa por clique: mudanças observadas | APROVADO | 1361 |  |
| T025 | Elemento removido: referência invalidada | APROVADO | 775 |  |
| T026 | Elemento substituído: identidade revalidada | APROVADO | 899 |  |
| T027 | Botão desabilitado: estado correto | APROVADO | 342 |  |
| T028 | Texto isolado: classificação correta | APROVADO | 502 |  |
| T029 | Label externo: associação correta | APROVADO | 327 |  |
| T030 | Elemento contenteditable: identificação correta | APROVADO | 336 |  |
| T031 | Variação responsiva: reencontro validado | APROVADO | 414 |  |
| T032 | Alvo ambíguo: recusa explícita | APROVADO | 390 |  |
| T033 | Dados sensíveis de teste: ausência na exportação | APROVADO | 576 |  |
| T034 | Destaque rosa: contorno visual correto | APROVADO | 4982 |  |
| T035 | Desativação da inspeção: recursos removidos | APROVADO | 1466 |  |
| T036 | Página grande: desempenho mensurado | APROVADO | 23124 |  |
| T037 | Mutações frequentes: atualização incremental | APROVADO | 5070 |  |
| T038 | Funções locais de teste: resultado verificado | APROVADO | 750 |  |
| T039 | Seleção em modo inspector: nenhuma ação acidental | APROVADO | 2612 |  |
| T040 | Alteração semântica com ID constante: identidade incompatível detectada | APROVADO | 784 |  |

Total: 109 · aprovados: 109 · não aprovados: 0
