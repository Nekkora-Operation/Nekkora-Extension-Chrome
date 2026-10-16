# CHROME-SECURITY-001: dados proibidos

Nunca atravessam a página, em nenhum canal (motor, Studio, PageMap, registro, logs, diagnósticos e armazenamento da extensão), seja qual for o formato:

- **Conteúdo de negócio e texto livre:** parágrafos, títulos de seção, células, legendas de tabela, itens de lista, alertas, status, diálogos e texto solto. Vale mesmo quando são feitos de palavras do vocabulário ("Ativo" numa célula).
- **Nome, rótulo ou título fora do vocabulário:** "Consultar cliente Bartolomeu Quixadá", "telefone do cliente joão", "Ficha de Belmira Saraiva". Não há supressão parcial.
- **Valores do usuário:** texto digitado, termo pesquisado (a antiga exceção de `exposedValue` dos campos de pesquisa foi removida), opção escolhida (`parameters.option` sempre `null`, nota `OPTION_WITHHELD`), conteúdo de contenteditable, valor de opção (`<option value>`), `aria-valuetext`.
- **Valores de atributos personalizados e `data-*`**, por exemplo `data-cliente="Eustáquio Marangoni"`. Só a presença atravessa, e só se o nome do atributo passar.
- **Identificadores com conteúdo**, como `cliente-anacleto-prudencio`, `usuario_hermengarda` e `pedido_483192` (mais de 2 dígitos).
- **Partes de URL:** segmentos fora do vocabulário (`/clientes/joao-oliveira`), valores de parâmetros, fragmentos e URLs de `href`/`src`/`action` como atributos.
- **Localizadores com qualquer literal fora da política**, como `//button[text()='Consultar João Oliveira']` ou `[data-customer='joao-oliveira']`. São descartados (`LOCATORS_WITHHELD`), nunca reescritos.
- **Texto antes e depois de mudanças** e valores antigos e novos de atributos não estruturais (`[omitido]`).
- **Mensagens de exceção de origem externa nos logs e erros do host** (parser, validação, motor): ficam tipo, código, posição e pilha. Também não há eco de identificadores fora do formato nas respostas.
- **Substitutos derivados**, como hash, pseudônimo ou o texto original em campo oculto.
