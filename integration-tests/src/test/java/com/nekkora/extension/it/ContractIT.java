package com.nekkora.extension.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.nekkora.extension.it.support.BrowserLab;
import com.nekkora.extension.it.support.BrowserLab.Page;
import com.nekkora.extension.it.support.Contracts;
import com.nekkora.extension.it.support.LabIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openqa.selenium.By;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Contrato com dados REAIS do navegador: snapshots e PageMaps de várias páginas do laboratório. */
class ContractIT extends LabIT {

    @ParameterizedTest(name = "C-8 — Contrato real: {0}")
    @DisplayName("C-8 — Snapshot bruto e PageMap reais conformes ao contrato")
    @ValueSource(strings = {
            "/lab/simple/form-basics.html",
            "/lab/intermediate/widgets.html",
            "/lab/complex/duplicated-ids.html",
            "/lab/complex/shadow-iframes.html",
            "/lab/complex/dropdowns.html",
            "/clientes?nome=ana&status=ativo",
            "/generated/large?n=1000"})
    void realContracts(String path) throws Exception {
        Page p = open(path);
        if (path.contains("shadow")) {
            BrowserLab.waitUntil(Duration.ofSeconds(10), () -> Boolean.TRUE.equals(lab.js(p,
                    "const f=document.getElementById('frame-pagamento'); return !!(f.contentDocument && f.contentDocument.getElementById('pagar'))")), "iframe carregado");
        }
        JsonNode raw = lab.call("page.rawSnapshot", p);
        Contracts.assertValid("raw-snapshot.schema.json", raw);
        // Popula todas as seções: alvo, resolução, mutação e evidências.
        lab.call("page.capture", p);
        JsonNode first = lab.call("page.idFor", p, "css", "button");
        if (first.get("count").asInt() > 0) {
            lab.call("page.select", p, "nodeId", first.at("/nodeIds/0").asText());
            JsonNode state = lab.call("state.get", java.util.Map.of("tabId", p.tabId()));
            String target = state.at("/tab/selected/targetId").asText();
            lab.call("target.resolve", p, "targetId", target);
        }
        if (path.contains("duplicated")) {
            lab.click(p, By.id("reordenar"));
        }
        lab.call("page.compare", p);
        JsonNode export = lab.call("pagemap.export", p, "fresh", true);
        JsonNode map = export.get("pageMap");
        Contracts.assertValid("page-map.schema.json", map);
        assertTrue(map.get("elements").size() > 0);
        assertTrue(map.get("mutations").size() > 0);
    }
}
