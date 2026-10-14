package com.nekkora.extension.core.frames;

import com.nekkora.extension.core.analysis.PageAnalysis;
import com.nekkora.extension.core.context.ElementFamily;
import com.nekkora.extension.core.locator.LocatorDescriptor;
import com.nekkora.extension.core.locator.LocatorSet;
import com.nekkora.extension.core.locator.StabilityLevel;
import com.nekkora.extension.core.semantic.Classification;
import com.nekkora.extension.core.snapshot.RawNode;
import com.nekkora.extension.core.snapshot.ShadowRootInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Constrói a {@link ElementAccessPath} de um nó: sobe pela árvore composta trocando de escopo a cada shadow root
 * e a cada documento de frame, e registra para cada passo o localizador no escopo pai, a identidade do frame, o
 * documento e a restrição de acesso. Nunca gera uma string CSS única que "atravesse" documentos.
 */
public final class AccessPathBuilder {

    private static final List<String> STABILITY_ORDER = List.of("OBSERVED_STABLE", "HEURISTIC_STABLE", "UNKNOWN",
            "HEURISTIC_UNSTABLE", "OBSERVED_UNSTABLE");

    public ElementAccessPath build(PageAnalysis a, FrameGraph graph, String nodeId) {
        RawNode node = a.model().node(nodeId);
        Classification cls = a.semantics().get(nodeId);
        List<ElementAccessPath.Step> reversed = new ArrayList<>();
        String worst = "OBSERVED_STABLE";
        String blocking = null;

        LocatorDescriptor el = primary(a, node);
        String elStability = el == null ? "UNKNOWN" : stabilityOf(el);
        worst = worse(worst, elStability);
        String elementLabel = label(cls, node);
        reversed.add(new ElementAccessPath.Step("ELEMENT", elementLabel, "doc:" + node.frameId(), null, null, node.id(),
                el == null ? null : (el.css() != null ? el.css() : el.semantic()), el == null ? null : el.strategy().name(),
                el == null ? 0 : el.matchCount(), elStability, null, null, "ELEMENT_PRESENT", null));

        RawNode current = node;
        int frames = 0;
        int shadows = 0;
        int guard = 0;
        while (guard++ < 128) {
            String scope = current.scopeId();
            if (scope.equals("doc:f0")) {
                break;
            }
            if (scope.startsWith("shadow:")) {
                RawNode host = a.model().find(scope.substring("shadow:".length())).orElse(null);
                if (host == null) {
                    blocking = blocking == null ? "SHADOW_HOST_UNKNOWN" : blocking;
                    break;
                }
                ShadowRootInfo info = a.model().snapshot().shadowRoots().stream()
                        .filter(s -> host.id().equals(s.hostNodeId())).findFirst().orElse(null);
                String restriction = info != null && !"open".equals(info.mode()) ? "SHADOW_ROOT_CLOSED" : null;
                LocatorDescriptor hl = primary(a, host);
                String st = hl == null ? "UNKNOWN" : stabilityOf(hl);
                worst = worse(worst, st);
                reversed.add(new ElementAccessPath.Step("SHADOW_ROOT", host.tag() + (host.attr("id") == null ? "" : "#" + host.attr("id")),
                        "doc:" + host.frameId(), null, null, host.id(), hl == null ? null : (hl.css() != null ? hl.css() : hl.semantic()),
                        hl == null ? null : hl.strategy().name(), hl == null ? 0 : hl.matchCount(), st, null, null,
                        "SHADOW_ROOT_OPEN", restriction));
                if (restriction != null && blocking == null) {
                    blocking = restriction;
                }
                shadows++;
                current = host;
                continue;
            }
            String frameId = scope.substring("doc:".length());
            Optional<FrameDescriptor> fd = graph.frame(frameId);
            if (fd.isEmpty() || fd.get().hostNodeId() == null) {
                blocking = blocking == null ? "FRAME_HOST_UNKNOWN" : blocking;
                break;
            }
            FrameDescriptor f = fd.get();
            RawNode host = a.model().find(f.hostNodeId()).orElse(null);
            if (host == null) {
                blocking = blocking == null ? "FRAME_HOST_UNKNOWN" : blocking;
                break;
            }
            String restriction = switch (f.status()) {
                case "PERMISSION_DENIED" -> "PERMISSION_DENIED";
                case "SANDBOX_RESTRICTED" -> "SANDBOX_RESTRICTED";
                case "LOADING", "ATTACHING" -> "FRAME_LOADING";
                case "INSPECTED" -> f.inspectedBy() != null && !"top".equals(f.inspectedBy()) ? "FRAME_SOURCE_CONTEXT" : null;
                default -> "INACCESSIBLE";
            };
            if (restriction != null && !"FRAME_SOURCE_CONTEXT".equals(restriction) && blocking == null) {
                blocking = restriction;
            }
            String st = f.locatorStability() == null ? "UNKNOWN" : f.locatorStability();
            worst = worse(worst, st);
            LocatorDescriptor hl = primary(a, host);
            reversed.add(new ElementAccessPath.Step("IFRAME", frameLabel(f), f.parentDocumentId(), f.documentId(),
                    f.browser() == null ? null : f.browser().browserDocumentId(), host.id(), f.locator(),
                    hl == null ? null : hl.strategy().name(), hl == null ? 0 : hl.matchCount(), st, f.identity(), f.effectiveUrl(),
                    "FRAME_DOCUMENT_LOADED" + (f.effectiveUrl() == null ? "" : " (" + UrlSanitizer.path(f.effectiveUrl()) + ")"),
                    restriction));
            frames++;
            current = host;
        }
        FrameDescriptor main = graph.frame("f0").orElse(null);
        reversed.add(new ElementAccessPath.Step("MAIN_DOCUMENT", main != null && main.documentTitle() != null ? main.documentTitle() : "Documento principal",
                null, "doc:f0", main == null || main.browser() == null ? null : main.browser().browserDocumentId(), null, null, null, 0,
                null, null, main == null ? null : main.effectiveUrl(), "DOCUMENT_ACTIVE", null));
        List<ElementAccessPath.Step> steps = new ArrayList<>(reversed);
        java.util.Collections.reverse(steps);
        ElementAccessPath.Target target = new ElementAccessPath.Target(cls == null ? node.tag().toUpperCase() : cls.kind().name(), elementLabel);
        String explanation = explain(a, node, cls, steps, frames, shadows, blocking);
        return new ElementAccessPath(node.id(), target, steps, frames + 1, frames, shadows, blocking == null, blocking, worst, explanation);
    }

    private static LocatorDescriptor primary(PageAnalysis a, RawNode n) {
        try {
            LocatorSet ls = a.locators().generate(n.id());
            return ls.primary();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String stabilityOf(LocatorDescriptor d) {
        StabilityLevel s = d.stability();
        return s == null ? "UNKNOWN" : s.name();
    }

    private static String worse(String a, String b) {
        int ia = STABILITY_ORDER.indexOf(a);
        int ib = STABILITY_ORDER.indexOf(b);
        return ib > ia ? b : a;
    }

    private static String label(Classification cls, RawNode n) {
        String name = cls == null || cls.name() == null ? "" : cls.name().name();
        return name == null || name.isBlank() ? n.tag() + (n.attr("id") == null ? "" : "#" + n.attr("id")) : name;
    }

    static String frameLabel(FrameDescriptor f) {
        if (f.title() != null && !f.title().isBlank()) {
            return f.title();
        }
        if (f.name() != null && !f.name().isBlank()) {
            return f.name();
        }
        if (f.documentTitle() != null && !f.documentTitle().isBlank()) {
            return f.documentTitle();
        }
        if (f.identity() != null && f.identity().idAttr() != null) {
            return "#" + f.identity().idAttr();
        }
        return f.effectiveUrl() != null ? UrlSanitizer.path(f.effectiveUrl()) : f.frameId();
    }

    private static String explain(PageAnalysis a, RawNode node, Classification cls, List<ElementAccessPath.Step> steps,
                                  int frames, int shadows, String blocking) {
        StringBuilder sb = new StringBuilder();
        String kind = cls == null ? node.tag() : cls.kind().name().toLowerCase().replace('_', ' ');
        String name = cls == null || cls.name() == null ? "" : cls.name().name();
        sb.append("O elemento ").append(kind);
        if (name != null && !name.isBlank()) {
            sb.append(" «").append(name).append("»");
        }
        sb.append(" foi encontrado");
        ElementFamily fam = null;
        try {
            fam = a.graph().family(node.id());
        } catch (RuntimeException ignored) {
            // família indisponível
        }
        if (fam != null && fam.form() != null) {
            sb.append(" no formulário «").append(nonBlank(fam.form().name(), fam.form().idAttr())).append("»");
        } else if (fam != null && fam.table() != null) {
            sb.append(" na tabela «").append(nonBlank(fam.table().name(), fam.table().idAttr())).append("»");
        } else if (fam != null && fam.dialog() != null) {
            sb.append(" no diálogo «").append(nonBlank(fam.dialog().name(), fam.dialog().idAttr())).append("»");
        }
        if (frames == 0 && shadows == 0) {
            sb.append(", diretamente no documento principal.");
        } else {
            if (frames > 0) {
                sb.append(", no ").append(frames).append("º nível de iframe da página");
            }
            if (shadows > 0) {
                sb.append(frames > 0 ? " e" : ",").append(" dentro de ").append(shadows).append(shadows == 1 ? " shadow root" : " shadow roots");
            }
            sb.append(". ");
            List<String> chain = new ArrayList<>();
            for (ElementAccessPath.Step s : steps) {
                if ("IFRAME".equals(s.type())) {
                    chain.add("o iframe «" + s.label() + "»" + (s.locator() == null ? "" : " (" + s.locator() + ")"));
                } else if ("SHADOW_ROOT".equals(s.type())) {
                    chain.add("o shadow root de «" + s.label() + "»");
                }
            }
            sb.append("O documento principal contém ").append(String.join(", que contém ", chain)).append('.');
        }
        List<String> basis = new ArrayList<>();
        if (name != null && !name.isBlank()) {
            basis.add("nome acessível");
        }
        if (fam != null && fam.form() != null) {
            basis.add("formulário associado");
        }
        if (fam != null && fam.row() != null) {
            basis.add("linha da tabela");
        }
        if (frames > 0) {
            basis.add("hierarquia documental");
        }
        if (shadows > 0) {
            basis.add("host do shadow root");
        }
        if (!basis.isEmpty()) {
            sb.append(" A identidade é sustentada por: ").append(String.join(", ", basis)).append('.');
        }
        if (blocking != null) {
            sb.append(" Restrição: ").append(blocking).append(" — o conteúdo além desse ponto não pôde ser verificado.");
        }
        return sb.toString().trim();
    }

    private static String nonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : (b == null ? "?" : b);
    }
}
