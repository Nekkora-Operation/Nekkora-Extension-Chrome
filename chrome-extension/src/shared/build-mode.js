/*
 * Modo de construção da extensão, definido pelo PROJETO (scripts/build.ps1 -Mode PRD gera dist/prd com
 * 'PRD'). Nunca é alterado por páginas web. Em DEV, as páginas internas da extensão podem simular PRD para
 * homologação (config.setMode); em PRD a troca é recusada.
 */
export const BUILD_MODE = 'DEV';
