package com.nekkora.extension.core.pagemap;

import com.nekkora.extension.core.analysis.PageAnalysis;
import com.nekkora.extension.core.coverage.CoverageAnalyzer;
import com.nekkora.extension.core.identity.TargetIdentity;
import com.nekkora.extension.core.mutation.MutationRecord;
import com.nekkora.extension.core.navigation.UrlContextParser;
import com.nekkora.extension.core.options.OptionHistory;
import com.nekkora.extension.core.options.OptionSetAnalyzer;
import com.nekkora.extension.core.resolve.ResolutionResult;
import com.nekkora.extension.core.semantic.Classification;
import com.nekkora.extension.core.semantic.ElementCategory;
import com.nekkora.extension.core.semantic.ElementKind;
import com.nekkora.extension.core.snapshot.DocumentInfo;
import com.nekkora.extension.core.snapshot.RawNode;
import com.nekkora.extension.core.text.TextNormalizer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Monta o {@link PageMap} a partir de uma análise. */
public final class PageMapAssembler {

    private static final Set<ElementKind> DESCENDANT_TEXT_KINDS = Set.of(
            ElementKind.BUTTON, ElementKind.LINK, ElementKind.OPTION, ElementKind.MENUITEM, ElementKind.TAB,
            ElementKind.HEADING, ElementKind.TABLE_CELL, ElementKind.TABLE_HEADER_CELL, ElementKind.LABEL,
            ElementKind.LEGEND, ElementKind.CAPTION, ElementKind.PARAGRAPH, ElementKind.ALERT, ElementKind.STATUS,
            ElementKind.VALIDATION_MESSAGE, ElementKind.DESCRIPTION, ElementKind.INSTRUCTION, ElementKind.CUSTOM_DROPDOWN,
            ElementKind.COMBOBOX, ElementKind.CHECKBOX, ElementKind.RADIO, ElementKind.SWITCH, ElementKind.LIST_ITEM,
            ElementKind.CONTENTEDITABLE);

    private final CoverageAnalyzer coverage = new CoverageAnalyzer();
    private final OptionSetAnalyzer options = new OptionSetAnalyzer();
    private final UrlContextParser urls = new UrlContextParser();

    public PageMap assemble(PageAnalysis a, PageMapOptions opts, OptionHistory history, List<TargetIdentity> targets,
                            List<ResolutionResult> resolutions, List<MutationRecord> mutations,
                            List<InspectionEvidence> evidence, String generatedAt, String mode) {
        long t0 = System.nanoTime();
        List<ElementDescriptor> elements = new ArrayList<>();
        long locatorNanos = 0;
        long familyNanos = 0;
        int locatorCount = 0;
        for (RawNode n : a.model().inDocumentOrder()) {
            if (elements.size() >= opts.maxElements()) {
                break;
            }
            Classification c = a.semantics().get(n.id());
            if (!opts.includeContainers() && c.category() == ElementCategory.CONTAINER && n.ownText().isBlank()) {
                continue;
            }
            long f0 = System.nanoTime();
            var family = opts.familyFor().contains(c.category()) ? a.graph().family(n.id()) : null;
            long f1 = System.nanoTime();
            var locators = opts.locatorsFor().contains(c.category()) ? a.locators().generate(n.id()) : null;
            long f2 = System.nanoTime();
            familyNanos += f1 - f0;
            locatorNanos += f2 - f1;
            if (locators != null) {
                locatorCount++;
            }
            String descendant = DESCENDANT_TEXT_KINDS.contains(c.kind())
                    ? TextNormalizer.truncate(a.model().visibleText(n), 160) : null;
            ElementDescriptor.ElementText text = new ElementDescriptor.ElementText(
                    TextNormalizer.truncate(n.ownText(), 160), emptyToNull(descendant), c.name().name(),
                    c.name().source().name(), emptyToNull(c.name().description()),
                    family == null ? List.of() : family.contextualTexts());
            elements.add(new ElementDescriptor(n.id(), n.tag(), n.scopeId(), n.frameId(), n.parentId(), c.kind().name(),
                    c.category().name(), c.role(), c.nativeSemantics(), c.confidence().name(), text,
                    exportableAttributes(n, c), c.conditions(), c.sensitive(), n.rect(), family, locators, c.evidence(),
                    c.uncertainties()));
        }
        long t1 = System.nanoTime();
        var optionSets = options.analyzeAll(a.graph(), history);
        long t2 = System.nanoTime();
        DocumentInfo doc = a.model().snapshot().document();
        PageMap.DocumentContext dc = new PageMap.DocumentContext(doc == null ? null : doc.url(), doc == null ? null : doc.title(),
                doc == null ? null : doc.readyState(), doc == null ? null : urls.parse(doc.url(), doc.title()),
                a.model().snapshot().frames(), a.model().snapshot().shadowRoots(), a.model().snapshot().requests());
        PageMap.PageSnapshot ps = new PageMap.PageSnapshot(a.model().snapshot().captureId(), a.model().snapshot().capturedAt(),
                doc == null ? null : doc.pageSessionId(), mode, a.model().snapshot().stats());
        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("captureMs", a.model().snapshot().stats() == null ? -1 : a.model().snapshot().stats().durationMs());
        metrics.put("indexMs", a.indexMs());
        metrics.put("classificationMs", a.classificationMs());
        metrics.put("familyMs", round(familyNanos / 1e6));
        metrics.put("locatorMs", round(locatorNanos / 1e6));
        metrics.put("locatorTargets", (double) locatorCount);
        metrics.put("assemblyMs", round((t1 - t0) / 1e6));
        metrics.put("optionSetsMs", round((t2 - t1) / 1e6));
        metrics.put("nodesProcessed", (double) a.model().size());
        metrics.put("elementsExported", (double) elements.size());
        return new PageMap(PageMap.SCHEMA, PageMap.VERSION, generatedAt,
                Map.of("name", "nekkora-extension-core", "version", PageMap.VERSION, "language", "Java 21"),
                ps, dc, coverage.analyze(a.model(), a.semantics()), elements, a.graph().declaredRelations(), optionSets,
                targets, resolutions, mutations, evidence, metrics);
    }

    /** Atributos exportáveis: sem valores de campos, sem conteúdo de manipuladores, valores longos truncados. */
    static Map<String, String> exportableAttributes(RawNode n, Classification c) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : n.attributes().entrySet()) {
            String k = e.getKey().toLowerCase(Locale.ROOT);
            String v = e.getValue();
            if (k.startsWith("on")) {
                out.put(e.getKey(), "[manipulador declarado]");
                continue;
            }
            if (k.equals("value") && (c.sensitive() || c.kind().isTextEntry() || c.kind() == ElementKind.HIDDEN_INPUT)) {
                continue; // valores de campos nunca são exportados
            }
            if (k.equals("style")) {
                continue;
            }
            out.put(e.getKey(), v != null && v.length() > 200 ? v.substring(0, 200) + "…" : v);
        }
        return out;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
