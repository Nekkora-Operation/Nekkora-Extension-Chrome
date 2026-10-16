# CHROME-SECURITY-001: relatório final

> **Situação: entregue para revisão técnica, sem merge.** Esta missão **não libera** o Nekkora Recorder para gravações
> com dados reais de clientes. A liberação depende da análise deste relatório, dos riscos residuais (§24) e do
> cumprimento dos critérios da §25. Risco zero não é declarado em nenhuma parte deste documento.

Branches: extensão `feat/chrome-security-001` (base `main` `8f3326b`) e Studio `feat/chrome-security-001-studio-guards`
(base `main` `250778f`). Documentação técnica em `docs/chrome-security-001/` (21 documentos).

## 1. Linhas de base

| Item | Valor na base |
|---|---|
| Extensão (`main` `8f3326b`) | Java 238 · Node 134 · Chrome real 227 testes |
| Studio (`main` `250778f`) | 338 testes no Windows e 338 no Linux |
| Política de privacidade | `nekkora-privacy/1`: máscaras por formato (`maskText`, expressões regulares de CPF, e-mail, telefone…) |
| PageMap | 0.2.0 |
| Exposição medida no Chrome real | **12 de 12** cenários do laboratório com dado fictício em pelo menos um destino (`SEC001-exposure-baseline.json`) |
| Capacidade do motor (34 páginas, 359 interativos) | ver §8 e `performance.md` |

## 2. Inventário dos canais

Inventário completo em `docs/chrome-security-001/data-flow-inventory.md`: 38 fluxos de origem → transformação → destino
→ risco → política, levantados por leitura do código e por medição no Chrome real. Destinos auditados: snapshot
transportado ao motor, resposta do motor, registro de alvos (memória e disco), PageMap (resposta e arquivo), log de
eventos do service worker, `chrome.storage.session`, `chrome.storage.local`, eventos ao Studio e logs do Native Host.
Durante a missão surgiram caminhos fora da matriz inicial. Todos foram tratados: eco de identificadores em erros do
host, mensagens do Jackson, validação do Bridge no log, título da aba, `InteractionAnalyzer`, classe CSS e atributos
ARIA descritivos, título do documento, e hooks de teste legíveis em PRD (fase 13).

## 3. Modelo de ameaças

`docs/chrome-security-001/threat-model.md`. São quatro fronteiras: página → service worker → Native Host/motor →
Studio. O agente mais provável é a **retenção acidental** (registro, PageMap, logs), seguido do compartilhamento legítimo
de artefatos, da automação gravada que embute o nome do cliente, da correlação por hash, da página hostil, das versões
misturadas e da restauração/reenvio. A leitura da memória dos processos locais (A8) está fora do alcance. Os
princípios são segurança por construção, defesa em profundidade (a mesma regra nas quatro barreiras), falha segura,
nenhum substituto reversível ou correlacionável e nenhuma causalidade inventada.

## 4. Vulnerabilidades reproduzidas

Reproduzidas **antes** de qualquer correção, no Chrome real, pela cadeia completa (`ChromeSecurity001IT`, commit
`e44dcfb`):

- **SEC001-001:** a frase "o cliente joão já existe" chegava ao snapshot, ao motor, ao registro, ao PageMap, ao log do
  SW e ao `storage.session`;
- **SEC001-002:** o `aria-label` "telefone do cliente …" chegava ao **nome da etapa** no Studio;
- **SEC001-003:** o caminho `/clientes/joao-oliveira` chegava ao Studio (contexto e evidência);
- **SEC001-004:** o termo pesquisado "Quirino Valadares" saía em `exposedValue`;
- mais 30 superfícies do inventário §1: SVG `<title>`, `id`/`name` com nome de cliente, `data-*`, opção escolhida, Shadow
  DOM, conteúdo de iframe, texto alterado depois da captura, células e legenda de tabela, títulos de documento, URLs
  de iframe, recurso e requisição.

Em resumo: **12/12** cenários com exposição; o Studio recebia nome privado em 14 superfícies.

## 5. Testes executados antes das correções

- `ChromeSecurity001IT`: 12 cenários ponta a ponta com 52 marcadores fictícios (`PrivacyCanaries`), buscados com
  transformações (sem acento, minúsculas, hífen, `%20`, entidades HTML, escapes JSON/Unicode e trechos de 5+
  caracteres). Resultado na base: 12 falhas por exposição (evidência `SEC001-exposure-baseline.json`). Depois das
  correções evidentes da fase 1 (`6293dc7`), o resultado foi registrado em `SEC001-exposure-fase1.json`.
- `SemanticCapabilityIT`: a capacidade do motor sob cinco políticas simuladas, que sustentou a decisão do ponto de
  controle (`checkpoint-fase-2.md`).
- `LogSafetyTest`, que encontrou o eco de identificadores nas respostas de erro do host.

## 6. Alterações realizadas

| Commit | Conteúdo |
|---|---|
| `e44dcfb` | laboratório `chrome-security-001` (12 páginas), testes de reprodução e medição |
| `6293dc7` | correções evidentes: `exposedValue` eliminado, `NodeChange`/`ResolutionResult` sem valores, lista fechada no `eventLog`, `LogSafety`, forma fechada de identificadores |
| `abbc960` | inventário, modelo de ameaças e ponto de controle (fase 2) |
| `c351f66` | política `nekkora-privacy/2` compartilhada: vocabulário versionado, `capture-policy.js`, `CapturePrivacyPolicy.java` e vetores comuns |
| `979599c` | captura na origem, `SnapshotPrivacy` no host, motor com relações por id de nó, URL como modelo |
| `fb297f2` | etapas (SW e host), títulos (D4), frames, Bridge `client.privacy`, PageMap 0.3.0, `registry-v2` |
| `b1d10b7` | identidade sem invenção (característica do próprio elemento obrigatória), nome nunca parcial, ajustes da 1ª regressão |
| `68816db`, `afeaa38` e seguintes | categorias de atributos (frames, mídia, `slot`/`part`/`is`, `srcdoc`), `textRevision`, rótulos estruturais, opções por posição, hooks de teste só em DEV, `render` no vocabulário, deduplicação de relações, adaptação da regressão, medições e documentação |

Studio (`feat/chrome-security-001-studio-guards`): `CapturePrivacy` (cópia gerada da política Java, verificada pelos
mesmos vetores e por teste de sincronia), `RecordedStep.guard`, título da aba, URLs de evidência como modelo,
`ExtensionInfo.privacy` com aviso de política ausente, `DisplaySafety` reconhecendo `nekkora-privacy/2` e homologação
`Sec001Homologation`.

## 7. Nova política de privacidade

`nekkora-privacy/2` (`docs/chrome-security-001/privacy-policy.md`), aprovada no ponto de controle (D1+D2: vocabulário
controlado; D3: origem + modelo do caminho; D4: título só do vocabulário; D5: contratos versionados).

- Três categorias: **estrutural** atravessa; **texto da página** só como metadado de interface elegível cujo valor
  **inteiro** está no vocabulário fechado `nekkora-ui-vocabulary/1` (1062 termos PT/EN: os 928 aprovados mais termos
  técnicos revisados à mão); **valor do usuário** nunca atravessa.
- Sem detecção de dado pessoal, sem supressão parcial, sem expansão automática e sem exceção por cliente. Conteúdo de
  negócio, tabelas, mensagens e texto livre ficam retidos **mesmo** quando feitos só de palavras do vocabulário.
- Unicode: NFC, conjunto seguro de caracteres; largura zero, controle, largura total, outros alfabetos e símbolos
  recusam o valor inteiro.
- Relações entre elementos são resolvidas **na página** em ids de nó temporários: nenhum valor de id privado nem hash
  atravessa.
- A mesma implementação em JS e Java, verificada por vetores comuns (`contracts/privacy/privacy-v2-vectors.json`), com
  cópia no Studio verificada pelos mesmos vetores.

## 8. Impactos no motor semântico

Medição real, mesmo corpus (34 páginas do laboratório), mesmos comandos, build da base × build da missão
(`CapabilityMeasureIT`; detalhes em `performance.md`):

| Métrica | Antes | Depois |
|---|---:|---:|
| Elementos classificados | 925 | 925 |
| Interativos | 359 | 359 |
| Com nome legível | 350 | 181 |
| Localizador principal único | 359 | 351 |
| Localizador principal verificável (CSS/XPath) | 297 | 330 |
| Localizadores não verificáveis (só do motor) | 62 | 29 |
| Elementos sem nenhum localizador único | 0 | 0 |
| **Alvos resolvidos na página inalterada** | **355** | **178** (143 `AMBIGUOUS`, 38 `INSUFFICIENT_EVIDENCE`; nenhum resolvido para outro elemento) |
| Tempo de captura (mediana por página) | 9,1 ms | 9,9 ms |
| Tamanho do snapshot (soma) | 678 798 B | 694 973 B (+2,4%) |
| Tamanho do PageMap (soma) | 4 057 282 B | 3 253 591 B (−19,8%) |

**O que se perdeu, e por quê.** A classificação estrutural ficou intacta. Perdeu-se o nome legível onde o nome não é
do vocabulário (169 elementos) e, principalmente, a **reidentificação lógica** de alvos que só se distinguiam por
texto: opções e itens de lista, botões repetidos em linhas, campos sem id do vocabulário. Por desenho anterior
(`NegativeCasesIT.caseA`), o motor recusa escolher entre elementos indistinguíveis, mesmo com a página inalterada. As
maiores perdas estão em `dropdowns` (33 → 6), `widgets` (24 → 10), `form-basics` (26 → 13), `forms-groups` (16 → 4),
`duplicated-ids` (14 → 2) e `tables` (9 → 0). A gravação de etapas continua utilizável: cada etapa leva os localizadores
verificados no momento do clique (351/359 com principal único), e a homologação real gravou o processo completo
(§21). O que fica mais fraco é reencontrar alvos depois de mudanças na página.

**Alternativa para decisão da revisão (não implementada):** aceitar a continuidade técnica do nó (id temporário
monotônico, nunca reutilizado na sessão) como evidência de identidade dentro da mesma sessão de página. Isso não usa
texto, mas muda a semântica hoje testada ("indistinguível ⇒ AMBIGUOUS").

Também foi corrigido um defeito do motor achado na homologação real (SEC001-087): com o `aria-label` retido, o nome
acessível caía para o conteúdo do botão ("Consultar") e produzia um nome que não é o real. Agora a fonte retida fica
marcada (`{…}`) e o nome é retido inteiro.

Nenhuma informação privada foi recuperada para melhorar as métricas.

## 9. Tratamento dos localizadores

`locator-security.md`. Toda expressão (CSS, XPath, descrição semântica) só é transportável se **todo** literal passar
na política (`expressionSafe`). Ids, `name`, classes e nomes acessíveis seguem a mesma regra do texto. Estratégias que
dependiam de texto (`TEXT`, `ROLE_NAME`, `ROW_CONTEXT` por chave de linha, `CONTEXTUAL` por título) só sobrevivem com
literais do vocabulário. Sem localizador seguro, a limitação é declarada (`SAFE_LOCATOR_UNAVAILABLE`,
`LOCATORS_WITHHELD`). A estabilidade continua declarada pela evidência (posição = `HEURISTIC_UNSTABLE`); nada é
promovido a estável por falta de alternativa.

## 10. Proteção de snapshots

`capture-security.md`. A política é aplicada **na origem** (content script) e reaplicada pelo host (`SnapshotPrivacy`),
caso a extensão seja antiga. O snapshot leva `textPresent`/`textSuppressed`/`textRevision` em vez de texto, atributos por
categoria fechada, URLs como modelo, `exposedValue` sempre `null`, `document.title` anulável e a política declarada.

## 11. Proteção de deltas

`mutation-security.md`. Um delta é uma captura parcial com a mesma política. `MutationRecord` leva o **fato** da
mudança (`[omitido]` antes e depois; nome do atributo; valor só para atributos estruturais como `aria-expanded`). A
mudança de um texto retido é detectada por um contador de revisão mantido na página, sem conteúdo nem resumo.

## 12. Proteção de registros de alvos

`target-registry-security.md`. Assinaturas só com o que passou pela política. A identidade exige uma característica do
**próprio** elemento (contêiner, contexto ou tipo sozinhos não confirmam). O registro persistido passa a
`targets/registry-v2.json`; o antigo não é lido nem apagado, e o procedimento de limpeza está documentado
(`storage-and-retention.md`).

## 13. Proteção de etapas

`step-security.md`. Três barreiras: service worker (`studio-steps.js`), host (`StepPrivacy`) e Studio
(`RecordedStep.guard`). Nome, ids, localizadores, caminho de acesso, contexto e URL passam pela política. A opção
escolhida não é enviada (`OPTION_WITHHELD`) e deve ser configurada no Studio. O nome da etapa fica genérico quando o
nome do controle não é do vocabulário ("Clicar em botão", "Preencher campo de telefone").

## 14. Proteção de URLs

`url-security.md`. Origem + modelo do caminho (D3): segmentos fora do vocabulário viram `{…}`, só os nomes de parâmetro
do vocabulário aparecem e sempre sem valor, o fragmento nunca sai e outros esquemas viram `esquema:{…}`. Vale para
documento, rota, iframe, recurso, requisição, etapa, evidência e PageMap. O tipo de navegação, a identidade do documento,
o frame e a ordem são preservados.

## 15. Proteção de diagnósticos

`diagnostics-security.md`. Lista fechada de campos por tipo de evento no `eventLog`; erros com código, componente,
operação e identificador de forma fechada; diagnósticos de teste que devolvem dados crus (`frames.list`,
`testHooks.read`, `interaction.observe` com `readHooks`) recusados em PRD.

## 16. Proteção de PageMaps

`pagemap-security.md`. PageMap 0.3.0 com `privacy` declarada e `text.suppressed`. O 0.2.0 fica congelado. A exportação
é construída só a partir do que já passou pela política. Verificação programática do arquivo inteiro na homologação
(cenário 5).

## 17. Proteção do Native Host

`native-host-security.md`. Defesa em profundidade: o host reaplica a política a snapshots, deltas, etapas, evidências e
títulos, mesmo vindos de extensão antiga; `LogSafety` nos logs; respostas de erro sem eco; o `hello` do Bridge declara
a política (`client.privacy`).

## 18. Proteção do Studio

4ª barreira (`RecordedStep.guard`, `CapturePrivacy`): o Studio reaplica a política a tudo o que recebe, não confia
no produtor e **não aceita em silêncio** uma extensão sem a política atual. A barra de status mostra "extensão sem a
política de privacidade atual; o Studio revalida nomes, localizadores e endereços". Referências marcadas com a política
anterior não são exibidas como seguras. A procedência (RECORDED/MANUAL/MANUALLY_REVISED) e o modo estrito da
STUDIO-005 continuam preservados.

## 19. Compatibilidade de contratos

`compatibility.md`.

| Contrato | Mudança |
|---|---|
| `nekkora-privacy` | 1 → 2 (nova versão; a 1 não foi redefinida) |
| PageMap | 0.2.0 → 0.3.0 (0.2.0 congelado) |
| RawSnapshot | aditivo |
| `nekkora-bridge/1` | aditivo (`client.privacy`, notas novas) |
| `nekkora-ui-vocabulary` | 1 (novo) |

Não há downgrade silencioso: uma política ausente ou desconhecida nunca vale como garantia.

## 20. Testes de regressão

Plano e regras em `docs/chrome-security-001/regression-plan.md`. Resultado final (4ª rodada completa, com todas as
mudanças; nenhum teste removido):

| Suíte | Base | Final | Falhas |
|---|---:|---:|---:|
| Java núcleo + host (`run-tests.ps1 -Suite unit`) | 238 | 254 (193 + 61) | 0 |
| Node (`npm test`) | 134 | 140 | 0 |
| Chrome real, 7 grupos (+ `PageMapContractTest` por grupo) | 227 | 241 | 0 |
| Studio Windows (`./mvnw clean verify`) | 338 | 342 | 0 (nenhum ignorado) |
| Studio Linux (`tools/golden-linux.sh verify`) | 338 | 342 | 0 |

Rodadas anteriores (registro honesto): 1ª rodada do Chrome real com 78 falhas em 240; 2ª, com 48; 3ª, com 1 (relações
`LABELLED_BY` duplicadas em sentidos opostos, corrigido). As adaptações exigem o resultado exato da política e uma
verificação estrutural equivalente. Onde o texto era o único diferencial, o teste exige o resultado honesto
(`AMBIGUOUS`/`INSUFFICIENT_EVIDENCE`/`IDENTITY_MISMATCH`) e nunca o elemento errado. Os golden screenshots não foram
regenerados. Defeitos achados pela regressão e pela homologação e corrigidos: hooks de teste legíveis em PRD,
duplicação de relações, `Json.NULL` tratado como expressão insegura no Studio (descartava localizadores válidos),
nome acessível por fonte de prioridade menor (SEC001-087) e degradação de captura (memória de decisões só no content
script).

## 21. Homologação real

Windows 11, Chrome for Testing 155.0.8059.39, extensão real (DEV), Native Host instalado pelo script oficial, Studio
desta branch, mouse e teclado do sistema operacional (Robot) e só páginas fictícias. Em cada cenário, o domínio da
automação, a tela do Studio, `chrome.storage` (session e local), o log de eventos do SW e os logs e arquivos do host
foram varridos por 60 canários fictícios (os 52 da matriz e 8 das páginas usadas) (sem acento, minúsculas, separadores normalizados) e pelos
valores digitados. Evidências em `reports/chrome-security-001/homologacao/`.

| Cenário | Resultado |
|---|---|
| 1. Processo completo | Autenticação = [Preencher campo Usuário, Preencher campo Senha, Clicar no botão Entrar]; Consulta = [Clicar no link Clientes, Preencher campo Pesquisa, Selecionar opção no campo Estado, Clicar no botão Pesquisar]. Tipos corretos, senha sensível sem valor, opção não registrada (o Studio avisa para configurar). Nenhum valor digitado nem "São Paulo" em lugar algum |
| 2. Nome acessível sensível | botão `aria-label="Consultar cliente Bartolomeu Quixadá"` → "Clicar no botão" (nome retido inteiro, fonte declarada); campo rotulado por "telefone de …" → "Preencher campo" |
| 3. URLs sensíveis | rota History API, fragmento e novo documento chegam como `http://127.0.0.1:18765/{…}/{…}/clientes/{…}` e `…?cliente={…}`; aba identificada pelo host |
| 4. Captura técnica | captura completa + deltas de texto e atributo: snapshot, resposta, eventLog, estado de depuração e host sem os textos dinâmicos ("Ulisses Paranhos", "Firmino Laranjeira"); política `nekkora-privacy/2` declarada |
| 5. Exportação | PageMap 0.3.0 exportado (100 176 caracteres) examinado inteiro: nenhum canário |
| 6. Recuperação | service worker encerrado (id do worker trocou) e Native Host desconectado/reconectado durante a gravação: 4 etapas exatas, sem duplicação nem reenvio; storage e eventLog limpos |
| 7. Elementos difíceis | iframe autorizado (trajetória com IFRAME, nome privado retido), Shadow DOM (SHADOW_ROOT, posicional instável declarado), tabela (2ª linha; posicional, nenhum localizador com titular/pedido), componente dinâmico; todo preferido validado na página real |
| 8. Compatibilidade | (a) extensão nova declara `nekkora-privacy/2` e o Studio aceita sem aviso; (b) extensão e host da **linha de base** com o Studio novo: aviso "extensão sem a política de privacidade atual…", nome privado revalidado, localizadores com texto descartados, domínio e tela limpos (5/5); (c) Studio da base com a extensão nova: os 56 eventos válidos do contrato novo validam no schema do Bridge da base, e o `hello` da base é lido por chave (campo novo ignorado) |

Resultado: **38 verificações, 0 falhas** (completo) e **5 verificações, 0 falhas** (compatibilidade). A primeira
execução falhou em 2 verificações e revelou o defeito SEC001-087 (§8) e a falta de aviso visível da opção retida; os
dois foram corrigidos antes da execução final.

## 22. Resultados de desempenho

Detalhes em `docs/chrome-security-001/performance.md` (mesmas classes nos dois builds, mesma máquina).

- **Captura:** a primeira medição mostrou até +30% (a política avaliada para cada nó e atributo). Isso foi
  investigado e resolvido com memória de decisões **só no content script** (no SW e no host não há cache de texto).
  Depois disso: 10 mil elementos em 399 → 378 ms; captura cooperativa de 18,7 mil nós em 346 → 343 ms, com fatias
  < 11 ms. A captura síncrona dos maiores casos ficou até 12% mais lenta.
- **Motor na base (host):** 105 → 143 ms com 9,3 mil nós. É a reaplicação da política no host (defesa em
  profundidade), sem cache de propósito.
- **Seleção:** mais rápida (10 mil: fria 114,5 → 77,1 ms; aquecida 87,1 → 52,1 ms).
- **Exportação:** 35% menor e até 2× mais rápida.
- **Etapas:** clique → etapa no Studio p50 815 → 818 ms (dominado pela janela de silêncio); eventos 13% menores; 0
  perdas.
- **Normalização (JS, sem cache):** 1,5 a 4,2 µs por chamada.
- **Heap do host** na sessão contínua: 90/376 MB (antes 110/428 MB).

## 23. Limitações conhecidas

`docs/chrome-security-001/known-limitations.md` (19 itens). Os mais visíveis no uso:

- nomes de etapa genéricos quando o nome do controle não é do vocabulário;
- linhas de tabela, filiais e frames que só se distinguiam por texto passam a ser declarados ambíguos;
- a opção escolhida precisa ser configurada no Studio;
- títulos de aba e de frame fora do vocabulário somem;
- rotas por hash não se distinguem pela URL;
- menções textuais a CAPTCHA e o desafio ativo do hCaptcha deixaram de ser observáveis.

## 24. Riscos residuais

Nenhum destes riscos é zero:

1. **Vocabulário x nomes próprios:** uma palavra de interface que também seja nome de pessoa ou de empresa pode
   atravessar num contexto raro, e a combinação de várias palavras do vocabulário pode, em tese, compor um dado. A
   elegibilidade (só portadores de nome) e a exclusão dos sobrenomes frequentes reduzem, mas não eliminam, o risco.
2. **Identificadores do desenvolvedor feitos de palavras do vocabulário** (por exemplo `cliente-ativo`) atravessam;
   um id montado com dado de negócio composto só de termos comuns passaria.
3. **Estrutura como canal lateral:** quantidade de linhas, posição e tamanho não são tratados.
4. **Memória dos processos locais** (A8): fora do alcance.
5. **Dados de versões anteriores** (`registry.json`, PageMaps 0.2.0, logs) continuam no disco até o usuário
   removê-los; apagar não garante eliminação física.
6. **Cobertura dos testes:** a busca encontra os marcadores plantados e as transformações conhecidas. Uma
   transformação desconhecida (outra codificação, por exemplo) poderia escapar.
7. **Construção DEV:** os diagnósticos de teste continuam devolvendo dados crus em DEV. A distribuição a usuários
   precisa ser PRD.
8. **Studio antigo:** um Studio anterior a esta branch aceita o que recebe. A proteção dele depende das barreiras da
   extensão e do host.
9. **Manutenção do vocabulário:** cada inclusão é uma decisão de segurança. Uma revisão descuidada amplia o que
   atravessa.

## 25. Critérios de liberação para dados reais

A liberação **não** é automática e **não** está concedida por este relatório. Proposta de critérios, a decidir pela
revisão:

1. revisão técnica e de segurança destes PRs aprovada, com merge feito pela responsável;
2. revisão do vocabulário `nekkora-ui-vocabulary/1` (1062 termos) por uma segunda pessoa, com atenção aos riscos 1 e 2;
3. distribuição só em construção **PRD**, com modo travado (diagnósticos de teste recusados);
4. Studio com a barreira desta branch (versão mínima definida no release), nunca combinado com Studio anterior;
5. procedimento de limpeza dos dados de versões anteriores executado nas máquinas que vão gravar
   (`storage-and-retention.md`);
6. homologação repetida com páginas sintéticas que imitem as aplicações reais do cliente (sem dados reais),
   confirmando zero marcadores nos destinos auditados;
7. avaliação de aceitação dos impactos funcionais (§8 e §23) pelos usuários do Recorder;
8. decisão explícita registrada sobre os riscos residuais da §24;
9. só depois disso, um piloto controlado e com escopo definido, com monitoramento dos artefatos gerados.

## Apêndice: critérios de aceite

| Critério | Exigência | Evidência |
|---|---|---|
| CA-001 | Captura técnica não transmite textos arbitrários proibidos | `ChromeSecurity001IT` 12/12 sem exposição (SNAP, MOTOR); homologação cenário 4 |
| CA-002 | Deltas não expõem valores privados | página 9 (dinâmica) e cenário 4: deltas sem texto, `textRevision` |
| CA-003 | MutationRecord não revela conteúdos proibidos | `[omitido]`, nome do atributo; `SnapshotComparator` e LOG-SW limpos |
| CA-004 | Registro de alvos respeita a política | destino REG limpo; `registry-v2.json` |
| CA-005 | Etapas não recebem nomes acessíveis privados | cenários 1, 2 e 8b; destino STUDIO limpo |
| CA-006 | Localizadores não incluem textos privados | `expressionSafe` nas três implementações; `LOCATORS_WITHHELD` |
| CA-007 | URLs são minimizadas | origem + modelo `{…}`; cenário 3 |
| CA-008 | Logs não revelam conteúdos da página | `LogSafety`; LOG-HOST limpo em todos os cenários |
| CA-009 | Diagnósticos respeitam a política | lista fechada no `eventLog`; diagnósticos crus só em DEV |
| CA-010 | Chrome Storage não retém informações proibidas | SES/LOC limpos; cenário 6 |
| CA-011 | Native Host aplica defesa em profundidade | `SnapshotPrivacy`/`StepPrivacy` reaplicam a v2; testes de host |
| CA-012 | Studio rejeita dados incompatíveis | `RecordedStep.guard`; cenário 8b (extensão antiga) |
| CA-013 | PageMap exportado respeita a política | cenário 5; destino PM limpo |
| CA-014 | Nenhum valor digitado é exportado automaticamente | `exposedValue` eliminado; valores digitados ausentes em todos os destinos |
| CA-015 | Shadow DOM e iframe permanecem protegidos | páginas 7 e 8; cenário 7 |
| CA-016 | Reenvios não recuperam conteúdos privados | cenário 6 (SW reiniciado, host reconectado) |
| CA-017 | A classificação estrutural continua funcional | 925/925 classificados, mesma classificação (§8) |
| CA-018 | Localizadores inseguros são rejeitados ou degradados | `SAFE_LOCATOR_UNAVAILABLE`, `LOCATORS_WITHHELD`, posicional declarado instável |
| CA-019 | Nenhuma validação técnica é inventada | identidade exige característica própria; SEC001-087; resultados `AMBIGUOUS`/`INSUFFICIENT_EVIDENCE` honestos |
| CA-020 | A STUDIO-003 continua funcionando | `Studio003InteractionsIT` 14/14; cenário 1 |
| CA-021 | A STUDIO-004 continua funcionando | suíte do Studio (editor) aprovada no Windows e no Linux |
| CA-022 | A STUDIO-005 continua funcionando | `Studio005EvidenceIT` 16/16; modo estrito preservado |
| CA-023 | Os contratos são versionados corretamente | `nekkora-privacy/2`, PageMap 0.3.0, aditivos no RawSnapshot e no Bridge (§19) |
| CA-024 | As suítes existentes permanecem aprovadas | §20 (nenhum teste removido) |
| CA-025 | A matriz SEC001 passa integralmente | `ChromeSecurity001IT`, `CapturePrivacyPolicyTest`, `SnapshotPrivacyTest`, `capture-policy.test.js`, `CapturePrivacyTest` (Studio) |
| CA-026 | A homologação real é aprovada | §21: 38/38 e 5/5 |
| CA-027 | Os riscos residuais são documentados | §24 |
| CA-028 | Os PRs são entregues sem merge | PRs abertos para revisão, sem merge |
