# STUDIO-005 — Contexto observado (antes, durante e depois)

## Antes da ação

- Na abertura da janela, o observador tira a fotografia das regiões acompanhadas: visível, tamanho e hash do texto,
  inválido e ocupado. Também tira a dos atributos de estado do elemento acionado.
- Essa linha de base é o que impede uma mensagem pré-existente de virar "consequência" da ação.
- Se já há janelas abertas, a varredura de abertura primeiro entrega a elas o que mudou e só depois registra a linha de
  base da nova janela. Nada é perdido entre duas ações.

## No momento da ação (`AT_ACTION`)

- `FORM_SUBMITTED`: o envio observado pelo normalizador (STUDIO-003), agora também como evidência estruturada
  (`INTERACTION_NORMALIZER`, `DIRECTLY_OBSERVED`, sinal `SUBMIT_EVENT`).

## Depois da ação (`AFTER_ACTION`)

Mensagens, validação, carregamento, conteúdo, estado do elemento e navegação, conforme `evidence-observer.md` e
`evidence-correlation.md`.

## Documento

Cada evidência leva `document`:

| Campo | Conteúdo |
|---|---|
| `frame` | `MAIN` (documento principal do frame 0) ou `FRAME` (iframe acompanhado, ou documento filho de mesma origem observado pelo frame 0) |
| `inShadow` | a região estava num shadow root aberto. Homologado com o componente `caixa-confirmacao` |
| `title` | sempre `null` (revisão 3: o título é texto da página; o campo segue no contrato) |
| `url` | URL sanitizada pela política (`sanitizeUrl`), ou `null` |

Documento filho de mesma origem observado pelo frame 0: a evidência leva a limitação `CROSS_DOCUMENT_NOT_ATTRIBUTED`
e só concorre com ações desse mesmo documento.

## Ausência

| Situação | Como aparece |
|---|---|
| nada observado na janela | `OBSERVATION_COMPLETE` com `signalsObserved: 0`. **Não significa ausência de erro** |
| observação interrompida | `OBSERVATION_INCOMPLETE` + `WINDOW_CUT_BY_*` |
| carregamento sem fim em 8 s | `OBSERVATION_INCOMPLETE` + `PARTIAL_COVERAGE` |
| sem permissão (iframe de outra origem) | nenhuma evidência; cobertura `PARTIAL` na gravação |
| ambígua | `AMBIGUOUS_FEEDBACK` sem etapa |
| sensor indisponível ou extensão antiga | nenhuma evidência; o Studio mostra "Observação indisponível" |
| `CAPTURE_ONLY` | nenhuma janela; o Studio mostra "Não se aplica" |
