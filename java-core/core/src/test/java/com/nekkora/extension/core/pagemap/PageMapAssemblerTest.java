package com.nekkora.extension.core.pagemap;

import com.nekkora.extension.core.analysis.PageAnalysis;
import com.nekkora.extension.core.options.OptionHistory;
import com.nekkora.extension.core.testing.Engine;
import com.nekkora.extension.core.testing.HtmlSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PageMapAssemblerTest {

    private static PageMap assemble(HtmlSnapshot h) {
        PageAnalysis a = Engine.analyze(h);
        return new PageMapAssembler().assemble(a, PageMapOptions.defaults(), new OptionHistory(), List.of(), List.of(),
                List.of(), List.of(), "2026-10-13T00:00:00Z", "FULL");
    }

    @Test
    void sensitiveValuesNeverAppear() { // T033 (camada Java)
        HtmlSnapshot h = HtmlSnapshot.of("""
                <form><label for=s>Senha</label><input id=s type=password value="S3nh@Secreta!">
                <label for=cc>Cartão</label><input id=cc name=cartao value="4111111111111111">
                <input id=t name=apelido value="Apelido Privado"><input type=hidden name=csrf value="tok-123"></form>""");
        PageMap map = assemble(h);
        String dump = map.toString();
        assertFalse(dump.contains("S3nh@Secreta!"));
        assertFalse(dump.contains("4111111111111111"));
        assertFalse(dump.contains("Apelido Privado"));
        assertFalse(dump.contains("tok-123"));
        ElementDescriptor pwd = map.elements().stream().filter(e -> "s".equals(e.attributes().get("id"))).findFirst().orElseThrow();
        assertTrue(pwd.sensitive());
    }

    @Test
    void textsAreDifferentiated() { // §9 / T028
        HtmlSnapshot h = HtmlSnapshot.of("<div class=cliente><h3>Cliente Ana</h3><p>Status: Ativo</p><button>Editar</button></div><span>Texto isolado</span>");
        PageMap map = assemble(h);
        ElementDescriptor btn = map.elements().stream().filter(e -> e.tag().equals("button")).findFirst().orElseThrow();
        assertEquals("Editar", btn.text().own());
        assertEquals("Editar", btn.text().accessibleName());
        assertTrue(btn.text().contextual().contains("Cliente Ana"));
        ElementDescriptor container = map.elements().stream().filter(e -> e.tag().equals("div")).findFirst().orElse(null);
        // contêiner sem texto próprio não é exportado e não duplica o texto dos descendentes
        assertEquals(null, container);
        ElementDescriptor isolated = map.elements().stream().filter(e -> e.tag().equals("span")).findFirst().orElseThrow();
        assertEquals("TEXT", isolated.kind());
    }

    @Test
    void coverageAndContract() {
        HtmlSnapshot h = HtmlSnapshot.of("""
                <nk-x id=closed><template shadowrootmode="closed"><button>Oculto</button></template></nk-x>
                <iframe id=ext data-fixture-frame=x></iframe>""").frame("x", "<p>x</p>", false, "CROSS_ORIGIN");
        PageMap map = assemble(h);
        assertEquals("nekkora.pagemap", map.schema());
        // CHROME-SECURITY-001: 0.3.0 (0.1.0 e 0.2.0 arquivados em contracts/v0.1.0 e contracts/v0.2.0); a política é declarada
        assertEquals("0.3.0", map.version());
        assertEquals("nekkora-privacy/2", map.privacy());
        assertNotNull(map.frameGraph(), "grafo documental presente");
        assertTrue(map.frameGraph().frames().stream().anyMatch(f -> "PERMISSION_DENIED".equals(f.status())), "iframe inacessível declarado no grafo");
        assertNotNull(map.document().navigation());
        assertTrue(map.coverage().limitations().stream().anyMatch(l -> l.contains("closed")));
        assertTrue(map.coverage().limitations().stream().anyMatch(l -> l.contains("CROSS_ORIGIN")));
        assertTrue(map.metrics().containsKey("classificationMs"));
    }

    @Test
    void parallelStreamedElementsEqualSequential() throws Exception { // §20.3
        StringBuilder sb = new StringBuilder("<main id=m>");
        for (int i = 0; i < 300; i++) {
            sb.append("<div class=card data-item=").append(i).append("><h3>Item ").append(i)
                    .append("</h3><label for=q").append(i).append(">Qtd</label><input id=q").append(i)
                    .append("><button>Adicionar</button></div>");
        }
        sb.append("</main>");
        PageAnalysis a = Engine.analyze(HtmlSnapshot.of(sb.toString()));
        PageMapAssembler asm = new PageMapAssembler();
        List<ElementDescriptor> sequential = new java.util.ArrayList<>();
        asm.elements(a, PageMapOptions.defaults(), sequential::add, 1);
        List<ElementDescriptor> parallel = new java.util.ArrayList<>();
        PageMapAssembler.ElementStats st = asm.elements(a, PageMapOptions.defaults(), parallel::add, 4);
        assertEquals(4, st.parallelism());
        assertEquals(sequential.size(), parallel.size());
        assertEquals(sequential, parallel, "mesmo conteúdo e mesma ordem de documento");
    }
}
