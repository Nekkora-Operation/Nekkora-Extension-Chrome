package com.nekkora.extension.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.nekkora.extension.it.support.BrowserLab.Page;
import com.nekkora.extension.it.support.LabIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Captura e classificação em página real (form-basics). */
class CaptureClassificationIT extends LabIT {

    private static final String PAGE = "/lab/simple/form-basics.html";

    @Test
    @DisplayName("T001 — Input com ID estável: identificação correta")
    void stableId() {
        Page p = open(PAGE);
        JsonNode s = select(p, "#customer-name");
        assertEquals("TEXT_INPUT", s.at("/inspection/classification/kind").asText());
        assertEquals("Nome", s.at("/inspection/classification/name/name").asText());
        assertEquals("LABEL_ELEMENT", s.at("/inspection/classification/name/source").asText());
        assertEquals("ID", s.at("/inspection/locators/primary/strategy").asText());
        assertEquals("input#customer-name", s.at("/inspection/locators/primary/css").asText());
        assertEquals("customer-form", s.at("/inspection/family/form/idAttr").asText());
        assertTrue(hasCondition(s, "REQUIRED"));
        assertLiveAgreement(s);
        JsonNode r = resolve(p, s.at("/identity/targetId").asText());
        assertEquals("RESOLVED", r.get("status").asText());
        assertEquals(s.at("/inspection/nodeId").asText(), r.get("resolvedNodeId").asText());
    }

    @Test
    @DisplayName("T002 — Input sem ID: localizador alternativo")
    void withoutId() {
        Page p = open(PAGE);
        JsonNode s = select(p, "input[name=email]");
        assertEquals("EMAIL_INPUT", s.at("/inspection/classification/kind").asText());
        assertEquals("E-mail", s.at("/inspection/classification/name/name").asText());
        assertEquals("WRAPPING_LABEL", s.at("/inspection/classification/name/source").asText());
        assertEquals(1, s.at("/inspection/locators/primary/matchCount").asInt());
        assertNotEquals("POSITIONAL", s.at("/inspection/locators/primary/strategy").asText());
        assertNotNull(locator(s, "NAME"));
        assertNotNull(locator(s, "LABEL"));
        assertLiveAgreement(s);
        assertEquals("RESOLVED", resolve(p, s.at("/identity/targetId").asText()).get("status").asText());
    }

    @Test
    @DisplayName("T003 — Input sem name: localizador contextual")
    void withoutName() {
        Page p = open(PAGE);
        JsonNode s = select(p, "input[type=tel]");
        assertEquals("TEL_INPUT", s.at("/inspection/classification/kind").asText());
        String primary = s.at("/inspection/locators/primary/strategy").asText();
        assertTrue(primary.equals("ATTRIBUTE") || primary.equals("CONTEXTUAL"), "primário: " + primary);
        assertEquals(1, s.at("/inspection/locators/primary/matchCount").asInt());
        assertTrue(s.at("/inspection/family/contextualTexts").toString().contains("Telefone"), "texto contextual 'Telefone' da família");
        assertLiveAgreement(s);
    }

    @Test
    @DisplayName("T010 — Select nativo: opções identificadas")
    void nativeSelect() {
        Page p = open(PAGE);
        JsonNode s = select(p, "#uf");
        JsonNode o = s.at("/inspection/optionSet");
        assertEquals("SELECT", s.at("/inspection/classification/kind").asText());
        assertEquals("Estado", s.at("/inspection/classification/name/name").asText());
        assertEquals("NATIVE", o.get("association").asText());
        assertEquals("COMPLETE", o.get("completeness").asText());
        assertEquals(4, o.get("materializedCount").asInt());
        // CHROME-SECURITY-001: o VALOR de uma opção ("SP") é dado de negócio — não atravessa; a estrutura da lista, sim
        assertTrue(o.at("/options/1/value").isMissingNode() || o.at("/options/1/value").isNull() || o.at("/options/1/value").asText().isEmpty(),
                o.at("/options/1").toString());
        assertFalse(o.toString().contains("\"SP\""), "valor da opção retido: " + o);
        assertTrue(o.at("/options/1/selected").asBoolean());
        assertTrue(o.at("/options/2/disabled").asBoolean());
        assertFalse(o.get("multiple").asBoolean());
    }

    @Test
    @DisplayName("T011 — Select com optgroup: grupos identificados")
    void optgroups() {
        Page p = open(PAGE);
        JsonNode o = select(p, "#segmento").at("/inspection/optionSet");
        assertEquals(2, o.get("groups").size());
        // o rótulo do grupo (atributo label) é texto de negócio: não atravessa; a estrutura dos grupos, sim
        assertFalse(o.toString().contains("Varejo") || o.toString().contains("Serviços"), "rótulos de grupo retidos: " + o);
        assertEquals(2, o.at("/groups/0/optionCount").asInt());
        assertTrue(o.at("/groups/1/disabled").asBoolean());
    }

    @Test
    @DisplayName("T027 — Botão desabilitado: estado correto")
    void disabled() {
        JsonNode s = select(open(PAGE), "#excluir-conta");
        assertEquals("BUTTON", s.at("/inspection/classification/kind").asText());
        assertTrue(hasCondition(s, "DISABLED"));
        assertFalse(hasCondition(s, "ENABLED"));
        assertTrue(hasCondition(s, "VISIBLE"));
    }

    @Test
    @DisplayName("T028 — Texto isolado: classificação correta")
    void isolatedText() {
        Page p = open(PAGE);
        JsonNode s = select(p, "#nota-solta");
        assertEquals("TEXT", s.at("/inspection/classification/kind").asText());
        assertTrue(hasCondition(s, "NON_INTERACTIVE"));
        // role=alert + class field-error: mensagem de validação (heurística declarada nas evidências)
        JsonNode erro = select(p, "#erro-geral");
        assertEquals("VALIDATION_MESSAGE", erro.at("/inspection/classification/kind").asText());
        assertTrue(erro.at("/inspection/classification/evidence").toString().contains("erro"));
        assertEquals("INSTRUCTION", select(p, "#dica").at("/inspection/classification/kind").asText());
        assertEquals("HEADING", select(p, "#ajuda").at("/inspection/classification/kind").asText());
        assertEquals("PARAGRAPH", select(p, "#intro").at("/inspection/classification/kind").asText());
    }

    @Test
    @DisplayName("T029 — Label externo: associação correta")
    void externalLabel() {
        Page p = open(PAGE);
        JsonNode s = select(p, "#cidade-externa");
        // CHROME-SECURITY-001: "Cidade (label externo)" não é inteiro do vocabulário → o nome é retido inteiro; a ASSOCIAÇÃO
        // com o label externo continua resolvida (relação por id de nó resolvida na página)
        assertEquals("", s.at("/inspection/classification/name/name").asText());
        assertEquals("label", s.at("/inspection/family/labels/0/tag").asText());
        assertLiveAgreement(s);
        // a mesma capacidade com metadado do vocabulário: o nome vem do <label> e gera localizador por rótulo
        JsonNode uf = select(p, "#uf");
        assertEquals("Estado", uf.at("/inspection/classification/name/name").asText());
        assertEquals("LABEL_ELEMENT", uf.at("/inspection/classification/name/source").asText());
        assertNotNull(locator(uf, "LABEL"));
        assertLiveAgreement(uf);
    }

    @Test
    @DisplayName("T030 — Elemento contenteditable: identificação correta")
    void contentEditable() {
        JsonNode s = select(open(PAGE), "#bio");
        assertEquals("CONTENTEDITABLE", s.at("/inspection/classification/kind").asText());
        // "Biografia" não é do vocabulário: nome retido; a relação aria-labelledby continua resolvida (por id de nó)
        assertEquals("", s.at("/inspection/classification/name/name").asText());
        assertTrue(s.at("/inspection/family/relations").toString().contains("LABELLED_BY"), s.at("/inspection/family/relations").toString());
    }

    @Test
    @DisplayName("T033 — Dados sensíveis de teste: ausência na exportação")
    void sensitiveDataNeverExported() throws Exception {
        Page p = open(PAGE);
        select(p, "#senha");
        JsonNode export = lab.call("pagemap.export", p, "fresh", true);
        String json = export.get("pageMap").toString();
        String file = Files.readString(Path.of(export.get("path").asText()));
        String[] secrets = {"S3nh@Secreta!", "4111111111111111", "Apelido Privado", "tok-csrf-998877"};
        for (String secret : secrets) {
            assertFalse(json.contains(secret), "PageMap retornado contém dado sensível: " + secret);
            assertFalse(file.contains(secret), "PageMap persistido contém dado sensível: " + secret);
        }
        // Nenhum artefato gravado pelo host (PageMaps, alvos, logs) pode conter os segredos.
        try (Stream<Path> files = Files.walk(lab.hostData())) {
            for (Path f : files.filter(Files::isRegularFile).filter(f -> !f.toString().endsWith(".lck")).toList()) {
                String content = Files.readString(f);
                for (String secret : secrets) {
                    assertFalse(content.contains(secret), f + " contém " + secret);
                }
            }
        }
        JsonNode senha = null;
        for (JsonNode e : export.at("/pageMap/elements")) {
            if ("senha".equals(e.at("/attributes/id").asText())) {
                senha = e;
            }
        }
        assertNotNull(senha);
        assertTrue(senha.get("sensitive").asBoolean());
        assertFalse(senha.get("attributes").has("value"));
    }
}
