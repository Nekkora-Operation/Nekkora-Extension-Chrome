package com.nekkora.extension.it.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekkora.extension.it.support.BrowserLab.Page;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Base dos testes CHROME-003: grafo documental, trajetórias, proteções e prontidão. */
public abstract class FrameIT extends SessionIT {

    protected static final ObjectMapper M = new ObjectMapper();
    protected static final String FRAMES = "/lab/chrome003/frames.html";
    protected static final String DYNAMIC = "/lab/chrome003/frames-dynamic.html";
    protected static final String ORIGINS = "/lab/chrome003/frames-origins.html";
    protected static final String PROTECTION = "/lab/chrome003/protection/";

    /** Abre a página, ativa a inspeção (captura automática) e espera o grafo estabilizar com {@code frames} frames. */
    protected Page openActive(String path, int expectedFrames) {
        Page p = open(path);
        activate(p);
        if (expectedFrames >= 0) {
            waitGraph(p, g -> g.at("/counts/frames").asInt() >= expectedFrames && g.at("/counts/loading").asInt() == 0,
                    expectedFrames + " frame(s) inventariados");
        }
        return p;
    }

    protected JsonNode graph(Page p) {
        return lab.call("frames.graph", p);
    }

    protected JsonNode waitGraph(Page p, Predicate<JsonNode> condition, String what) {
        JsonNode[] last = new JsonNode[1];
        BrowserLab.waitUntil(Duration.ofSeconds(30), () -> condition.test(last[0] = graph(p)), what);
        return last[0];
    }

    protected JsonNode diagnose(Page p) {
        return lab.call("page.diagnose", p);
    }

    protected JsonNode accessPath(Page p, String nodeId) {
        return lab.call("element.accessPath", p, "nodeId", nodeId);
    }

    /** Id temporário de um elemento seguindo uma cadeia de escopos ({@code "FRAME", css, "SHADOW_HOST", css, ...}). */
    protected String idIn(Page p, String css, Object... chain) {
        List<Map<String, String>> hops = new ArrayList<>();
        for (int i = 0; i + 1 < chain.length; i += 2) {
            hops.add(Map.of("kind", (String) chain[i], "css", (String) chain[i + 1]));
        }
        JsonNode r = lab.call("page.idFor", p, "css", css, "scope", Map.of("chain", hops));
        assertEquals(1, r.path("count").asInt(), css + " " + r);
        return r.at("/nodeIds/0").asText();
    }

    protected JsonNode selectNode(Page p, String nodeId) {
        return lab.call("page.select", p, "nodeId", nodeId);
    }

    protected JsonNode exportMap(Page p) {
        return lab.call("pagemap.export", p, "returnPageMap", true).get("pageMap");
    }

    protected static JsonNode frameTitled(JsonNode graph, String title) {
        for (JsonNode f : graph.get("frames")) {
            if (title.equals(f.path("title").asText(null))) {
                return f;
            }
        }
        return null;
    }

    protected static JsonNode requireFrame(JsonNode graph, String title) {
        JsonNode f = frameTitled(graph, title);
        assertNotNull(f, "frame '" + title + "' em " + titles(graph));
        return f;
    }

    protected static List<String> titles(JsonNode graph) {
        List<String> out = new ArrayList<>();
        graph.get("frames").forEach(f -> out.add(f.path("frameId").asText() + ":" + f.path("title").asText(null) + ":" + f.path("status").asText()));
        return out;
    }

    protected static JsonNode document(JsonNode graph, String documentId) {
        for (JsonNode d : graph.get("documents")) {
            if (documentId.equals(d.path("documentId").asText())) {
                return d;
            }
        }
        return null;
    }

    protected static List<String> relationTypes(JsonNode graph) {
        List<String> out = new ArrayList<>();
        graph.get("relations").forEach(r -> {
            if (!out.contains(r.path("type").asText())) {
                out.add(r.path("type").asText());
            }
        });
        return out;
    }

    protected static List<String> stepTypes(JsonNode path) {
        List<String> out = new ArrayList<>();
        path.get("steps").forEach(s -> out.add(s.path("type").asText()));
        return out;
    }

    protected static List<String> stepLabels(JsonNode path) {
        List<String> out = new ArrayList<>();
        path.get("steps").forEach(s -> out.add(s.path("label").asText()));
        return out;
    }

    protected static List<String> texts(JsonNode array, String field) {
        List<String> out = new ArrayList<>();
        if (array != null) {
            array.forEach(x -> out.add(x.path(field).asText()));
        }
        return out;
    }

    protected static JsonNode assessment(JsonNode protection, String product) {
        for (JsonNode a : protection.get("assessments")) {
            if (product.equals(a.path("product").asText())) {
                return a;
            }
        }
        return null;
    }

    protected void evidence(String name, Object data) {
        try {
            lab.writeEvidence(name, M.writerWithDefaultPrettyPrinter().writeValueAsString(data));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    protected void screenshot(Page p, String name) {
        try {
            lab.screenshot(p, name);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
