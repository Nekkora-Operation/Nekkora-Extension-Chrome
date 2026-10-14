# Limitações conhecidas (NEKKORA-REAL-001)

A conclusão desta missão autoriza **testes exploratórios controlados** em sites autorizados. **Não** representa
homologação para produção.

## Escopo homologado

1. Página real homologada: **somente a Wikipedia** (`en.wikipedia.org`, autorizada pela usuária para o teste), em
   uma execução da suíte opcional `-Suite real`. Outros sites não foram exercitados; o conteúdo da Wikipedia muda
   sem aviso, e a suíte depende de internet.
2. Iframes de outra origem, SPA, Shadow DOM aberto, revogação e fixtures de proteção em origem "externa" foram
   homologados com hosts de teste (`*.nekkora.test` → 127.0.0.1), não em sites de terceiros.
3. O **diálogo real** de permissão do Chrome ("Autorizar este site" / "Autorizar todos os sites…") não é clicado
   pela automação; a concessão na homologação é feita na instalação da construção de teste. A verificação do
   diálogo é manual.
4. Windows 11 + Chrome for Testing 155 / Chrome 155, como nas missões anteriores.

## Privacidade

1. Dados pessoais **sem formato reconhecível** (nomes, endereços, textos livres) não são detectados; o texto
   visível continua sendo coletado para a análise semântica. A política é por regras, sem inteligência artificial.
2. Heurística de segredo em caminho/valor é conservadora: pode redigir valores inofensivos (ex.: nomes de arquivos
   com hash, `oldid` numérico) — preferível a vazar. Valores de parâmetros de nome livre (`q`, `nome`) são mantidos
   quando curtos e sem dado pessoal reconhecível, porque fazem parte do estado de filtros/pesquisa analisado.
3. Telefones só são reconhecidos em formato explícito (com parênteses ou `+`); sequências de ≥ 6 dígitos são
   redigidas apenas em URLs.
4. Alvos de sites externos ficam só em memória: o reencontro de um alvo de site externo não sobrevive ao reinício do
   motor (no laboratório continua persistido).
5. O texto mascarado (`[email]`…) deixa de servir como localizador por texto/atributo; o elemento continua
   localizável pelas demais estratégias.

## Páginas reais

1. **Tamanho**: o artigo "Brazil" (13.134 elementos exportados) gera um PageMap de ~146 MB em disco; acima de 48 MiB (JSON compacto)
   o PageMap não é devolvido ao painel (somente o arquivo). O tamanho por elemento (famílias, localizadores,
   relações) é um ponto de otimização para o Studio.
2. **Classe trocada em ancestral grande** (ex.: `body` ao rolar): redescreve o elemento e só os descendentes cuja
   visibilidade mudou; outros estilos derivados dos descendentes (ex.: `cursor`) só são reavaliados na próxima
   mutação deles ou na próxima base.
3. **Redirecionamento para outra origem** (ex.: criação de conta da Wikipedia → `auth.wikimedia.org`): a
   autorização não se estende; a página fica `PERMISSION_REQUIRED` até o usuário autorizar aquela origem.
4. **Ambiguidade real**: páginas com muitos links de mesmo nome (citações, "edit") recebem `AMBIGUOUS_ELEMENTS`
   e `REQUIRES_SPECIAL_HANDLING` na prontidão — é uma observação correta, não uma falha.

## Proteções

1. Nenhuma tecnologia de proteção foi observada nas páginas reais visitadas (`NOT_OBSERVED`, sempre com a ressalva
   de que isso não comprova ausência). A detecção em páginas reais de fornecedores **não** foi homologada: os
   acertos continuam demonstrados apenas com fixtures simuladas (agora também servidas por origem "externa").
2. A página de criação de conta (onde a Wikimedia pode exigir verificação humana) é servida por outra origem e não
   foi inspecionada. Falsos negativos possíveis: verificações aplicadas no servidor, CAPTCHAs próprios (ex.:
   FancyCaptcha do MediaWiki, fora do catálogo) e verificações carregadas só após interação.

## Desempenho

Medições repetidas (7 execuções × frio/aquecido) estão em `reports/real-001/performance.md`. A variação entre
rodadas é real e grande no cenário aquecido (CV ≈ 40%, p95 ≈ 2,4× a mediana em 10.000 elementos) e a primeira
medição após iniciar a JVM do motor é muito mais lenta; comparar medições isoladas não permite concluir regressão.
