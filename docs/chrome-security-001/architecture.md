# CHROME-SECURITY-001: arquitetura de privacidade

## Separar reconhecimento local de transmissão

O navegador **vê** a página inteira, e isso é inevitável. A política decide o que **sai** dela. A separação ficou assim:

```
página (DOM)
  │  content script ─ 1ª barreira ─ capture-policy.js
  │     • elegibilidade estrutural (portador de nome) + vocabulário controlado (valor inteiro)
  │     • relações → refs (ids temporários de nó)       • texto retido → textPresent/textSuppressed/textRevision
  │     • atributos por categoria                         • URL → modelo; título → vocabulário
  ▼
service worker ─ 2ª barreira ─ studio-steps / studio-evidence / studio-recording / diagnostics / frames / eventLog
  ▼  Native Messaging
Native Host ─ 3ª barreira ─ SnapshotPrivacy (entrada) · StepPrivacy · EvidencePrivacy · títulos · LogSafety
  │  motor Java: classifica, monta família, localiza e resolve SOBRE O MODELO FILTRADO
  ▼  Bridge local (hello.client.privacy = nekkora-privacy/2)
Nekkora Studio ─ 4ª barreira ─ CapturePrivacy (domínio) · RecordedStep.guard · títulos · URLs
```

## Decisões estruturais

1. **O motor não recebe texto privado** (objetivo 1 do pedido). O reconhecimento que dependia de texto foi redesenhado com base no que pode atravessar:
   - relações por id de nó;
   - presença de texto;
   - revisão de texto para mudanças;
   - nomes só quando o valor inteiro é metadado do vocabulário.

   O "texto efêmero no host" (opção 3 do ponto de controle) **não** é usado, nem como plano B.
2. **Uma regra, quatro implementações, os mesmos vetores:** `capture-policy.js`, `CapturePrivacyPolicy` (motor/host) e `CapturePrivacy` (Studio) contra `contracts/privacy/privacy-v2-vectors.json`.
3. **Barreira no ponto de entrada:**
   - o host aplica `SnapshotPrivacy` antes de qualquer análise;
   - `NodeChange`, `ResolutionResult`, `RawNode.exposedValue` e `BrowserFrameMetadata` têm a regra no construtor;
   - nenhum produtor contorna a política.
4. **Falha segura e honesta:**
   - sem valor elegível, sai vazio;
   - sem localizador seguro, declara-se a limitação;
   - sem característica do próprio elemento, a identidade não é confirmada (correção SEC001-078).
5. **Sem substitutos:** nada de hash, pseudônimo, texto em campo oculto ou URL artificial executável.

## Componentes novos

| Componente | Repositório | Papel |
|---|---|---|
| `contracts/privacy/ui-vocabulary.json` | extensão (cópia no Studio) | vocabulário fechado |
| `contracts/privacy/privacy-v2-vectors.json` | extensão (cópia no Studio) | vetores compartilhados |
| `chrome-extension/src/shared/ui-vocabulary.js` | extensão | gerado da fonte única (`scripts/gen-ui-vocabulary.mjs`) |
| `chrome-extension/src/shared/capture-policy.js` | extensão | política JS |
| `core/privacy/CapturePrivacyPolicy`, `SnapshotPrivacy` | extensão | política Java e barreira de entrada |
| `host/runtime/LogSafety` | extensão | logs e erros sem conteúdo |
| `studio/domain/privacy/CapturePrivacy` | Studio | 4ª barreira |
| `test-lab/pages/chrome-security-001/` | extensão | laboratório de 12 páginas fictícias |
| `ChromeSecurity001IT`, `SemanticCapabilityIT`, `support/PrivacyCanaries` | extensão | verificação de ponta a ponta e medição |
