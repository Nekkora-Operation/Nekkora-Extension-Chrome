/*
 * Perfil de captura visual do PDD — contrato revisável `nekkora-visual-capture-profile/2` (NEKKORA-STUDIO-013).
 *
 * A /1 (NEKKORA-E2E-001) aceitava, além do laboratório local, só origens públicas COMPILADAS numa lista fechada
 * (BENCHMARK_ORIGINS). A /2 retira a lista: uma origem http/https entra quando DUAS autorizações independentes existem:
 *   1. a permissão de site do Chrome (inspeção/gravação; access-policy.js — kind AUTHORIZED);
 *   2. o consentimento PRÓPRIO de captura PDD para aquela origem exata (pdd-consent.js).
 * Perfis:
 *   LAB              só loopback (access-policy.js). Aceita nekkora-visual/1, /2 e /3.
 *   AUTHORIZED_SITE  origem autorizada pelo Chrome + consentimento PDD. Aceita nekkora-visual/3 (inteiramente sintética)
 *                    e nekkora-visual/4 (print real com o texto de dados mascarado). Studios anteriores, que não pedem
 *                    nenhuma das duas, são recusados. A nekkora-visual/5 (print integral) também é deste perfil, mas só
 *                    vale quando o consentimento da origem tem o nível FULL ({@link levelAllows}).
 *   BENCHMARK        valor da /1, mantido no contrato para clientes antigos. NÃO é mais produzido a partir da 0.6.0.
 */
import { VISUAL_POLICY, VISUAL_POLICY_V2, VISUAL_POLICY_V3, VISUAL_POLICY_V4, VISUAL_POLICY_V5 } from './visual-sanitizer.js';

export const PROFILE_VERSION = 'nekkora-visual-capture-profile/2';
export const PROFILES = Object.freeze(['LAB', 'AUTHORIZED_SITE']);
/** Políticas visuais aceitas por perfil. */
export const POLICIES_BY_PROFILE = Object.freeze({
  LAB: Object.freeze([VISUAL_POLICY, VISUAL_POLICY_V2, VISUAL_POLICY_V3, VISUAL_POLICY_V4, VISUAL_POLICY_V5]),
  // revisão da usuária: também o print real com o texto de dados mascarado (v4); a v2 (pixels reais dos rótulos sem
  // máscara de texto em volta, fundo redesenhado) continua fora
  AUTHORIZED_SITE: Object.freeze([VISUAL_POLICY_V3, VISUAL_POLICY_V4, VISUAL_POLICY_V5]),
});

/**
 * Revisão da usuária (19/10/2026): o print integral (v5) só vale num site cujo consentimento PDD tem o nível FULL; o
 * laboratório local não tem consentimento e aceita a v5. As demais políticas não dependem do nível.
 */
export function levelAllows(profile, visualPolicy, consent) {
  if (visualPolicy !== VISUAL_POLICY_V5 || profile === 'LAB') return true;
  return profile === 'AUTHORIZED_SITE' && !!consent && consent.level === 'FULL';
}

/**
 * Perfil de captura visual para uma avaliação da política de acesso ({@link evaluate} de access-policy.js) e o
 * consentimento PDD da origem (registro de pdd-consent.js, ou null).
 * @returns {'LAB'|'AUTHORIZED_SITE'|null}
 */
export function visualProfile(evaluation, consent) {
  if (!evaluation || !evaluation.allowed) return null;
  if (evaluation.kind === 'LAB') return 'LAB';
  if (evaluation.kind === 'AUTHORIZED' && consent && consent.origin === evaluation.origin) return 'AUTHORIZED_SITE';
  return null;
}

/**
 * Por que a captura NÃO vale nesta aba (código estável para o Studio/painel), ou null se vale.
 *   PERMISSION_REQUIRED / BROWSER_INTERNAL / BROWSER_RESTRICTED / UNSUPPORTED_SCHEME / INVALID_URL / URL_UNAVAILABLE
 *     — os motivos da política de acesso do Chrome;
 *   PDD_CONSENT_REQUIRED — o Chrome permite gravar/inspecionar, mas a captura de PDD não foi autorizada nesta origem.
 */
export function denialReason(evaluation, consent) {
  if (!evaluation || !evaluation.allowed) return (evaluation && evaluation.reason) || 'URL_UNAVAILABLE';
  return visualProfile(evaluation, consent) ? null : 'PDD_CONSENT_REQUIRED';
}
