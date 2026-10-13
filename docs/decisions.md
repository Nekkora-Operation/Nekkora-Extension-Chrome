# Registro de decisões

| ID | Decisão | Alternativas consideradas | Justificativa / evidência |
|---|---|---|---|
| D-001 | **Native Messaging** como canal Chrome ↔ Java | servidor Java em `127.0.0.1` | sem porta aberta, sem CORS, processo iniciado pelo próprio Chrome e restrito à extensão autorizada (`allowed_origins`); fragmentação implementada e testada (C-2, C-3); reconexão testada (C-5) |
| D-002 | Regras de análise **somente em Java**; JS restrito a APIs do navegador | motor em JS/TS | requisito da missão; motor testável sem navegador (113 testes unitários) e reutilizável pelo Studio |
| D-003 | Extensão em **JavaScript puro**, sem bundler | TypeScript + build | elimina etapa de build e dependências; content scripts clássicos compartilham um namespace; testes com `node --test` sem pacotes |
| D-004 | **Maven** multi-módulo com wrapper | Gradle | mesma convenção do Nekkora-Studio (`com.nekkora`, `mvnw`); Maven já em cache local |
| D-005 | Testes de navegador com **Chrome for Testing** via Selenium | Chrome de marca | Chrome de marca ignora `--load-extension` desde a 137; CfT tem versão fixa e baixável (`ChromeForTesting`) |
| D-006 | **Arquivos JSON** locais; sem PostgreSQL | PostgreSQL + Docker | nenhum requisito desta fase exige consultas relacionais; registro de alvos, PageMaps e evidências cabem em arquivos; testes rodam sem banco. Reavaliar quando o Studio consumir histórico multi-sessão |
| D-007 | Destaque por **overlay independente** (shadow root fechado) | `outline` inline no elemento | não altera estilo/layout (verificado em T034: retângulo e estilo do alvo idênticos antes/depois), não contamina a captura (overlay excluído) e acompanha re-renderização |
| D-008 | **Id fixo** da extensão por chave pública no manifesto | id variável de carga não compactada | `allowed_origins` previsível; o instalador calcula o id a partir da chave (sem digitação); chave privada não versionada |
| D-009 | Registrar host em `HKCU\Software\Google\Chrome` e `HKCU\Software\Chromium` | incluir `Software\Google\Chrome for Testing` | verificado experimentalmente: o CfT 155 **não** lê `Google\Chrome for Testing`, lê `Chromium` (registro somente CfT → `HOST_UNAVAILABLE`; somente Chromium → sucesso) |
| D-010 | **Licença não definida**: "todos os direitos reservados" até decisão da Nekkora | MIT / Apache-2.0 | a missão pede avaliação antes da publicação; a biblioteca de referência é `UNLICENSED`. Decisão pertence à Nekkora |
| D-011 | Valores de campos nunca exportados, exceto **campos de pesquisa** | exportar todos / nenhum | permite distinguir "consulta realizada pela interface" (§14) sem expor dados pessoais; sensíveis bloqueados em duas camadas (T033) |
| D-012 | Mutações por **deltas incrementais** encadeados | recaptura completa a cada mutação | requisito de desempenho; 513 mutações → 7 deltas, máx. 7,6 ms no host (T037) |
| D-013 | **Discriminadores** calculados no registro contra elementos parecidos | comparar somente com o seletor | evita confundir botões idênticos em linhas/tabelas/formulários diferentes; um alvo indistinguível no registro nunca é resolvido depois (T032) |
| D-014 | Chaves estruturais (formulário, grupo, tabela, diálogo, título) comparadas **estritamente** | similaridade de tokens | "Endereço de cobrança" × "Endereço de entrega" tinham similaridade parcial; identidade não admite semelhança parcial (teste `billingStateIsNotShippingState`) |
| D-015 | Ids/names com aparência dinâmica têm estabilidade **reduzida**, nunca rejeitada | descartar ids "aleatórios" | requisito §11; estabilidade comprovada por observação (`OBSERVED_*`) prevalece sobre heurística (T005) |
| D-016 | Hooks de teste: lista fechada, somente leitura, marca `__nekkoraLab`, só em origem local, via `chrome.scripting` no mundo da página | percorrer `window` | requisito §15.2; função com efeito colateral fora da lista comprovadamente não executada (T038) |
| D-017 | Harness e página em **janelas separadas** nos testes, sem estrangulamento por oclusão | abas | abas em segundo plano suspendem `requestAnimationFrame` e estrangulam timers, invalidando medições de responsividade e acompanhamento do destaque |
