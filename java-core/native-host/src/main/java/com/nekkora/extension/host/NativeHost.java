package com.nekkora.extension.host;

import com.nekkora.extension.host.commands.CaptureCommands;
import com.nekkora.extension.host.commands.CommandRouter;
import com.nekkora.extension.host.commands.EngineServices;
import com.nekkora.extension.host.commands.ExportCommands;
import com.nekkora.extension.host.commands.HostCommands;
import com.nekkora.extension.host.commands.InspectionCommands;
import com.nekkora.extension.host.commands.TargetCommands;
import com.nekkora.extension.host.json.JsonCodec;
import com.nekkora.extension.host.persistence.DataDirectory;
import com.nekkora.extension.host.persistence.JsonFileStore;
import com.nekkora.extension.host.protocol.MessageProcessor;
import com.nekkora.extension.host.session.HostSession;

/** Composição do host: monta sessão, serviços e roteador (raiz de injeção de dependências). */
public final class NativeHost {

    private final MessageProcessor processor;
    private final HostSession session;

    public NativeHost(DataDirectory dataDir) {
        JsonCodec json = new JsonCodec();
        JsonFileStore store = dataDir == null ? null : new JsonFileStore(dataDir, json);
        this.session = new HostSession(store);
        EngineServices services = new EngineServices(session, json);
        CommandRouter router = new CommandRouter()
                .install(new HostCommands(session, dataDir == null ? null : dataDir.root().toString()))
                .install(new CaptureCommands(services))
                .install(new InspectionCommands(services))
                .install(new TargetCommands(services))
                .install(new ExportCommands(services))
                .install(new com.nekkora.extension.host.commands.FrameCommands(services))
                .install(new com.nekkora.extension.host.commands.RecorderCommands(services))
                .install(new com.nekkora.extension.host.commands.DebugCommands(services))
                .install(new com.nekkora.extension.host.commands.SessionCommands(services,
                        new com.nekkora.extension.host.capture.ExportService(session, dataDir, json, services::diagnosticsInput)));
        this.processor = new MessageProcessor(router, json);
    }

    public MessageProcessor processor() {
        return processor;
    }

    public HostSession session() {
        return session;
    }
}
