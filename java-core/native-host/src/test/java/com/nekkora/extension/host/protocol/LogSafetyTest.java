package com.nekkora.extension.host.protocol;

import com.nekkora.extension.host.NativeHost;
import com.nekkora.extension.host.persistence.DataDirectory;
import com.nekkora.extension.host.runtime.LogSafety;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CHROME-SECURITY-001 — SEC001-057/058: o log do Native Host e as respostas de erro técnico não levam conteúdo recebido
 * (texto da página, valores). Fica o tipo, o código, a posição e a pilha.
 */
class LogSafetyTest {

    private static final String SECRET = "Bartolomeu Quixadá";
    private final List<String> logged = new CopyOnWriteArrayList<>();
    private final Logger root = Logger.getLogger("");
    private Handler capture;
    @TempDir
    Path dataDir;

    @BeforeEach
    void captureLogs() {
        SimpleFormatter f = new SimpleFormatter();
        capture = new Handler() {
            @Override
            public void publish(LogRecord r) {
                logged.add(f.format(r)); // como o FileHandler do host grava: mensagem + exceção + pilha
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        capture.setLevel(Level.ALL);
        root.addHandler(capture);
    }

    @AfterEach
    void release() {
        root.removeHandler(capture);
    }

    private void assertNothingLeaked(String where, String content) {
        assertFalse(content.contains("Quixad"), where + " contém o texto recebido: " + content);
    }

    @Test
    @DisplayName("SEC001-057 — exceção redigida: mesma pilha e tipo, nenhuma mensagem (nem das causas)")
    void redactedThrowable() {
        Exception e = new IllegalArgumentException("valor inválido: " + SECRET, new RuntimeException("causa com " + SECRET));
        Throwable r = LogSafety.redacted(e);
        java.io.StringWriter sw = new java.io.StringWriter();
        r.printStackTrace(new java.io.PrintWriter(sw));
        assertNothingLeaked("pilha redigida", sw.toString());
        assertTrue(sw.toString().contains("IllegalArgumentException"), "o tipo original continua: " + sw);
        assertTrue(r.getStackTrace().length > 0 && r.getStackTrace()[0].equals(e.getStackTrace()[0]), "mesma pilha");
    }

    @Test
    @DisplayName("SEC001-057/058 — JSON malformado e payload incompatível: resposta e log sem o trecho recebido")
    void malformedAndMismatchedPayloads() throws Exception {
        NativeHost host = new NativeHost(new DataDirectory(dataDir));
        String malformed = "{\"protocol\":\"nekkora-nm/1\",\"id\":\"r1\",\"type\":\"snapshot.submit\",\"payload\":{\"x\": " + SECRET + " }}";
        String mismatched = "{\"protocol\":\"nekkora-nm/1\",\"id\":\"r2\",\"type\":\"snapshot.submit\",\"payload\":{\"tabKey\":\"tab-1\","
                + "\"snapshot\":{\"captureId\":\"cap-1\",\"nodes\":[{\"id\":\"n1\",\"tag\":\"p\",\"rect\":\"" + SECRET + "\"}]}}}";
        String unknownField = "{\"protocol\":\"nekkora-nm/1\",\"id\":\"r3\",\"type\":\"element.inspect\",\"payload\":{\"tabKey\":\"tab-1\","
                + "\"captureId\":\"" + SECRET + "\",\"nodeId\":\"" + SECRET + "\"}}";
        for (String raw : List.of(malformed, mismatched, unknownField)) {
            String resp = String.join("", host.processor().process(raw));
            assertTrue(resp.contains("\"ok\":false"), resp);
            assertNothingLeaked("resposta de erro", resp);
        }
        assertNothingLeaked("log do host", String.join("\n", logged));
    }
}
