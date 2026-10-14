package com.nekkora.extension.core.protection;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Catálogo versionado de assinaturas de tecnologias de proteção ({@code contracts/protection-signatures.json}).
 * O núcleo recebe o catálogo já desserializado (o motor de domínio não depende de biblioteca JSON); a validação
 * estrutural é feita aqui para que um catálogo inválido nunca chegue ao detector.
 *
 * @param catalogVersion    versão do catálogo
 * @param reviewedAt        data da revisão
 * @param genericIndicators indicadores genéricos (nunca identificam fornecedor)
 * @param signatures        assinaturas por fornecedor/produto
 */
public record SignatureCatalog(String catalogVersion, String reviewedAt, List<Indicator> genericIndicators, List<Signature> signatures) {

    public static final Set<String> KINDS = Set.of("SCRIPT_URL", "FRAME_URL", "RESOURCE_URL", "DOM_CLASS", "DOM_ID", "DOM_ATTRIBUTE",
            "FORM_FIELD", "TITLE", "TEXT");
    public static final Set<String> STRENGTHS = Set.of("STRONG", "MEDIUM", "WEAK");
    public static final Set<String> SIGNALS = Set.of("TECHNOLOGY", "WIDGET", "CHALLENGE_ACTIVE", "CHALLENGE_PAGE", "BLOCKED",
            "COMPLETED_FIELD", "MENTION");
    public static final Set<String> CONFIDENCES = Set.of("HIGH", "MEDIUM", "LOW");

    public SignatureCatalog {
        genericIndicators = genericIndicators == null ? List.of() : List.copyOf(genericIndicators);
        signatures = signatures == null ? List.of() : List.copyOf(signatures);
    }

    /**
     * Assinatura de um produto.
     *
     * @param basis         {@code OFFICIAL_DOCUMENTATION} ou {@code THIRD_PARTY_OBSERVATION}
     * @param maxConfidence teto de confiança (assinaturas não confirmadas pelo fornecedor são limitadas)
     */
    public record Signature(String id, String provider, String product, String category, String signatureVersion,
                            String reviewedAt, String basis, List<String> sources, String maxConfidence, List<Indicator> indicators) {
        public Signature {
            sources = sources == null ? List.of() : List.copyOf(sources);
            indicators = indicators == null ? List.of() : List.copyOf(indicators);
        }
    }

    /**
     * Indicador observável.
     *
     * @param kind             {@link #KINDS}
     * @param host             expressão regular do host (indicadores de URL)
     * @param path             expressão regular do caminho (indicadores de URL)
     * @param token            classe/id/atributo/campo/texto (expressão regular, exceto DOM_CLASS e DOM_ATTRIBUTE: literal)
     * @param requiresQueryKey nome de parâmetro que deve existir na URL original (valor nunca usado)
     * @param requiresFragment trecho exigido no fragmento do {@code src} declarado do iframe
     * @param strength         {@code STRONG}, {@code MEDIUM} ou {@code WEAK}
     * @param signal           o que o indicador evidencia ({@link #SIGNALS})
     */
    public record Indicator(String id, String kind, String host, String path, String token, String requiresQueryKey,
                            String requiresFragment, String strength, String signal, String description) {
    }

    /** Valida o catálogo; devolve a lista de problemas (vazia = válido). */
    public List<String> validate() {
        List<String> problems = new java.util.ArrayList<>();
        if (catalogVersion == null || catalogVersion.isBlank()) {
            problems.add("catalogVersion ausente");
        }
        Set<String> ids = new java.util.HashSet<>();
        for (Indicator i : genericIndicators) {
            check(i, problems, ids);
        }
        for (Signature s : signatures) {
            if (s.id() == null || s.provider() == null || s.product() == null || s.category() == null) {
                problems.add("assinatura incompleta: " + s.id());
            }
            if (!CONFIDENCES.contains(s.maxConfidence())) {
                problems.add(s.id() + ": maxConfidence inválido " + s.maxConfidence());
            }
            if (!"OFFICIAL_DOCUMENTATION".equals(s.basis()) && !"THIRD_PARTY_OBSERVATION".equals(s.basis())) {
                problems.add(s.id() + ": basis inválido " + s.basis());
            }
            if ("THIRD_PARTY_OBSERVATION".equals(s.basis()) && "HIGH".equals(s.maxConfidence())) {
                problems.add(s.id() + ": assinatura não confirmada pelo fornecedor não pode ter confiança máxima HIGH");
            }
            if (s.indicators().isEmpty()) {
                problems.add(s.id() + ": sem indicadores");
            }
            for (Indicator i : s.indicators()) {
                check(i, problems, ids);
            }
        }
        return problems;
    }

    private static void check(Indicator i, List<String> problems, Set<String> ids) {
        if (i.id() == null || !ids.add(i.id())) {
            problems.add("indicador sem id ou duplicado: " + i.id());
        }
        if (!KINDS.contains(i.kind())) {
            problems.add(i.id() + ": kind inválido " + i.kind());
        }
        if (!STRENGTHS.contains(i.strength())) {
            problems.add(i.id() + ": strength inválido " + i.strength());
        }
        if (!SIGNALS.contains(i.signal())) {
            problems.add(i.id() + ": signal inválido " + i.signal());
        }
        for (String re : new String[]{i.host(), i.path(), "TEXT".equals(i.kind()) || "DOM_ID".equals(i.kind())
                || "FORM_FIELD".equals(i.kind()) || "TITLE".equals(i.kind()) ? i.token() : null}) {
            if (re != null) {
                try {
                    Pattern.compile(re);
                } catch (RuntimeException e) {
                    problems.add(i.id() + ": expressão inválida " + re);
                }
            }
        }
    }
}
