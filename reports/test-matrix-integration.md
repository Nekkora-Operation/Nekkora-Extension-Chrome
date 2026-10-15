# Matriz de homologação — execução integration

Gerado automaticamente em 2026-10-15T03:00:01.942196900Z a partir da execução real dos testes.

| ID | Cenário | Resultado | Duração (ms) | Detalhe |
|---|---|---|---|---|
| C-1 | Extensão conecta ao host Java via Native Messaging | APROVADO | 10 |  |
| C-2 | Resposta grande (5 M caracteres) é fragmentada pelo host e remontada pela extensão | APROVADO | 450 |  |
| C-3 | Requisição grande (3 M caracteres) é fragmentada pela extensão e remontada pelo host | APROVADO | 778 |  |
| C-4 | Erros do host chegam à extensão com código estável | APROVADO | 12 |  |
| C-5 | Após desconexão, a extensão reconecta e o host volta a responder | APROVADO | 872 |  |
| C-8 | Contrato real: /lab/simple/form-basics.html | APROVADO | 621 |  |
| C-8 | Contrato real: /lab/intermediate/widgets.html | APROVADO | 524 |  |
| C-8 | Contrato real: /lab/complex/duplicated-ids.html | APROVADO | 953 |  |
| C-8 | Contrato real: /lab/complex/shadow-iframes.html | APROVADO | 561 |  |
| C-8 | Contrato real: /lab/complex/dropdowns.html | APROVADO | 455 |  |
| C-8 | Contrato real: /clientes?nome=ana&status=ativo | APROVADO | 541 |  |
| C-8 | Contrato real: /generated/large?n=1000 | APROVADO | 1602 |  |
| C-9 | Painel: inspecionar, família, localizadores, reencontro, comparação, exportação e desativação | APROVADO | 2150 |  |
| C002-001 | Botão inicialmente inativo | APROVADO | 774 |  |
| C002-002 | Ativação: captura inicial, rastreamento, ouvintes e texto 'Desativar inspeção' | APROVADO | 914 |  |
| C002-003 | Desativação: ouvintes, destaques e sessão liberados | APROVADO | 1301 |  |
| C002-004 | Clique duplo: uma única ativação | APROVADO | 2461 |  |
| C002-005 | Falha na ativação nunca indica ativo | APROVADO | 3360 |  |
| C002-006 | Captura inicial automática | APROVADO | 395 |  |
| C002-007 | Sem recapturas desnecessárias (página parada) | APROVADO | 4395 |  |
| C002-008 | Mutação simples gera delta (sem captura completa) | APROVADO | 480 |  |
| C002-009 | Múltiplas mutações agrupadas em poucos deltas | APROVADO | 507 |  |
| C002-010 | Seleção logo após mutação usa o estado atualizado (barreira) | APROVADO | 499 |  |
| C002-011 | Dez seleções sem mudanças não geram dez capturas completas | APROVADO | 702 |  |
| C002-012 / C002-013 | Fechar e reabrir o painel preserva o estado e a captura | APROVADO | 1257 |  |
| C002-014 | Mudança de aba: sessões independentes | APROVADO | 828 |  |
| C002-015 | Recarga da página: nova base, inspeção preservada | APROVADO | 640 |  |
| C002-016 | SPA: rota, query e fragmento sem perder o estado válido | APROVADO | 1130 |  |
| C002-017 | Perda de delta: ressincronização controlada e limitada | APROVADO | 3525 |  |
| C002-018 | Reinício do motor Java: degradado, reconexão e nova base | APROVADO | 2468 |  |
| C002-019 | Reinício do service worker: estado restaurado sem observadores duplicados | APROVADO | 1378 |  |
| C002-020 | Desativação remove todos os observadores (inclusive shadow roots) | APROVADO | 2010 |  |
| C002-021 | Exportação manual em DEV (sincronizada, contrato válido) | APROVADO | 1159 |  |
| C002-022 | PRD: recording.session.finished sincroniza, consolida, valida e persiste | APROVADO | 497 |  |
| C002-023 | Finalização duplicada e simultânea: idempotente | APROVADO | 514 |  |
| C002-024 | Finalização com delta pendente: mutação incluída | APROVADO | 455 |  |
| C002-025 | Falha de exportação explícita e nova tentativa | APROVADO | 813 |  |
| C002-026 | Novo shadow root (inclusive aninhado e conteúdo alterado) incorporado automaticamente | APROVADO | 664 |  |
| C002-027 | Shadow root removido: nós e observador descartados | APROVADO | 643 |  |
| C002-028 | attachShadow() em host já conectado: detectado pela reconciliação (latência medida) | APROVADO | 1547 |  |
| C002-029 | Iframe de mesma origem: capturado pelo documento principal, sem duplicidade | APROVADO | 559 |  |
| C002-030 | Iframe de outra origem local permitida (porta +1), aninhado e com Shadow DOM | APROVADO | 809 |  |
| C002-031 | Iframe dinâmico: inserido, removido e documento substituído | APROVADO | 1102 |  |
| C002-032 | Iframe sem permissão (127.0.0.2) e sandbox: não inspecionados, limite declarado | APROVADO | 624 |  |
| C002-033 | AccName: aria-labelledby recursivo (referência não é seguida em segundo nível) | APROVADO | 435 |  |
| C002-034 | AccName: aria-labelledby múltiplo e autorreferência | APROVADO | 508 |  |
| C002-035 | AccName: referência circular termina | APROVADO | 426 |  |
| C002-036 | AccName: elemento oculto referenciado por aria-labelledby | APROVADO | 400 |  |
| C002-037 | AccName: botões com ícone, title, controles e HTML-AAM (comparação com o Chrome) | APROVADO | 1203 |  |
| C002-038 / C002-039 | 100 a 20.000 elementos: captura cooperativa, seleção e exportação (antes × depois) | APROVADO | 33872 |  |
| C002-039 | Captura cooperativa: mutações durante a travessia entram no primeiro delta (base + delta) | APROVADO | 2260 |  |
| C002-040 | Memória: página pequena, média, grande e três grandes (heap usado/comprometido/máximo e RSS) | APROVADO | 8026 |  |
| C002-041 | Sessão prolongada com mutações contínuas (10/s): estável e sem acúmulo | APROVADO | 305832 |  |
| C002-042 | Descarte de sessão: aba fechada libera navegador e motor | APROVADO | 653 |  |
| C002-043 | Destaque acompanha a re-renderização (reencontro automático comprovado) | APROVADO | 578 |  |
| C002-044 | Ambiguidade após mutação: sem destaque e sem escolha silenciosa | APROVADO | 561 |  |
| C002-045 | PageMap incremental × completo: equivalência semântica | APROVADO | 1459 |  |
| C002-046 | Contrato v0.1.0 preservado e contratos de sessão/inspeção/finalização/exportação | APROVADO | 1444 |  |
| C002-047 | Painel lateral nativo: abertura, botão único, captura automática e desativação | APROVADO | 2792 |  |
| C002-048 | Recursos DEV não expostos em PRD | APROVADO | 1029 |  |
| C002-049 | Término da inspeção sem gravação não exporta | APROVADO | 519 |  |
| C002-050 | Conteúdo sensível alterado por mutação não é exportado | APROVADO | 449 |  |
| C002-HOM | Chrome convencional (§30): host, extensão sem compactação, painel nativo, captura automática, seleção, destaque, exportação e liberação | APROVADO | 2701 |  |
| C002-R01 | Sincronização com erro de flush nunca é declarada SYNCHRONIZED (documento e frame) | APROVADO | 550 |  |
| C002-R02 | Finalização sem sincronização confirmada falha sem memorizar; nova tentativa exporta completo | APROVADO | 427 |  |
| C002-R03 | Gravação sobrevive ao reinício do service worker e finaliza normalmente | APROVADO | 1641 |  |
| C002-R04 | Alvos isolados entre abas com a mesma página (exportação da gravação e manual) | APROVADO | 854 |  |
| C002-R05 | Finalização × cancelamento simultâneos: um único desfecho, sessão liberada uma vez | APROVADO | 859 |  |
| C002-R06 | Retenção: gravações ativas nunca são descartadas (55 simultâneas) | APROVADO | 1457 |  |
| C002-R07 | Reencontro explícito de alvo de outra aba é recusado (identidade intacta) | APROVADO | 802 |  |
| C002-R08 | Reencontro automático ignora alvos de uma sessão anterior da mesma aba | APROVADO | 1109 |  |
| C002-RAP | Alterações rápidas a 10, 50 e 100 mutações/s: agrupadas, sem ressincronização, estado equivalente | APROVADO | 16564 |  |
| D003-001 | 1/5/10/25 iframes e profundidades 1–5: descoberta, grafo, trajetória, reencontro, navegação, diagnóstico, memória e exportação | APROVADO | 7087 |  |
| D003-002 | Comparação com o baseline CHROME-002 (mesmo procedimento): 100 a 10.000 elementos | APROVADO | 9431 |  |
| F003-001 | Grafo documental: documentos, frames, profundidade e relações tipadas (§6) | APROVADO | 466 |  |
| F003-002 | documentId/frameId do Chrome associados sem inventar; ids lógicos separados (§5.2) | APROVADO | 2173 |  |
| F003-003 | URL declarada × efetiva × origem: about:blank, srcdoc e navegação interna (§5.3) | APROVADO | 528 |  |
| F003-004 | Cenário A: documento → iframe → elemento (trajetória, localizador escopado e reencontro) | APROVADO | 545 |  |
| F003-005 | Cenário B: dois níveis de iframe (Financeiro → Contas a pagar) | APROVADO | 534 |  |
| F003-006 | Cenário C: dois níveis + Shadow DOM, explicação legível e destaque do iframe (§9, §21) | APROVADO | 644 |  |
| F003-007 | Cenário D: Shadow DOM contendo iframe (ordem SHADOW_ROOT → IFRAME) | APROVADO | 520 |  |
| F003-008 | Cenário E: iframes irmãos com botões idênticos são distinguidos pelo frame | APROVADO | 2096 |  |
| F003-009 | Cenário F: mesmo name/URL em contêineres diferentes; contêineres iguais ⇒ FRAME_AMBIGUOUS, nunca o primeiro | APROVADO | 619 |  |
| F003-010 | Cenário G: iframe removido durante a inspeção ⇒ FRAME_NOT_FOUND (alvo não procurado em outro frame) | APROVADO | 502 |  |
| F003-011 | Cenário H: iframe recriado por re-renderização de SPA ⇒ invalidado, identificado, validado e reconstruído | APROVADO | 594 |  |
| F003-012 | Cenário I: about:blank preenchido pelo pai (origem herdada, metadados por URL única) | APROVADO | 506 |  |
| F003-013 | srcdoc: documento SRCDOC inventariado pelo pai, sem duplicidade | APROVADO | 548 |  |
| F003-014 | Cenário J: origem sem permissão ⇒ PERMISSION_DENIED; permissão da página não se estende aos frames | APROVADO | 524 |  |
| F003-015 | Sandbox: sem allow-same-origin ⇒ SANDBOX_RESTRICTED com origem opaca; com allow-same-origin ⇒ inspecionado | APROVADO | 525 |  |
| F003-016 | Outra origem permitida com iframe aninhado e Shadow DOM: trajetória entre documentos de origens diferentes | APROVADO | 679 |  |
| F003-017 | Frame em carregamento: LOADING declarado e confiança reduzida; após carregar, INSPECTED (§23.2) | APROVADO | 4505 |  |
| F003-018 | Observação contínua: inserção, troca de src e navegação interna sem recaptura completa (§22) | APROVADO | 636 |  |
| F003-019 | Painel: Documentos/Iframes/Shadow Roots, TRAJETÓRIA e DIAGNÓSTICO; botão DEV Destacar iframe (§9, §26) | APROVADO | 1538 |  |
| F003-020 | PageMap 0.2.0: frameGraph, accessPaths, protection e readiness válidos no contrato; campos 0.1.0 preservados | APROVADO | 592 |  |
| I003-01 | Financeiro → Pagamentos → Shadow DOM → Confirmar pagamento | APROVADO | 573 |  |
| I003-02 | Filiais idênticas: Aprovar da Filial Sul | APROVADO | 559 |  |
| I003-03 | Shadow DOM contendo iframe: Enviar mensagem do widget de suporte | APROVADO | 527 |  |
| I003-04 | Outra origem permitida: Relatórios → Gráficos → Shadow DOM → Exportar gráfico | APROVADO | 659 |  |
| I003-05 | SPA: Salvar do iframe Cadastro após re-renderização (frame reconstruído) | APROVADO | 581 |  |
| I003-06 | about:blank preenchido pelo pai: Guardar nota | APROVADO | 511 |  |
| I003-07 | Formulário com reCAPTCHA (simulado): Enviar; verificação humana diagnosticada | APROVADO | 518 |  |
| I003-08 | Turnstile dentro de dois níveis de iframe: Confirmar pagamento no mesmo documento da proteção | APROVADO | 501 |  |
| I003-09 | Página de desafio (simulada): caixa de verificação identificada; intervenção humana recomendada | APROVADO | 479 |  |
| I003-10 | 10 iframes com profundidade 3: Confirmar 7 no frame mais profundo | APROVADO | 713 |  |
| N-A | Dois botões indistinguíveis: AMBIGUOUS | APROVADO | 400 |  |
| N-B | Elemento removido: NOT_FOUND ou STALE_TARGET | APROVADO | 1307 |  |
| N-C | ID preservado com controle incompatível: IDENTITY_MISMATCH | APROVADO | 830 |  |
| N-D | Elemento em região inacessível: SCOPE_UNAVAILABLE | APROVADO | 917 |  |
| N-E | Contexto insuficiente para comprovar identidade: INSUFFICIENT_EVIDENCE | APROVADO | 811 |  |
| N003-A | Origem sem permissão (127.0.0.2): comandos de diagnóstico recusados com ORIGIN_NOT_ALLOWED | APROVADO | 391 |  |
| N003-B | Trajetória de nó inexistente: UNKNOWN_NODE (nada é reconstruído por adivinhação) | APROVADO | 459 |  |
| N003-C | Anúncios de handshake forjados pela página não alteram a associação de frames | APROVADO | 1048 |  |
| N003-D | Elemento no documento principal: 'Destacar iframe' não destaca nada (ELEMENT_IN_MAIN_DOCUMENT) | APROVADO | 505 |  |
| N003-E | Iframe sem permissão: nenhuma trajetória para o interior; o próprio iframe tem trajetória no documento pai | APROVADO | 465 |  |
| N003-F | Documento do frame trocado (src alterado): alvo não aceito em outro documento ⇒ FRAME_DOCUMENT_CHANGED | APROVADO | 564 |  |
| N003-G | Shadow root fechado: contado e declarado, conteúdo nunca inventado | APROVADO | 542 |  |
| N003-H | Permissão da página não se estende ao frame: nenhum content script no frame de 127.0.0.2 | APROVADO | 449 |  |
| N003-I | Diagnóstico não altera a página: DOM idêntico antes/depois de diagnosticar, avaliar e exportar | APROVADO | 515 |  |
| N003-J | Sem captura: diagnóstico recusado (NO_CAPTURE), nunca fabricado | APROVADO | 388 |  |
| P003-001 | LAB-P01 reCAPTCHA v2: HIGH por canais independentes, CHALLENGE_PRESENT, evidência simulada rotulada | APROVADO | 437 |  |
| P003-002 | Conclusão só com evidência explícita: campo de resposta preenchido ⇒ CHALLENGE_COMPLETED (valor não coletado) | APROVADO | 515 |  |
| P003-003 | LAB-P02 hCaptcha: HIGH; desafio aberto (frame=challenge) ⇒ CHALLENGE_ACTIVE | APROVADO | 449 |  |
| P003-004 | LAB-P03 Cloudflare Turnstile: HIGH, CHALLENGE_PRESENT | APROVADO | 384 |  |
| P003-005 | LAB-P04 página de desafio: observação de terceiros limitada a MEDIUM, CHALLENGE_ACTIVE ⇒ intervenção humana | APROVADO | 386 |  |
| P003-006 | LAB-P05 verificação não identificável: INSUFFICIENT_EVIDENCE, fornecedor UNKNOWN (nunca inferido) | APROVADO | 360 |  |
| P003-007 | LAB-P06 falso positivo: texto sobre CAPTCHA ⇒ NOT_OBSERVED, sem afirmar ausência de proteção | APROVADO | 387 |  |
| P003-008 | LAB-P07 múltiplas tecnologias: v3/DataDome/Akamai; referência compartilhada v2×v3 reduz o produto menos específico | APROVADO | 354 |  |
| P003-009 | LAB-P08 proteção dentro de iframe: localização na hierarquia (documento, cadeia de frames e trajetória) | APROVADO | 447 |  |
| P003-010 | LAB-P09 proteção dinâmica: surge por delta (sem recaptura) e o desaparecimento NÃO é conclusão | APROVADO | 516 |  |
| P003-011 | LAB-P10 metadados insuficientes: POSSIBLE/LOW com estado UNKNOWN; frame sem permissão declarado | APROVADO | 423 |  |
| P003-012 | Tecnologia ≠ desafio ≠ bloqueio: LAB-P07 × LAB-P04 × LAB-P11 (§18) | APROVADO | 1124 |  |
| P003-013 | Detecção passiva: nenhuma requisição a fornecedores, nenhuma função da página chamada, nenhum valor sensível exportado | APROVADO | 444 |  |
| P003-014 | Recomendações: nenhuma sugere evasão; tipos dentro do conjunto permitido (§15) em todas as fixtures | APROVADO | 7880 |  |
| P003-015 | Catálogo versionado e determinismo: mesma entrada ⇒ mesmo diagnóstico; versão e base de cada assinatura expostas | APROVADO | 408 |  |
| R001-01 | Página externa autorizada: acesso AUTHORIZED, navegar não captura; ativar inspeciona com as garantias anteriores | APROVADO | 608 |  |
| R001-02 | Página externa sem autorização, página interna e esquema não suportado: indisponíveis com erro explícito | APROVADO | 1109 |  |
| R001-03 | Iframe de outra origem SEM permissão: PERMISSION_DENIED, conteúdo não inventariado, permissão não ampliada | APROVADO | 837 |  |
| R001-04 | Iframe de outra origem AUTORIZADA: anexado pelo próprio content script, trajetória e reencontro | APROVADO | 631 |  |
| R001-05 | Navegação SPA e atualização dinâmica no site externo: deltas, sem recaptura completa, rota sanitizada | APROVADO | 568 |  |
| R001-06 | Shadow DOM aberto em página externa: elemento inventariado com trajetória SHADOW_ROOT | APROVADO | 518 |  |
| R001-07 | Tecnologia de proteção observável em origem externa autorizada: diagnóstico passivo preservado | APROVADO | 813 |  |
| R001-08 | Desativação e reativação no site externo: ouvintes/observadores removidos; nova sessão ao reativar | APROVADO | 558 |  |
| R001-09 | Revogação de permissão durante a sessão (página e frame): sessão encerrada, nada presumido; reconcessão restabelece | APROVADO | 1915 |  |
| R001-10 | Exportação e verificação de ausência de informações sensíveis (PageMap, sessão, eventos, disco do motor) | APROVADO | 3440 |  |
| R001-11 | Página grande externa: rolagem e classe trocada no body (padrão da Wikipedia) sem recaptura completa; visibilidade correta | APROVADO | 2833 |  |
| RP-01 | Seleção em 5.000 e 10.000 elementos: frio × aquecido, 7 execuções, mediana/p95/variabilidade | APROVADO | 20969 |  |
| T001 | Input com ID estável: identificação correta | APROVADO | 409 |  |
| T002 | Input sem ID: localizador alternativo | APROVADO | 407 |  |
| T003 | Input sem name: localizador contextual | APROVADO | 1120 |  |
| T004 | ID duplicado: desambiguação ou recusa | APROVADO | 422 |  |
| T005 | IDs dinâmicos: reencontro após renderização | APROVADO | 2292 |  |
| T006 | Texto duplicado: contextualização correta | APROVADO | 369 |  |
| T007 | Botões iguais em tabelas diferentes: família correta | APROVADO | 495 |  |
| T008 | Botões iguais em linhas diferentes: linha correta | APROVADO | 427 |  |
| T009 | Reordenação de tabela: identidade preservada | APROVADO | 1473 |  |
| T010 | Select nativo: opções identificadas | APROVADO | 365 |  |
| T011 | Select com optgroup: grupos identificados | APROVADO | 341 |  |
| T012 | Dropdown personalizado: controle e lista associados | APROVADO | 379 |  |
| T013 | Dropdown com portal: associação lógica correta | APROVADO | 362 |  |
| T014 | Dropdown dinâmico: cobertura declarada | APROVADO | 1192 |  |
| T015 | Modal: contexto correto | APROVADO | 814 |  |
| T016 | Modal fechado e aberto: estado atualizado | APROVADO | 1309 |  |
| T017 | Shadow DOM aberto: inspeção quando acessível | APROVADO | 437 |  |
| T018 | Shadow DOM fechado: limite declarado | APROVADO | 355 |  |
| T019 | Iframe acessível: caminho identificado | APROVADO | 453 |  |
| T020 | Iframe inacessível: limite declarado | APROVADO | 405 |  |
| T021 | Lista virtualizada: não presumir completude | APROVADO | 701 |  |
| T022 | Navegação SPA: contexto atualizado | APROVADO | 868 |  |
| T023 | Alteração de query: parâmetros atualizados | APROVADO | 787 |  |
| T024 | Pesquisa por clique: mudanças observadas | APROVADO | 1388 |  |
| T025 | Elemento removido: referência invalidada | APROVADO | 815 |  |
| T026 | Elemento substituído: identidade revalidada | APROVADO | 993 |  |
| T027 | Botão desabilitado: estado correto | APROVADO | 350 |  |
| T028 | Texto isolado: classificação correta | APROVADO | 518 |  |
| T029 | Label externo: associação correta | APROVADO | 384 |  |
| T030 | Elemento contenteditable: identificação correta | APROVADO | 340 |  |
| T031 | Variação responsiva: reencontro validado | APROVADO | 513 |  |
| T032 | Alvo ambíguo: recusa explícita | APROVADO | 394 |  |
| T033 | Dados sensíveis de teste: ausência na exportação | APROVADO | 586 |  |
| T034 | Destaque rosa: contorno visual correto | APROVADO | 4987 |  |
| T035 | Desativação da inspeção: recursos removidos | APROVADO | 1555 |  |
| T036 | Página grande: desempenho mensurado | APROVADO | 23517 |  |
| T037 | Mutações frequentes: atualização incremental | APROVADO | 5059 |  |
| T038 | Funções locais de teste: resultado verificado | APROVADO | 757 |  |
| T039 | Seleção em modo inspector: nenhuma ação acidental | APROVADO | 2618 |  |
| T040 | Alteração semântica com ID constante: identidade incompatível detectada | APROVADO | 824 |  |

Total: 186 · aprovados: 186 · não aprovados: 0
