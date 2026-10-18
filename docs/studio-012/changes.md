# NEKKORA-STUDIO-012 — Mudanças na extensão

O relatório principal está no Studio: `Nekkora-Studio/reports/studio-012-final-report.md`. Diagnóstico, decisões e
limitações estão em `Nekkora-Studio/docs/studio-012/`.

## Recorder e iframes (`content/interaction-recorder.js`, `content/capture.js`, `content/feedback-observer.js`)

- **Causa comprovada:** os ouvintes do sensor ficavam no documento ANTIGO quando um iframe da mesma origem navegava, ou
  quando um iframe surgia depois do início da gravação. Nada mais era gravado com interação nesse frame.
- O sensor agora se liga por DOCUMENTO e se religa a cada documento de frame que a captura aceita. O aviso vem por
  `NS.capture.onFrameDocument`, e só para frames que a política já aceitou.
- `frameWindows()` agora elimina janelas repetidas.
- Quando só um iframe sai (`pagehide` no frame), o que estava aberto nele é consolidado, sem cortar as janelas de
  observação.

## Política de identificadores (`nekkora-identifier-terms/1`, aprovada pela usuária)

- Contrato novo `contracts/privacy/identifier-terms.json`: 48 termos técnicos (ds, txt, ddl, hdn, ctl, temp…).
- `developerIdentifier()` aceita esses termos SÓ em `id`/`name`/`data-testid` e nas posições equivalentes dos
  localizadores (`#id`, `[name=…]`, `@id`, `{id=…}` sem aspas), sempre com pelo menos um termo do vocabulário.
- A regra é a mesma em `shared/capture-policy.js`, no núcleo Java (`CapturePrivacyPolicy`, `LocatorPrivacy`) e no host
  (`StepPrivacy`).
- Vetores compartilhados em `privacy-v2-vectors.json`: a seção `developerIdentifiers` e casos novos de expressões e
  atributos. Os vetores foram acrescentados ao arquivo versionado, que já tinha casos manuais (o gerador continua
  defasado e foi atualizado para incluir os casos novos).

## Notas novas da etapa (contrato `studio-bridge`, aditivo)

| Nota | Significado |
|---|---|
| `IDENTIFIER_WITHHELD` | O elemento tem `id`/`name`, mas o valor foi retido; só a presença sai. |
| `REVEALED_BY_HOVER` | O alvo está num contêiner que uma regra CSS `:hover` da própria página exibe com o ponteiro sobre OUTRO elemento. É limitação do clique; nenhuma ação é inventada. |

## Debug guiado: conferência da opção (contrato `studio-bridge`, aditivo)

- `studio.debug.inspect` aceita `expectedOption` (opcional, até 120 caracteres).
- O resultado traz `option`: `{matches, selected}` ou `null`.
- O script da página (`cs.debug.optionState`) compara o rótulo ou valor CONFIGURADO NO STUDIO e devolve só a contagem e
  sim/não.
- Sem identidade comprovada do alvo, não há conferência.

## Laboratório e testes

- `test-lab/pages/studio012/`: login, portal, menu, início, solicitação e sósias. Dados fictícios.
- `Studio012RecorderIT`: 4 cenários (fluxo completo com interação, hover, sósias, confirmação do nível). O teste do
  fluxo foi rodado primeiro SEM a correção dos frames, para reproduzir a falha.
- Node: `capture-policy.test.js` (vetores da lista técnica) e `interaction-normalizer.test.js` (nota de hover).
- Java: `CapturePolicyVectorsTest` (vetores da lista técnica).

## Dependência

Integrar a extensão ANTES do Studio. O Studio sincroniza `identifier-terms.json`, os vetores e o contrato do Bridge
deste PR.
