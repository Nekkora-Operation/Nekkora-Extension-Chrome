# STUDIO-005 — Limitações conhecidas (extensão)

1. **Janela fixa de 3 s (até 8 s com carregamento).** Respostas mais lentas não são atribuídas a nenhuma etapa. Essa é a
   escolha honesta: não se associa "por proximidade". Homologado com "Pedido lento" (4,5 s).
2. **Duas ações em menos de 3 s** tornam ambíguos os avisos que aparecerem nesse intervalo. É o comportamento
   pretendido (D-S005), mas gravações rápidas terão mais evidências sem etapa.
3. **Navegação para novo documento** depende do content script do documento novo ficar pronto (`document_idle`). Se o
   carregamento passar de 3 s, a navegação não é associada à ação; a janela anterior continua declarada como cortada.
4. **Shadow roots criados depois da abertura da janela** não são observados até a próxima janela. O evento nativo
   `invalid` não atravessa shadow roots.
5. **Iframes de outra origem sem permissão** não são observados. A cobertura fica `PARTIAL`, como na STUDIO-003.
6. **Regiões fora do seletor** (avisos sem papel ARIA, "toasts" só visuais) não são detectadas. Isso é intencional:
   classe CSS sozinha não comprova nada.
7. **A política de mensagem é conservadora.** Mensagens legítimas com 4 ou mais dígitos ("Pedido 12345 criado") ou com
   nome próprio no meio da frase ("Bem-vindo ao Portal") saem como `SUPPRESSED`.
8. **Valores dos campos lidos para comparação e impressões digitais salgadas** (só no content script, só para suprimir,
   nunca o valor). Isso está documentado em `privacy.md`.
9. **Dado pessoal de terceiro em minúsculas**, nunca digitado nesta página, não é reconhecido pela forma. É o limite
   declarado da política; a garantia total exigiria nunca enviar o texto (decisão de produto).
10. **Achado de segurança, prioridade alta, fora do escopo da STUDIO-005:** o texto visível da página que repete um valor
    digitado aparece na captura técnica existente (log local de diagnóstico do service worker e `contextTexts` do
    registro de alvos do motor). Não atravessa o Bridge nem é evidência. **Até a correção na origem, o Recorder não deve
    ser usado com dados reais de clientes.**
11. **Reinício do service worker** (revisão do PR #6): a janela aberta persiste o vínculo exato com a etapa. Se a etapa
    já tinha sido emitida, as evidências seguem vinculadas e a janela termina declarada (normalmente
    `OBSERVATION_INCOMPLETE`/`PARTIAL_COVERAGE`, porque os sensores voltam numa época nova). Se a etapa não tinha sido
    emitida, a observação é declarada perdida (`lost`, `EVIDENCE_LOST`) e nada é emitido sem vínculo. As navegações não
    são associadas a ações anteriores ao reinício (os candidatos ficam só na memória). A persistência definitiva é da STUDIO-006.
12. **Ordem entre documentos**: a sequência das evidências é a ordem de emissão no service worker (técnica), não a
    ordem absoluta entre iframes.
