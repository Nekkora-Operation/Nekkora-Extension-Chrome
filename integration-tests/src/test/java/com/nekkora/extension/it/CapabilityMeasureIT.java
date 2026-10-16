package com.nekkora.extension.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekkora.extension.it.support.BrowserLab.Page;
import com.nekkora.extension.it.support.LabIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CHROME-SECURITY-001 (fase 13): medição REAL de capacidade e desempenho, a mesma classe executada sobre dois builds — a
 * linha de base ({@code main} 8f3326b, política {@code nekkora-privacy/1}) e a branch da missão ({@code nekkora-privacy/2})
 * — no mesmo corpus de páginas do laboratório e usando somente comandos que existem nas duas versões
 * ({@code page.capture}, {@code page.rawSnapshot}, {@code pagemap.export}, {@code page.select}, {@code target.resolve}).
 *
 * <p>Por página: elementos classificados, interativos, com nome, localizador principal único/verificável, sem nenhum
 * localizador único (ambíguos), alvos resolvidos na página inalterada (por status), tempo de captura (mediana de 3),
 * tamanho do snapshot e do PageMap. O rótulo da execução vem de {@code -Dnekkora.measure.label} ou da variável {@code NEKKORA_MEASURE_LABEL}
 * ("antes"/"depois").
 */
class CapabilityMeasureIT extends LabIT {

    private static final ObjectMapper M = new ObjectMapper();

    /** O mesmo corpus do SemanticCapabilityIT (copiado: a classe também roda no build da linha de base). */
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

    @Test
    @DisplayName("SEC001-074b — capacidade e desempenho medidos no build (antes × depois, mesmo corpus)")
    void measure() throws Exception {
        String label = System.getProperty("nekkora.measure.label", System.getenv().getOrDefault("NEKKORA_MEASURE_LABEL", "depois"));
        Map<String, Object> perPage = new LinkedHashMap<>();
        Map<String, Long> total = new TreeMap<>();
        Map<String, Long> statuses = new TreeMap<>();
        List<Double> captureMs = new ArrayList<>();
        for (String path : CORPUS) {
            Page p = open(path);
            Thread.sleep(400);
            Map<String, Object> row = new LinkedHashMap<>();
            List<Double> times = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                long t0 = System.nanoTime();
                lab.call("page.capture", p);
                times.add((System.nanoTime() - t0) / 1e6);
            }
            Collections.sort(times);
            double median = times.get(1);
            captureMs.add(median);
            long snapBytes = M.writeValueAsBytes(lab.call("page.rawSnapshot", p)).length;
            JsonNode map = lab.call("pagemap.export", p, "returnPageMap", true).get("pageMap");
            long mapBytes = M.writeValueAsBytes(map).length;
            long classified = 0;
            long interactive = 0;
            long named = 0;
            long primaryUnique = 0;
            long primaryVerifiable = 0;
            long noUnique = 0;
            List<String> interactiveIds = new ArrayList<>();
            for (JsonNode e : map.path("elements")) {
                if (!e.path("kind").asText("").isEmpty() && !"UNKNOWN".equals(e.path("kind").asText())) {
                    classified++;
                }
                if (!"INTERACTIVE".equals(e.path("category").asText())) {
                    continue;
                }
                interactive++;
                interactiveIds.add(e.path("nodeId").asText());
                if (!e.at("/text/accessibleName").asText("").isBlank()) {
                    named++;
                }
                JsonNode pr = e.at("/locators/primary");
                if (pr.path("matchCount").asInt(-1) == 1) {
                    primaryUnique++;
                }
                if (!pr.path("css").isMissingNode() && !pr.path("css").isNull() || !pr.path("xpath").isMissingNode() && !pr.path("xpath").isNull()) {
                    primaryVerifiable++;
                }
                boolean any = pr.path("matchCount").asInt(-1) == 1;
                for (JsonNode f : e.at("/locators/fallbacks")) {
                    any |= f.path("matchCount").asInt(-1) == 1;
                }
                if (!any) {
                    noUnique++;
                }
            }
            Map<String, Long> st = new TreeMap<>();
            for (String id : interactiveIds) {
                String status;
                try {
                    JsonNode sel = lab.call("page.select", p, "nodeId", id);
                    JsonNode r = lab.call("target.resolve", p, "targetId", sel.at("/identity/targetId").asText()).get("result");
                    status = r.path("status").asText("?");
                    if ("RESOLVED".equals(status) && !id.equals(r.path("resolvedNodeId").asText())) {
                        status = "RESOLVED_OTHER"; // nunca deveria ocorrer: contado à parte para tornar visível
                    }
                } catch (RuntimeException ex) {
                    status = "SELECT_ERROR";
                }
                st.merge(status, 1L, Long::sum);
                statuses.merge(status, 1L, Long::sum);
            }
            row.put("elementosClassificados", classified);
            row.put("interativos", interactive);
            row.put("comNome", named);
            row.put("principalUnico", primaryUnique);
            row.put("principalVerificavel", primaryVerifiable);
            row.put("semNenhumUnico", noUnique);
            row.put("resolucao", st);
            row.put("capturaMsMediana", Math.round(median * 10) / 10.0);
            row.put("bytesSnapshot", snapBytes);
            row.put("bytesPageMap", mapBytes);
            perPage.put(path, row);
            total.merge("elementosClassificados", classified, Long::sum);
            total.merge("interativos", interactive, Long::sum);
            total.merge("comNome", named, Long::sum);
            total.merge("principalUnico", primaryUnique, Long::sum);
            total.merge("principalVerificavel", primaryVerifiable, Long::sum);
            total.merge("semNenhumUnico", noUnique, Long::sum);
            total.merge("bytesSnapshot", snapBytes, Long::sum);
            total.merge("bytesPageMap", mapBytes, Long::sum);
            lab.close(p);
        }
        Collections.sort(captureMs);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rotulo", label);
        out.put("paginas", CORPUS.size());
        out.put("totais", total);
        out.put("resolucaoNaPaginaInalterada", statuses);
        out.put("capturaMsMedianaDasPaginas", captureMs.get(captureMs.size() / 2));
        out.put("capturaMsSomaDasMedianas", Math.round(captureMs.stream().mapToDouble(Double::doubleValue).sum()));
        out.put("porPagina", perPage);
        Path dir = lab.repo().resolve("reports").resolve("evidence");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SEC001-capability-" + label + ".json"),
                M.writerWithDefaultPrettyPrinter().writeValueAsString(out), StandardCharsets.UTF_8);
        assertTrue(total.getOrDefault("interativos", 0L) > 0, "corpus medido");
        assertTrue(!statuses.containsKey("RESOLVED_OTHER"), "nenhum alvo resolvido para outro elemento: " + statuses);
    }
}
