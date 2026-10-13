package com.nekkora.extension.lab;

/**
 * Gera páginas com aproximadamente N elementos para medição de desempenho (T036). Cada bloco de
 * cartão soma 12 elementos; a tabela final completa o restante. A contagem exata é medida pela captura.
 */
final class LargePageGenerator {

    private LargePageGenerator() {
    }

    static String generate(int n) {
        StringBuilder sb = new StringBuilder(n * 120);
        sb.append("<!doctype html><html lang=\"pt-BR\"><head><meta charset=\"utf-8\"><title>Página grande (")
                .append(n).append(")</title><style>.card{border:1px solid #ddd;margin:4px;padding:4px}</style></head><body>")
                .append("<h1 data-testid=\"titulo\">Página grande com ").append(n).append(" elementos</h1><main id=\"conteudo\">");
        int used = 6; // html, head(ignorado), body, h1, main, ...
        int block = 0;
        while (used + 12 <= n * 0.8) {
            block++;
            sb.append("<div class=\"card\" data-item=\"").append(block).append("\">")
                    .append("<h3>Item ").append(block).append("</h3>")
                    .append("<p>Descrição do item ").append(block).append("</p>")
                    .append("<label for=\"campo-").append(block).append("\">Quantidade ").append(block).append("</label>")
                    .append("<input id=\"campo-").append(block).append("\" name=\"qtd").append(block).append("\" type=\"number\">")
                    .append("<button type=\"button\">Adicionar</button>")
                    .append("<span class=\"preco\">R$ ").append(block * 3).append(",00</span>")
                    .append("<a href=\"#item-").append(block).append("\">Detalhes</a>")
                    .append("<ul><li>Opção A</li><li>Opção B</li></ul>")
                    .append("</div>");
            used += 12;
        }
        sb.append("<table id=\"grande\"><tbody>");
        int row = 0;
        while (used + 4 <= n) {
            row++;
            sb.append("<tr data-row=\"").append(row).append("\"><td>Linha ").append(row).append("</td><td><button type=\"button\">Excluir</button></td></tr>");
            used += 4;
        }
        sb.append("</tbody></table></main></body></html>");
        return sb.toString();
    }
}
