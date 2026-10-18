package com.nekkora.extension.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.nekkora.extension.it.support.BrowserLab;
import com.nekkora.extension.it.support.BrowserLab.Page;
import com.nekkora.extension.it.support.FrameIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NEKKORA-E2E-001 — leitura do desafio REAL (https://rpachallenge.com, autorizado pela usuária para a missão).
 * Suíte opcional real-web ({@code -Suite real -Test E2e001ChallengeLiveWebIT}). Somente leitura: NÃO clica em Start nem
 * em Submit, não digita nada. Registra a classificação e os localizadores que a extensão propõe para os 7 campos.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class E2e001ChallengeLiveWebIT extends FrameIT {

    static final String PAGE = BrowserLab.CHALLENGE_ORIGIN + "/";
    static final List<String> LABELS = List.of("First Name", "Last Name", "Company Name", "Role in Company", "Address", "Email",
            "Phone Number");

    @Test
    @Order(1)
    @DisplayName("E2E-01 — desafio autorizado: campos com rótulo irmão; candidatos de localizador observados (sem Start)")
    void challengeFields() {
        Page p = openUrl(PAGE);
        JsonNode acc = lab.call("access.describe", p);
        assertEquals("AUTHORIZED", acc.get("kind").asText(), acc.toString());
        activate(p);
        List<Map<String, Object>> fields = new ArrayList<>();
        for (String label : LABELS) {
            // seleção da HOMOLOGAÇÃO (não do produto): o contêiner cujo único rótulo é o texto do campo
            String css = "input[ng-reflect-name='label" + label.replace(" in Company", "").replace(" Number", "").replace(" ", "") + "']";
            long n = liveCount(p, css);
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("label", label);
            f.put("selectionCount", n);
            if (n == 1) {
                JsonNode sel = select(p, css);
                f.put("classification", sel.at("/inspection/classification"));
                f.put("locators", sel.at("/inspection/locators"));
            }
            fields.add(f);
        }
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("access", acc);
        ev.put("fields", fields);
        evidence("E2E-01-challenge-fields.json", ev);
        assertTrue(fields.stream().allMatch(f -> Long.valueOf(1).equals(f.get("selectionCount"))), fields.toString());
    }
}
