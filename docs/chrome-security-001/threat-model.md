# CHROME-SECURITY-001: modelo de ameaças

## Ativos

| Ativo | Exemplos (fictícios) | Categoria |
|---|---|---|
| Texto da página | "o cliente joão já existe", nomes em tabelas, títulos, rótulos, cabeçalhos | texto da página |
| Valores do usuário | termos pesquisados, campos preenchidos, opção escolhida | valor do usuário |
| Identificadores de negócio embutidos | `cliente-anacleto-prudencio`, `pedido_483192`, `/clientes/joao-oliveira` | texto da página (em atributo ou URL) |
| Segredos | senhas, tokens, OTP | valor do usuário (já protegido) |
| Estrutura | tag, papel, estado, posição, hierarquia, frame, Shadow DOM | estrutural (permitido) |

## Fronteiras de confiança

```
página (DOM, JS da página)
   │ content script, mundo isolado            ← fronteira 1: o que sai da página
service worker (memória, storage.session/local, eventLog)
   │ Native Messaging (stdin/stdout)          ← fronteira 2: o que entra no processo do host
Native Host + motor Java (memória, logs, registry.json, pagemaps)
   │ Bridge local (canal autenticado)         ← fronteira 3: o que vai ao Studio
Nekkora Studio (domínio, tela; persistência na STUDIO-006)
```

Toda saída retida (disco, armazenamento, log) ou exportável (PageMap, automação, relatório) pode ser copiada,
compartilhada com suporte, sincronizada, indexada ou guardada em backup. É por aí que a maior parte do risco se realiza.

## Agentes e cenários

| # | Agente / situação | Ameaça | Superfícies |
|---|---|---|---|
| A1 | **Retenção acidental** (o mais provável) | texto do cliente fica em `registry.json`, PageMaps e logs, e sobrevive à sessão | REG, PM, LOG-HOST |
| A2 | **Compartilhamento legítimo** | o usuário envia o PageMap, o log ou a automação ao suporte ou a outra equipe, sem saber o que há dentro | PM, LOG, Studio (STUDIO-006) |
| A3 | **Automação gravada** | o localizador ou o nome da etapa embute o nome do cliente (`//button[text()='Consultar João Oliveira']`) e passa a fazer parte do projeto | Studio |
| A4 | **Correlação** | hash determinístico ou pseudônimo estável permite ligar registros ou testar valores prováveis | qualquer saída com "substituto" |
| A5 | **Página hostil** | a página coloca texto em campos de identificador, atributos ou mensagens para fazê-lo atravessar ou ser ecoado em erros | SNAP, respostas de erro, logs |
| A6 | **Versões misturadas** | extensão antiga + host/Studio novos (ou o inverso) fazem dado inseguro ser aceito como se fosse da política nova | contratos |
| A7 | **Restauração e reenvio** | depois de reinício do SW, reconexão ou reenvio, dado antigo (de antes da política) reaparece | SES, outbox, registry |
| A8 | Acesso local à memória dos processos | quem lê a memória do host também lê a do Chrome | memória |

O A8 está **fora do alcance** desta missão: o próprio navegador mantém a página em memória. O objetivo é que nada
privado seja **retido, exportado ou enviado adiante** e que o processo do host não precise conhecer o texto (ver
D1 em `checkpoint-fase-2.md`).

## Princípios de mitigação

1. **Segurança por construção:** um contrato sem campo para o dado proibido. O que não pertence a uma lista fechada
   não é transportado.
2. **Defesa em profundidade:** a mesma regra no content script, no service worker, no host e no Studio. Cada barreira
   assume que a anterior falhou.
3. **Falha segura:** na dúvida, não transportar e declarar a limitação.
4. **Sem substituto reversível ou correlacionável:** nada de hash determinístico de baixo espaço nem de texto
   original em campo oculto.
5. **Sem causalidade inventada:** a perda de texto nunca vira um localizador "verificado", uma identidade ou uma
   validação que não aconteceu.

## Riscos residuais conhecidos (independentes da decisão)

- Uma lista fechada pode conter uma palavra que, num contexto raro, também seja um nome. Por isso o vocabulário exclui
  de propósito os sobrenomes comuns que também são palavras.
- A estrutura (quantidade de linhas, posição) pode, em casos extremos, revelar algo sobre o conteúdo. Isso não é
  tratado.
- A memória dos processos locais (A8).
- Arquivos produzidos por versões anteriores continuam no disco até o usuário removê-los (procedimento em
  `storage-and-retention.md`).
