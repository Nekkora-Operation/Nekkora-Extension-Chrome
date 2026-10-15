package com.nekkora.extension.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekkora.extension.host.bridge.BridgeDiscovery;
import com.nekkora.extension.host.bridge.StudioLink;
import com.nekkora.extension.host.persistence.DataDirectory;
import com.nekkora.extension.host.protocol.NativeMessageCodec;
import com.nekkora.extension.host.runtime.HostRuntime;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Ponto de entrada iniciado pelo Chrome. O primeiro argumento é a origem da extensão chamadora
 * ({@code chrome-extension://<id>/}).
 *
 * <p>Regra crítica: {@code stdout} pertence exclusivamente ao protocolo. Antes de qualquer outra
 * coisa o descritor real é capturado e {@code System.out} é redirecionado para {@code stderr}, de modo
 * que nenhuma escrita acidental corrompa o fluxo. Logs vão para arquivo.
 */
public final class NativeHostMain {

    private static final Logger LOG = Logger.getLogger(NativeHostMain.class.getName());

    private NativeHostMain() {
    }

    public static void main(String[] args) throws IOException {
        FileOutputStream protocolOut = new FileOutputStream(FileDescriptor.out);
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.err), true));

        DataDirectory dataDir = DataDirectory.fromEnvironment();
        configureLogging(dataDir);
        String origin = args.length > 0 ? args[0] : "(sem origem)";
        LOG.info("Host iniciado. pid=" + ProcessHandle.current().pid() + " origem=" + origin + " java=" + System.getProperty("java.version"));

        NativeHost host = new NativeHost(dataDir);
        NativeMessageCodec codec = new NativeMessageCodec(new BufferedInputStream(System.in, 1 << 16),
                new BufferedOutputStream(protocolOut, 1 << 16));
        // Leitor, motor, controle e escritor separados (STUDIO-002): o canal do Studio nunca bloqueia o motor nem o
        // contrário, e cada resposta fragmentada sai inteira antes de qualquer outro envelope.
        ObjectMapper mapper = new ObjectMapper();
        HostRuntime runtime = new HostRuntime(codec, host.processor(), mapper);
        Path bridgeDir = BridgeDiscovery.defaultDirectory();
        runtime.attachStudio(new StudioLink(mapper, host.processor(), runtime::sendToExtension, () -> runtime.engineBusy(1000),
                new BridgeDiscovery(bridgeDir, mapper), 1000));
        runtime.run();
        LOG.info("Host encerrado.");
    }

    private static void configureLogging(DataDirectory dataDir) {
        LogManager.getLogManager().reset();
        Logger root = Logger.getLogger("");
        root.setLevel(Level.INFO);
        try {
            Path logs = dataDir.dir("logs");
            Handler file = new FileHandler(logs.resolve("native-host-%g.log").toString(), 2_000_000, 3, true);
            file.setFormatter(new SimpleFormatter());
            file.setEncoding("UTF-8");
            root.addHandler(file);
        } catch (IOException e) {
            // Sem arquivo de log: registra apenas no stderr (nunca no stdout).
            System.err.println("[nekkora-host] log em arquivo indisponível: " + e.getMessage());
        }
    }
}
