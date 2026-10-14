# Modelo de confiança (CHROME-003 §13, §25)

Todos os níveis são calculados por regras explícitas sobre evidências; não há pontuação artificial nem IA.

## Proteções — `ProtectionAssessment.confidence`

Cada evidência tem **força** (`STRONG`, `MEDIUM`, `WEAK`) e **canal**: `RESOURCE` (URL de script, iframe ou recurso
carregado), `DOM` (classe, id, atributo, campo de resposta) ou `DOCUMENT` (título).

| Nível | Regra |
|---|---|
| `HIGH` | ≥ 2 evidências fortes de **canais independentes** (ex.: script oficial + widget no DOM) |
| `MEDIUM` | ≥ 1 evidência forte, ou ≥ 2 médias de canais independentes |
| `LOW` | somente evidências médias/fracas ou parciais ⇒ `status: POSSIBLE` |
| `UNKNOWN` | somente indicadores genéricos (fornecedor não identificado) |

Ajustes, sempre registrados em `confidenceReasons`:

1. **Teto da assinatura** (`maxConfidence`): observação de terceiros nunca passa de `MEDIUM` (Akamai e Imperva: `LOW`).
2. **Conflito**: o mesmo elemento casa assinaturas fortes de fornecedores diferentes ⇒ −1 nível.
3. **Sombreamento**: toda a evidência vem de URLs que outra assinatura do **mesmo fornecedor** casa de forma mais
   específica (parâmetro/fragmento exigido) ⇒ −1 nível e limitação "produto incerto". Ex.: `api.js?render=` é
   compatível com reCAPTCHA v2 (`render=explicit`) e v3 (`render=<chave>`); o valor não é coletado, então v2 cai
   para `LOW/POSSIBLE` e v3 permanece `MEDIUM` (LAB-P07).
4. **Somente evidência fraca** ⇒ estado do desafio `UNKNOWN` (LAB-P10).
5. **Evidência simulada** ⇒ limitação explícita (não altera o nível: o laboratório reproduz assinaturas observáveis).

## Prontidão — `AutomationReadinessAssessment.confidence`

Mede a **cobertura** da observação, não a facilidade da automação:

| Nível | Regra |
|---|---|
| `HIGH` | todas as regiões observáveis foram inspecionadas |
| `MEDIUM` | há regiões não observáveis (frame sem permissão, sandbox, shadow root fechado) |
| `LOW` | há frames carregando ou o diagnóstico de proteções é `INSUFFICIENT_EVIDENCE` |

## Frames e trajetórias

* `FrameIdentity` — pontuação do reencontro (limiar 0,6, empate ⇒ ambiguidade);
* estabilidade do localizador de cada passo e da trajetória (`HEURISTIC_STABLE`, `OBSERVED_STABLE`, `UNSTABLE`,
  `UNKNOWN`), promovida somente por observação em várias capturas.
