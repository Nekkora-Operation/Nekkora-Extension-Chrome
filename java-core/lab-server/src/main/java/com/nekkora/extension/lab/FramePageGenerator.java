package com.nekkora.extension.lab;

/**
 * Páginas geradas para a Frame Intelligence (CHROME-003): {@code count} iframes de mesma origem, cada um com uma
 * cadeia de {@code depth} níveis; a folha contém um formulário. Usadas nas medições com 1/5/10/25 iframes e em
 * diferentes profundidades.
 */
final class FramePageGenerator {

    static final int MAX_COUNT = 40;
    static final int MAX_DEPTH = 6;

    private FramePageGenerator() {
    }

    static String page(int count, int depth) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html lang=\"pt-BR\"><head><meta charset=\"utf-8\"><title>Frames gerados (")
                .append(count).append(" × profundidade ").append(depth).append(")</title>")
                .append("<style>iframe{width:300px;height:160px;border:1px solid #999;margin:2px}</style></head><body>")
                .append("<h1>").append(count).append(" iframe(s), profundidade ").append(depth).append("</h1>")
                .append("<button type=\"button\" id=\"principal\">Ação principal</button><div id=\"frames\">");
        for (int i = 1; i <= count; i++) {
            sb.append("<section aria-label=\"Bloco ").append(i).append("\"><iframe title=\"Frame ").append(i).append(" nível 1\" src=\"")
                    .append(frameUrl(i, 1, depth)).append("\"></iframe></section>");
        }
        return sb.append("</div></body></html>").toString();
    }

    static String frame(int index, int level, int depth) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html lang=\"pt-BR\"><head><meta charset=\"utf-8\"><title>Frame ").append(index).append(" nível ")
                .append(level).append("</title><style>body{font:12px system-ui;margin:4px}iframe{width:95%;height:110px;border:1px solid #999}</style>")
                .append("</head><body><p>Frame ").append(index).append(" · nível ").append(level).append("</p>");
        if (level < depth) {
            sb.append("<iframe title=\"Frame ").append(index).append(" nível ").append(level + 1).append("\" src=\"")
                    .append(frameUrl(index, level + 1, depth)).append("\"></iframe>");
        } else {
            sb.append("<form aria-label=\"Formulário ").append(index).append("\" onsubmit=\"event.preventDefault()\">")
                    .append("<label for=\"q").append(index).append("\">Quantidade</label> <input id=\"q").append(index).append("\" name=\"quantidade\">")
                    .append("<button type=\"submit\">Confirmar ").append(index).append("</button></form>");
        }
        return sb.append("</body></html>").toString();
    }

    static String slow(int ms) {
        return "<!doctype html><html lang=\"pt-BR\"><head><meta charset=\"utf-8\"><title>Relatório lento</title></head><body>"
                + "<p>Carregado após " + ms + " ms</p><button type=\"button\">Baixar relatório</button></body></html>";
    }

    private static String frameUrl(int index, int level, int depth) {
        return "/generated/frame?i=" + index + "&amp;level=" + level + "&amp;depth=" + depth;
    }
}
