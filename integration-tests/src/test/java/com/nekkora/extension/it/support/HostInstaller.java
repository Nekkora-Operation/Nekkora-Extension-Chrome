package com.nekkora.extension.it.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Executa o script oficial de instalação do host (o mesmo documentado no README). */
final class HostInstaller {

    private HostInstaller() {
    }

    static void install(Path repo) throws IOException, InterruptedException {
        if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
            throw new IllegalStateException("Instalação automatizada do host validada somente no Windows; veja docs/limitations.md");
        }
        Path jar = repo.resolve("java-core/native-host/target/nekkora-native-host.jar");
        if (!Files.exists(jar)) {
            throw new IllegalStateException("Host não compilado: " + jar + " (execute mvnw package)");
        }
        Process p = new ProcessBuilder(List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                repo.resolve("native-host/scripts/install-host.ps1").toString(), "-SkipBuild"))
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        if (code != 0) {
            throw new IllegalStateException("install-host.ps1 falhou (" + code + "):\n" + out);
        }
    }
}
