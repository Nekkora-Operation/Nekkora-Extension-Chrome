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
import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import com.nekkora.extension.core.locator.LocatorEngine;

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

    /** Campos que precedem {@code elements} no contrato. */
    public record Prefix(String schema, String version, String generatedAt, Map<String, String> generator,
                         PageMap.PageSnapshot snapshot, PageMap.DocumentContext document,
                         com.nekkora.extension.core.coverage.CoverageReport coverage) {
    }

    /** Campos que sucedem {@code elements} no contrato. */
    public record Suffix(List<com.nekkora.extension.core.context.ContextRelation> relations,
                         List<com.nekkora.extension.core.options.OptionSet> optionSets, List<TargetIdentity> targets,
                         List<ResolutionResult> resolutions, List<MutationRecord> mutations,
                         List<InspectionEvidence> evidence, Map<String, Double> metrics) {
    }

    /** Medições da geração dos elementos. */
    public record ElementStats(int exported, int locatorTargets, double familyMs, double locatorMs, double wallMs,
                               int parallelism) {
    }

    /** Recebe elementos em ordem de documento. */
    @FunctionalInterface
    public interface ElementSink {
        void accept(ElementDescriptor element) throws Exception;
    }

    public PageMap assemble(PageAnalysis a, PageMapOptions opts, OptionHistory history, List<TargetIdentity> targets,
                            List<ResolutionResult> resolutions, List<MutationRecord> mutations,
                            List<InspectionEvidence> evidence, String generatedAt, String mode) {
        Prefix p = prefix(a, generatedAt, mode);
        List<ElementDescriptor> elements = new ArrayList<>();
        ElementStats st;
        try {
            st = elements(a, opts, elements::add, 1);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        Suffix sf = suffix(a, history, targets, resolutions, mutations, evidence, st);
        return new PageMap(p.schema(), p.version(), p.generatedAt(), p.generator(), p.snapshot(), p.document(), p.coverage(),
                elements, sf.relations(), sf.optionSets(), sf.targets(), sf.resolutions(), sf.mutations(), sf.evidence(), sf.metrics());
    }

    public Prefix prefix(PageAnalysis a, String generatedAt, String mode) {
        DocumentInfo doc = a.model().snapshot().document();
        PageMap.DocumentContext dc = new PageMap.DocumentContext(doc == null ? null : doc.url(), doc == null ? null : doc.title(),
                doc == null ? null : doc.readyState(), doc == null ? null : urls.parse(doc.url(), doc.title()),
                a.model().snapshot().frames(), a.model().snapshot().shadowRoots(), a.model().snapshot().requests());
        PageMap.PageSnapshot ps = new PageMap.PageSnapshot(a.model().snapshot().captureId(), a.model().snapshot().capturedAt(),
                doc == null ? null : doc.pageSessionId(), mode, a.model().snapshot().stats());
        return new Prefix(PageMap.SCHEMA, PageMap.VERSION, generatedAt,
                Map.of("name", "nekkora-extension-core", "version", PageMap.VERSION, "language", "Java 21"),
                ps, dc, coverage.analyze(a.model(), a.semantics()));
    }

    /**
     * Gera os elementos e os entrega ao {@code sink} na ORDEM DO DOCUMENTO. Com {@code parallelism > 1}, blocos
     * de nós são processados em paralelo (uma sessão de localizadores por thread) e entregues em ordem, com no
     * máximo 2 x parallelism blocos em memória: a lista completa nunca é materializada.
     */
    public ElementStats elements(PageAnalysis a, PageMapOptions opts, ElementSink sink, int parallelism) throws Exception {
        long t0 = System.nanoTime();
        List<RawNode> selected = new ArrayList<>();
        for (RawNode n : a.model().inDocumentOrder()) {
            if (selected.size() >= opts.maxElements()) {
                break;
            }
            Classification c = a.semantics().get(n.id());
            if (!opts.includeContainers() && c.category() == ElementCategory.CONTAINER && n.ownText().isBlank()) {
                continue;
            }
            selected.add(n);
        }
        AtomicLong familyNanos = new AtomicLong();
        AtomicLong locatorNanos = new AtomicLong();
        AtomicInteger locatorCount = new AtomicInteger();
        int threads = Math.max(1, parallelism);
        if (threads == 1 || selected.size() < 512) {
            for (RawNode n : selected) {
                sink.accept(describe(n, a, opts, a.locators(), familyNanos, locatorNanos, locatorCount));
            }
            return new ElementStats(selected.size(), locatorCount.get(), round(familyNanos.get() / 1e6),
                    round(locatorNanos.get() / 1e6), round((System.nanoTime() - t0) / 1e6), 1);
        }
        int blockSize = 256;
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread th = new Thread(r, "nekkora-pagemap");
            th.setDaemon(true);
            return th;
        });
        ThreadLocal<LocatorEngine.Session> sessions = ThreadLocal.withInitial(() -> a.locators().fork());
        ArrayDeque<Future<List<ElementDescriptor>>> window = new ArrayDeque<>();
        int next = 0;
        try {
            while (next < selected.size() || !window.isEmpty()) {
                while (next < selected.size() && window.size() < threads * 2) {
                    List<RawNode> block = selected.subList(next, Math.min(selected.size(), next + blockSize));
                    next += block.size();
                    window.add(pool.submit(() -> {
                        List<ElementDescriptor> out = new ArrayList<>(block.size());
                        for (RawNode n : block) {
                            out.add(describe(n, a, opts, sessions.get(), familyNanos, locatorNanos, locatorCount));
                        }
                        return out;
                    }));
                }
                for (ElementDescriptor d : window.removeFirst().get()) {
                    sink.accept(d);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return new ElementStats(selected.size(), locatorCount.get(), round(familyNanos.get() / 1e6),
                round(locatorNanos.get() / 1e6), round((System.nanoTime() - t0) / 1e6), threads);
    }

    private ElementDescriptor describe(RawNode n, PageAnalysis a, PageMapOptions opts, LocatorEngine.Session session,
                                       AtomicLong familyNanos, AtomicLong locatorNanos, AtomicInteger locatorCount) {
        Classification c = a.semantics().get(n.id());
        long f0 = System.nanoTime();
        var family = opts.familyFor().contains(c.category()) ? a.graph().family(n.id()) : null;
        long f1 = System.nanoTime();
        var locators = opts.locatorsFor().contains(c.category()) ? session.generate(n.id()) : null;
        long f2 = System.nanoTime();
        familyNanos.addAndGet(f1 - f0);
        locatorNanos.addAndGet(f2 - f1);
        if (locators != null) {
            locatorCount.incrementAndGet();
        }
        String descendant = DESCENDANT_TEXT_KINDS.contains(c.kind())
                ? TextNormalizer.truncate(a.model().visibleText(n), 160) : null;
        ElementDescriptor.ElementText text = new ElementDescriptor.ElementText(
                TextNormalizer.truncate(n.ownText(), 160), emptyToNull(descendant), c.name().name(),
                c.name().source().name(), emptyToNull(c.name().description()),
                family == null ? List.of() : family.contextualTexts());
        return new ElementDescriptor(n.id(), n.tag(), n.scopeId(), n.frameId(), n.parentId(), c.kind().name(),
                c.category().name(), c.role(), c.nativeSemantics(), c.confidence().name(), text,
                exportableAttributes(n, c), c.conditions(), c.sensitive(), n.rect(), family, locators, c.evidence(),
                c.uncertainties());
    }

    public Suffix suffix(PageAnalysis a, OptionHistory history, List<TargetIdentity> targets,
                         List<ResolutionResult> resolutions, List<MutationRecord> mutations,
                         List<InspectionEvidence> evidence, ElementStats st) {
        long t1 = System.nanoTime();
        var optionSets = options.analyzeAll(a.graph(), history);
        long t2 = System.nanoTime();
        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("captureMs", a.model().snapshot().stats() == null ? -1 : a.model().snapshot().stats().durationMs());
        metrics.put("indexMs", a.indexMs());
        metrics.put("classificationMs", a.classificationMs());
        metrics.put("familyMs", st.familyMs());
        metrics.put("locatorMs", st.locatorMs());
        metrics.put("locatorTargets", (double) st.locatorTargets());
        metrics.put("assemblyMs", st.wallMs());
        metrics.put("optionSetsMs", round((t2 - t1) / 1e6));
        metrics.put("nodesProcessed", (double) a.model().size());
        metrics.put("elementsExported", (double) st.exported());
        metrics.put("parallelism", (double) st.parallelism());
        return new Suffix(a.graph().declaredRelations(), optionSets, targets, resolutions, mutations, evidence, metrics);
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
