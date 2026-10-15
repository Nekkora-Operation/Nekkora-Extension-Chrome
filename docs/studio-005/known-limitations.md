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
7. **A política de mensagem é conservadora.** Mensagens legítimas com 4 ou mais dígitos ("Pedido 12345 criado")
   saem como `SUPPRESSED`.
8. **Valores dos campos lidos para comparação** (só no content script, só para suprimir). Isso está documentado em `privacy.md`.
9. **Achado de privacidade fora do escopo:** o texto visível da página que repete um valor digitado aparece na captura
   técnica existente (log local de diagnóstico do service worker e `contextTexts` do registro de alvos do motor). Não
   atravessa o Bridge nem é evidência, mas merece uma missão própria de privacidade da captura técnica.
10. **Persistência**: a outbox e o estado ficam em `storage.session`, e os vínculos ficam em memória. Depois de um
    reinício do service worker, evidências de etapas antigas são descartadas (`dropped`); a persistência definitiva é da STUDIO-006.
11. **Ordem entre documentos**: a sequência das evidências é a ordem de emissão no service worker (técnica), não a
    ordem absoluta entre iframes.
