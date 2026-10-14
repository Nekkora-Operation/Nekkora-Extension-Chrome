# Automation Diagnostics Engine (CHROME-003 §10, §23, §25)

`AutomationDiagnostics` combina grafo documental, análise semântica, conjuntos de opções, mutações da sessão e o
relatório de proteções em um `AutomationReadinessAssessment`: obstáculos com evidência, dependências de interação,
recomendações, status, confiança, base e limitações.

## Obstáculos

| Tipo | Severidade | Evidência |
|---|---|---|
| `FRAME_CONTEXT` | baixa | elementos em iframe exigem troca de contexto de documento |
| `NESTED_IFRAME` | média | profundidade de iframe ≥ 2 |
| `CROSS_ORIGIN_FRAME` | baixa | frame de outra origem permitida (capturado pelo próprio content script) |
| `INACCESSIBLE_FRAME` | média | frame sem permissão ou inacessível |
| `SANDBOXED_FRAME` | média | sandbox sem `allow-same-origin` |
| `FRAME_LOADING` | baixa | frame ainda carregando (reduz a confiança) |
| `SHADOW_DOM` / `CLOSED_SHADOW_ROOT` | baixa / média | shadow roots abertos / fechados |
| `DYNAMIC_IDS` | baixa (heurístico) | ids com aparência gerada |
| `AMBIGUOUS_ELEMENTS` | baixa/média | elementos indistinguíveis |
| `TRANSIENT_ELEMENTS` | baixa/média | nós substituídos durante a sessão |
| `INTERACTION_DEPENDENCY` | baixa | conteúdo atrás de expansão/aba/modal |
| `MODAL`, `PORTAL`, `VIRTUALIZED_COMPONENT`, `ASYNC_LOADING`, `SPA_NAVIGATION`, `INACCESSIBLE_ELEMENTS` | conforme o caso | componentes complexos (CHROME-001/002) |
| `HUMAN_VERIFICATION` | alta | CAPTCHA/verificação humana identificada (desafio ativo ou widget) |
| `ANTIBOT_TECHNOLOGY` | média | gerenciamento de bots/avaliação de risco (presença ≠ falha) |
| `ACCESS_BLOCKED` | alta | página de bloqueio |
| `UNKNOWN_PROTECTION` | média | fornecedor desconhecido ou proteção apenas `POSSIBLE` |

## Dependências de interação (§23)

Registradas, **nunca executadas**: `EXPAND_SECTION` (details/accordion), `OPEN_MODAL`, `SELECT_TAB`… com o nó da
região, o controlador e a quantidade de interativos dentro.

## Status (§25)

| Status | Quando |
|---|---|
| `NO_KNOWN_OBSTACLES` | nenhum obstáculo conhecido (limitação de proteção no servidor sempre declarada) |
| `MANAGEABLE` | somente obstáculos de severidade baixa |
| `REQUIRES_SPECIAL_HANDLING` | obstáculos de severidade média/alta sem desafio ativo |
| `REQUIRES_HUMAN_INTERVENTION` | desafio ativo ou bloqueio de acesso |

Sem pontuação numérica artificial: o status é derivado dos obstáculos e a `basis` lista as regras aplicadas.

## Recomendações

`HUMAN_INTERVENTION`, `AUTHORIZED_API`, `ENVIRONMENT_CONFIGURATION` (verificar com o proprietário da aplicação opções
legítimas, como chaves de teste e ambiente de homologação), `MANUAL_VALIDATION`, `UNKNOWN_PROTECTION`, `NO_ACTION_REQUIRED` — cada uma com motivo
e obstáculos relacionados. Nunca evasão.

## Exemplo (LAB-P04, página de desafio simulada)

```json
{
  "status": "REQUIRES_HUMAN_INTERVENTION", "confidence": "HIGH",
  "obstacles": [{ "type": "HUMAN_VERIFICATION", "severity": "HIGH",
                  "description": "Desafio de verificação humana ATIVO",
                  "evidence": ["CLOUDFLARE CHALLENGE_PAGE (MEDIUM, CHALLENGE_ACTIVE)"] }],
  "recommendations": [
    { "type": "HUMAN_INTERVENTION", "reason": "…" },
    { "type": "ENVIRONMENT_CONFIGURATION", "reason": "…" },
    { "type": "AUTHORIZED_API", "reason": "…" }
  ],
  "limitations": ["Proteções aplicadas no servidor ou na borda (WAF, gerenciamento de bots) podem não deixar evidência no documento: a ausência de assinaturas não comprova ausência de proteção.", "…"]
}
```
