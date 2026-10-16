package com.nekkora.extension.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.nekkora.extension.it.support.BrowserLab;
import com.nekkora.extension.it.support.BrowserLab.Page;
import com.nekkora.extension.it.support.LabIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.interactions.WheelInput;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fase 08 — componentes complexos: suporte implementado ou limite declarado. */
class ComplexComponentsIT extends LabIT {

    private static final String DROPDOWNS = "/lab/complex/dropdowns.html";
    private static final String WIDGETS = "/lab/intermediate/widgets.html";
    private static final String SHADOW = "/lab/complex/shadow-iframes.html";

    @Test
    @DisplayName("T012 — Dropdown personalizado: controle e lista associados")
    void customDropdown() {
        JsonNode s = select(open(DROPDOWNS), "#estado");
        assertEquals("COMBOBOX", s.at("/inspection/classification/kind").asText());
        JsonNode o = s.at("/inspection/optionSet");
        assertEquals("ARIA_CONTROLS", o.get("association").asText());
        assertFalse(o.get("listOutsideControlTree").asBoolean());
        assertEquals(2, o.get("materializedCount").asInt());
        // CHROME-SECURITY-001: rótulo de opção é dado, não metadado de interface — identificada, nunca transmitida
        assertEquals(com.nekkora.extension.it.support.Policy.name("São Paulo"), o.at("/options/0/text").asText(""));
        assertEquals("UNKNOWN", o.get("completeness").asText(), "componente personalizado não comprova completude");
    }

    @Test
    @DisplayName("T013 — Dropdown com portal: associação lógica correta")
    void portalDropdown() {
        JsonNode s = select(open(DROPDOWNS), "#pais");
        assertEquals(com.nekkora.extension.it.support.Policy.name("País de entrega"), s.at("/inspection/classification/name/name").asText());
        JsonNode o = s.at("/inspection/optionSet");
        assertTrue(o.get("listOutsideControlTree").asBoolean(), "lista renderizada no portal, fora da subárvore do controle");
        assertEquals(3, o.get("materializedCount").asInt());
        assertTrue(s.at("/inspection/family/relations").toString().contains("CONTROLS"));
    }

    @Test
    @DisplayName("T014 — Dropdown dinâmico: cobertura declarada")
    void dynamicDropdown() {
        Page p = open(DROPDOWNS);
        JsonNode before = select(p, "#cidade").at("/inspection/optionSet");
        assertEquals(0, before.get("materializedCount").asInt(), "captura inicial é somente leitura: lista ainda não carregada");
        assertEquals("UNKNOWN", before.get("completeness").asText());
        // Exploração interativa em etapa separada, por clique real.
        lab.click(p, By.id("cidade"));
        BrowserLab.waitUntil(Duration.ofSeconds(10), () -> ((Number) lab.js(p,
                "return document.querySelectorAll('#lista-cidades [role=option]').length")).intValue() > 0, "opções carregadas");
        JsonNode after = select(p, "#cidade").at("/inspection/optionSet");
        assertEquals(15, after.get("materializedCount").asInt());
        assertTrue(after.get("dynamicLoading").asBoolean(), after.get("evidence").toString());
        assertEquals("UNKNOWN", after.get("completeness").asText());
    }

    @Test
    @DisplayName("T021 — Lista virtualizada: não presumir completude")
    void virtualizedList() {
        Page p = open(DROPDOWNS);
        JsonNode o = select(p, "#virtual").at("/inspection/optionSet");
        assertEquals(20, o.get("materializedCount").asInt());
        assertEquals(1000, o.get("announcedSetSize").asInt());
        assertTrue(o.get("virtualizationSuspected").asBoolean());
        assertEquals("INCOMPLETE", o.get("completeness").asText());
        // Rolagem real (roda do mouse): novas opções materializadas, observadas acumulam.
        lab.focus(p);
        WebElement list = lab.driver().findElement(By.id("virtual"));
        new Actions(lab.driver()).scrollFromOrigin(WheelInput.ScrollOrigin.fromElement(list), 0, 1200).perform();
        BrowserLab.waitUntil(Duration.ofSeconds(5), () -> ((Number) lab.js(p, "return document.getElementById('virtual').scrollTop")).intValue() > 600, "rolagem");
        JsonNode o2 = select(p, "#virtual").at("/inspection/optionSet");
        assertEquals(20, o2.get("materializedCount").asInt());
        assertTrue(o2.get("observedCount").asInt() > 20, "observadas: " + o2.get("observedCount"));
        assertEquals("INCOMPLETE", o2.get("completeness").asText());
    }

    @Test
    @DisplayName("T015 — Modal: contexto correto")
    void modalContext() {
        Page p = open(WIDGETS);
        lab.click(p, By.id("abrir-modal"));
        BrowserLab.waitUntil(Duration.ofSeconds(5), () -> Boolean.TRUE.equals(lab.js(p, "return document.getElementById('modal-editar').open")), "modal aberto");
        JsonNode inside = select(p, "#salvar-modal");
        assertEquals("MODAL", inside.at("/inspection/family/dialog/kind").asText());
        assertEquals("Editar cliente", inside.at("/inspection/family/dialog/name").asText());
        JsonNode outside = select(p, "#salvar-pagina");
        assertTrue(outside.at("/inspection/family/dialog").isNull(), "botão Salvar da página não pertence ao modal");
        assertTrue(hasCondition(outside, "INACCESSIBLE"), "conteúdo fora do modal aberto fica inerte");
        assertTrue(inside.at("/identity/discriminators").toString().contains("DIALOG"), inside.at("/identity/discriminators").toString());
    }

    @Test
    @DisplayName("T016 — Modal fechado e aberto: estado atualizado")
    void modalStates() {
        Page p = open(WIDGETS);
        JsonNode closed = select(p, "#salvar-modal");
        assertTrue(hasCondition(closed, "HIDDEN"));
        lab.click(p, By.id("abrir-modal"));
        JsonNode cmp = lab.call("page.compare", p);
        assertTrue(cmp.get("relevant").asBoolean());
        assertTrue(cmp.get("changed").toString().contains("open: false→true"), cmp.get("changed").toString());
        JsonNode opened = select(p, "#salvar-modal");
        assertTrue(hasCondition(opened, "VISIBLE"));
        JsonNode r = resolve(p, closed.at("/identity/targetId").asText());
        assertEquals("RESOLVED", r.get("status").asText());
        lab.click(p, By.id("fechar-modal"));
        BrowserLab.waitUntil(Duration.ofSeconds(5), () -> Boolean.FALSE.equals(lab.js(p, "return document.getElementById('modal-editar').open")), "modal fechado");
        assertTrue(hasCondition(select(p, "#salvar-modal"), "HIDDEN"));
    }

    @Test
    @DisplayName("T017 — Shadow DOM aberto: inspeção quando acessível")
    void openShadow() {
        Page p = open(SHADOW);
        JsonNode s = selectIn(p, "#aplicar", "SHADOW_HOST", "nk-open-card#cartao-aberto");
        assertEquals(com.nekkora.extension.it.support.Policy.name("Aplicar cupom"), s.at("/inspection/classification/name/name").asText());
        assertEquals("SHADOW_ROOT", s.at("/inspection/locators/primary/scope/type").asText());
        // CHROME-SECURITY-001: o id "cartao-aberto" não é do vocabulário → o hospedeiro do shadow é localizado pela estrutura
        String hostCss = s.at("/inspection/locators/primary/scope/chain/0/css").asText();
        assertTrue(com.nekkora.extension.core.privacy.CapturePrivacyPolicy.expressionSafe(hostCss) && hostCss.contains("nk-open-card"), hostCss);
        assertFalse(hostCss.contains("cartao-aberto"), hostCss);
        assertTrue(s.at("/inspection/locators/primary/xpath").isNull(), "XPath não atravessa shadow root");
        assertLiveAgreement(s);
        assertEquals("RESOLVED", resolve(p, s.at("/identity/targetId").asText()).get("status").asText());
    }

    @Test
    @DisplayName("T018 — Shadow DOM fechado: limite declarado")
    void closedShadow() {
        Page p = open(SHADOW);
        JsonNode cov = capture(p).get("coverage");
        JsonNode closed = null;
        for (JsonNode s : cov.get("shadowRoots")) {
            if ("SHADOW_ROOT_CLOSED".equals(s.get("kind").asText())) {
                closed = s;
            }
        }
        assertNotNull(closed, cov.toString());
        assertFalse(closed.get("inspected").asBoolean());
        assertEquals(0, closed.get("elements").asInt(), "conteúdo encapsulado não é inventariado");
        assertTrue(cov.get("limitations").toString().contains("closed"));
    }

    @Test
    @DisplayName("T019 — Iframe acessível: caminho identificado")
    void accessibleIframe() {
        Page p = open(SHADOW);
        BrowserLab.waitUntil(Duration.ofSeconds(10), () -> Boolean.TRUE.equals(lab.js(p,
                "const f=document.getElementById('frame-pagamento'); return !!(f.contentDocument && f.contentDocument.getElementById('pagar'))")), "iframe carregado");
        JsonNode s = selectIn(p, "#pagar", "FRAME", "iframe#frame-pagamento");
        assertEquals("FRAME", s.at("/inspection/locators/primary/scope/type").asText());
        // CHROME-SECURITY-001: ids fora do vocabulário não atravessam; o escopo do frame usa só o que é transportável
        String frameCss = s.at("/inspection/locators/primary/scope/chain/0/css").asText();
        assertTrue(com.nekkora.extension.core.privacy.CapturePrivacyPolicy.expressionSafe(frameCss), frameCss);
        assertFalse(frameCss.contains("frame-pagamento"), frameCss);
        assertEquals(com.nekkora.extension.it.support.Policy.id("frame-pagamento"), s.at("/inspection/family/scopeChain/0/idAttr").asText(null));
        assertEquals(com.nekkora.extension.it.support.Policy.id("form-pagamento"), s.at("/inspection/family/form/idAttr").asText(null));
        assertLiveAgreement(s);
        assertEquals("RESOLVED", resolve(p, s.at("/identity/targetId").asText()).get("status").asText());
    }

    @Test
    @DisplayName("T020 — Iframe inacessível: limite declarado")
    void inaccessibleIframes() {
        Page p = open(SHADOW);
        BrowserLab.waitUntil(Duration.ofSeconds(10), () -> Boolean.TRUE.equals(lab.js(p,
                "return document.getElementById('frame-sandbox').getAttribute('sandbox') !== null")), "página pronta");
        JsonNode cov = capture(p).get("coverage");
        String cross = null;
        String sandbox = null;
        for (JsonNode f : cov.get("frames")) {
            if (f.get("url").asText("").contains("localhost")) {
                cross = f.get("reason").asText();
                assertFalse(f.get("inspected").asBoolean());
            }
            if (!f.get("inspected").asBoolean() && "SANDBOXED".equals(f.get("reason").asText())) {
                sandbox = f.get("reason").asText();
            }
        }
        assertEquals("CROSS_ORIGIN", cross, cov.get("frames").toString());
        assertEquals("SANDBOXED", sandbox, cov.get("frames").toString());
        assertTrue(cov.get("limitations").toString().contains("CROSS_ORIGIN"));
        assertNull(null);
    }
}
