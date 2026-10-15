package com.nekkora.extension.host.runtime;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;

/**
 * CHROME-SECURITY-001 — diagnóstico sem conteúdo: o que o host registra em log ou devolve como erro técnico nunca leva a
 * MENSAGEM de uma exceção de origem externa (parser JSON, validação, motor), porque ela pode citar trechos do que foi
 * recebido (texto da página, valores, seletores). Ficam: tipo da exceção, código, componente, operação, posição e a
 * pilha de chamadas.
 */
public final class LogSafety {

    private LogSafety() {
    }

    /** Mesma pilha, sem mensagens (inclusive das causas): segura para {@code Logger.log(level, msg, thrown)}. */
    public static Throwable redacted(Throwable e) {
        if (e == null) {
            return null;
        }
        Throwable cause = e.getCause() == e ? null : redacted(e.getCause());
        Redacted r = new Redacted(e.getClass().getName(), cause);
        r.setStackTrace(e.getStackTrace());
        return r;
    }

    /** Descrição de erro de JSON só com a posição (linha/coluna), nunca o trecho lido. */
    public static String jsonProblem(Exception e) {
        if (e instanceof JsonProcessingException j && j.getLocation() != null) {
            JsonLocation l = j.getLocation();
            return "JSON inválido (linha " + l.getLineNr() + ", coluna " + l.getColumnNr() + ")";
        }
        return "JSON inválido";
    }

    /** Exceção-substituta: o nome do tipo original e a pilha; nenhuma mensagem. */
    static final class Redacted extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Redacted(String originalType, Throwable cause) {
            super(originalType + " (mensagem omitida)", cause, false, true);
        }
    }
}
