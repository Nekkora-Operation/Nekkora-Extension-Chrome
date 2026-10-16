# CHROME-SECURITY-001: segurança do registro de alvos

## Conteúdo

A `IdentitySignature` (`name`, `text`, `label`, `form`, `fieldGroup`, `table`, `rowKey`, `rowTexts`, `column`, `dialog`, `region`, `containerHeading`, `contextTexts`, `scopePath`) é extraída do modelo que o motor recebeu, **já sob a política**. Por isso só contém metadado de interface do vocabulário e estrutura. Ficam de fora valores de `data-*` (a chave de linha `data-client=202` vira presença), textos de células (`rowTexts`), cabeçalhos e títulos de seção fora do vocabulário. No Chrome real, o registro ficou limpo em todos os cenários do laboratório (`ChromeSecurity001IT`, destino "registro de alvos").

## Persistência e retenção

- Arquivo novo: `targets/registry-v2.json`. O `targets/registry.json` de versões anteriores, que podia conter texto da página, **não é lido nem apagado** (`NativeHostTest.sec001RegistroAntigoNaoELidoNemApagado`). Limpeza: ver `storage-and-retention.md`.
- Mantido: alvos de sites externos autorizados ficam **só em memória** (NEKKORA-REAL-001). Só origens de laboratório são persistidas.
- A liberação da sessão e o cancelamento seguem as regras das missões anteriores, e o conteúdo retido já não está no registro.

## Resolução

Ver `semantic-engine-security.md`: a confirmação exige uma característica do próprio elemento. Um alvo sem nenhuma característica de identidade fica "identificado parcialmente" (`INSUFFICIENT_EVIDENCE`/`AMBIGUOUS`) e nunca vira `RESOLVED` por contexto.

## Continuidade técnica do nó (decisão D7, revisão dos PRs #7)

A resolução **lógica** (o alvo encontrado corresponde ao alvo registrado, por evidência) e a **continuidade técnica** (o
mesmo objeto DOM continua identificável) são conceitos diferentes e ficam em campos diferentes do resultado:

| Campo | Significado | Autoriza |
|---|---|---|
| `status` / `resolvedNodeId` | identidade **lógica** (inalterada pela D7; indistinguível continua `AMBIGUOUS`) | usar o elemento como o alvo |
| `continuity.status = LIVE_NODE_CONTINUITY` | o **mesmo objeto DOM**, na mesma sessão documental, com tipo compatível; limitação fixa `LOGICAL_IDENTITY_NOT_REVALIDATED` | reconhecer o nó físico, consultar o estado estrutural, destacar, acompanhar mudanças |
| `continuity.status = CONTINUITY_BROKEN` | a prova de continuidade falhou (motivo em `limitations`) | nada |
| `continuity.status = NOT_PROVEN` | alvo de versão anterior (sem âncora) ou produtor sem época | nada |

A continuidade **nunca** preenche `resolvedNodeId`, nunca gera localizador persistente (`updatedLocators`/`updatedIdentity`
continuam nulos fora de `RESOLVED`) e nunca autoriza ação automática sobre alvo logicamente ambíguo.

**Prova exigida** (`TechnicalContinuity.evaluate`, sobre a âncora `NodeAnchor` registrada com o alvo):

1. a **época do registro de nós** do frame é a mesma. É um token aleatório do content script, renovado em todo
   documento novo, recarga de frame e reinicialização do registro (`configurePrefix`). Isso fecha o caso em que os
   contadores recomeçam e o mesmo id passa a outro objeto (`REGISTRY_EPOCH_CHANGED`);
2. o frame ancorado ainda existe (`FRAME_GONE`), o nó existe (`NODE_GONE`) e está no mesmo frame e escopo (`FRAME_CHANGED`,
   `SCOPE_CHANGED`);
3. tag e família de compatibilidade iguais. Mudança de tipo ou finalidade quebra a continuidade (`TYPE_CHANGED`);
4. as revisões de texto (contadores mantidos na página, sem conteúdo) do nó e da linha/item que o contém são
   comparadas. Quando mudam, a continuidade vale para o objeto, mas é declarado `CONTENT_CHANGED`/`ROW_CONTENT_CHANGED`
   (por exemplo, virtualização que reaproveita a linha para outro registro).

Quando a resolução lógica confirma um nó (`RESOLVED`), a âncora passa a ele. Fora disso, a âncora do registro é mantida.
A âncora não contém texto, valor nem hash de conteúdo da página.

Regressões: `TechnicalContinuityTest` (motor: indistinguíveis, remoção e criação na mesma posição, re-renderização,
virtualização, navegação, iframe recarregado e removido, reinicialização do registro com reuso do id, mudança de tipo,
identidade antiga sem âncora), `NativeHostTest` (reinício do Native Host com a âncora persistida e época trocada) e
`TechnicalContinuityIT` (Chrome real, página 14 do laboratório). O `NegativeCasesIT.caseA` ("dois botões indistinguíveis:
AMBIGUOUS", sem destaque) continua inalterado.
