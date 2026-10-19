# NEKKORA-STUDIO-013 — resultados (extensão + Native Host)

Execuções de 19/10/2026 no Windows 11, com Chrome for Testing 155.0.8059.39 e Java 21.0.12. Dados 100% sintéticos.
Nenhum ambiente ou dado de cliente foi usado.

## Suítes

| Suíte | Resultado | Observação |
|---|---|---|
| Node (`npm test`) | **209/209** | Base `main`: 186. Novos: `studio-pdd-013.test.js` (21) e `studio-pdd-013-access.test.js` (2) |
| Java (núcleo + Native Host + contrato) | **301/301** | Base: 297. Novo: `PddVisualPrivacyV3Test` (4). Fixtures do Bridge `s013-*`: 13 válidas, 15 inválidas |
| ITs headless, grupos G1–G9 | **todos verdes** | 1 ajuste: `Studio002BridgeIT` passou a admitir o campo `origin` (sem caminho) e continua proibindo URL completa |
| ITs com janela e gesto real (Alt+Shift+K pelo SO) | **`Studio013SitesIT` 12/12** · `Studio010SafeCaptureIT` 12/12 · `Studio012PddVisualIT` 5/5 · `Studio002BridgeIT` 11/11 | Regressão das capturas 010/012 intacta |

## `Studio013SitesIT` (Chrome real; hosts `*.nekkora.test` resolvidos para 127.0.0.1, porta não compilada)

| Cenário | Resultado |
|---|---|
| S013-01 | Site com acesso do Chrome e sem consentimento PDD: listado com origem exata, sem caminho. Armação recusada com `PDD_CONSENT_REQUIRED` (v3, v2 e sem política) |
| S013-02 | Consentimento pelo Studio (sessão, TEST_AGENT). O Studio antigo (v2 ou sem política) recebe `POLICY_REQUIRES_V3`. Captura v3 com gesto real: sem `png`/`sha256`, nenhum marcador-sentinela, só rótulos do vocabulário, shadow roots aberta e fechada, ≥ 8 campos vazios, frame e mídias só como lugar. Recaptura igual |
| S013-03 | Menu dinâmico aberto e DOM mudando a cada 50 ms: "Sair" (vocabulário) aparece; o texto mutante nunca sai |
| S013-04 | Outra porta do mesmo host e subdomínio (ambos com acesso do Chrome) dão `PDD_CONSENT_REQUIRED`. Sem acesso do Chrome, nem dá para consentir |
| S013-06 | Revogar com a captura armada dá `PDD_CONSENT_REVOKED`. Revogar no MEIO da captura (plano esperando) dá `PDD_CONSENT_REVOKED`, sem nada publicado. Reautorizar volta a capturar |
| S013-07 | Acesso do Chrome revogado com o consentimento presente: `PERMISSION_REVOKED` e o consentimento é apagado. Devolver o acesso não devolve o consentimento |
| S013-08 | Redirecionamento SSO para outra origem com a captura armada: `PAGE_CHANGED`. Armar com a origem antiga: `ORIGIN_CHANGED` |
| S013-09 | A→B→A durante a captura: `ACTIVE_TAB_CHANGED`. Cancelamento em curso: `CANCELLED` |
| S013-10 | `chrome://` dá `BROWSER_INTERNAL`, sem origem. Site sem acesso dá `PERMISSION_REQUIRED`. Comando de screenshot bruto inexistente (recusado pelo host) |
| S013-11 | Laboratório local: v3 sem consentimento |
| S013-12 | Consentimento e revogação pelo PAINEL da extensão (DEVELOPER, via EXTENSION), vistos pelo Studio |
| S013-13 | O mesmo `pdd-plan.js` no motor real do Chrome, na página adversarial: ordem, px inteiros, nenhum marcador |
| S013-05 | Permissão ampla: coberta em Node com a política de acesso real (o diálogo nativo não é automatizado) |

Evidências: `reports/evidence/S013-02-descricao-v3.json` (descrição real que chegou ao Studio),
`S013-13-plano-v3-motor-real.json` e `S013-12-painel-consentimento.png`.

## Teste de mutação (barreiras de autorização e de vazamento)

Cada mutação enfraquece uma barreira. O teste indicado precisa falhar.

| id | Mutação | Resultado |
|---|---|---|
| E1 | O perfil ignora o consentimento PDD | morta |
| E2 | Site autorizado aceita a v2 | morta |
| E3 | Conferência de origem/autorização durante a captura desligada | morta |
| E4 | A revogação não derruba a armação ou a captura em curso | morta |
| E5 | A descrição aceita rótulo fora do vocabulário | morta |
| E6 | A descrição aceita campos fora do contrato | morta |
| E7 | O consentimento aceita origem com caminho | morta |
| E8 | A concessão não confere a origem mostrada | morta |
| E9 | Concessão sem acesso do Chrome | morta |
| E10 | A v3 volta a chamar `captureVisibleTab` | morta (pela asserção "captureVisibleTab nunca é chamado na v3") |
| E11 | Sem limpeza do consentimento quando o Chrome revoga | morta |
| E12 | O host aceita rótulo fora do vocabulário | morta |
| E13 | O host aceita campos fora do contrato | morta |
| E14 | O host aceita v3 com imagem | morta |
| E15 | O host deixa de reverificar a v3 | morta |

**15/15 mortas.**

## Defeitos achados e corrigidos durante a missão

- **Ordem dos motivos ao armar:** armar com a origem antiga depois de um SSO respondia `PDD_CONSENT_REQUIRED`. Agora
  `ORIGIN_CHANGED` vem primeiro.
- **Tinta dos rótulos em botão:** usava a classe do fundo da página, o que deixava texto claro sobre o botão claro
  desenhado. Agora é sempre escura.
- **Checagem de primeiro plano dos ITs:** devolvia `#< CLIXML` do PowerShell sem console.
