# NEKKORA-E2E-001 — mudanças na extensão

Missão: cadeia real Studio → extensão → framework sobre o RPA Challenge (`https://rpachallenge.com`, Input Forms).
Relatório principal no repositório do Studio: `reports/e2e-001-final-report.md`.

| Commit | Mudança | Contrato / política | Prova |
|---|---|---|---|
| `2278a4f` | candidato **LABEL por `<label>` irmão**: campo sem nome acessível cujo contêiner tem exatamente um `<label>` e um controle recebe `//*[label[normalize-space(.)='X']]/input` (não posicional; ambiguidade medida pelo motor) | — | `LocatorEngineTest` (+3); `E2e001ChallengeLiveWebIT` (site real, sem Start): os 7 campos com LABEL único |
| `2278a4f` | perfil de captura visual **BENCHMARK** (`nekkora-visual-capture-profile/1`): PDD também em aba AUTORIZADA cuja origem esteja na lista FECHADA (`https://rpachallenge.com`); LAB continua só loopback | `nekkora-visual/1` e sanitização inalteradas; `browser.tabs.list` ganha o campo aditivo `visualCapture` | `studio-pdd.test.js`, `studio-recording.test.js`; homologação do Studio (2 capturas BENCHMARK) |
| `1a77cad` | descrição de localizador acima de 200 caracteres (DOM profundo) vai vazia em vez de derrubar a etapa no host | contrato do Bridge (`description` ≤ 200) | `studio-steps.test.js` (n9) |
| `6307268` | vocabulário de interface **+"excel" +"cloud"** (revisão versionada aprovada pela usuária): o link "Download Excel cloud_download" deixa de ser só POSICIONAL | `nekkora-ui-vocabulary/1` (1062 → 1064 termos; regra inalterada; histórico no contrato) | vetores `privacy-v2-vectors.json` (passa / recusa); `CapturePolicyVectorsTest`; `capture-policy.test.js` |

Regressão no HEAD `6307268`: Java (núcleo + Native Host) 293/293; Node 179/179; ITs Chrome for Testing 275/275 em
10 grupos (+ contrato do PageMap). A construção de TESTE declara a origem exata do desafio no manifesto
(`BrowserLab.TEST_GRANTED_ORIGINS`); a extensão publicada não muda de permissões.
