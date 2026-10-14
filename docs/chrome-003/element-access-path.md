# Element Access Path (CHROME-003 §5, §20, §21)

`ElementAccessPath` descreve **como chegar** a um elemento: a sequência de escopos do documento principal até o
elemento, com um localizador **no escopo de cada passo**. Não existe uma string CSS única atravessando documentos
(§20): cada passo executa no documento indicado em `documentId`.

| Campo | Significado |
|---|---|
| `steps[].type` | `MAIN_DOCUMENT`, `IFRAME`, `SHADOW_ROOT`, `ELEMENT` |
| `steps[].documentId` | documento onde o passo é executado (para `IFRAME`: o documento que **contém** o iframe) |
| `steps[].enteredDocumentId` / `browserDocumentId` | documento em que se entra (lógico / Chrome, quando conhecido) |
| `steps[].locator`, `locatorStrategy`, `matchCount`, `stability` | localizador do passo, sempre com `matchCount` no escopo |
| `steps[].expectedCondition` | `DOCUMENT_ACTIVE`, `FRAME_DOCUMENT_LOADED (<caminho>)`, `SHADOW_ROOT_OPEN`, `ELEMENT_PRESENT` |
| `steps[].restriction` | `PERMISSION_DENIED`, `SANDBOX_RESTRICTED`, `FRAME_LOADING`, `SHADOW_ROOT_CLOSED`… |
| `frameDepth`, `shadowDepth`, `documentsTraversed` | profundidade da trajetória |
| `accessible`, `blockingRestriction` | se todos os passos são executáveis e, se não, o primeiro bloqueio |
| `explanation` | explicação legível gerada **das evidências** (§21) |

## Exemplo real (cenário C, F003-006)

```json
{
  "target": { "kind": "BUTTON", "name": "Confirmar pagamento" },
  "frameDepth": 2, "shadowDepth": 1, "documentsTraversed": 3, "accessible": true, "stability": "HEURISTIC_STABLE",
  "steps": [
    { "type": "MAIN_DOCUMENT", "enteredDocumentId": "doc:f0", "browserDocumentId": "3756E833…", "expectedCondition": "DOCUMENT_ACTIVE" },
    { "type": "IFRAME", "label": "Financeiro", "documentId": "doc:f0", "enteredDocumentId": "doc:f2",
      "locator": "iframe#f-financeiro", "matchCount": 1, "expectedCondition": "FRAME_DOCUMENT_LOADED (/lab/chrome003/financeiro.html)" },
    { "type": "IFRAME", "label": "Pagamentos", "documentId": "doc:f2", "enteredDocumentId": "doc:f4",
      "locator": "iframe#f-pagamentos", "matchCount": 1 },
    { "type": "SHADOW_ROOT", "documentId": "doc:f4", "locator": "nk-pagamento#form-pagamento", "expectedCondition": "SHADOW_ROOT_OPEN" },
    { "type": "ELEMENT", "documentId": "doc:f4", "locatorStrategy": "CONTEXTUAL",
      "locator": "form {aria-label=Pagamento} >> button {text=\"Confirmar pagamento\"}", "matchCount": 1 }
  ],
  "explanation": "O elemento button «Confirmar pagamento» foi encontrado no formulário «Pagamento», no 2º nível de iframe da página e dentro de 1 shadow root. O documento principal contém o iframe «Financeiro» (iframe#f-financeiro), que contém o iframe «Pagamentos» (iframe#f-pagamentos), que contém o shadow root de «nk-pagamento#form-pagamento». A identidade é sustentada por: nome acessível, formulário associado, hierarquia documental, host do shadow root."
}
```

O operador `>>` da estratégia `CONTEXTUAL` (CHROME-001) encadeia contêiner e elemento **dentro do mesmo documento**;
nunca atravessa iframes.

## Onde aparece

* `element.inspect` (seleção): `accessPath` e `documentId` do nó;
* `element.accessPath` (comando dedicado);
* PageMap 0.2.0: `accessPaths[]` para elementos dentro de iframes/shadow roots (máximo 300 por exportação; o
  restante continua localizável por `elements[].documentId` e pelos localizadores);
* `protection.assessments[].location.accessPath` (trajetória até o widget/desafio);
* painel: seção **TRAJETÓRIA** (elemento, documento, iframe atual, profundidade, passos, estado e explicação).

## Limites

* Iframe sem permissão, sandbox sem `allow-same-origin` e shadow root fechado: a trajetória chega ao **host**
  e declara a restrição; não há passo para dentro (N003-E, N003-G).
* A trajetória é uma descrição; a CHROME-003 **não** executa trajetórias (execução de automações está fora do
  escopo, §41).
