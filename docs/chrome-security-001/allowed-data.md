# CHROME-SECURITY-001: dados permitidos

O que pode atravessar a página, sob `nekkora-privacy/2`. Tudo o que não está aqui é retido.

| Dado | Forma | Destinos |
|---|---|---|
| Tag, papel, tipo do campo (`type`), estados (`disabled`, `checked`, `aria-expanded`…), `tabindex`, `autocomplete`, números ARIA | valor de forma técnica (`[A-Za-z0-9-_. ]`, até 80) | motor, PageMap, registro |
| Geometria (retângulo, viewport), visibilidade, inércia, modal | números e booleanos | motor, PageMap |
| Presença de texto | `textPresent`, `textSuppressed` | motor, PageMap (`text.suppressed`) |
| Mudança de texto | `textRevision` (contador) | motor → `MutationRecord` como `[omitido]` |
| Presença de valor em campo | `hasValue`, `valueLength` (não em campo sensível) | motor |
| Relações entre elementos | `labelIds`, `refs` (ids temporários de nó) | motor |
| Nome de controle, rótulo, `aria-label`, `title`, `placeholder`, `alt`, `value` de botão | **só** se o valor inteiro for do vocabulário controlado e o elemento for portador elegível | motor, Studio (nome da etapa), PageMap |
| `id`, `name`, classes, atributos de teste | **só** identificadores ASCII cujos termos sejam todos do vocabulário | motor, localizadores, Studio (`htmlId`/`htmlName`) |
| Presença de `href`/`src`/`action`/relações/`data-*` | valor vazio (`""`) | motor |
| URL | modelo: origem + segmentos do vocabulário; nomes de parâmetro do vocabulário | motor, Studio, PageMap |
| Título da aba/documento | só se inteiro do vocabulário | Studio, PageMap |
| Localizador | só se TODO literal (CSS, XPath, descrição) passar na política | Studio, PageMap, registro |
| Identificadores técnicos | `captureId`, `nodeId`, `targetId`, `sessionId`… (forma fechada) | todos |
| Evidências (STUDIO-005) | tipo, classificação, sinais, região e campo controlados, horário, correlação; mensagem `SUPPRESSED` | Studio |
