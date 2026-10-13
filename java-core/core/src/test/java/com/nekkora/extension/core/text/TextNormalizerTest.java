package com.nekkora.extension.core.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextNormalizerTest {

    @Test
    void normalizeKeepsIdentityRelevantContent() {
        assertEquals("Olá Mundo", TextNormalizer.normalize("  Olá\n\t  Mundo  "));
        assertEquals("São Paulo", TextNormalizer.normalize("São Paulo")); // NFD → NFC
        assertEquals("R$ 1.234,56", TextNormalizer.normalize("R$ 1.234,56"));
    }

    @Test
    void foldAndCompare() {
        assertEquals("sao paulo", TextNormalizer.fold("SÃO  Paulo"));
        assertEquals(TextMatch.EXACT, TextNormalizer.compare("Excluir", " Excluir\n"));
        assertEquals(TextMatch.FOLDED, TextNormalizer.compare("Ação", "acao"));
        assertEquals(TextMatch.DIGITS_VARIED, TextNormalizer.compare("Pesquisar (3)", "Pesquisar (12)"));
        assertEquals(TextMatch.NONE, TextNormalizer.compare("Nome", "CPF do titular"));
        assertEquals(TextMatch.PARTIAL, TextNormalizer.compare("Cliente Ana Souza", "Cliente Ana"));
    }

    @Test
    void truncateOnWordBoundary() {
        assertEquals("alpha beta…", TextNormalizer.truncate("alpha beta gamma", 12));
    }
}
