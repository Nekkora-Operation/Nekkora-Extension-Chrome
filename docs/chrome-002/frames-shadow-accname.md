# Iframes, Shadow DOM, AccName e cobertura (CHROME-002)

## Iframes de origens locais

`manifest.json` passou a `all_frames: true` **sem ampliar permissões** (`host_permissions` continua
`http://127.0.0.1/*` e `http://localhost/*`, qualquer porta). Cada documento é inventariado por
**uma única fonte**:

| Situação | Fonte | Ids | Resultado |
|---|---|---|---|
| Documento principal | `top` | `n…`, frames `f0, f1…` | capturado |
| Iframe de mesma origem | `top` (o principal lê o frame) | `n…`, frame `f<n>` | capturado pelo pai (`CAPTURED_BY_PARENT`); o content script do frame não duplica |
| Iframe de **outra origem permitida** (ex.: `127.0.0.1:18765` → `localhost:18766`) | `x<frameId>` (content script do próprio frame) | `x<id>n…`, frames `c<id>…` | anexado ao iframe hospedeiro (`capture.session.attachFrame`) |
| Aninhado (outra origem dentro de outra origem) | `x<frameId>` do neto; hospedeiro procurado no frame pai | idem | anexado (C002-030) |
| Origem sem permissão (ex.: `127.0.0.2`) | — (sem content script) | — | declarado `CROSS_ORIGIN`, não inspecionado (C002-032) |
| `sandbox` | — | — | não anexado por política (`POLICY_SANDBOX`), declarado `SANDBOXED` |

**Handshake** do hospedeiro: o service worker envia um token aleatório ao frame (`cs.frame.announce`),
o frame faz `parent.postMessage({__nekkoraFrame: token})` e o content script do pai identifica o
iframe cujo `contentWindow` é a origem da mensagem (`cs.frame.lookup`). Nenhum dado da página trafega.

Ciclo de vida: frame inserido ⇒ anexado (C002-031); removido ⇒ o delta do pai remove a subárvore e o
motor descarta as entradas de frame daquela fonte (`Sources.pruneDetachedFrames`); documento
substituído ⇒ reanexado (a fonte é substituída inteira, sem duplicar). Toda nova base do documento
principal reanexa os frames (nova época). Ids duplicados entre documentos não colidem (prefixos).
Seleção, verificação ao vivo e destaque são roteados para o frame dono do id (`x<frameId>n…`).

## Shadow DOM

| Situação | Tratamento | Teste |
|---|---|---|
| Componente com shadow aberto criado depois da base | o host entra por `childList`; o shadow root é descrito e passa a ser **observado** | C002-026 |
| Shadow aninhado | idem, recursivo | C002-026 |
| Conteúdo alterado dentro do shadow root | observador do shadow root | C002-026 |
| Slots | conteúdo projetado inventariado | C002-026 |
| Host removido | nós e declaração do shadow root descartados; observador desligado na reconciliação | C002-027 |
| `attachShadow()` em host **já conectado** | não gera registro no `MutationObserver`; detectado pela **reconciliação** a cada 1 s (varredura de `element.shadowRoot` no documento, frames acessíveis e shadow roots conhecidos) | C002-028 |
| Shadow **fechado** | detectado (`chrome.dom.openOrClosedShadowRoot`), declarado `SHADOW_ROOT_CLOSED`, não inventariado | C002-028, T018 |

Sem monkeypatch de `attachShadow` em produção. **Limitação residual medida**: latência de detecção
de `attachShadow()` tardio ≤ intervalo de reconciliação (1 s) + barreira; medido **≈ 1 s**
(`reports/evidence/C002-dynamic-evidence.json`, critério ≤ 3 s).

## Nome acessível (AccName 1.2 / HTML-AAM)

Implementação no motor Java (`AccessibleNameCalculator`); o navegador apenas captura atributos,
textos e, agora, **segmentos de texto** (`textSegments`) que preservam a ordem texto/elemento.

| Caso | Regra | Resultado validado |
|---|---|---|
| `aria-labelledby` múltiplo | ordem declarada | "Remessa internacional" (C002-034) |
| Autorreferência + `aria-label` | o próprio nó contribui sem reaplicar 2B | "Enviar internacional" (C002-034) |
| Referência recursiva | `aria-labelledby` do referenciado **não** é seguido | "ignorado" (C002-033) |
| Referência circular | termina; cada nó visitado uma vez | "Ciclo um Ciclo dois" (C002-035) |
| Oculto referenciado | incluído | "Rótulo oculto referenciado" (C002-036) |
| Ícones | `<svg><title>`, `img alt`, `aria-hidden`, `title`, `aria-label` | C002-037 |
| Controles embutidos | `select` contribui com a opção selecionada na posição correta | "Mostrar 20 itens" (unitário) |
| Conteúdo misto | `Ver <strong>detalhes</strong> do pedido` | "Ver detalhes do pedido" (C002-037) |
| `placeholder`, `title`, `fieldset/legend`, `aria-describedby` | HTML-AAM / descrição | C002-037 |

Cada nome registra **fonte** (`ARIA_LABELLEDBY`, `ARIA_LABEL`, `LABEL_ELEMENT`, `CONTENT`, `TITLE`,
`PLACEHOLDER`…) e **confiança** (`HIGH`, `MEDIUM`, `LOW`, `NONE`). Os testes comparam o resultado com
o nome calculado pelo próprio Chrome (CDP `Accessibility.getPartialAXTree`) — referência de
homologação, **não** dependência do domínio: 17 casos, todos concordantes (tabela em
`reports/evidence/C002-dynamic-evidence.json`). O Chrome não apara espaços de fronteira em um caso
(texto após ícone `aria-hidden`); a comparação normaliza espaços.

Não se declara conformidade integral com AccName 1.2: pseudo-elementos CSS (`::before/::after`),
`aria-owns` e nomes de controles de intervalo continuam fora do escopo (docs/limitations.md).

## Cobertura

O `CoverageReport` do PageMap declara frames (inspecionados ou não, com motivo e URL) e shadow roots
(abertos/fechados). Entradas cujo hospedeiro deixou de existir são removidas pelo motor a cada delta.
