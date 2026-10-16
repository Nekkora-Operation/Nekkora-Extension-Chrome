# CHROME-SECURITY-001: limitações conhecidas

1. **Nomes genéricos fora do vocabulário.** Um controle cujo nome não é inteiro do vocabulário aparece no Studio como genérico ("Clicar em botão", "Preencher campo de telefone"). O usuário pode renomear a etapa manualmente.
2. **Vocabulário PT/EN, versão 1.** Termos de interface comuns que ainda não estão na lista (exemplos vistos nos testes: "guardar", "detalhar", "gráficos", "contato") fazem o nome sair vazio. Para incluir um termo é preciso revisão versionada do vocabulário. A política não aprende sozinha.
3. **Localizadores mais frágeis.** Sem texto da página e sem identificadores fora do vocabulário, parte dos elementos fica só com localizadores posicionais ou relativos (instáveis e declarados como tal) ou sem localizador seguro (`SAFE_LOCATOR_UNAVAILABLE`). A medição está em `performance.md` e no relatório final.
4. **Linhas de tabela iguais.** Botões iguais ("Visualizar") em linhas cujo texto ou chave é retido não se distinguem pela linha: o resultado é `AMBIGUOUS`/lookalikes em vez de `ROW_CONTEXT`.
5. **Reencontro e identidade mais conservadores.** Um alvo sem característica do próprio elemento não é confirmado (`INSUFFICIENT_EVIDENCE`/`AMBIGUOUS`). É o comportamento correto (SEC001-078), mas resolve menos alvos automaticamente.
6. **Opção escolhida.** `SELECT_OPTION` não informa qual opção foi escolhida (`OPTION_WITHHELD`). A escolha precisa ser configurada no Studio.
7. **URLs.** O caminho fora do vocabulário vira `{…}` e o fragmento some. Rotas por hash não são distinguidas pela URL, e a origem (host) é mantida (ver `url-security.md`).
8. **Títulos.** Abas e frames com título fora do vocabulário ficam sem título. O Studio identifica a aba pelo host.
9. **Menções textuais genéricas de CAPTCHA** ("verifique que você é humano") não são lidas: são texto da página. A detecção por fornecedor (script, classe, campo e caminho do catálogo fechado) continua.
10. **Dicas de texto do motor** (por exemplo "carregar mais") só funcionam quando o texto é metadado do vocabulário dentro de um portador.
11. **Dados de versões anteriores** ficam no disco até o usuário removê-los (procedimento em `storage-and-retention.md`). Apagar não garante eliminação física.
12. **Vocabulário e nomes de pessoas.** Uma palavra de interface que também seja nome próprio pode, num contexto raro, atravessar. As mais frequentes foram excluídas de propósito.
13. **`frames.list` e `testHooks.read` (DEV).** O primeiro devolve URLs cruas do navegador; o segundo, valores que a própria página expõe em `window.nekkoraTestHooks`, inclusive pelo `interaction.observe` com `readHooks`. Ambos são diagnósticos de teste da construção DEV e são recusados em PRD (`MODE_RESTRICTED`). Na linha de base, `testHooks.read` também funcionava em PRD, e qualquer página podia declarar o marcador.
14. **Símbolos dentro de um nome.** Um caractere fora do conjunto seguro (setas, marcas de seleção e outros símbolos, como em "→ Entrar" ou "✓ Concluído") retém o nome inteiro. Não há supressão parcial, então o nome sai vazio mesmo quando as palavras são do vocabulário.
15. **Desafio ativo do hCaptcha.** O estado "desafio aberto" do hCaptcha só se distinguia pelo fragmento `#…frame=challenge` da URL do iframe, e fragmentos e valores de URL não atravessam. O motor continua vendo o widget (confiança HIGH, desafio PRESENTE) e nunca declara o desafio como concluído. A diferença entre presente e ativo para esse fornecedor deixou de ser observável.
16. **Títulos de contêiner e de seção.** Títulos (`h1`–`h6`) não são portadores de nome de interface e ficam retidos mesmo quando suas palavras são do vocabulário. `containerHeading` sai vazio, e o contexto "botão sob o título X" não serve mais para distinguir alvos.
17. **IDs dinâmicos.** IDs com sufixo numérico longo (ou qualquer id fora do vocabulário) não chegam ao motor. O histórico de atributos registra que o id existia, sem o valor, e a troca de id deixa de ser observável por ele.
18. **Frames identificados só por título.** Iframes irmãos que só se distinguiam pelo título ou por um id fora do vocabulário ("Filial Norte"/"Filial Sul") ficam indistinguíveis: a resolução declara `AMBIGUOUS`/`INSUFFICIENT_EVIDENCE` e nunca escolhe o frame irmão. Quando o contêiner tem identificador do vocabulário (`#compras`), o frame continua sendo distinguido.
19. **Chaves de consulta do catálogo de proteção.** O único nome de parâmetro exigido por uma assinatura (`render`, reCAPTCHA v3) foi acrescentado ao vocabulário com revisão manual, na seção de termos de fornecedores. Outros parâmetros de fornecedor (por exemplo `k`, a chave do site) continuam fora: o parâmetro some do modelo da URL.

20. **Continuidade técnica não é identidade lógica (D7).** `LIVE_NODE_CONTINUITY` diz que o mesmo objeto DOM continua
    identificável, não que ele ainda representa o mesmo registro de negócio. Mudanças de **atributos** (sem texto) não
    entram nas revisões de conteúdo: um nó reaproveitado só por atributos aparece como continuidade sem
    `CONTENT_CHANGED`. Por isso a continuidade nunca autoriza uso automático.
21. **Época por content script.** Iframes de mesma origem capturados pelo documento principal usam a época do
    principal. A recarga do iframe é detectada pela troca do frame (`FRAME_GONE`) ou dos nós (`NODE_GONE`). Iframes com
    content script próprio têm época própria.
22. **Valores estruturais legítimos fora da lista.** Um valor válido em HTML que não esteja na regra fechada (MIME raro,
    idioma ISO 639-2/3, `autocomplete` com `section-*`) vai só como presença. Para ampliar a lista é preciso revisão
    versionada do contrato.
23. **Números longos legítimos em localizadores.** Um atributo com número de mais de 2 dígitos como valor (por exemplo
    `[data-page='100']`) deixa de ser localizador transportável. Posições continuam.

24. **Componentes com nome fora do vocabulário.** O nome de um componente de biblioteca (`mat-card`, `sl-button`,
    `nk-open-card`) não atravessa: aparece como `custom-element`, e o localizador usa a posição real (`:nth-child`) em
    vez do nome. Esse localizador é mais frágil e é declarado `HEURISTIC_UNSTABLE`. Para manter um nome de biblioteca, é
    preciso uma revisão versionada do vocabulário. O vocabulário não se expande sozinho.
25. **Localizadores de outros produtores.** A gramática aceita é a do motor. Um seletor escrito à mão com construções
    fora dela (`:not(...)`, vírgula, `:has(...)`, funções XPath como `contains`) é recusado nas barreiras e declarado
    (`LOCATORS_WITHHELD`), mesmo sem dado privado.
26. **Descrição semântica longa.** Acima de 500 caracteres, a descrição do localizador vai vazia, e CSS ou XPath acima de
    500 são descartados. Antes, eram cortados.
