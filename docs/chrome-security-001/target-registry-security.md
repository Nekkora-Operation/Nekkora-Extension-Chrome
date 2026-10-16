# CHROME-SECURITY-001: segurança do registro de alvos

## Conteúdo

A `IdentitySignature` (`name`, `text`, `label`, `form`, `fieldGroup`, `table`, `rowKey`, `rowTexts`, `column`, `dialog`, `region`, `containerHeading`, `contextTexts`, `scopePath`) é extraída do modelo que o motor recebeu, **já sob a política**. Por isso só contém metadado de interface do vocabulário e estrutura. Ficam de fora valores de `data-*` (a chave de linha `data-client=202` vira presença), textos de células (`rowTexts`), cabeçalhos e títulos de seção fora do vocabulário. No Chrome real, o registro ficou limpo em todos os cenários do laboratório (`ChromeSecurity001IT`, destino "registro de alvos").

## Persistência e retenção

- Arquivo novo: `targets/registry-v2.json`. O `targets/registry.json` de versões anteriores, que podia conter texto da página, **não é lido nem apagado** (`NativeHostTest.sec001RegistroAntigoNaoELidoNemApagado`). Limpeza: ver `storage-and-retention.md`.
- Mantido: alvos de sites externos autorizados ficam **só em memória** (NEKKORA-REAL-001). Só origens de laboratório são persistidas.
- A liberação da sessão e o cancelamento seguem as regras das missões anteriores, e o conteúdo retido já não está no registro.

## Resolução

Ver `semantic-engine-security.md`: a confirmação exige uma característica do próprio elemento. Um alvo sem nenhuma característica de identidade fica "identificado parcialmente" (`INSUFFICIENT_EVIDENCE`/`AMBIGUOUS`) e nunca vira `RESOLVED` por contexto.
