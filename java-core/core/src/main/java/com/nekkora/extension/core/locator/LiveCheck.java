package com.nekkora.extension.core.locator;

/**
 * Verificação do localizador contra a DOM REAL no navegador (preenchida pela extensão).
 *
 * @param cssCount           correspondências de {@code querySelectorAll} (-1 = não executado)
 * @param xpathCount         correspondências de {@code document.evaluate} (-1 = não executado)
 * @param cssMatchesTarget   o alvo está entre os resultados CSS
 * @param xpathMatchesTarget o alvo está entre os resultados XPath
 * @param error              erro de execução no navegador, se houver
 */
public record LiveCheck(int cssCount, int xpathCount, boolean cssMatchesTarget, boolean xpathMatchesTarget, String error) {

    /** Contagens do navegador coincidem com a contagem do snapshot. */
    public boolean agreesWith(int snapshotCount) {
        boolean cssOk = cssCount < 0 || cssCount == snapshotCount;
        boolean xpOk = xpathCount < 0 || xpathCount == snapshotCount;
        return error == null && cssOk && xpOk;
    }
}
