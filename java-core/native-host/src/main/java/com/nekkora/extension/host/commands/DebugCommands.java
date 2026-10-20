package com.nekkora.extension.host.commands;

import com.fasterxml.jackson.databind.JsonNode;
import com.nekkora.extension.core.analysis.PageAnalysis;
import com.nekkora.extension.core.identity.TargetIdentity;
import com.nekkora.extension.core.resolve.CandidateEvaluation;
import com.nekkora.extension.core.resolve.ResolutionResult;
import com.nekkora.extension.core.resolve.ResolutionStatus;
import com.nekkora.extension.core.semantic.Classification;
import com.nekkora.extension.core.semantic.ElementCondition;
import com.nekkora.extension.core.semantic.ElementKind;
import com.nekkora.extension.host.session.TabState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * STUDIO-009 (guided-debug/1): reencontro de alvo para o debug guiado do Studio — SOMENTE LEITURA (D-S009-03).
 *
 * <ul>
 *   <li>Com {@code targetId} registrado: o mesmo {@code TargetResolver} da inspeção, sem atualizar o registro nem transferir
 *       o dono (o isolamento por aba da CHROME-002 continua valendo para reencontro automático e exportação).</li>
 *   <li>Sem {@code targetId} utilizável: identidade TRANSITÓRIA montada a partir do descritor recuperável (tipo, nome do
 *       vocabulário, id/name/atributo de teste) — nada é registrado; o resultado declara a fonte {@code DESCRIPTOR}.</li>
 * </ul>
 *
 * A saída é estruturada (status, códigos, verificações, candidatos) e passa pela barreira de privacidade do Bridge antes de
 * ir ao Studio; as frases livres do resolvedor ficam aqui, como evidência local da aba.
 */
public final class DebugCommands implements CommandRouter.CommandGroup {

    static final int MAX_CANDIDATES = 5;

    /** Tipos usuais por ação (mesma tabela do plano do Studio: {@code GuidedPlan.USUAL_KINDS}). */
    static final Map<String, Set<String>> USUAL_KINDS = Map.of(
            "FILL", Set.of("TEXT_INPUT", "EMAIL_INPUT", "PASSWORD_INPUT", "NUMBER_INPUT", "DATE_INPUT", "SEARCH_INPUT", "TEL_INPUT", "URL_INPUT",
                    "OTHER_INPUT", "TEXTAREA", "CONTENTEDITABLE", "COMBOBOX"),
            "SELECT_OPTION", Set.of("SELECT", "COMBOBOX", "LISTBOX", "CUSTOM_DROPDOWN", "OPTION"),
            "CHECK", Set.of("CHECKBOX", "RADIO", "SWITCH"),
            "UNCHECK", Set.of("CHECKBOX", "SWITCH"));

    private static final Pattern ID_CSS = Pattern.compile("^#([A-Za-z][A-Za-z0-9_-]{0,119})$");
    private static final Pattern ATTR_CSS = Pattern.compile("^\\[(data-testid|data-test|data-test-id|data-qa|data-cy|name)=\"([^\"\\\\]{1,120})\"\\]$");

    private final EngineServices s;

    public DebugCommands(EngineServices services) {
        this.s = services;
    }

    @Override
    public void register(CommandRouter router) {
        router.register("debug.resolve", p -> {
            long t0 = System.nanoTime();
            TabState tab = s.session.tab(Payloads.required(p, "tabKey"));
            PageAnalysis a = s.capture(tab, Payloads.optional(p, "captureId"));
            String action = Payloads.required(p, "actionType");
            String targetId = Payloads.optional(p, "targetId");
            JsonNode descriptor = p.path("descriptor");
            List<String> reasons = new ArrayList<>();
            TargetIdentity identity = targetId == null ? null : s.session.targets().get(targetId).orElse(null);
            String source = "TARGET_ID";
            if (identity == null) {
                if (targetId != null) {
                    reasons.add("TARGET_UNKNOWN_IN_REGISTRY");
                }
                source = "DESCRIPTOR";
                reasons.add("DESCRIPTOR_ONLY");
                if (crossesScope(descriptor)) {
                    // a identidade transitória só cobre o documento principal: atravessar frame/shadow exige a registrada
                    reasons.add("DESCRIPTOR_CANNOT_CROSS_SCOPE");
                    return outcome(source, "SCOPE_UNAVAILABLE", 0, null, false, reasons, List.of(), a, action, descriptor, null, t0);
                }
                identity = transientIdentity(descriptor, a);
            }
            // SOMENTE LEITURA: o resultado não é gravado no registro (nem a identidade atualizada nem o dono)
            ResolutionResult r = s.resolver.resolve(identity, a.graph(), a.locators());
            reasons.addAll(r.diagnostics());
            if (identity.indistinguishableAtRegistration()) {
                reasons.add("INDISTINGUISHABLE_AT_REGISTRATION");
            }
            if (identity.lookalikeCount() > 0) {
                reasons.add("LOOKALIKES_PRESENT");
            }
            if (r.nodeReplaced() && r.status() == ResolutionStatus.RESOLVED && "TARGET_ID".equals(source)) {
                reasons.add("NODE_REPLACED");
            }
            if (r.attributeChanges().containsKey("id")) {
                reasons.add("ID_ATTRIBUTE_CHANGED");
            }
            if (r.attributeChanges().containsKey("name")) {
                reasons.add("NAME_ATTRIBUTE_CHANGED");
            }
            if (r.status() == ResolutionStatus.IDENTITY_MISMATCH) {
                reasons.add("KIND_INCOMPATIBLE_AT_ADDRESS");
            }
            List<String> evidence = new ArrayList<>(r.evidence()); // frases livres: ficam no host (evidência local da aba)
            tab.addEvidence(EngineServices.evidence(identity.targetId(), "DEBUG_RESOLUTION", "ENGINE", evidence));
            String continuity = r.continuity() == null ? null : r.continuity().status();
            String byLocator = r.status() == ResolutionStatus.AMBIGUOUS ? recordedLocatorChoice(descriptor, a, r.candidates()) : null;
            if (byLocator != null) {
                // NEKKORA-E2E-002: o empate entre candidatos de identidade CONFIRMADA é decidido pelo localizador contextual
                // gravado (verificado único na gravação) quando ele seleciona hoje exatamente UM deles — o mesmo que a
                // execução vai usar. Nenhum palpite: sem seleção única, continua ambíguo.
                reasons.add("DISAMBIGUATED_BY_RECORDED_LOCATOR");
                double score = r.candidates().stream().filter(c -> c.nodeId().equals(byLocator)).mapToDouble(CandidateEvaluation::score).findFirst().orElse(0);
                return outcome(source, ResolutionStatus.RESOLVED.name(), score, byLocator, r.nodeReplaced(), reasons, r.candidates(), a, action, descriptor,
                        continuity, t0);
            }
            return outcome(source, r.status().name(), r.score(), r.resolvedNodeId(), r.nodeReplaced(), reasons, r.candidates(), a, action, descriptor,
                    continuity, t0);
        });

        // Estado de um nó qualquer da captura (nó escolhido no navegador ou candidato adotado): as mesmas verificações
        router.register("debug.node.state", p -> {
            TabState tab = s.session.tab(Payloads.required(p, "tabKey"));
            PageAnalysis a = s.capture(tab, Payloads.optional(p, "captureId"));
            String nodeId = Payloads.required(p, "nodeId");
            if (!a.model().contains(nodeId)) {
                throw new CommandException("UNKNOWN_NODE", "Nó não existe na captura");
            }
            List<String> reasons = new ArrayList<>();
            Map<String, Object> checks = checks(a, nodeId, Payloads.required(p, "actionType"), "NOT_APPLICABLE", "NOT_APPLICABLE", reasons, null);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("checks", checks);
            out.put("reasons", reasons);
            out.put("kind", a.semantics().get(nodeId).kind().name());
            return out;
        });
    }

    private Map<String, Object> outcome(String source, String status, double score, String resolvedNodeId, boolean replaced, List<String> reasons,
            List<CandidateEvaluation> evaluations, PageAnalysis a, String action, JsonNode descriptor, String continuity, long t0) {
        boolean resolved = "RESOLVED".equals(status);
        String identity = resolved ? "PASS" : "FAIL";
        String unique = resolved ? "PASS" : "AMBIGUOUS".equals(status) ? "FAIL" : "UNKNOWN";
        List<Map<String, Object>> candidates = new ArrayList<>();
        String best = null;
        for (CandidateEvaluation c : evaluations) {
            if (candidates.size() >= MAX_CANDIDATES) {
                break;
            }
            if (!"CONFIRMED".equals(c.verdict()) && !"VIABLE".equals(c.verdict())) {
                continue; // só candidatos plausíveis: desqualificados e incompatíveis não são oferecidos
            }
            Classification cl = a.semantics().get(c.nodeId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("nodeId", c.nodeId());
            m.put("verdict", c.verdict());
            m.put("score", Math.round(c.score() * 100.0) / 100.0);
            m.put("kind", cl.kind().name());
            m.put("name", RecorderCommands.text(cl.name().name(), 120));
            m.put("chosen", c.nodeId().equals(resolvedNodeId));
            candidates.add(m);
            if (best == null) {
                best = c.nodeId();
            }
        }
        // sujeito das verificações de estado: o resolvido; sem resolução, o único plausível (para explicar), nunca um dentre vários
        String subject = resolvedNodeId != null ? resolvedNodeId : candidates.size() == 1 ? best : null;
        Map<String, Object> checks = checks(a, subject, action, identity, unique, reasons, descriptor);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("identitySource", source);
        out.put("status", status);
        out.put("score", Math.round(score * 100.0) / 100.0);
        out.put("resolvedNodeId", resolvedNodeId);
        out.put("subjectNodeId", subject);
        out.put("subjectTag", subject == null ? null : a.model().node(subject).tag());
        out.put("subjectSensitive", subject != null && a.semantics().get(subject).sensitive());
        out.put("nodeReplaced", replaced);
        out.put("reasons", new ArrayList<>(new LinkedHashSet<>(reasons)));
        out.put("checks", checks);
        out.put("candidates", candidates);
        out.put("continuity", continuity);
        out.put("captureId", a.model().snapshot().captureId());
        out.put("hostMs", EngineServices.ms(t0));
        return out;
    }

    /** Verificações situadas (esta captura): PASS, FAIL, UNKNOWN ou NOT_APPLICABLE. Sem sujeito: estado UNKNOWN. */
    static Map<String, Object> checks(PageAnalysis a, String nodeId, String action, String identity, String unique, List<String> reasons,
            JsonNode descriptor) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("identity", identity);
        c.put("unique", unique);
        boolean editableAction = "FILL".equals(action) || "PRESS_KEY".equals(action);
        if (nodeId == null) {
            c.put("visible", "UNKNOWN");
            c.put("enabled", "UNKNOWN");
            c.put("editable", editableAction ? "UNKNOWN" : "NOT_APPLICABLE");
            c.put("kindMatchesAction", USUAL_KINDS.containsKey(action) ? "UNKNOWN" : "NOT_APPLICABLE");
        } else {
            Classification cl = a.semantics().get(nodeId);
            Set<ElementCondition> cond = cl.conditions();
            if (cond.contains(ElementCondition.HIDDEN)) {
                c.put("visible", "FAIL");
                reasons.add("HIDDEN");
            } else if (cond.contains(ElementCondition.VISIBLE)) {
                c.put("visible", "PASS");
            } else {
                c.put("visible", "UNKNOWN");
            }
            if (cond.contains(ElementCondition.OUT_OF_VIEWPORT)) {
                reasons.add("OUT_OF_VIEWPORT");
            }
            if (cond.contains(ElementCondition.DISABLED)) {
                c.put("enabled", "FAIL");
                reasons.add("DISABLED");
            } else if (cond.contains(ElementCondition.INACCESSIBLE)) {
                c.put("enabled", "FAIL");
                reasons.add("INACCESSIBLE");
            } else if (cond.contains(ElementCondition.ENABLED) || cl.interactive()) {
                c.put("enabled", "PASS");
            } else {
                c.put("enabled", "UNKNOWN");
            }
            if (editableAction) {
                boolean readOnly = cond.contains(ElementCondition.READ_ONLY);
                if (readOnly) {
                    reasons.add("READ_ONLY");
                }
                boolean editable = cl.kind().isTextEntry() && !readOnly && !cond.contains(ElementCondition.DISABLED);
                c.put("editable", editable ? "PASS" : "FAIL");
                if (!editable && !readOnly) {
                    reasons.add("NOT_EDITABLE");
                }
            } else {
                c.put("editable", "NOT_APPLICABLE");
            }
            Set<String> usual = USUAL_KINDS.get(action);
            if (usual == null) {
                c.put("kindMatchesAction", "NOT_APPLICABLE");
            } else if (cl.kind() == ElementKind.UNKNOWN) {
                c.put("kindMatchesAction", "UNKNOWN");
            } else if (usual.contains(cl.kind().name())) {
                c.put("kindMatchesAction", "PASS");
            } else {
                c.put("kindMatchesAction", "FAIL");
                reasons.add("KIND_UNUSUAL_FOR_ACTION");
            }
        }
        boolean frame = has(descriptor, "IFRAME") || reasons.stream().anyMatch(r -> r.startsWith("FRAME_"));
        boolean shadow = has(descriptor, "SHADOW_ROOT") || reasons.stream().anyMatch(r -> r.startsWith("SHADOW_"));
        c.put("frameReachable", !frame ? "NOT_APPLICABLE" : reasons.stream().anyMatch(r -> r.startsWith("FRAME_")) ? "FAIL"
                : "PASS".equals(identity) ? "PASS" : "UNKNOWN");
        c.put("shadowReachable", !shadow ? "NOT_APPLICABLE" : reasons.stream().anyMatch(r -> r.startsWith("SHADOW_")) ? "FAIL"
                : "PASS".equals(identity) ? "PASS" : "UNKNOWN");
        return c;
    }

    private static boolean has(JsonNode descriptor, String type) {
        if (descriptor == null) {
            return false;
        }
        for (JsonNode s : descriptor.path("accessPath")) {
            if (type.equals(s.path("type").asText())) {
                return true;
            }
        }
        return false;
    }

    private static boolean crossesScope(JsonNode descriptor) {
        return has(descriptor, "IFRAME") || has(descriptor, "SHADOW_ROOT");
    }

    /**
     * Identidade transitória a partir do descritor recuperável (nunca registrada). Só características que o descritor
     * persistido traz: tipo e família, tag, nome (já do vocabulário), id/name e atributo de teste. Sem discriminadores
     * nem localizadores executáveis: o resolvedor usa as sondas de id/name e a busca ampla por família e nome — e um
     * sósia sem diferença observável vira AMBIGUOUS, nunca "o primeiro".
     */
    static TargetIdentity transientIdentity(JsonNode d, PageAnalysis a) {
        ElementKind kind;
        try {
            kind = ElementKind.valueOf(d.path("kind").asText("UNKNOWN"));
        } catch (IllegalArgumentException e) {
            kind = ElementKind.UNKNOWN;
        }
        String id = text(d, "htmlId");
        String nameAttr = text(d, "htmlName");
        String testAttr = null;
        for (JsonNode l : d.path("locators")) {
            String css = l.path("css").asText("");
            Matcher m = ID_CSS.matcher(css);
            if (id == null && m.matches()) {
                id = m.group(1);
            }
            Matcher at = ATTR_CSS.matcher(css);
            if (at.matches()) {
                if ("name".equals(at.group(1))) {
                    nameAttr = nameAttr == null ? at.group(2) : nameAttr;
                } else if (testAttr == null) {
                    testAttr = at.group(1) + "=" + at.group(2);
                }
            }
        }
        List<String> css = new ArrayList<>();
        for (JsonNode l : d.path("locators")) {
            css.add(l.path("css").asText(""));
        }
        return com.nekkora.extension.core.identity.DescriptorIdentity.of(a, kind, text(d, "tag"), text(d, "name"), id, nameAttr, testAttr, css);
    }

    /** O candidato CONFIRMADO que um localizador contextual do descritor seleciona sozinho hoje; senão {@code null}. */
    static String recordedLocatorChoice(JsonNode d, PageAnalysis a, List<CandidateEvaluation> candidates) {
        if (d == null || d.isMissingNode() || d.isNull()) {
            return null;
        }
        ElementKind kind;
        try {
            kind = ElementKind.valueOf(d.path("kind").asText("UNKNOWN"));
        } catch (IllegalArgumentException e) {
            return null;
        }
        Set<String> confirmed = new LinkedHashSet<>();
        for (CandidateEvaluation c : candidates) {
            if ("CONFIRMED".equals(c.verdict())) {
                confirmed.add(c.nodeId());
            }
        }
        for (JsonNode l : d.path("locators")) {
            String only = com.nekkora.extension.core.identity.DescriptorIdentity.uniqueByRegionLocator(a, l.path("css").asText(""), kind, text(d, "tag"));
            if (only != null) {
                return confirmed.contains(only) ? only : null;
            }
        }
        return null;
    }

    private static String text(JsonNode d, String field) {
        JsonNode v = d.get(field);
        return v == null || !v.isTextual() || v.asText().isBlank() ? null : v.asText();
    }
}
