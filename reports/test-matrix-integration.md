# Matriz de homologação — execução integration

Gerado automaticamente em 2026-10-14T01:38:23.624921900Z a partir da execução real dos testes.

| ID | Cenário | Resultado | Duração (ms) | Detalhe |
|---|---|---|---|---|
| C-1 | Extensão conecta ao host Java via Native Messaging | APROVADO | 9 |  |
| C-2 | Resposta grande (5 M caracteres) é fragmentada pelo host e remontada pela extensão | APROVADO | 401 |  |
| C-3 | Requisição grande (3 M caracteres) é fragmentada pela extensão e remontada pelo host | APROVADO | 745 |  |
| C-4 | Erros do host chegam à extensão com código estável | APROVADO | 11 |  |
| C-5 | Após desconexão, a extensão reconecta e o host volta a responder | APROVADO | 802 |  |
| C-8 | Contrato real: /lab/simple/form-basics.html | APROVADO | 592 |  |
| C-8 | Contrato real: /lab/intermediate/widgets.html | APROVADO | 592 |  |
| C-8 | Contrato real: /lab/complex/duplicated-ids.html | APROVADO | 1004 |  |
| C-8 | Contrato real: /lab/complex/shadow-iframes.html | APROVADO | 566 |  |
| C-8 | Contrato real: /lab/complex/dropdowns.html | APROVADO | 444 |  |
| C-8 | Contrato real: /clientes?nome=ana&status=ativo | APROVADO | 554 |  |
| C-8 | Contrato real: /generated/large?n=1000 | APROVADO | 1619 |  |
| C-9 | Painel: inspecionar, família, localizadores, reencontro, comparação, exportação e desativação | APROVADO | 2100 |  |
| C002-001 | Botão inicialmente inativo | APROVADO | 734 |  |
| C002-002 | Ativação: captura inicial, rastreamento, ouvintes e texto 'Desativar inspeção' | APROVADO | 833 |  |
| C002-003 | Desativação: ouvintes, destaques e sessão liberados | APROVADO | 1245 |  |
| C002-004 | Clique duplo: uma única ativação | APROVADO | 2405 |  |
| C002-005 | Falha na ativação nunca indica ativo | APROVADO | 3167 |  |
| C002-006 | Captura inicial automática | APROVADO | 378 |  |
| C002-007 | Sem recapturas desnecessárias (página parada) | APROVADO | 4422 |  |
| C002-008 | Mutação simples gera delta (sem captura completa) | APROVADO | 500 |  |
| C002-009 | Múltiplas mutações agrupadas em poucos deltas | APROVADO | 462 |  |
| C002-010 | Seleção logo após mutação usa o estado atualizado (barreira) | APROVADO | 469 |  |
| C002-011 | Dez seleções sem mudanças não geram dez capturas completas | APROVADO | 697 |  |
| C002-012 / C002-013 | Fechar e reabrir o painel preserva o estado e a captura | APROVADO | 1154 |  |
| C002-014 | Mudança de aba: sessões independentes | APROVADO | 809 |  |
| C002-015 | Recarga da página: nova base, inspeção preservada | APROVADO | 516 |  |
| C002-016 | SPA: rota, query e fragmento sem perder o estado válido | APROVADO | 1091 |  |
| C002-017 | Perda de delta: ressincronização controlada e limitada | APROVADO | 3439 |  |
| C002-018 | Reinício do motor Java: degradado, reconexão e nova base | APROVADO | 2292 |  |
| C002-019 | Reinício do service worker: estado restaurado sem observadores duplicados | APROVADO | 1288 |  |
| C002-020 | Desativação remove todos os observadores (inclusive shadow roots) | APROVADO | 1910 |  |
| C002-021 | Exportação manual em DEV (sincronizada, contrato válido) | APROVADO | 1085 |  |
| C002-022 | PRD: recording.session.finished sincroniza, consolida, valida e persiste | APROVADO | 467 |  |
| C002-023 | Finalização duplicada e simultânea: idempotente | APROVADO | 479 |  |
| C002-024 | Finalização com delta pendente: mutação incluída | APROVADO | 433 |  |
| C002-025 | Falha de exportação explícita e nova tentativa | APROVADO | 837 |  |
| C002-026 | Novo shadow root (inclusive aninhado e conteúdo alterado) incorporado automaticamente | APROVADO | 643 |  |
| C002-027 | Shadow root removido: nós e observador descartados | APROVADO | 535 |  |
| C002-028 | attachShadow() em host já conectado: detectado pela reconciliação (latência medida) | APROVADO | 1608 |  |
| C002-029 | Iframe de mesma origem: capturado pelo documento principal, sem duplicidade | APROVADO | 527 |  |
| C002-030 | Iframe de outra origem local permitida (porta +1), aninhado e com Shadow DOM | APROVADO | 783 |  |
| C002-031 | Iframe dinâmico: inserido, removido e documento substituído | APROVADO | 1048 |  |
| C002-032 | Iframe sem permissão (127.0.0.2) e sandbox: não inspecionados, limite declarado | APROVADO | 617 |  |
| C002-033 | AccName: aria-labelledby recursivo (referência não é seguida em segundo nível) | APROVADO | 424 |  |
| C002-034 | AccName: aria-labelledby múltiplo e autorreferência | APROVADO | 432 |  |
| C002-035 | AccName: referência circular termina | APROVADO | 403 |  |
| C002-036 | AccName: elemento oculto referenciado por aria-labelledby | APROVADO | 375 |  |
| C002-037 | AccName: botões com ícone, title, controles e HTML-AAM (comparação com o Chrome) | APROVADO | 1135 |  |
| C002-038 / C002-039 | 100 a 20.000 elementos: captura cooperativa, seleção e exportação (antes × depois) | APROVADO | 32180 |  |
| C002-039 | Captura cooperativa: mutações durante a travessia entram no primeiro delta (base + delta) | APROVADO | 2217 |  |
| C002-040 | Memória: página pequena, média, grande e três grandes (heap usado/comprometido/máximo e RSS) | APROVADO | 7713 |  |
| C002-041 | Sessão prolongada com mutações contínuas (10/s): estável e sem acúmulo | APROVADO | 305712 |  |
| C002-042 | Descarte de sessão: aba fechada libera navegador e motor | APROVADO | 633 |  |
| C002-043 | Destaque acompanha a re-renderização (reencontro automático comprovado) | APROVADO | 606 |  |
| C002-044 | Ambiguidade após mutação: sem destaque e sem escolha silenciosa | APROVADO | 520 |  |
| C002-045 | PageMap incremental × completo: equivalência semântica | APROVADO | 1525 |  |
| C002-046 | Contrato v0.1.0 preservado e contratos de sessão/inspeção/finalização/exportação | APROVADO | 1388 |  |
| C002-047 | Painel lateral nativo: abertura, botão único, captura automática e desativação | APROVADO | 2750 |  |
| C002-048 | Recursos DEV não expostos em PRD | APROVADO | 982 |  |
| C002-049 | Término da inspeção sem gravação não exporta | APROVADO | 495 |  |
| C002-050 | Conteúdo sensível alterado por mutação não é exportado | APROVADO | 427 |  |
| C002-HOM | Chrome convencional (§30): host, extensão sem compactação, painel nativo, captura automática, seleção, destaque, exportação e liberação | APROVADO | 2590 |  |
| C002-R01 | Sincronização com erro de flush nunca é declarada SYNCHRONIZED (documento e frame) | APROVADO | 533 |  |
| C002-R02 | Finalização sem sincronização confirmada falha sem memorizar; nova tentativa exporta completo | APROVADO | 431 |  |
| C002-R03 | Gravação sobrevive ao reinício do service worker e finaliza normalmente | APROVADO | 1474 |  |
| C002-R04 | Alvos isolados entre abas com a mesma página (exportação da gravação e manual) | APROVADO | 837 |  |
| C002-R05 | Finalização × cancelamento simultâneos: um único desfecho, sessão liberada uma vez | APROVADO | 954 |  |
| C002-R06 | Retenção: gravações ativas nunca são descartadas (55 simultâneas) | APROVADO | 1475 |  |
| C002-RAP | Alterações rápidas a 10, 50 e 100 mutações/s: agrupadas, sem ressincronização, estado equivalente | APROVADO | 16522 |  |
| N-A | Dois botões indistinguíveis: AMBIGUOUS | APROVADO | 362 |  |
| N-B | Elemento removido: NOT_FOUND ou STALE_TARGET | APROVADO | 1265 |  |
| N-C | ID preservado com controle incompatível: IDENTITY_MISMATCH | APROVADO | 777 |  |
| N-D | Elemento em região inacessível: SCOPE_UNAVAILABLE | APROVADO | 868 |  |
| N-E | Contexto insuficiente para comprovar identidade: INSUFFICIENT_EVIDENCE | APROVADO | 822 |  |
| T001 | Input com ID estável: identificação correta | APROVADO | 375 |  |
| T002 | Input sem ID: localizador alternativo | APROVADO | 379 |  |
| T003 | Input sem name: localizador contextual | APROVADO | 1073 |  |
| T004 | ID duplicado: desambiguação ou recusa | APROVADO | 346 |  |
| T005 | IDs dinâmicos: reencontro após renderização | APROVADO | 1502 |  |
| T006 | Texto duplicado: contextualização correta | APROVADO | 350 |  |
| T007 | Botões iguais em tabelas diferentes: família correta | APROVADO | 464 |  |
| T008 | Botões iguais em linhas diferentes: linha correta | APROVADO | 374 |  |
| T009 | Reordenação de tabela: identidade preservada | APROVADO | 1371 |  |
| T010 | Select nativo: opções identificadas | APROVADO | 340 |  |
| T011 | Select com optgroup: grupos identificados | APROVADO | 402 |  |
| T012 | Dropdown personalizado: controle e lista associados | APROVADO | 342 |  |
| T013 | Dropdown com portal: associação lógica correta | APROVADO | 335 |  |
| T014 | Dropdown dinâmico: cobertura declarada | APROVADO | 1155 |  |
| T015 | Modal: contexto correto | APROVADO | 973 |  |
| T016 | Modal fechado e aberto: estado atualizado | APROVADO | 1310 |  |
| T017 | Shadow DOM aberto: inspeção quando acessível | APROVADO | 429 |  |
| T018 | Shadow DOM fechado: limite declarado | APROVADO | 389 |  |
| T019 | Iframe acessível: caminho identificado | APROVADO | 521 |  |
| T020 | Iframe inacessível: limite declarado | APROVADO | 395 |  |
| T021 | Lista virtualizada: não presumir completude | APROVADO | 667 |  |
| T022 | Navegação SPA: contexto atualizado | APROVADO | 771 |  |
| T023 | Alteração de query: parâmetros atualizados | APROVADO | 779 |  |
| T024 | Pesquisa por clique: mudanças observadas | APROVADO | 1375 |  |
| T025 | Elemento removido: referência invalidada | APROVADO | 747 |  |
| T026 | Elemento substituído: identidade revalidada | APROVADO | 902 |  |
| T027 | Botão desabilitado: estado correto | APROVADO | 358 |  |
| T028 | Texto isolado: classificação correta | APROVADO | 499 |  |
| T029 | Label externo: associação correta | APROVADO | 354 |  |
| T030 | Elemento contenteditable: identificação correta | APROVADO | 333 |  |
| T031 | Variação responsiva: reencontro validado | APROVADO | 427 |  |
| T032 | Alvo ambíguo: recusa explícita | APROVADO | 385 |  |
| T033 | Dados sensíveis de teste: ausência na exportação | APROVADO | 549 |  |
| T034 | Destaque rosa: contorno visual correto | APROVADO | 4981 |  |
| T035 | Desativação da inspeção: recursos removidos | APROVADO | 1476 |  |
| T036 | Página grande: desempenho mensurado | APROVADO | 24175 |  |
| T037 | Mutações frequentes: atualização incremental | APROVADO | 5029 |  |
| T038 | Funções locais de teste: resultado verificado | APROVADO | 756 |  |
| T039 | Seleção em modo inspector: nenhuma ação acidental | APROVADO | 2573 |  |
| T040 | Alteração semântica com ID constante: identidade incompatível detectada | APROVADO | 796 |  |

Total: 115 · aprovados: 115 · não aprovados: 0
