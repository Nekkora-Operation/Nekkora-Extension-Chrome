package com.nekkora.extension.core.semantic;

/** Agrupamento funcional de alto nível. */
public enum ElementCategory {
    /** Controles operáveis pelo usuário (campos, botões, links, opções...). */
    INTERACTIVE,
    /** Conteúdo textual (títulos, parágrafos, mensagens, células de texto...). */
    TEXT,
    /** Estruturas que organizam outros elementos (formulário, tabela, linha, lista, diálogo...). */
    STRUCTURAL,
    /** Contêineres genéricos sem semântica própria. */
    CONTAINER,
    /** Demais elementos (imagens, iframes, campos ocultos...). */
    OTHER
}
