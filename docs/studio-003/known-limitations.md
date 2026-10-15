# STUDIO-003 — Limitações conhecidas (lado da extensão)

- **Ordem entre documentos.** Interações em documentos diferentes (iframes de outra origem com sensor próprio) são
  ordenadas pela recepção no service worker. A ordem real não pode ser comprovada além disso; a etapa leva a nota
  `CROSS_FRAME_ORDER_BY_RECEPTION`.
- **Contexto posterior por janela.** O envio e a navegação são anexados a um CLICK ou PRESS_KEY quando chegam até
  400 ms depois e antes da interação seguinte. Efeitos mais lentos (resposta de servidor demorada) ficam para a
  STUDIO-005. Essa janela também entra na latência do clique até a etapa no Studio (p50 de cerca de 0,8 s no Chrome
  real, incluindo o clique do WebDriver).
- **Perda declarada, não recuperada.** Se o documento for substituído com um preenchimento ainda aberto (raro: a
  saída do campo, o Enter e o `pagehide` consolidam antes), a interação é contada como perdida (`steps.lost`) e não
  é reconstruída.
- **Componentes personalizados.** Só há `SELECT_OPTION` quando a relação combobox → listbox está declarada
  (`aria-controls` ou `aria-owns`). Menus sem semântica viram CLICKs ou `UNKNOWN`.
- **Teclas.** Só Enter num campo de texto vira etapa (`PRESS_KEY`). Outras teclas e atalhos não são registrados.
- **Arquivos, arrastar, rolagem e hover** não são registrados (fora do escopo).
- **Iframes sem permissão** não são observados. A cobertura é declarada `PARTIAL`, e nenhuma permissão é pedida ou
  ampliada.
- **Encerramento abrupto do Studio.** As etapas ainda não confirmadas ficam retidas na extensão até a concessão
  expirar. Sem persistência definitiva no Studio, não há promessa de recuperá-las.
- **Studio da STUDIO-002.** Sem a capacidade `steps/1`, a extensão grava só a parte técnica, como antes.
