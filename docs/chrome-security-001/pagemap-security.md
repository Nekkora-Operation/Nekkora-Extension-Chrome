# CHROME-SECURITY-001: segurança do PageMap (0.3.0)

- **Versão 0.3.0**, `privacy: "nekkora-privacy/2"`. O schema 0.2.0 fica congelado em `contracts/v0.2.0/page-map.schema.json` para arquivos antigos. A semântica de 0.2.0 não foi redefinida.
- **Elementos:** `text.own`, `text.descendant`, `text.accessibleName` e `contextual` só com o que atravessou. `text.suppressed: true` marca o elemento que **tem** texto retido.
- **Atributos exportáveis:** o modelo já sob a política (identificadores do vocabulário, estruturais, presenças).
- **Localizadores, família, alvos e resoluções:** gerados do modelo filtrado (ver `locator-security.md`).
- **Mutações:** `[omitido]` (ver `mutation-security.md`).
- **Documento, frames, recursos e requisições:** URLs como modelo; títulos do vocabulário.
- **Exportação explícita não abre exceção:** exportar continua sob a mesma política (SEC001-054).
- **Escrita:** atômica, como antes (`.tmp` e troca), com validação de schema. Uma exportação interrompida não deixa arquivo parcial publicado (SEC001-055, testes existentes de exportação).

Em todos os cenários do laboratório, o PageMap exportado e os arquivos `pagemaps/*.json` ficaram sem nenhum marcador (`ChromeSecurity001IT`).
