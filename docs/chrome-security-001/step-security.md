# CHROME-SECURITY-001: segurança do canal de etapas

Cadeia: `interaction-recorder.js` (`descriptor()`) → `studio-steps.js` (`elementRef()`, `build()`) → host (`StepPrivacy`) → Studio (`RecordedStep.guard` → `RecordedStepDrafts` → `ElementReference`).

| Campo da etapa | Regra |
|---|---|
| `element.name` | resultado do motor (já sob a política) ou do descritor observado. Só sai se o valor inteiro for do vocabulário; senão `""`, e o Studio usa o nome genérico ("Preencher campo de telefone", "Clicar em botão") |
| `element.htmlId` / `htmlName` | só identificadores do vocabulário; campo sensível nunca |
| `element.locators` | descartados se qualquer literal sair da política (`LOCATORS_WITHHELD`); sem localizador seguro e único: `SAFE_LOCATOR_UNAVAILABLE` |
| `element.accessPath[].label` / `.locator` | rótulo pela política; localizador pela regra de expressão |
| `element.context[].name` | pela política |
| `parameters.option` | sempre `null`; houve escolha → `OPTION_WITHHELD` |
| `context.url`, `context.after.url` | modelo (D3) |
| `context.title` | só do vocabulário (D4) |

**Antes**, com o mesmo teste da STUDIO-005: `"name": "telefone do cliente joão"`. **Agora**, no Chrome real (`ChromeSecurity001IT`, página 3): nenhuma das superfícies chega ao Studio.

Mantidos sem mudança: `StepId`, identidade da gravação, ordem, rotas, épocas, ACK, deduplicação, reconciliação, pausa, retomada, finalização, cancelamento e os modos CAPTURE_ONLY e CAPTURE_AND_INTERACT. A ausência de um nome seguro **não altera** a identidade técnica da ação, que é correlacionada por ids temporários.

O usuário continua podendo nomear a etapa e editar localizadores **manualmente** no Studio. Essa é uma operação diferente da captura automática, e nada da página é guardado em campo oculto (`CapturedBaseline` recebe a etapa já sob a política).
