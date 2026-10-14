# Matriz de homologação — execução integration

Gerado automaticamente em 2026-10-14T01:57:15.764623100Z a partir da execução real dos testes.

| ID | Cenário | Resultado | Duração (ms) | Detalhe |
|---|---|---|---|---|
| C-1 | Extensão conecta ao host Java via Native Messaging | APROVADO | 9 |  |
| C-2 | Resposta grande (5 M caracteres) é fragmentada pelo host e remontada pela extensão | APROVADO | 413 |  |
| C-3 | Requisição grande (3 M caracteres) é fragmentada pela extensão e remontada pelo host | APROVADO | 809 |  |
| C-4 | Erros do host chegam à extensão com código estável | APROVADO | 12 |  |
| C-5 | Após desconexão, a extensão reconecta e o host volta a responder | APROVADO | 803 |  |
| C-8 | Contrato real: /lab/simple/form-basics.html | APROVADO | 597 |  |
| C-8 | Contrato real: /lab/intermediate/widgets.html | APROVADO | 583 |  |
| C-8 | Contrato real: /lab/complex/duplicated-ids.html | APROVADO | 1004 |  |
| C-8 | Contrato real: /lab/complex/shadow-iframes.html | APROVADO | 582 |  |
| C-8 | Contrato real: /lab/complex/dropdowns.html | APROVADO | 449 |  |
| C-8 | Contrato real: /clientes?nome=ana&status=ativo | APROVADO | 557 |  |
| C-8 | Contrato real: /generated/large?n=1000 | APROVADO | 1595 |  |
| C-9 | Painel: inspecionar, família, localizadores, reencontro, comparação, exportação e desativação | APROVADO | 1987 |  |
| C002-001 | Botão inicialmente inativo | APROVADO | 745 |  |
| C002-002 | Ativação: captura inicial, rastreamento, ouvintes e texto 'Desativar inspeção' | APROVADO | 844 |  |
| C002-003 | Desativação: ouvintes, destaques e sessão liberados | APROVADO | 1257 |  |
| C002-004 | Clique duplo: uma única ativação | APROVADO | 2442 |  |
| C002-005 | Falha na ativação nunca indica ativo | APROVADO | 3212 |  |
| C002-006 | Captura inicial automática | APROVADO | 393 |  |
| C002-007 | Sem recapturas desnecessárias (página parada) | APROVADO | 4371 |  |
| C002-008 | Mutação simples gera delta (sem captura completa) | APROVADO | 481 |  |
| C002-009 | Múltiplas mutações agrupadas em poucos deltas | APROVADO | 481 |  |
| C002-010 | Seleção logo após mutação usa o estado atualizado (barreira) | APROVADO | 479 |  |
| C002-011 | Dez seleções sem mudanças não geram dez capturas completas | APROVADO | 725 |  |
| C002-012 / C002-013 | Fechar e reabrir o painel preserva o estado e a captura | APROVADO | 1231 |  |
| C002-014 | Mudança de aba: sessões independentes | APROVADO | 866 |  |
| C002-015 | Recarga da página: nova base, inspeção preservada | APROVADO | 701 |  |
| C002-016 | SPA: rota, query e fragmento sem perder o estado válido | APROVADO | 1104 |  |
| C002-017 | Perda de delta: ressincronização controlada e limitada | APROVADO | 3440 |  |
| C002-018 | Reinício do motor Java: degradado, reconexão e nova base | APROVADO | 2370 |  |
| C002-019 | Reinício do service worker: estado restaurado sem observadores duplicados | APROVADO | 1300 |  |
| C002-020 | Desativação remove todos os observadores (inclusive shadow roots) | APROVADO | 1977 |  |
| C002-021 | Exportação manual em DEV (sincronizada, contrato válido) | APROVADO | 1131 |  |
| C002-022 | PRD: recording.session.finished sincroniza, consolida, valida e persiste | APROVADO | 491 |  |
| C002-023 | Finalização duplicada e simultânea: idempotente | APROVADO | 501 |  |
| C002-024 | Finalização com delta pendente: mutação incluída | APROVADO | 507 |  |
| C002-025 | Falha de exportação explícita e nova tentativa | APROVADO | 843 |  |
| C002-026 | Novo shadow root (inclusive aninhado e conteúdo alterado) incorporado automaticamente | APROVADO | 624 |  |
| C002-027 | Shadow root removido: nós e observador descartados | APROVADO | 607 |  |
| C002-028 | attachShadow() em host já conectado: detectado pela reconciliação (latência medida) | APROVADO | 1539 |  |
| C002-029 | Iframe de mesma origem: capturado pelo documento principal, sem duplicidade | APROVADO | 631 |  |
| C002-030 | Iframe de outra origem local permitida (porta +1), aninhado e com Shadow DOM | APROVADO | 829 |  |
| C002-031 | Iframe dinâmico: inserido, removido e documento substituído | APROVADO | 1033 |  |
| C002-032 | Iframe sem permissão (127.0.0.2) e sandbox: não inspecionados, limite declarado | APROVADO | 547 |  |
| C002-033 | AccName: aria-labelledby recursivo (referência não é seguida em segundo nível) | APROVADO | 407 |  |
| C002-034 | AccName: aria-labelledby múltiplo e autorreferência | APROVADO | 501 |  |
| C002-035 | AccName: referência circular termina | APROVADO | 389 |  |
| C002-036 | AccName: elemento oculto referenciado por aria-labelledby | APROVADO | 407 |  |
| C002-037 | AccName: botões com ícone, title, controles e HTML-AAM (comparação com o Chrome) | APROVADO | 1184 |  |
| C002-038 / C002-039 | 100 a 20.000 elementos: captura cooperativa, seleção e exportação (antes × depois) | APROVADO | 30448 |  |
| C002-039 | Captura cooperativa: mutações durante a travessia entram no primeiro delta (base + delta) | APROVADO | 2216 |  |
| C002-040 | Memória: página pequena, média, grande e três grandes (heap usado/comprometido/máximo e RSS) | APROVADO | 7635 |  |
| C002-041 | Sessão prolongada com mutações contínuas (10/s): estável e sem acúmulo | APROVADO | 305736 |  |
| C002-042 | Descarte de sessão: aba fechada libera navegador e motor | APROVADO | 643 |  |
| C002-043 | Destaque acompanha a re-renderização (reencontro automático comprovado) | APROVADO | 597 |  |
| C002-044 | Ambiguidade após mutação: sem destaque e sem escolha silenciosa | APROVADO | 456 |  |
| C002-045 | PageMap incremental × completo: equivalência semântica | APROVADO | 1424 |  |
| C002-046 | Contrato v0.1.0 preservado e contratos de sessão/inspeção/finalização/exportação | APROVADO | 1349 |  |
| C002-047 | Painel lateral nativo: abertura, botão único, captura automática e desativação | APROVADO | 2633 |  |
| C002-048 | Recursos DEV não expostos em PRD | APROVADO | 1028 |  |
| C002-049 | Término da inspeção sem gravação não exporta | APROVADO | 537 |  |
| C002-050 | Conteúdo sensível alterado por mutação não é exportado | APROVADO | 501 |  |
| C002-HOM | Chrome convencional (§30): host, extensão sem compactação, painel nativo, captura automática, seleção, destaque, exportação e liberação | APROVADO | 2942 |  |
| C002-R01 | Sincronização com erro de flush nunca é declarada SYNCHRONIZED (documento e frame) | APROVADO | 508 |  |
| C002-R02 | Finalização sem sincronização confirmada falha sem memorizar; nova tentativa exporta completo | APROVADO | 438 |  |
| C002-R03 | Gravação sobrevive ao reinício do service worker e finaliza normalmente | APROVADO | 1502 |  |
| C002-R04 | Alvos isolados entre abas com a mesma página (exportação da gravação e manual) | APROVADO | 845 |  |
| C002-R05 | Finalização × cancelamento simultâneos: um único desfecho, sessão liberada uma vez | APROVADO | 821 |  |
| C002-R06 | Retenção: gravações ativas nunca são descartadas (55 simultâneas) | APROVADO | 1386 |  |
| C002-R07 | Reencontro explícito de alvo de outra aba é recusado (identidade intacta) | APROVADO | 824 |  |
| C002-R08 | Reencontro automático ignora alvos de uma sessão anterior da mesma aba | APROVADO | 1055 |  |
| C002-RAP | Alterações rápidas a 10, 50 e 100 mutações/s: agrupadas, sem ressincronização, estado equivalente | APROVADO | 16498 |  |
| N-A | Dois botões indistinguíveis: AMBIGUOUS | APROVADO | 373 |  |
| N-B | Elemento removido: NOT_FOUND ou STALE_TARGET | APROVADO | 1271 |  |
| N-C | ID preservado com controle incompatível: IDENTITY_MISMATCH | APROVADO | 794 |  |
| N-D | Elemento em região inacessível: SCOPE_UNAVAILABLE | APROVADO | 883 |  |
| N-E | Contexto insuficiente para comprovar identidade: INSUFFICIENT_EVIDENCE | APROVADO | 774 |  |
| T001 | Input com ID estável: identificação correta | APROVADO | 356 |  |
| T002 | Input sem ID: localizador alternativo | APROVADO | 421 |  |
| T003 | Input sem name: localizador contextual | APROVADO | 1045 |  |
| T004 | ID duplicado: desambiguação ou recusa | APROVADO | 389 |  |
| T005 | IDs dinâmicos: reencontro após renderização | APROVADO | 1450 |  |
| T006 | Texto duplicado: contextualização correta | APROVADO | 340 |  |
| T007 | Botões iguais em tabelas diferentes: família correta | APROVADO | 482 |  |
| T008 | Botões iguais em linhas diferentes: linha correta | APROVADO | 343 |  |
| T009 | Reordenação de tabela: identidade preservada | APROVADO | 1374 |  |
| T010 | Select nativo: opções identificadas | APROVADO | 354 |  |
| T011 | Select com optgroup: grupos identificados | APROVADO | 367 |  |
| T012 | Dropdown personalizado: controle e lista associados | APROVADO | 344 |  |
| T013 | Dropdown com portal: associação lógica correta | APROVADO | 377 |  |
| T014 | Dropdown dinâmico: cobertura declarada | APROVADO | 1156 |  |
| T015 | Modal: contexto correto | APROVADO | 922 |  |
| T016 | Modal fechado e aberto: estado atualizado | APROVADO | 1305 |  |
| T017 | Shadow DOM aberto: inspeção quando acessível | APROVADO | 447 |  |
| T018 | Shadow DOM fechado: limite declarado | APROVADO | 442 |  |
| T019 | Iframe acessível: caminho identificado | APROVADO | 467 |  |
| T020 | Iframe inacessível: limite declarado | APROVADO | 408 |  |
| T021 | Lista virtualizada: não presumir completude | APROVADO | 713 |  |
| T022 | Navegação SPA: contexto atualizado | APROVADO | 827 |  |
| T023 | Alteração de query: parâmetros atualizados | APROVADO | 781 |  |
| T024 | Pesquisa por clique: mudanças observadas | APROVADO | 1361 |  |
| T025 | Elemento removido: referência invalidada | APROVADO | 780 |  |
| T026 | Elemento substituído: identidade revalidada | APROVADO | 938 |  |
| T027 | Botão desabilitado: estado correto | APROVADO | 383 |  |
| T028 | Texto isolado: classificação correta | APROVADO | 504 |  |
| T029 | Label externo: associação correta | APROVADO | 374 |  |
| T030 | Elemento contenteditable: identificação correta | APROVADO | 332 |  |
| T031 | Variação responsiva: reencontro validado | APROVADO | 430 |  |
| T032 | Alvo ambíguo: recusa explícita | APROVADO | 383 |  |
| T033 | Dados sensíveis de teste: ausência na exportação | APROVADO | 539 |  |
| T034 | Destaque rosa: contorno visual correto | APROVADO | 4978 |  |
| T035 | Desativação da inspeção: recursos removidos | APROVADO | 1520 |  |
| T036 | Página grande: desempenho mensurado | APROVADO | 22196 |  |
| T037 | Mutações frequentes: atualização incremental | APROVADO | 5005 |  |
| T038 | Funções locais de teste: resultado verificado | APROVADO | 777 |  |
| T039 | Seleção em modo inspector: nenhuma ação acidental | APROVADO | 2589 |  |
| T040 | Alteração semântica com ID constante: identidade incompatível detectada | APROVADO | 778 |  |

Total: 117 · aprovados: 117 · não aprovados: 0
