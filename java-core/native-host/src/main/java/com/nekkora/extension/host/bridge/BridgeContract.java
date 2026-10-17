package com.nekkora.extension.host.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validação de TODA mensagem que cruza a fronteira do Bridge contra {@code contracts/studio-bridge.schema.json}
 * (empacotado no JAR): comandos vindos do Studio (entrada não confiável) e respostas/eventos que o host repassa
 * (nada fora do contrato — URL completa, PageMap, segredo — sai pelo canal de controle).
 */
public final class BridgeContract {

    private static final JsonSchema SCHEMA = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(SchemaLocation.of("classpath:contracts/studio-bridge.schema.json"));

    /** Comandos que o host atende sozinho. */
    public static final Set<String> LOCAL_COMMANDS = Set.of("bridge.status", "bridge.heartbeat");
    /** Comandos repassados à extensão (lista EXPLÍCITA: não há repasse genérico). */
    public static final Set<String> FORWARDED_COMMANDS = Set.of("browser.tabs.list", "studio.recording.start", "studio.recording.pause",
            "studio.recording.resume", "studio.recording.mode", "studio.recording.finish", "studio.recording.cancel",
            "studio.recording.status", "studio.recording.renew",
            // STUDIO-003: entrega confiável das etapas gravadas e rota de sessão lógica
            "studio.recording.steps.ack", "studio.recording.steps.sync", "studio.recording.route",
            // STUDIO-005: entrega confiável das evidências observadas
            "studio.recording.evidence.ack", "studio.recording.evidence.sync",
            // STUDIO-009 (guided-debug/1): inspeção, destaque e seleção — nunca ações na página
            "studio.debug.start", "studio.debug.inspect", "studio.debug.highlight", "studio.debug.pick", "studio.debug.finish",
            "studio.debug.status", "studio.debug.renew", "studio.debug.probe");
    /** Eventos da extensão que podem ser repassados ao Studio. */
    public static final Set<String> FORWARDED_EVENTS = Set.of("studio.recording.started", "studio.recording.paused", "studio.recording.resumed",
            "studio.recording.modeChanged", "studio.recording.finished", "studio.recording.cancelled", "studio.recording.degraded",
            "studio.recording.recovered", "studio.recording.failed", "studio.recording.expired",
            // STUDIO-003: etapa gravada (validada contra o schema estrito antes de sair do host)
            "studio.recording.step",
            // STUDIO-005: evidência observada (privacidade reaplicada e schema estrito antes de sair do host)
            "studio.recording.evidence",
            // STUDIO-009: elemento escolhido no navegador e mudança de estado da sessão de debug
            "studio.debug.picked", "studio.debug.changed");

    private BridgeContract() {
    }

    /** Problemas de validação (vazio = mensagem válida). */
    public static Set<String> problems(JsonNode message) {
        return SCHEMA.validate(message).stream().map(ValidationMessage::getMessage).collect(Collectors.toCollection(java.util.TreeSet::new));
    }

    /**
     * Problemas de validação SEM valores (CHROME-SECURITY-001): só o caminho e a regra violada — a mensagem do validador
     * pode citar o conteúdo recusado. É o que vai para o log.
     */
    public static Set<String> problemSummary(JsonNode message) {
        return SCHEMA.validate(message).stream().map(m -> m.getInstanceLocation() + " " + m.getType())
                .collect(Collectors.toCollection(java.util.TreeSet::new));
    }

    public static boolean valid(JsonNode message) {
        return SCHEMA.validate(message).isEmpty();
    }
}
