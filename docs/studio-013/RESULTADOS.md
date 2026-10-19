# NEKKORA-STUDIO-013 — resultados (extensão + Native Host)

Execuções de 19/10/2026 no Windows 11, com Chrome for Testing 155.0.8059.39 e Java 21.0.12. Dados 100% sintéticos.
Nenhum ambiente ou dado de cliente foi usado.

## Suítes

| Suíte | Resultado | Observação |
|---|---|---|
| Node (`npm test`) | **212/212** | Base `main`: 186. Novos: `studio-pdd-013.test.js` (23, com a v4), `studio-pdd-013-access.test.js` (2) e o teto de tamanho da v4 em `native-client-studio.test.js` (1) |
| Java (núcleo + Native Host + contrato) | **308/308** | Base: 297. Novos: `PddVisualPrivacyV3Test` (4) e `PddVisualPrivacyV4Test` (3). Fixtures do Bridge `s013-*`: 14 válidas, 19 inválidas (v4: 1 válida, 4 inválidas) |
| ITs headless, grupos G1–G9 | **todos verdes** | 1 ajuste: `Studio002BridgeIT` passou a admitir o campo `origin` (sem caminho) e continua proibindo URL completa. Na rodada da v4, o G6 falhou uma vez (`Studio002BridgeIT`: tempo esgotado na conexão ao Studio de laboratório, primeira rodada após reiniciar a máquina) e passou inteiro (41/41) ao repetir, sem mudança de código |
| ITs com janela e gesto real (Alt+Shift+K pelo SO) | **`Studio013SitesIT` 14/14** (+ S013-16 headless) · `Studio010SafeCaptureIT` 12/12 · `Studio012PddVisualIT` 5/5 · `Studio002BridgeIT` 11/11 | Regressão das capturas 010/012 intacta |

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
| S013-14 | **v4** com o gesto real: o print que chega ao Studio não tem NENHUM pixel da cor-sentinela (todo dado da página adversarial está nela); a tela bruta, só em memória, tem (controle positivo) |
| S013-15 | **v4** com menu aberto e DOM mudando: ou falha (`PAGE_CHANGED`) ou sai sem pixel da cor-sentinela; laboratório também em v4 |
| S013-16 | Plano **v4** no motor real do Chrome: toda linha de texto e todo campo na cor-sentinela dentro de uma máscara; cada tabela visível inteira dentro de um BLOCK, com `TABLE_CONTENT_HIDDEN`; ordem BLOCK, FIELD, TEXT |
| S013-05 | Permissão ampla: coberta em Node com a política de acesso real (o diálogo nativo não é automatizado) |

Evidências: `reports/evidence/S013-02-descricao-v3.json` (descrição real que chegou ao Studio),
`S013-13-plano-v3-motor-real.json`, `S013-12-painel-consentimento.png` e `S013-14-print-v4-mascarado.png` (print v4 da página
sintética como chegou ao Studio).

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
| E16 | v4: a máscara TEXT não é pintada | morta (a âncora do script estava com o recuo errado na 1ª rodada; corrigida e repetida) |
| E17 | v4: verificação das máscaras desligada | morta |
| E18 | v4: plano incompleto vira print parcial | morta |
| E19 | Teto de controle aberto para qualquer captura | morta |
| E20 | O host deixa de conferir os pixels das máscaras v4 | morta |
| E21 | O host escreve quadro grande para qualquer política | morta |
| P1 | O plano v4 não cobre a tabela | morta (SOBREVIVEU na 1ª rodada: o texto das células continuava coberto por TEXT; o S013-16 passou a exigir a tabela inteira num BLOCK e a limitação) |
| P2 | O plano v4 não cobre o texto de dados | morta |
| P3 | O plano v4 não cobre campos | morta |
| P4 | O plano v4 trata rótulo fora do vocabulário como interface | morta |

**25/25 mortas.** As P1–P4 rodam no Chrome real (S013-16).

## Defeitos achados e corrigidos durante a missão

- **Ordem dos motivos ao armar:** armar com a origem antiga depois de um SSO respondia `PDD_CONSENT_REQUIRED`. Agora
  `ORIGIN_CHANGED` vem primeiro.
- **Tinta dos rótulos em botão:** usava a classe do fundo da página, o que deixava texto claro sobre o botão claro
  desenhado. Agora é sempre escura.
- **Checagem de primeiro plano dos ITs:** devolvia `#< CLIXML` do PowerShell sem console.
