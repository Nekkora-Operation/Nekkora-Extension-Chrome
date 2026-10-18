/*
 * Perfil de captura visual do PDD — contrato revisável `nekkora-visual-capture-profile/1` (NEKKORA-E2E-001).
 *
 * A captura de tela sanitizada (`nekkora-visual/1`, studio-pdd.js) existia só no laboratório local (LAB). Este arquivo
 * acrescenta, de forma EXPLÍCITA e FECHADA, o perfil BENCHMARK: origens públicas de treinamento autorizadas pela
 * usuária para uma missão. Regras:
 *   - LAB continua sendo só loopback (access-policy.js); BENCHMARK nunca é tratado como LAB;
 *   - a aba precisa estar AUTORIZADA pelo próprio Chrome (permissão de site concedida) E a origem precisa estar
 *     nesta lista, comparada EXATAMENTE (esquema + host + porta; sem curinga, sem subdomínio);
 *   - a sanitização, as barreiras de pixel e o gesto explícito são os mesmos do LAB.
 * Mudar esta lista é mudança de código revisada, nunca configuração em tempo de execução.
 */

export const PROFILE_VERSION = 'nekkora-visual-capture-profile/1';

/** Origens BENCHMARK (exatas). NEKKORA-E2E-001: desafio "Input Forms" autorizado pela usuária. */
export const BENCHMARK_ORIGINS = Object.freeze(['https://rpachallenge.com']);

/**
 * Perfil de captura visual para uma avaliação da política de acesso ({@link evaluate} de access-policy.js).
 * @returns {'LAB'|'BENCHMARK'|null}
 */
export function visualProfile(evaluation) {
  if (!evaluation || !evaluation.allowed) return null;
  if (evaluation.kind === 'LAB') return 'LAB';
  if (evaluation.kind === 'AUTHORIZED' && BENCHMARK_ORIGINS.includes(evaluation.origin)) return 'BENCHMARK';
  return null;
}
