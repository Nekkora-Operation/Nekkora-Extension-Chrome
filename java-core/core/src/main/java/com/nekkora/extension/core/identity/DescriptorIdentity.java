package com.nekkora.extension.core.identity;

import com.nekkora.extension.core.analysis.PageAnalysis;
import com.nekkora.extension.core.locator.LocatorSet;
import com.nekkora.extension.core.semantic.ElementKind;
import com.nekkora.extension.core.snapshot.RawNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Identidade TRANSITÓRIA montada só com o descritor de uma etapa (o que o projeto guarda: tipo, tag, nome de interface,
 * id/name e os localizadores gravados) — usada quando o registro não conhece o alvo (outra sessão do navegador). Nunca
 * é registrada.
 *
 * <p>NEKKORA-E2E-002: o localizador CONTEXTUAL gravado nomeia a REGIÃO semântica do alvo ({@code header input[name="search"]}).
 * Se essa região + alvo seleciona hoje exatamente um elemento compatível, a região dele entra na assinatura como
 * discriminador — a cópia com o mesmo name fora do cabeçalho deixa de ser "idêntica". Sem correspondência única, nada
 * é acrescentado (nenhum palpite).
 */
public final class DescriptorIdentity {

    /** Região (tag de marco, com papel opcional) + alvo (tag com #id ou com um atributo), como o ContextualStrategy escreve. */
    private static final Pattern REGION_CSS = Pattern.compile(
            "^(header|nav|main|aside|footer|search|form|section|div)(?:\\[role=\"([a-z]+)\"\\])? ([a-z][a-z0-9-]*)"
                    + "(?:#([A-Za-z][A-Za-z0-9_-]{0,119})|\\[([a-z][a-z0-9-]*)=\"([^\"\\\\]{1,120})\"\\])$");

    private DescriptorIdentity() {
    }

    public static TargetIdentity of(PageAnalysis a, ElementKind kind, String tag, String name, String id, String nameAttr, String testAttr,
                                    List<String> locatorCss) {
        String region = null;
        for (String css : locatorCss) {
            region = regionOf(a, css, kind, tag);
            if (region != null) {
                break;
            }
        }
        IdentitySignature sig = new IdentitySignature(kind.name(), kind.compatibilityFamily(tag), null, tag, name, null, id, nameAttr, testAttr, null,
                null, null, null, null, null, null, null, null, null, region, null, null, null);
        Map<String, List<String>> history = new LinkedHashMap<>();
        history.put("id", new ArrayList<>(java.util.Collections.singletonList(id)));
        history.put("name", new ArrayList<>(java.util.Collections.singletonList(nameAttr)));
        var doc = a.model().snapshot().document();
        return new TargetIdentity("tgt-transient", TargetIdentity.SCHEMA_VERSION, java.time.Instant.now().toString(), IdentityFactory.originOf(doc), sig,
                region == null ? Set.of() : Set.of(IdentityFeature.REGION), 0, new LocatorSet(null, null, List.of(), List.of()), null, null, null, history,
                List.of(), List.of(), null);
    }

    /** Chave da região do ÚNICO elemento compatível que a região + alvo do localizador gravado selecionam hoje; senão {@code null}. */
    static String regionOf(PageAnalysis a, String css, ElementKind kind, String tag) {
        String only = uniqueByRegionLocator(a, css, kind, tag);
        return only == null ? null : new SignatureExtractor().extract(only, a.graph()).region();
    }

    /**
     * O ÚNICO elemento compatível (mesma família do alvo) que a região + alvo de um localizador CONTEXTUAL gravado
     * seleciona hoje; {@code null} se o localizador não tem essa forma ou seleciona zero ou mais de um.
     */
    public static String uniqueByRegionLocator(PageAnalysis a, String css, ElementKind kind, String tag) {
        Matcher m = css == null ? null : REGION_CSS.matcher(css);
        if (m == null || !m.matches()) {
            return null;
        }
        String family = kind.compatibilityFamily(tag);
        RawNode only = null;
        for (RawNode n : a.model().nodes()) {
            if (!m.group(3).equals(n.locatorTag()) || !family.equals(a.semantics().get(n.id()).kind().compatibilityFamily(n.tag()))) {
                continue;
            }
            boolean self = m.group(4) != null ? m.group(4).equals(n.attr("id")) : m.group(6).equals(n.attr(m.group(5)));
            if (!self || !inRegion(a, n, m.group(1), m.group(2))) {
                continue;
            }
            if (only != null) {
                return null; // mais de um: a região não decide
            }
            only = n;
        }
        return only == null ? null : only.id();
    }

    private static boolean inRegion(PageAnalysis a, RawNode n, String regionTag, String role) {
        for (RawNode anc : a.model().ancestorsInScope(n)) {
            if (regionTag.equals(anc.locatorTag()) && (role == null || role.equals(anc.attr("role")))) {
                return true;
            }
        }
        return false;
    }
}
