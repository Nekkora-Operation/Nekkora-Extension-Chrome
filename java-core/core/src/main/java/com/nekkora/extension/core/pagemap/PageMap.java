package com.nekkora.extension.core.pagemap;

import com.nekkora.extension.core.context.ContextRelation;
import com.nekkora.extension.core.coverage.CoverageReport;
import com.nekkora.extension.core.identity.TargetIdentity;
import com.nekkora.extension.core.mutation.MutationRecord;
import com.nekkora.extension.core.navigation.NavigationContext;
import com.nekkora.extension.core.options.OptionSet;
import com.nekkora.extension.core.resolve.ResolutionResult;
import com.nekkora.extension.core.snapshot.CaptureStats;
import com.nekkora.extension.core.snapshot.FrameInfo;
import com.nekkora.extension.core.snapshot.ObservedRequest;
import com.nekkora.extension.core.snapshot.ShadowRootInfo;

import java.util.List;
import java.util.Map;

/**
 * Nekkora PageMap — representação estruturada e independente do mecanismo de execução.
 *
 * @param schema      sempre {@code nekkora.pagemap}
 * @param version     versão do contrato ({@value #VERSION})
 * @param generatedAt instante de geração
 * @param generator   gerador
 * @param snapshot    resumo do snapshot (PageSnapshot)
 * @param document    contexto do documento (DocumentContext)
 * @param coverage    cobertura (CoverageReport)
 * @param elements    elementos (ElementDescriptor)
 * @param relations   relações declaradas (ContextRelation)
 * @param optionSets  controles de escolha e opções observadas
 * @param targets     identidades lógicas registradas (TargetIdentity)
 * @param resolutions resoluções realizadas (ResolutionResult)
 * @param mutations   alterações observadas (MutationRecord)
 * @param evidence    evidências (InspectionEvidence)
 * @param metrics     medições de tempo/volume
 */
public record PageMap(
        String schema,
        String version,
        String generatedAt,
        Map<String, String> generator,
        PageSnapshot snapshot,
        DocumentContext document,
        CoverageReport coverage,
        List<ElementDescriptor> elements,
        List<ContextRelation> relations,
        List<OptionSet> optionSets,
        List<TargetIdentity> targets,
        List<ResolutionResult> resolutions,
        List<MutationRecord> mutations,
        List<InspectionEvidence> evidence,
        Map<String, Double> metrics,
        com.nekkora.extension.core.frames.FrameGraph frameGraph,
        List<com.nekkora.extension.core.frames.ElementAccessPath> accessPaths,
        com.nekkora.extension.core.protection.ProtectionReport protection,
        com.nekkora.extension.core.diagnostics.AutomationReadinessAssessment readiness,
        String privacy) {

    public static final String SCHEMA = "nekkora.pagemap";
    /**
     * 0.2.0 (CHROME-003): frameGraph, accessPaths, protection, readiness; documentId nos elementos. 0.3.0
     * (CHROME-SECURITY-001): conteúdo sob {@code nekkora-privacy/2}, declarado em {@code privacy}; texto retido marcado.
     */
    public static final String VERSION = "0.3.0";

    /** Construtor CHROME-003 (a política é sempre a vigente: nekkora-privacy/2). */
    public PageMap(String schema, String version, String generatedAt, Map<String, String> generator, PageSnapshot snapshot,
                   DocumentContext document, CoverageReport coverage, List<ElementDescriptor> elements, List<ContextRelation> relations,
                   List<OptionSet> optionSets, List<TargetIdentity> targets, List<ResolutionResult> resolutions,
                   List<MutationRecord> mutations, List<InspectionEvidence> evidence, Map<String, Double> metrics,
                   com.nekkora.extension.core.frames.FrameGraph frameGraph,
                   List<com.nekkora.extension.core.frames.ElementAccessPath> accessPaths,
                   com.nekkora.extension.core.protection.ProtectionReport protection,
                   com.nekkora.extension.core.diagnostics.AutomationReadinessAssessment readiness) {
        this(schema, version, generatedAt, generator, snapshot, document, coverage, elements, relations, optionSets, targets, resolutions,
                mutations, evidence, metrics, frameGraph, accessPaths, protection, readiness,
                com.nekkora.extension.core.privacy.CapturePrivacyPolicy.VERSION);
    }

    /**
     * Estado da página no momento da observação.
     *
     * @param captureId     captura
     * @param capturedAt    instante
     * @param pageSessionId sessão de página
     * @param mode          {@code FULL} ou {@code INCREMENTAL}
     * @param stats         estatísticas da captura
     */
    public record PageSnapshot(String captureId, String capturedAt, String pageSessionId, String mode, CaptureStats stats) {
    }

    /**
     * Identificação do documento, URL e navegação.
     *
     * @param url              URL
     * @param title            título
     * @param readyState       readyState
     * @param navigation       contexto de navegação (observado × inferido)
     * @param frames           frames
     * @param shadowRoots      shadow roots
     * @param observedRequests requisições observadas (Resource Timing)
     */
    public record DocumentContext(String url, String title, String readyState, NavigationContext navigation,
                                  List<FrameInfo> frames, List<ShadowRootInfo> shadowRoots,
                                  List<ObservedRequest> observedRequests) {
    }
}
