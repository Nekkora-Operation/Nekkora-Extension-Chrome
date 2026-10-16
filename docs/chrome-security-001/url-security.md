# CHROME-SECURITY-001: segurança das URLs (decisão D3)

## Forma

| Entrada | Saída |
|---|---|
| `https://sistema.exemplo/clientes/joao` | `https://sistema.exemplo/clientes/{…}` |
| `http://127.0.0.1:18765/lab/chrome-security-001/clientes/ataulfo-marcondes.html?cliente=Ataulfo#ficha-ataulfo` | `http://127.0.0.1:18765/{…}/{…}/clientes/{…}?cliente={…}` |
| `https://app.exemplo/clientes/consulta?status=ativo&nome=Ana` | `https://app.exemplo/clientes/consulta?status={…}&nome={…}` |
| `https://user:pass@App.Exemplo:8443/pedidos/index.html?page=2` | `https://app.exemplo:8443/pedidos/index.html?page={…}` |
| `https://app.exemplo/api/v2/clientes/483192` | `https://app.exemplo/api/v2/clientes/{…}` |
| `mailto:ana@exemplo.com`, `javascript:void(0)`, `data:…`, `blob:…` | `mailto:{…}`, `javascript:{…}`, `data:{…}`, `blob:{…}` |
| `#/clientes/benvinda-guaraciaba` (só fragmento) | `null` |

- A **origem** (esquema, host em minúsculas e porta) fica, e as credenciais caem. Critério: a origem é necessária para a política de acesso, a seleção de aba e o grafo de frames. Um host com nome de pessoa (`joao.empresa.com`) é risco residual documentado.
- **Segmentos de caminho** ficam só se o segmento decodificado inteiro for do vocabulário (`index.html`, `pedidos`, `v2`). Os demais viram `{…}`.
- **Consulta:** só os nomes de parâmetro do vocabulário, sempre `={…}`.
- **Fragmento** nunca sai, nem em rota SPA por hash. A mudança de fragmento continua observada como fato (evento `FRAGMENT`).

## Onde se aplica

Documento, frames (capturados, de outra origem e metadados do navegador), recursos, requisições, navegação difundida, contexto da etapa (`context.url`, `after.url`), evidências (`document.url`, `navigation.url`), PageMap e Studio (4ª barreira). O host reaplica em `UrlSanitizer`, `BrowserFrameMetadata`, `StepPrivacy` e `EvidencePrivacy`.

## Consequências

- A tela inferida da URL só usa segmentos do vocabulário. Com tudo omitido, a tela fica indeterminada, não "inventada".
- Frames de mesma página com caminhos diferentes podem ter o mesmo modelo. A identidade de frame continua pelo hospedeiro e pela cadeia de frames.
- `{…}` não é aceito por `java.net.URI`. O motor usa `forUri`/`fromUri` para analisar modelos sem perder a lacuna.
