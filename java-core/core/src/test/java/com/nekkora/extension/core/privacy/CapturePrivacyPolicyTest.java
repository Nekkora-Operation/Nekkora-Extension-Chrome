package com.nekkora.extension.core.privacy;

import com.nekkora.extension.core.analysis.PageAnalysis;
import com.nekkora.extension.core.mutation.MutationRecord;
import com.nekkora.extension.core.mutation.NodeChange;
import com.nekkora.extension.core.mutation.SnapshotComparator;
import com.nekkora.extension.core.snapshot.RawNode;
import com.nekkora.extension.core.testing.Engine;
import com.nekkora.extension.core.testing.HtmlSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CHROME-SECURITY-001 — superfícies corrigidas antes do ponto de controle (independentes da decisão de política). */
class CapturePrivacyPolicyTest {

    @Test
    @DisplayName("SEC001-030/034 — MutationRecord: texto alterado vira o FATO da mudança, nunca o conteúdo antes/depois")
    void textChangeIsWithheld() {
        HtmlSnapshot h = HtmlSnapshot.of("<main><p id=cliente>Cliente não informado</p><button id=b>Carregar</button></main>");
        PageAnalysis a = Engine.analyze(h);
        h.dom().selectFirst("#cliente").text("Cliente Ulisses Paranhos");
        MutationRecord r = new SnapshotComparator().compare(a.graph(), Engine.analyze(h).graph(), "FULL", 0, 0);
        NodeChange ch = r.changed().stream().filter(c -> c.textBefore() != null).findFirst().orElseThrow();
        assertEquals(CapturePrivacyPolicy.WITHHELD, ch.textBefore());
        assertEquals(CapturePrivacyPolicy.WITHHELD, ch.textAfter());
        assertFalse(r.toString().contains("Paranhos"), "o texto novo não aparece no registro: " + r);
        assertFalse(r.toString().contains("não informado"), "nem o antigo");
    }

    @Test
    @DisplayName("SEC001-031 — delta de atributo: o nome do atributo fica; valor de data-* fica retido; estado ARIA continua")
    void attributeValuesWithheldUnlessStructural() {
        HtmlSnapshot h = HtmlSnapshot.of("<div id=c data-cliente=nenhum><button id=t aria-expanded=false>Mais</button></div>");
        PageAnalysis a = Engine.analyze(h);
        h.dom().selectFirst("#c").attr("data-cliente", "Adalgisa Tremembé");
        h.dom().selectFirst("#t").attr("aria-expanded", "true");
        MutationRecord r = new SnapshotComparator().compare(a.graph(), Engine.analyze(h).graph(), "FULL", 0, 0);
        Map<String, List<String>> all = new java.util.HashMap<>();
        r.changed().forEach(c -> all.putAll(c.attributeChanges()));
        assertEquals(List.of(CapturePrivacyPolicy.WITHHELD, CapturePrivacyPolicy.WITHHELD), all.get("data-cliente"));
        assertEquals(List.of("false", "true"), all.get("aria-expanded"), "estado de vocabulário fechado continua útil ao diagnóstico");
        assertFalse(r.toString().contains("Trememb"), r.toString());
    }

    @Test
    @DisplayName("SEC001-034 — barreira no próprio registro: qualquer produtor de NodeChange tem o conteúdo retido; ausência continua null")
    void nodeChangeBarrier() {
        NodeChange ch = new NodeChange("n1", Map.of("title", Arrays.asList(null, "Observação sobre Teodolinda Vasconi"),
                "hidden", Arrays.asList(null, "")), "antes", "depois", List.of(), false);
        assertEquals(Arrays.asList(null, CapturePrivacyPolicy.WITHHELD), ch.attributeChanges().get("title"), "ausência → presença retida");
        assertEquals(Arrays.asList(null, ""), ch.attributeChanges().get("hidden"));
        assertEquals(CapturePrivacyPolicy.WITHHELD, ch.textBefore());
        assertFalse(ch.toString().contains("Vasconi"));
        NodeChange none = new NodeChange("n2", Map.of(), null, null, List.of("visible: true→false"), false);
        assertNull(none.textBefore(), "sem mudança de texto continua null (não inventa mudança)");
        assertFalse(none.noChanges());
    }

    @Test
    @DisplayName("SEC001-004 — exposedValue nunca entra no motor, mesmo que uma extensão antiga o envie")
    void exposedValueNeverAccepted() {
        RawNode n = new RawNode("n1", null, List.of(), "input", "f0", "doc:f0", Map.of("type", "search"), "", null, null, List.of(), null,
                "Quirino Valadares");
        assertNull(n.exposedValue());
    }

    @Test
    @DisplayName("SEC001-011 — observação de interação: lista informa a POSIÇÃO da opção selecionada, não o texto")
    void selectValueIsPosition() {
        HtmlSnapshot h = HtmlSnapshot.of("<label for=t>Titular</label><select id=t><option>—</option><option>Zéfiro Quintanilha</option></select>");
        PageAnalysis a = Engine.analyze(h);
        h.dom().selectFirst("option:nth-child(2)").attr("selected", "");
        PageAnalysis b = Engine.analyze(h);
        var obs = new com.nekkora.extension.core.interaction.InteractionAnalyzer().analyze(a.graph(), b.graph(), null);
        assertFalse(obs.toString().contains("Quintanilha"), obs.toString());
        assertTrue(obs.toString().contains("#2"), "a mudança continua observável pela posição: " + obs);
    }
}
