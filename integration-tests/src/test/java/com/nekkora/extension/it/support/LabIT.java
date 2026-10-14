package com.nekkora.extension.it.support;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Base dos testes de homologação: abre páginas do laboratório e aciona a extensão. */
public abstract class LabIT {

    protected static BrowserLab lab;
    private final List<BrowserLab.Page> opened = new ArrayList<>();

    @BeforeAll
    static void environment() {
        lab = BrowserLab.get();
    }

    @AfterEach
    void closePages() {
        for (BrowserLab.Page p : opened) {
            lab.close(p);
        }
        opened.clear();
    }

    protected BrowserLab.Page open(String path) {
        BrowserLab.Page p = lab.open(path);
        opened.add(p);
        return p;
    }

    protected BrowserLab.Page openUrl(String url) {
        BrowserLab.Page p = lab.openUrl(url);
        opened.add(p);
        return p;
    }

    protected BrowserLab.Page openWithoutContent(String url) {
        BrowserLab.Page p = lab.openWithoutContent(url);
        opened.add(p);
        return p;
    }

    /** Seleciona por CSS (exige exatamente 1 elemento) e devolve {captureId, inspection, identity, verification, liveChecks}. */
    protected JsonNode select(BrowserLab.Page p, String css) {
        return lab.call("page.select", p, "css", css);
    }

    protected JsonNode selectIn(BrowserLab.Page p, String css, String hopKind, String hostCss) {
        Map<String, Object> hop = new LinkedHashMap<>();
        hop.put("kind", hopKind);
        hop.put("css", hostCss);
        return lab.call("page.select", p, "css", css, "scope", Map.of("chain", List.of(hop)));
    }

    protected JsonNode resolve(BrowserLab.Page p, String targetId) {
        return lab.call("target.resolve", p, "targetId", targetId).get("result");
    }

    protected String nodeIdOf(BrowserLab.Page p, String css) {
        JsonNode r = lab.call("page.idFor", p, "css", css);
        assertEquals(1, r.path("count").asInt(), "esperado exatamente 1 elemento para " + css);
        return r.at("/nodeIds/0").asText();
    }

    protected JsonNode capture(BrowserLab.Page p) {
        return lab.call("page.capture", p);
    }

    /** As contagens da DOM real coincidem com as do snapshot para todos os localizadores verificáveis. */
    protected static void assertLiveAgreement(JsonNode selection) {
        JsonNode v = selection.get("verification");
        assertTrue(v.path("compared").asInt() > 0, "nenhum localizador verificado ao vivo");
        assertTrue(v.path("allAgree").asBoolean(), "divergência DOM real × snapshot: " + v.get("agreement"));
    }

    protected static JsonNode locator(JsonNode selection, String strategy) {
        JsonNode set = selection.at("/inspection/locators");
        List<JsonNode> all = new ArrayList<>();
        all.add(set.get("primary"));
        set.get("fallbacks").forEach(all::add);
        return all.stream().filter(l -> strategy.equals(l.path("strategy").asText())).findFirst().orElse(null);
    }

    protected static boolean hasCondition(JsonNode selection, String condition) {
        for (JsonNode c : selection.at("/inspection/classification/conditions")) {
            if (c.asText().equals(condition)) {
                return true;
            }
        }
        return false;
    }
}
