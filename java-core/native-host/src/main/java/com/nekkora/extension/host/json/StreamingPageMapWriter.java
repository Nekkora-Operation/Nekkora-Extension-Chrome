package com.nekkora.extension.host.json;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nekkora.extension.core.analysis.PageAnalysis;
import com.nekkora.extension.core.identity.TargetIdentity;
import com.nekkora.extension.core.mutation.MutationRecord;
import com.nekkora.extension.core.options.OptionHistory;
import com.nekkora.extension.core.pagemap.InspectionEvidence;
import com.nekkora.extension.core.pagemap.PageMap;
import com.nekkora.extension.core.pagemap.PageMapAssembler;
import com.nekkora.extension.core.pagemap.PageMapOptions;
import com.nekkora.extension.core.resolve.ResolutionResult;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Escreve o PageMap 0.1.0 em fluxo: o envelope e cada elemento são serializados à medida que são gerados
 * (sem lista completa de elementos nem string JSON inteira em memória). O arquivo resultante é um PageMap
 * completo e idêntico em estrutura ao produzido por {@code PageMapAssembler.assemble}.
 */
public final class StreamingPageMapWriter {

    /** Resultado da escrita. */
    public record Outcome(int elements, List<String> validationErrors, int validatedElements, String captureId,
                          boolean truncated, List<String> coverageLimitations) {
    }

    private final ObjectMapper mapper;
    private final PageMapAssembler assembler = new PageMapAssembler();
    private final PageMapSchema schema;

    public StreamingPageMapWriter(ObjectMapper mapper, PageMapSchema schema) {
        this.mapper = mapper;
        this.schema = schema;
    }

    public Outcome write(OutputStream out, PageAnalysis a, PageMapOptions opts, OptionHistory history,
                         List<TargetIdentity> targets, List<ResolutionResult> resolutions, List<MutationRecord> mutations,
                         List<InspectionEvidence> evidence, String generatedAt, String mode, int parallelism) throws IOException {
        List<String> errors = new ArrayList<>();
        int[] validated = {0};
        PageMapAssembler.Prefix p = assembler.prefix(a, generatedAt, mode);
        try (JsonGenerator g = mapper.getFactory().createGenerator(out, JsonEncoding.UTF8)) {
            g.writeStartObject();
            g.writeStringField("schema", p.schema());
            g.writeStringField("version", p.version());
            g.writeStringField("generatedAt", p.generatedAt());
            g.writeObjectField("generator", p.generator());
            g.writeObjectField("snapshot", p.snapshot());
            g.writeObjectField("document", p.document());
            g.writeObjectField("coverage", p.coverage());
            g.writeArrayFieldStart("elements");
            PageMapAssembler.ElementStats st;
            try {
                st = assembler.elements(a, opts, e -> {
                    if (schema != null && errors.size() < 20) {
                        List<String> v = schema.validateElement(mapper.valueToTree(e));
                        validated[0]++;
                        v.forEach(m -> errors.add("elements[" + e.nodeId() + "]: " + m));
                    }
                    g.writeObject(e);
                }, parallelism);
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException("Falha ao gerar elementos: " + e.getMessage(), e);
            }
            g.writeEndArray();
            PageMapAssembler.Suffix s = assembler.suffix(a, history, targets, resolutions, mutations, evidence, st);
            g.writeObjectField("relations", s.relations());
            g.writeObjectField("optionSets", s.optionSets());
            g.writeObjectField("targets", s.targets());
            g.writeObjectField("resolutions", s.resolutions());
            g.writeObjectField("mutations", s.mutations());
            g.writeObjectField("evidence", s.evidence());
            g.writeObjectField("metrics", s.metrics());
            g.writeEndObject();
            if (schema != null) {
                PageMap envelope = new PageMap(p.schema(), p.version(), p.generatedAt(), p.generator(), p.snapshot(),
                        p.document(), p.coverage(), List.of(), s.relations(), s.optionSets(), s.targets(), s.resolutions(),
                        s.mutations(), s.evidence(), s.metrics());
                ObjectNode tree = mapper.valueToTree(envelope);
                errors.addAll(schema.validateEnvelope(tree));
            }
            boolean truncated = p.snapshot().stats() != null && p.snapshot().stats().truncated();
            return new Outcome(st.exported(), errors, validated[0], p.snapshot().captureId(), truncated, p.coverage().limitations());
        }
    }
}
