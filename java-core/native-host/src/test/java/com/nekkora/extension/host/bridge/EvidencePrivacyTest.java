package com.nekkora.extension.host.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** STUDIO-005 (S005-047/048/049): terceira barreira das evidências — o host reaplica a política antes de sair para o Studio. */
class EvidencePrivacyTest {

    private final ObjectMapper m = new ObjectMapper();

    private ObjectNode evidence() throws Exception {
        Path f = Path.of("../../contracts/studio-bridge/fixtures/valid/s005-event-evidence-error.json");
        return (ObjectNode) m.readTree(Files.readString(f)).get("payload");
    }

    private boolean inContract(ObjectNode p) {
        return BridgeContract.valid(m.createObjectNode().put("protocol", "nekkora-bridge/1").put("kind", "event")
                .put("type", "studio.recording.evidence").put("seq", 1).set("payload", p));
    }

    @Test
    void mensagemComDadoPessoalTokenOuSenhaESuprimidaNoHost() throws Exception {
        for (String unsafe : List.of(
                "CPF 123.456.789-09 já cadastrado",
                "Enviamos o código para ana.souza@exemplo.com",
                "Sessão eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U expirada",
                "Authorization: Bearer abcdef1234567890ghij",
                "Conta 98765432 bloqueada",
                "Senha Senha2026Teste incorreta para token=a1b2c3d4e5f6a7b8c9d0e1f2",
                // revisão do PR #6: valor que a página repetiu depois de limpar o campo e dado de terceiro
                "O código Kq7Segredo expirou.", "Olá, JoanaPrado!", "Cliente Maria Souza já possui cadastro.")) {
            ObjectNode p = evidence();
            ((ObjectNode) p.at("/details/message")).put("text", unsafe);
            EvidencePrivacy.reapply(p);
            assertTrue(p.at("/details/message/text").isNull(), "suprimida: " + unsafe);
            assertEquals("SUPPRESSED", p.at("/details/message/availability").asText());
            assertTrue(p.get("limitations").toString().contains("MESSAGE_SUPPRESSED"));
            assertTrue(inContract(p), "continua no contrato");
        }
    }

    @Test
    void tituloRegiaoCampoEUrlsSaoReaplicados() throws Exception {
        ObjectNode p = evidence();
        ((ObjectNode) p.get("document")).put("title", "Pedido de ana.souza@exemplo.com");
        ((ObjectNode) p.get("document")).put("url", "http://127.0.0.1:18765/lab/studio005/login.html?token=abc123def456ghi789jkl&cpf=123.456.789-09");
        ((ObjectNode) p.get("details")).put("region", "alerta de 123.456.789-09");
        ((ObjectNode) p.get("details")).put("field", "E-mail ana.souza@exemplo.com");
        ((ObjectNode) p.get("details")).set("navigation", m.createObjectNode().put("kind", "SAME_DOCUMENT")
                .put("url", "http://127.0.0.1:18765/lab/studio005/spa.html?session=abc123def456ghi789jkl#/x"));
        EvidencePrivacy.reapply(p);
        String json = p.toString();
        for (String secret : List.of("ana.souza@exemplo.com", "123.456.789-09", "abc123def456ghi789jkl")) {
            assertFalse(json.contains(secret), secret + " em " + json);
        }
        assertTrue(inContract(p));
    }

    @Test
    void modoEstritoNenhumTextoAtravessaOHostNemMensagemComumNemDadoDeTerceiroEmMinusculas() throws Exception {
        for (String any : List.of("Usuário ou senha inválidos.", "Pedido registrado com sucesso.", "3 resultados encontrados.", "Senha incorreta.",
                "o cliente joão já existe")) {
            ObjectNode p = evidence();
            ((ObjectNode) p.at("/details/message")).put("text", any);
            EvidencePrivacy.reapply(p);
            assertTrue(p.at("/details/message/text").isNull(), any);
            assertEquals("SUPPRESSED", p.at("/details/message/availability").asText());
            assertTrue(p.get("limitations").toString().contains("MESSAGE_SUPPRESSED"));
            assertTrue(inContract(p), "continua no contrato");
        }
    }

    @Test
    void reaplicarEIdempotenteEMensagemSemTextoContinuaSemTexto() throws Exception {
        ObjectNode p = evidence();
        EvidencePrivacy.reapply(p);
        String once = p.toString();
        EvidencePrivacy.reapply(p);
        assertEquals(m.readTree(once), p, "idempotente");
        ObjectNode none = evidence();
        ((ObjectNode) none.at("/details/message")).put("availability", "NONE").putNull("text");
        EvidencePrivacy.reapply(none);
        assertEquals("NONE", none.at("/details/message/availability").asText(), "sem texto continua NONE");
        assertTrue(!none.get("limitations").toString().contains("MESSAGE_SUPPRESSED"));
    }
}
