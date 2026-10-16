package com.nekkora.extension.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nekkora.extension.core.analysis.PageAnalysis;
import com.nekkora.extension.core.analysis.PageAnalyzer;
import com.nekkora.extension.core.locator.LocatorDescriptor;
import com.nekkora.extension.core.locator.LocatorSet;
import com.nekkora.extension.core.semantic.Classification;
import com.nekkora.extension.core.semantic.ElementCategory;
import com.nekkora.extension.core.snapshot.RawNode;
import com.nekkora.extension.core.snapshot.RawSnapshot;
import com.nekkora.extension.host.json.JsonCodec;
import com.nekkora.extension.it.support.BrowserLab.Page;
import com.nekkora.extension.it.support.LabIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CHROME-SECURITY-001 — capacidade do motor semântico sob cada política de privacidade, medida sobre snapshots REAIS
 * do laboratório (Chrome real). O mesmo snapshot é transformado e analisado pelo motor Java:
 *
 * <ul>
 *   <li><b>ATUAL</b> — {@code nekkora-privacy/1} (texto e atributos mascarados por formato);</li>
 *   <li><b>ESTRUTURAL</b> — nenhum texto da página; só atributos de vocabulário fechado (tipo, papel, estados ARIA…) e a
 *       PRESENÇA de href; ids e referências entre elementos viram pseudônimos (relação preservada, valor não);</li>
 *   <li><b>ESTRUTURAL+IDS</b> — o anterior, mas id/name/classe/atributos de teste que passam numa gramática de
 *       "identificador técnico" seguem (heurística: mostra o quanto renderia e o que deixaria passar);</li>
 *   <li><b>VOCABULARIO</b> — texto e identificadores só seguem se TODOS os termos pertencerem ao vocabulário controlado
 *       de interface ({@code contracts/privacy/ui-vocabulary.json}); senão, suprimidos (ids viram pseudônimos);</li>
 *   <li><b>TOKENS</b> — todo texto e valor de atributo vira pseudônimo de sessão (HMAC com chave efêmera).</li>
 * </ul>
 *
 * Mede, para os elementos INTERATIVOS de hoje: classificação igual, nome disponível, localizador preferido único e
 * localizador TRANSPORTÁVEL único (primeiro candidato único sem pseudônimo). Sem limiares: é a evidência do ponto de
 * controle da fase 2.
 */
class SemanticCapabilityIT extends LabIT {

    private static final ObjectMapper M = new ObjectMapper();
    private static final JsonCodec CODEC = new JsonCodec();

    static final List<String> CORPUS = List.of(
            "/lab/simple/form-basics.html", "/lab/intermediate/forms-groups.html", "/lab/intermediate/tables.html",
            "/lab/intermediate/widgets.html", "/lab/complex/dropdowns.html", "/lab/complex/duplicated-ids.html",
            "/lab/complex/dynamic-ids.html", "/lab/complex/rerender.html", "/lab/complex/responsive.html",
            "/lab/complex/shadow-iframes.html", "/lab/chrome002/accessible-names.html", "/lab/chrome002/continuous.html",
            "/lab/chrome002/iframes.html", "/lab/chrome002/recording.html", "/lab/chrome002/shadow-dynamic.html",
            "/lab/chrome003/contas.html", "/lab/chrome003/filial.html", "/lab/chrome003/financeiro.html",
            "/lab/chrome003/frames.html", "/lab/chrome003/pagamentos.html", "/lab/chrome003/painel.html",
            "/lab/chrome003/relatorios.html", "/lab/studio003/consulta.html", "/lab/studio003/login.html",
            "/lab/studio003/frames.html", "/lab/studio005/login-erro.html", "/lab/studio005/pesquisa.html",
            "/lab/studio005/validacao.html", "/lab/chrome-security-001/formularios.html", "/lab/chrome-security-001/elementos.html",
            "/lab/chrome-security-001/atributos.html", "/lab/chrome-security-001/tabela.html", "/lab/chrome-security-001/exportacao.html",
            "/clientes?nome=ana&status=ativo");

    /** Atributos de vocabulário fechado ou numéricos: não carregam texto livre da página. */
    static final Set<String> STRUCTURAL = Set.of("type", "role", "tabindex", "disabled", "checked", "readonly", "required", "hidden",
            "open", "multiple", "selected", "contenteditable", "autocomplete", "inputmode", "method", "target", "scope", "colspan",
            "rowspan", "draggable", "spellcheck", "dir", "popover", "maxlength", "minlength", "min", "max", "step", "size", "rows", "cols",
            "aria-expanded", "aria-pressed", "aria-checked", "aria-selected", "aria-disabled", "aria-hidden", "aria-invalid",
            "aria-required", "aria-readonly", "aria-busy", "aria-modal", "aria-haspopup", "aria-live", "aria-current", "aria-level",
            "aria-multiselectable", "aria-orientation", "aria-sort", "aria-autocomplete", "aria-atomic", "aria-relevant",
            "aria-valuenow", "aria-valuemin", "aria-valuemax", "aria-colcount", "aria-rowcount", "aria-colindex", "aria-rowindex",
            "aria-posinset", "aria-setsize");
    /** Referências entre elementos (valor = ids). */
    static final Set<String> IDREFS = Set.of("for", "aria-labelledby", "aria-describedby", "aria-controls", "aria-owns",
            "aria-activedescendant", "aria-errormessage", "aria-details", "aria-flowto", "list", "form", "headers");
    /** Identificadores escritos pelo desenvolvedor (valor pode conter dado: "cliente-anacleto-prudencio"). */
    static final Set<String> IDENTIFIERS = Set.of("name", "class", "data-testid", "data-test", "data-test-id", "data-qa", "data-cy");
    /** Atributos de TEXTO apresentável (nome/descrição acessível). */
    static final Set<String> TEXTUAL = Set.of("aria-label", "title", "placeholder", "alt", "value", "aria-description",
            "aria-roledescription", "aria-valuetext", "aria-placeholder", "label");
    static final Pattern TECHNICAL = Pattern.compile("^[A-Za-z][A-Za-z0-9]*(?:[-_:.][A-Za-z0-9]+)*$");
    static final Pattern PSEUDONYM = Pattern.compile("nkref-|k[0-9a-f]{12}");
    static final String KEY = "chave-efemera-" + System.nanoTime();
    static final Set<String> VOCABULARY = new HashSet<>();

    enum Policy { ATUAL, ESTRUTURAL, ESTRUTURAL_IDS, VOCABULARIO, TOKENS }

    static boolean technical(String v) {
        return v.length() <= 32 && TECHNICAL.matcher(v).matches() && !v.matches(".*\\d{3,}.*");
    }

    /** Todos os termos no vocabulário (ou números de até 2 dígitos). */
    static boolean vocabularyOnly(String v) {
        String s = v.replaceAll("([a-z])([A-Z])", "$1 $2");
        s = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
        String[] parts = s.split("[^a-z0-9]+");
        boolean any = false;
        for (String t : parts) {
            if (t.isEmpty()) {
                continue;
            }
            any = true;
            if (!(t.matches("\\d{1,2}") || VOCABULARY.contains(t))) {
                return false;
            }
        }
        return any;
    }

    static String token(String v) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(KEY.getBytes(StandardCharsets.UTF_8));
            return "k" + HexFormat.of().formatHex(md.digest(v.getBytes(StandardCharsets.UTF_8))).substring(0, 12);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String pseudo(String v) {
        return "nkref-" + token(v).substring(1, 9);
    }

    static String text(Policy p, String t) {
        if (t == null || t.isBlank()) {
            return t == null ? "" : t;
        }
        return switch (p) {
            case ATUAL -> t;
            case ESTRUTURAL, ESTRUTURAL_IDS -> "";
            case VOCABULARIO -> vocabularyOnly(t) ? t : "";
            case TOKENS -> token(t);
        };
    }

    /** Um id (ou ref): mantido, pseudônimo ou nada, conforme a política. */
    static String ref(Policy p, String id) {
        return switch (p) {
            case ATUAL -> id;
            case ESTRUTURAL -> pseudo(id);
            case ESTRUTURAL_IDS -> technical(id) ? id : pseudo(id);
            case VOCABULARIO -> vocabularyOnly(id) ? id : pseudo(id);
            case TOKENS -> token(id);
        };
    }

    static String words(String v, java.util.function.Function<String, String> f) {
        List<String> out = new ArrayList<>();
        for (String part : v.trim().split("\\s+")) {
            String r = part.isEmpty() ? null : f.apply(part);
            if (r != null) {
                out.add(r);
            }
        }
        return out.isEmpty() ? null : String.join(" ", out);
    }

    static String attribute(Policy p, String k, String v) {
        if (p == Policy.ATUAL || STRUCTURAL.contains(k)) {
            return v;
        }
        if ("id".equals(k)) {
            return ref(p, v);
        }
        if (IDREFS.contains(k)) {
            return words(v, id -> ref(p, id));
        }
        if ("href".equals(k)) {
            return p == Policy.TOKENS ? token(v) : ""; // presença do link, nunca o endereço
        }
        if (IDENTIFIERS.contains(k)) {
            return switch (p) {
                case ESTRUTURAL -> null;
                case ESTRUTURAL_IDS -> words(v, w -> technical(w) ? w : null);
                case VOCABULARIO -> words(v, w -> vocabularyOnly(w) ? w : null);
                case TOKENS -> words(v, SemanticCapabilityIT::token);
                default -> v;
            };
        }
        if (TEXTUAL.contains(k)) {
            String t = text(p, v);
            return t.isEmpty() ? null : t;
        }
        return p == Policy.TOKENS ? token(v) : null; // src, action, data-*, personalizados: valor livre
    }

    static JsonNode apply(JsonNode raw, Policy p) {
        ObjectNode s = raw.deepCopy();
        for (JsonNode n : s.withArray("nodes")) {
            ObjectNode o = (ObjectNode) n;
            o.put("ownText", text(p, o.path("ownText").asText("")));
            if (o.has("textSegments")) {
                ArrayNode segs = M.createArrayNode();
                for (JsonNode t : o.get("textSegments")) {
                    segs.add(text(p, t.asText("")));
                }
                o.set("textSegments", segs);
            }
            if (p != Policy.ATUAL) {
                o.putNull("exposedValue");
            }
            ObjectNode attrs = M.createObjectNode();
            for (Iterator<Map.Entry<String, JsonNode>> it = o.with("attributes").fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> a = it.next();
                String v = attribute(p, a.getKey(), a.getValue().asText(""));
                if (v != null) {
                    attrs.put(a.getKey(), v);
                }
            }
            o.set("attributes", attrs);
        }
        return s;
    }

    static boolean transportable(LocatorDescriptor d) {
        String all = d.css() + " " + d.xpath() + " " + d.semantic();
        return !PSEUDONYM.matcher(all).find();
    }

    @Test
    @DisplayName("SEC001-074 — capacidade do motor por política (ATUAL, ESTRUTURAL, ESTRUTURAL+IDS, VOCABULÁRIO, TOKENS) no corpus do laboratório")
    void capabilityByPolicy() throws Exception {
        JsonNode voc = M.readTree(Files.readString(lab.repo().resolve("contracts/privacy/ui-vocabulary.json")));
        voc.get("terms").forEach(t -> VOCABULARY.add(t.asText()));
        Map<String, Object> perPage = new LinkedHashMap<>();
        Map<Policy, long[]> total = new TreeMap<>();
        Map<Policy, Map<String, Integer>> strategies = new TreeMap<>();
        Map<Policy, Map<String, Integer>> stability = new TreeMap<>();
        Map<Policy, Map<String, Integer>> kindChanges = new TreeMap<>();
        for (String path : CORPUS) {
            Page page = open(path);
            Thread.sleep(400);
            JsonNode raw = lab.call("page.rawSnapshot", page);
            Map<String, String> baseKinds = new LinkedHashMap<>();
            Map<String, long[]> row = new LinkedHashMap<>();
            for (Policy p : Policy.values()) {
                JsonNode tr = apply(raw, p);
                long size = M.writeValueAsBytes(tr).length;
                long t0 = System.nanoTime();
                PageAnalysis a = new PageAnalyzer().analyze(CODEC.read(tr, RawSnapshot.class));
                // [interativos, mesma classificação, com nome, preferido único, transportável único, transportável único e
                //  estável, sem nenhum único, bytes, ms]
                long[] m = new long[9];
                for (RawNode n : a.model().inDocumentOrder()) {
                    Classification c = a.semantics().get(n.id());
                    if (c == null) {
                        continue;
                    }
                    if (p == Policy.ATUAL) {
                        if (c.category() != ElementCategory.INTERACTIVE) {
                            continue;
                        }
                        baseKinds.put(n.id(), c.kind().name()); // o universo medido é o dos interativos de hoje
                    } else if (!baseKinds.containsKey(n.id())) {
                        continue;
                    }
                    m[0]++;
                    if (c.kind().name().equals(baseKinds.get(n.id()))) {
                        m[1]++;
                    } else {
                        kindChanges.computeIfAbsent(p, k -> new TreeMap<>()).merge(baseKinds.get(n.id()) + " -> " + c.kind().name(), 1, Integer::sum);
                    }
                    if (c.name() != null && !c.name().name().isBlank() && !PSEUDONYM.matcher(c.name().name()).find()) {
                        m[2]++;
                    }
                    LocatorSet ls = a.locators().generate(n.id());
                    LocatorDescriptor pr = ls == null ? null : ls.primary();
                    if (pr != null && pr.unique()) {
                        m[3]++;
                    }
                    LocatorDescriptor tr1 = ls == null ? null : ls.all().stream().filter(d -> d.unique() && transportable(d)).findFirst().orElse(null);
                    if (tr1 != null) {
                        m[4]++;
                        strategies.computeIfAbsent(p, k -> new TreeMap<>()).merge(tr1.strategy().name(), 1, Integer::sum);
                        stability.computeIfAbsent(p, k -> new TreeMap<>()).merge(String.valueOf(tr1.stability()), 1, Integer::sum);
                        if (String.valueOf(tr1.stability()).endsWith("_STABLE")) {
                            m[5]++;
                        }
                    }
                    if (ls == null || ls.all().stream().noneMatch(LocatorDescriptor::unique)) {
                        m[6]++;
                    }
                }
                m[7] = size;
                m[8] = Math.round((System.nanoTime() - t0) / 1e6);
                row.put(p.name(), m);
                long[] t = total.computeIfAbsent(p, k -> new long[9]);
                for (int i = 0; i < 9; i++) {
                    t[i] += m[i];
                }
            }
            perPage.put(path, row);
            lab.close(page);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> summary = new LinkedHashMap<>();
        for (Policy p : Policy.values()) {
            long[] t = total.get(p);
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("interativos", t[0]);
            s.put("classificacaoIgualAtual", t[1]);
            s.put("comNomeLegivel", t[2]);
            s.put("preferidoUnico", t[3]);
            s.put("transportavelUnico", t[4]);
            s.put("transportavelUnicoEstavel", t[5]);
            s.put("semNenhumUnico", t[6]);
            s.put("bytesSnapshot", t[7]);
            s.put("msAnalise", t[8]);
            s.put("estrategiaDoTransportavel", strategies.getOrDefault(p, Map.of()));
            s.put("estabilidadeDoTransportavel", stability.getOrDefault(p, Map.of()));
            s.put("classificacaoAlterada", kindChanges.getOrDefault(p, Map.of()));
            summary.put(p.name(), s);
        }
        out.put("legenda", "por página: [interativos, mesma classificação, com nome legível, preferido único, transportável único, "
                + "transportável único estável, sem nenhum único, bytes do snapshot, ms de análise+localizadores]");
        out.put("vocabulario", voc.path("version").asText() + " (" + VOCABULARY.size() + " termos)");
        out.put("resumo", summary);
        out.put("paginas", perPage);
        out.put("canariosDeIdentificador", Map.of(
                "cliente-anacleto-prudencio", Map.of("gramaticaTecnica", technical("cliente-anacleto-prudencio"), "vocabulario", vocabularyOnly("cliente-anacleto-prudencio")),
                "usuario_hermengarda", Map.of("gramaticaTecnica", technical("usuario_hermengarda"), "vocabulario", vocabularyOnly("usuario_hermengarda")),
                "Consultar cliente Bartolomeu Quixadá", Map.of("vocabulario", vocabularyOnly("Consultar cliente Bartolomeu Quixadá")),
                "btn-pesquisar", Map.of("gramaticaTecnica", technical("btn-pesquisar"), "vocabulario", vocabularyOnly("btn-pesquisar"))));
        lab.writeEvidence("SEC001-semantic-capability.json", M.writerWithDefaultPrettyPrinter().writeValueAsString(out));
        assertTrue(total.get(Policy.ATUAL)[0] > 0, "corpus analisado");
    }
}
