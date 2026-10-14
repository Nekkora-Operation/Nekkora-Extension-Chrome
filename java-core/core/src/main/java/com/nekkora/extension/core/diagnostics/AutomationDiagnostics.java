package com.nekkora.extension.core.diagnostics;

import com.nekkora.extension.core.analysis.PageAnalysis;
import com.nekkora.extension.core.context.ContextRelation;
import com.nekkora.extension.core.context.RelationType;
import com.nekkora.extension.core.diagnostics.AutomationReadinessAssessment.InteractionDependency;
import com.nekkora.extension.core.diagnostics.AutomationReadinessAssessment.Recommendation;
import com.nekkora.extension.core.frames.FrameDescriptor;
import com.nekkora.extension.core.frames.FrameGraph;
import com.nekkora.extension.core.frames.UrlSanitizer;
import com.nekkora.extension.core.locator.IdentifierHeuristics;
import com.nekkora.extension.core.locator.StabilityLevel;
import com.nekkora.extension.core.mutation.MutationRecord;
import com.nekkora.extension.core.options.OptionSet;
import com.nekkora.extension.core.protection.ProtectionAssessment;
import com.nekkora.extension.core.protection.ProtectionReport;
import com.nekkora.extension.core.semantic.Classification;
import com.nekkora.extension.core.semantic.ElementCondition;
import com.nekkora.extension.core.snapshot.RawNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Automation Diagnostics Engine — consolida, a partir de evidências, os obstáculos técnicos que um futuro mecanismo
 * de automação encontrará. Não executa automações nem interações: apenas diagnostica.
 *
 * <p>Tipos: {@code NESTED_IFRAME}, {@code FRAME_CONTEXT}, {@code CROSS_ORIGIN_FRAME}, {@code INACCESSIBLE_FRAME},
 * {@code SANDBOXED_FRAME}, {@code FRAME_LOADING}, {@code SHADOW_DOM}, {@code CLOSED_SHADOW_ROOT},
 * {@code VIRTUALIZED_COMPONENT}, {@code DYNAMIC_IDS}, {@code AMBIGUOUS_ELEMENTS}, {@code ASYNC_LOADING},
 * {@code TRANSIENT_ELEMENTS}, {@code MODAL}, {@code PORTAL}, {@code SPA_NAVIGATION}, {@code INTERACTION_DEPENDENCY},
 * {@code INACCESSIBLE_ELEMENTS}, {@code HUMAN_VERIFICATION}, {@code ANTIBOT_TECHNOLOGY}, {@code ACCESS_BLOCKED},
 * {@code UNKNOWN_PROTECTION}.
 */
public final class AutomationDiagnostics {

    private static final IdentifierHeuristics IDS = new IdentifierHeuristics();
    private static final Pattern LOAD_MORE = Pattern.compile("(carregar|ver|mostrar) mais|load more|show more", Pattern.CASE_INSENSITIVE);
    private static final Set<String> CAPTCHA_CATEGORIES = Set.of("CAPTCHA", "HUMAN_VERIFICATION", "CHALLENGE", "CHALLENGE_PAGE");

    public AutomationReadinessAssessment assess(PageAnalysis a, FrameGraph graph, ProtectionReport protection,
                                                List<OptionSet> optionSets, List<MutationRecord> mutations) {
        List<AutomationObstacle> obstacles = new ArrayList<>();
        List<String> limitations = new ArrayList<>(graph.limitations());

        // ---- Frames e documentos
        List<String> nested = new ArrayList<>();
        List<String> direct = new ArrayList<>();
        List<String> crossOrigin = new ArrayList<>();
        List<String> denied = new ArrayList<>();
        List<String> sandboxed = new ArrayList<>();
        List<String> loading = new ArrayList<>();
        for (FrameDescriptor f : graph.frames()) {
            if (f.depth() == 0) {
                continue;
            }
            (f.depth() >= 2 ? nested : direct).add(f.frameId());
            switch (f.status()) {
                case "PERMISSION_DENIED", "INACCESSIBLE" -> denied.add(f.frameId());
                case "SANDBOX_RESTRICTED" -> sandboxed.add(f.frameId());
                case "LOADING", "ATTACHING" -> loading.add(f.frameId());
                default -> {
                    if (f.inspectedBy() != null && !"top".equals(f.inspectedBy())) {
                        crossOrigin.add(f.frameId());
                    }
                }
            }
        }
        if (!nested.isEmpty()) {
            obstacles.add(new AutomationObstacle("NESTED_IFRAME", "MEDIUM",
                    "Iframes aninhados (profundidade máxima " + graph.maxDepth() + "): a automação precisa atravessar documentos em sequência",
                    List.of(nested.size() + " frame(s) com profundidade ≥ 2"), List.of(), nested, nested.size(), false));
        } else if (!direct.isEmpty()) {
            obstacles.add(new AutomationObstacle("FRAME_CONTEXT", "LOW", "Elementos em iframe exigem troca de contexto de documento",
                    List.of(direct.size() + " iframe(s) no documento principal"), List.of(), direct, direct.size(), false));
        }
        if (!crossOrigin.isEmpty()) {
            obstacles.add(new AutomationObstacle("CROSS_ORIGIN_FRAME", "LOW",
                    "Frames de outra origem inspecionados por um contexto próprio (content script do frame)",
                    List.of(crossOrigin.size() + " frame(s) de outra origem permitida"), List.of(), crossOrigin, crossOrigin.size(), false));
        }
        if (!denied.isEmpty()) {
            obstacles.add(new AutomationObstacle("INACCESSIBLE_FRAME", "MEDIUM",
                    "Frames sem permissão de inspeção: conteúdo desconhecido (somente metadados observáveis)",
                    List.of(denied.size() + " frame(s) inacessível(is)"), List.of(), denied, denied.size(), false));
        }
        if (!sandboxed.isEmpty()) {
            obstacles.add(new AutomationObstacle("SANDBOXED_FRAME", "MEDIUM", "Frames com sandbox: conteúdo não inspecionado por política",
                    List.of(sandboxed.size() + " frame(s) com sandbox"), List.of(), sandboxed, sandboxed.size(), false));
        }
        if (!loading.isEmpty()) {
            obstacles.add(new AutomationObstacle("FRAME_LOADING", "LOW",
                    "Frames ainda carregando no momento da observação: ausência de conteúdo NÃO significa ausência do alvo",
                    List.of(loading.size() + " frame(s) carregando"), List.of(), loading, loading.size(), false));
        }

        // ---- Shadow DOM
        int open = graph.counts().getOrDefault("shadowRootsOpen", 0);
        int closed = graph.counts().getOrDefault("shadowRootsClosed", 0);
        if (open > 0) {
            obstacles.add(new AutomationObstacle("SHADOW_DOM", "LOW", "Elementos em shadow roots abertos exigem atravessar o host",
                    List.of(open + " shadow root(s) aberto(s)"), List.of(), List.of(), open, false));
        }
        if (closed > 0) {
            obstacles.add(new AutomationObstacle("CLOSED_SHADOW_ROOT", "MEDIUM", "Shadow roots fechados: conteúdo encapsulado não inventariado",
                    List.of(closed + " shadow root(s) fechado(s)"), List.of(), List.of(), closed, false));
        }

        // ---- Elementos
        int interactive = 0;
        List<String> dynamicIds = new ArrayList<>();
        List<String> inaccessible = new ArrayList<>();
        Map<String, List<String>> lookalikes = new HashMap<>();
        boolean openModal = false;
        List<String> modals = new ArrayList<>();
        for (RawNode n : a.model().nodes()) {
            Classification c = a.semantics().get(n.id());
            if (c == null) {
                continue;
            }
            if (c.interactive()) {
                interactive++;
                String id = n.attr("id");
                if (id != null && IDS.assess(id).level() == StabilityLevel.HEURISTIC_UNSTABLE) {
                    dynamicIds.add(n.id());
                }
                if (c.has(ElementCondition.INACCESSIBLE)) {
                    inaccessible.add(n.id());
                }
                String name = c.name() == null ? "" : c.name().name();
                if (name != null && !name.isBlank() && !c.has(ElementCondition.HIDDEN)) {
                    lookalikes.computeIfAbsent(n.scopeId() + "§" + c.kind() + "§" + name.toLowerCase(), k -> new ArrayList<>()).add(n.id());
                }
            }
            if (n.tag().equals("dialog") || "dialog".equals(n.attr("role")) || "alertdialog".equals(n.attr("role"))) {
                modals.add(n.id());
                if (n.state() != null && Boolean.TRUE.equals(n.state().modal()) && Boolean.TRUE.equals(n.state().open())) {
                    openModal = true;
                }
            }
        }
        if (!dynamicIds.isEmpty()) {
            obstacles.add(new AutomationObstacle("DYNAMIC_IDS", "LOW",
                    "Ids com aparência de geração dinâmica: localizadores por id terão estabilidade reduzida (heurística)",
                    List.of(dynamicIds.size() + " elemento(s) interativo(s)"), dynamicIds, List.of(), dynamicIds.size(), true));
        }
        List<String> ambiguous = new ArrayList<>();
        for (List<String> group : lookalikes.values()) {
            if (group.size() > 1) {
                ambiguous.addAll(group);
            }
        }
        if (!ambiguous.isEmpty()) {
            obstacles.add(new AutomationObstacle("AMBIGUOUS_ELEMENTS", ambiguous.size() > 20 ? "MEDIUM" : "LOW",
                    "Elementos interativos de mesmo tipo e nome no mesmo escopo: identidade depende de família/contexto",
                    List.of(ambiguous.size() + " elemento(s) em grupos de nomes repetidos"), ambiguous, List.of(), ambiguous.size(), false));
        }
        if (!inaccessible.isEmpty()) {
            obstacles.add(new AutomationObstacle("INACCESSIBLE_ELEMENTS", "LOW",
                    "Elementos presentes mas fora de alcance do usuário (inert, bloqueados por modal ou escopo fechado)",
                    List.of(inaccessible.size() + " elemento(s)"), inaccessible, List.of(), inaccessible.size(), false));
        }
        if (openModal) {
            obstacles.add(new AutomationObstacle("MODAL", "MEDIUM", "Diálogo modal aberto bloqueia o restante da página",
                    List.of("dialog modal aberto"), modals, List.of(), 1, false));
        } else if (!modals.isEmpty()) {
            obstacles.add(new AutomationObstacle("MODAL", "LOW", "Diálogos presentes (fechados): conteúdo depende de abertura",
                    List.of(modals.size() + " diálogo(s)"), modals, List.of(), modals.size(), false));
        }

        // ---- Opções (virtualização, portal, materialização)
        List<String> virtualized = new ArrayList<>();
        List<String> portals = new ArrayList<>();
        List<InteractionDependency> deps = new ArrayList<>();
        for (OptionSet o : optionSets) {
            if (o.virtualizationSuspected()) {
                virtualized.add(o.controlNodeId());
            }
            if (o.listOutsideControlTree()) {
                portals.add(o.controlNodeId());
            }
            if (!"COMPLETE".equals(o.completeness())) {
                deps.add(new InteractionDependency("OPEN_DROPDOWN", o.listNodeId() == null ? o.controlNodeId() : o.listNodeId(), o.controlNodeId(),
                        o.observedCount(), "opções " + o.completeness() + " (" + o.observedCount() + " observada(s)"
                        + (o.announcedSetSize() == null ? "" : " de " + o.announcedSetSize() + " anunciadas") + ")"));
            }
        }
        if (!virtualized.isEmpty()) {
            obstacles.add(new AutomationObstacle("VIRTUALIZED_COMPONENT", "MEDIUM",
                    "Listas virtualizadas: somente parte das opções existe na DOM (heurística por aria-setsize/rolagem)",
                    List.of(virtualized.size() + " controle(s)"), virtualized, List.of(), virtualized.size(), true));
        }
        if (!portals.isEmpty()) {
            obstacles.add(new AutomationObstacle("PORTAL", "LOW", "Listas renderizadas fora da subárvore do controle (portal)",
                    List.of(portals.size() + " controle(s)"), portals, List.of(), portals.size(), true));
        }

        // ---- Dependências de interação (registradas, nunca executadas)
        deps.addAll(interactionDependencies(a));
        if (!deps.isEmpty()) {
            obstacles.add(new AutomationObstacle("INTERACTION_DEPENDENCY", "LOW",
                    "Regiões/elementos que só aparecem após interação (abrir modal, dropdown, aba, seção, carregar mais)",
                    List.of(deps.size() + " dependência(s) registrada(s)"), deps.stream().map(InteractionDependency::regionNodeId).toList(),
                    List.of(), deps.size(), deps.stream().anyMatch(d -> d.basis().contains("heurística"))));
        }

        // ---- Dinâmica (mutações, carregamento assíncrono, SPA)
        int replaced = 0;
        int removed = 0;
        for (MutationRecord m : mutations) {
            replaced += m.replaced() == null ? 0 : m.replaced().size();
            removed += m.removedCount();
        }
        if (replaced > 0 || removed > 0) {
            obstacles.add(new AutomationObstacle("TRANSIENT_ELEMENTS", replaced > 10 ? "MEDIUM" : "LOW",
                    "Elementos removidos/substituídos durante a observação: referências precisam de reencontro",
                    List.of(replaced + " substituição(ões), " + removed + " remoção(ões) em " + mutations.size() + " mutação(ões)"),
                    List.of(), List.of(), replaced + removed, false));
        }
        int asyncRequests = a.model().snapshot().requests().size();
        if (asyncRequests > 0) {
            obstacles.add(new AutomationObstacle("ASYNC_LOADING", "LOW", "Conteúdo carregado por requisições assíncronas (fetch/XHR)",
                    List.of(asyncRequests + " requisição(ões) observada(s) (somente URL e tempo)"), List.of(), List.of(), asyncRequests, false));
        }
        String nav = a.model().snapshot().document() == null ? null : a.model().snapshot().document().navigationKind();
        boolean spa = "SPA_ROUTE".equals(nav) || "FRAGMENT".equals(nav) || mutations.stream().anyMatch(m -> m.relevanceReasons() != null
                && m.relevanceReasons().stream().anyMatch(r -> r.contains("SPA") || r.contains("URL")));
        if (spa) {
            obstacles.add(new AutomationObstacle("SPA_NAVIGATION", "LOW", "Navegação sem troca de documento (SPA): estado muda sem recarga",
                    List.of("navegação " + nav), List.of(), List.of(), 1, false));
        }

        // ---- Proteções
        List<Map<String, String>> protections = new ArrayList<>();
        List<String> humanReasons = new ArrayList<>();
        boolean blocked = false;
        boolean challengeActive = false;
        boolean captchaPresent = false;
        boolean antibot = false;
        boolean unknownProtection = false;
        for (ProtectionAssessment p : protection.assessments()) {
            Map<String, String> s = new LinkedHashMap<>();
            s.put("provider", p.provider());
            s.put("product", p.product());
            s.put("category", p.category());
            s.put("confidence", p.confidence());
            s.put("challengeState", p.challengeState());
            s.put("documentId", p.location() == null ? null : p.location().documentId());
            protections.add(s);
            if ("UNKNOWN".equals(p.provider())) {
                unknownProtection = true;
                continue;
            }
            switch (p.challengeState()) {
                case "ACCESS_BLOCKED" -> blocked = true;
                case "CHALLENGE_ACTIVE" -> challengeActive = true;
                default -> {
                }
            }
            if (CAPTCHA_CATEGORIES.contains(p.category()) || "RISK_ASSESSMENT".equals(p.category())) {
                captchaPresent |= !"RISK_ASSESSMENT".equals(p.category()) || "CHALLENGE_ACTIVE".equals(p.challengeState());
                humanReasons.add(p.provider() + " " + p.product() + " (" + p.confidence() + ", " + p.challengeState() + ")");
                if ("RISK_ASSESSMENT".equals(p.category())) {
                    antibot = true;
                }
            } else {
                antibot = true;
            }
        }
        if (blocked) {
            obstacles.add(new AutomationObstacle("ACCESS_BLOCKED", "HIGH", "Página de bloqueio de acesso observada",
                    protectionEvidence(protection, "ACCESS_BLOCKED"), List.of(), List.of(), 1, false));
        }
        if (captchaPresent || challengeActive) {
            obstacles.add(new AutomationObstacle("HUMAN_VERIFICATION", challengeActive || captchaPresent ? "HIGH" : "MEDIUM",
                    challengeActive ? "Desafio de verificação humana ATIVO" : "Mecanismo de verificação humana presente (desafio não necessariamente ativo)",
                    humanReasons, List.of(), List.of(), humanReasons.size(), false));
        }
        if (antibot) {
            obstacles.add(new AutomationObstacle("ANTIBOT_TECHNOLOGY", "MEDIUM",
                    "Tecnologia de gerenciamento de bots/avaliação de risco identificada: presença não implica falha da automação",
                    protectionEvidence(protection, null), List.of(), List.of(), 1, false));
        }
        if (unknownProtection) {
            obstacles.add(new AutomationObstacle("UNKNOWN_PROTECTION", "MEDIUM",
                    "Indicadores genéricos de verificação humana sem fornecedor identificável",
                    List.of("status do diagnóstico: " + protection.status()), List.of(), List.of(), 1, false));
        }
        limitations.addAll(protection.limitations());

        // ---- Status, confiança e recomendações
        List<String> basis = new ArrayList<>();
        String status;
        if (blocked || challengeActive) {
            status = "REQUIRES_HUMAN_INTERVENTION";
            basis.add(blocked ? "acesso bloqueado observado" : "desafio de verificação humana ativo");
        } else if (obstacles.stream().anyMatch(o -> "HIGH".equals(o.severity()))) {
            status = "REQUIRES_SPECIAL_HANDLING";
            basis.add("obstáculo(s) de severidade alta: " + obstacles.stream().filter(o -> "HIGH".equals(o.severity()))
                    .map(AutomationObstacle::type).distinct().toList());
        } else if (obstacles.stream().anyMatch(o -> "MEDIUM".equals(o.severity()))) {
            status = "REQUIRES_SPECIAL_HANDLING";
            basis.add("obstáculo(s) de severidade média: " + obstacles.stream().filter(o -> "MEDIUM".equals(o.severity()))
                    .map(AutomationObstacle::type).distinct().toList());
        } else if (!obstacles.isEmpty()) {
            status = "MANAGEABLE";
            basis.add("somente obstáculos de severidade baixa");
        } else {
            status = "NO_KNOWN_OBSTACLES";
            basis.add("nenhum obstáculo observado (não comprova ausência de proteções no servidor)");
        }
        String confidence;
        int unobservable = denied.size() + sandboxed.size() + closed;
        if (!loading.isEmpty() || "INSUFFICIENT_EVIDENCE".equals(protection.status())) {
            confidence = "LOW";
            basis.add("observação incompleta (frames carregando ou proteções indeterminadas)");
        } else if (unobservable > 0) {
            confidence = "MEDIUM";
            basis.add(unobservable + " região(ões) não observável(is)");
        } else {
            confidence = "HIGH";
            basis.add("todas as regiões observáveis foram inspecionadas");
        }
        List<Recommendation> recs = new ArrayList<>();
        if (blocked || challengeActive || captchaPresent) {
            recs.add(new Recommendation("HUMAN_INTERVENTION", "O fluxo poderá exigir intervenção humana (verificação/desafio)",
                    types(obstacles, "HUMAN_VERIFICATION", "ACCESS_BLOCKED")));
            recs.add(new Recommendation("ENVIRONMENT_CONFIGURATION",
                    "Verificar com o proprietário da aplicação opções legítimas (ex.: chaves de teste, ambiente de homologação)",
                    types(obstacles, "HUMAN_VERIFICATION")));
        }
        if (antibot || captchaPresent || blocked) {
            recs.add(new Recommendation("AUTHORIZED_API", "Avaliar a existência de API oficial ou integração autorizada pelo proprietário",
                    types(obstacles, "ANTIBOT_TECHNOLOGY", "HUMAN_VERIFICATION", "ACCESS_BLOCKED")));
        }
        if (unknownProtection) {
            recs.add(new Recommendation("UNKNOWN_PROTECTION", "Proteção não identificada com segurança: investigar antes de planejar a automação",
                    types(obstacles, "UNKNOWN_PROTECTION")));
        }
        if (!denied.isEmpty() || !sandboxed.isEmpty() || closed > 0 || !ambiguous.isEmpty() || !loading.isEmpty()) {
            recs.add(new Recommendation("MANUAL_VALIDATION", "Validar manualmente as regiões não observáveis ou ambíguas antes de prosseguir",
                    types(obstacles, "INACCESSIBLE_FRAME", "SANDBOXED_FRAME", "CLOSED_SHADOW_ROOT", "AMBIGUOUS_ELEMENTS", "FRAME_LOADING")));
        }
        if (recs.isEmpty()) {
            recs.add(new Recommendation("NO_ACTION_REQUIRED", "Nenhum obstáculo específico observado", List.of()));
        }

        var snap = a.model().snapshot();
        Map<String, String> page = new LinkedHashMap<>();
        page.put("url", snap.document() == null ? null : UrlSanitizer.sanitize(snap.document().url()));
        page.put("title", snap.document() == null ? null : snap.document().title());
        Map<String, Integer> docs = new LinkedHashMap<>();
        docs.put("count", graph.documents().size());
        docs.put("maximumFrameDepth", graph.maxDepth());
        docs.put("inaccessible", denied.size() + sandboxed.size());
        docs.put("loading", loading.size());
        Map<String, Integer> shadows = new LinkedHashMap<>();
        shadows.put("open", open);
        shadows.put("closed", closed);
        Map<String, Integer> elements = new LinkedHashMap<>();
        elements.put("observed", a.model().size());
        elements.put("interactive", interactive);
        return new AutomationReadinessAssessment(page, docs, shadows, elements, obstacles, deps, protections, protection.status(), recs,
                status, confidence, basis, new ArrayList<>(new LinkedHashSet<>(limitations)));
    }

    /** Dependências declaradas: aria-controls com região oculta, details fechados, diálogos fechados, abas e "carregar mais". */
    private static List<InteractionDependency> interactionDependencies(PageAnalysis a) {
        List<InteractionDependency> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ContextRelation r : a.graph().declaredRelations()) {
            if (r.type() != RelationType.CONTROLS) {
                continue;
            }
            RawNode region = a.model().find(r.toNodeId()).orElse(null);
            RawNode controller = a.model().find(r.fromNodeId()).orElse(null);
            if (region == null || controller == null || !a.model().isHidden(region) || !seen.add(region.id())) {
                continue;
            }
            String role = controller.attr("role");
            String type = "tab".equals(role) ? "SELECT_TAB" : "combobox".equals(role) || "listbox".equals(region.attr("role"))
                    ? "OPEN_DROPDOWN" : "EXPAND_SECTION";
            out.add(new InteractionDependency(type, region.id(), controller.id(), interactiveInside(a, region),
                    "aria-controls de " + controller.tag() + " para região oculta"));
        }
        for (RawNode n : a.model().nodes()) {
            if (n.tag().equals("details") && (n.state() == null || !Boolean.TRUE.equals(n.state().open())) && seen.add(n.id())) {
                String summary = a.model().children(n).stream().filter(c -> c.tag().equals("summary")).map(RawNode::id).findFirst().orElse(null);
                out.add(new InteractionDependency("EXPAND_SECTION", n.id(), summary, interactiveInside(a, n), "details fechado"));
            } else if (n.tag().equals("dialog") && (n.state() == null || !Boolean.TRUE.equals(n.state().open())) && seen.add(n.id())) {
                int inside = interactiveInside(a, n);
                if (inside > 0) {
                    out.add(new InteractionDependency("OPEN_MODAL", n.id(), null, inside, "dialog fechado com " + inside + " controle(s)"));
                }
            } else if (n.ownText() != null && LOAD_MORE.matcher(n.ownText()).find() && (n.tag().equals("button") || n.tag().equals("a"))
                    && seen.add(n.id())) {
                out.add(new InteractionDependency("LOAD_MORE", n.id(), n.id(), 0, "heurística: texto de \"carregar mais\""));
            }
        }
        return out;
    }

    private static int interactiveInside(PageAnalysis a, RawNode region) {
        int c = 0;
        for (RawNode d : a.model().descendants(region)) {
            Classification cls = a.semantics().get(d.id());
            if (cls != null && cls.interactive()) {
                c++;
            }
        }
        return c;
    }

    private static List<String> protectionEvidence(ProtectionReport p, String state) {
        List<String> out = new ArrayList<>();
        for (ProtectionAssessment x : p.assessments()) {
            if (state == null || state.equals(x.challengeState())) {
                out.add(x.provider() + " " + x.product() + " (" + x.confidence() + ", " + x.challengeState() + ")");
            }
        }
        return out;
    }

    private static List<String> types(List<AutomationObstacle> obstacles, String... wanted) {
        List<String> out = new ArrayList<>();
        for (AutomationObstacle o : obstacles) {
            for (String w : wanted) {
                if (w.equals(o.type()) && !out.contains(w)) {
                    out.add(w);
                }
            }
        }
        return out;
    }
}
