package com.nekkora.extension.it.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.nekkora.extension.core.privacy.CapturePrivacyPolicy;

import java.util.function.Predicate;

/**
 * CHROME-SECURITY-001 — apoio dos testes de navegador sob {@code nekkora-privacy/2}. As asserções que antes comparavam o
 * TEXTO da página passam a exigir exatamente o resultado da política sobre esse texto (e a capacidade estrutural por
 * outro caminho), nunca um resultado mais frouxo.
 */
public final class Policy {

    private Policy() {
    }

    /** O nome que o motor pode mostrar para um texto de interface da página: ele mesmo, se do vocabulário; senão "". */
    public static String name(String pageText) {
        String t = CapturePrivacyPolicy.interfaceText(pageText);
        return t == null ? "" : t;
    }

    /** O texto é metadado do vocabulário controlado (pode atravessar)? */
    public static boolean passes(String pageText) {
        return CapturePrivacyPolicy.interfaceText(pageText) != null;
    }

    /** URL como o motor/Studio a veem (modelo). */
    public static String url(String pageUrl) {
        return CapturePrivacyPolicy.urlTemplate(pageUrl);
    }

    /** Quantos elementos do PageMap satisfazem a condição. */
    public static long count(JsonNode pageMap, Predicate<JsonNode> p) {
        long n = 0;
        for (JsonNode e : pageMap.path("elements")) {
            if (p.test(e)) {
                n++;
            }
        }
        return n;
    }

    public static long countTag(JsonNode pageMap, String tag) {
        return count(pageMap, e -> tag.equals(e.path("tag").asText()));
    }

    /** Frame do grafo documental hospedado pelo nó (elemento iframe) indicado — identificação estrutural, sem título. */
    public static JsonNode frameHostedBy(JsonNode graph, String hostNodeId) {
        for (JsonNode f : graph.path("frames")) {
            if (hostNodeId.equals(f.path("hostNodeId").asText())) {
                return f;
            }
        }
        return null;
    }
}
