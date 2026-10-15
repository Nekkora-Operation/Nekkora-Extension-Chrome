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
7. **Modo estrito: o Studio não vê o texto de nenhuma mensagem**, nem das inofensivas ("Usuário ou senha inválidos.").
   Ele vê que houve mensagem, com tipo, classificação, sinais e região. Mostrar mensagens úteis depende do catálogo
   controlado de mensagens seguras, uma evolução futura de contrato, não autorizada nesta missão.
8. **Os valores dos campos não são lidos.** O modo estrito tornou desnecessária a comparação; as impressões digitais da
   primeira correção foram retiradas.
9. **Classificação depende de sinais semânticos**: sem o texto, o Studio distingue erro, status e validação pelos
   sinais da página (ARIA, validação nativa), como antes.
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
13. **Metadados controlados (revisão 3):** região e campo são descrições genéricas do vocabulário (ex.: "mensagem de erro",
    "campo de telefone"); o Studio não sabe *qual* campo foi validado pelo rótulo. O título da página não é enviado.
14. **Canal de etapas e caminho das URLs:** fora do canal de evidências, o evento de etapa (STUDIO-003) ainda leva o nome
    acessível do elemento, e o caminho de uma URL sanitizada pode conter texto. Registrados para a CHROME-SECURITY-001.
