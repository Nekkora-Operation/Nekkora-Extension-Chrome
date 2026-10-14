package com.nekkora.extension.core.identity;

import com.nekkora.extension.core.locator.LocatorSet;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Identidade LÓGICA de um alvo. Não se associa permanentemente a um nó DOM: {@code lastNodeId} é
 * apenas a última referência temporária conhecida e pode se tornar obsoleta a qualquer re-renderização.
 *
 * @param targetId          identificador lógico estável ({@code tgt-...})
 * @param schemaVersion     versão do contrato
 * @param registeredAt      instante do registro
 * @param origin            origem + caminho da página no registro
 * @param signature         características observadas no registro
 * @param discriminators    características que diferenciaram o alvo dos elementos parecidos no registro
 * @param lookalikeCount    quantidade de elementos parecidos (mesma família de compatibilidade e nome) no registro
 * @param locators          localizadores gerados
 * @param lastNodeId        última referência temporária ao nó
 * @param lastPageSessionId sessão de página da última referência
 * @param lastCaptureId     captura da última resolução
 * @param attributeHistory  valores observados de atributos voláteis em cada resolução (id, name)
 * @param warnings          alertas do registro (ex.: alvo indistinguível)
 * @param frameChain        identidades lógicas dos frames a atravessar (CHROME-003; vazia no documento principal,
 *                          {@code null} em registros anteriores)
 */
public record TargetIdentity(
        String targetId,
        String schemaVersion,
        String registeredAt,
        String origin,
        IdentitySignature signature,
        Set<IdentityFeature> discriminators,
        int lookalikeCount,
        LocatorSet locators,
        String lastNodeId,
        String lastPageSessionId,
        String lastCaptureId,
        Map<String, List<String>> attributeHistory,
        List<String> warnings,
        List<com.nekkora.extension.core.frames.FrameIdentity> frameChain) {

    /** Construtor CHROME-001/002 (sem cadeia de frames). */
    public TargetIdentity(String targetId, String schemaVersion, String registeredAt, String origin, IdentitySignature signature,
                          Set<IdentityFeature> discriminators, int lookalikeCount, LocatorSet locators, String lastNodeId,
                          String lastPageSessionId, String lastCaptureId, Map<String, List<String>> attributeHistory,
                          List<String> warnings) {
        this(targetId, schemaVersion, registeredAt, origin, signature, discriminators, lookalikeCount, locators, lastNodeId,
                lastPageSessionId, lastCaptureId, attributeHistory, warnings, null);
    }

    public static final String SCHEMA_VERSION = "0.1.0";

    public TargetIdentity {
        discriminators = Set.copyOf(discriminators);
        attributeHistory = Map.copyOf(attributeHistory);
        warnings = List.copyOf(warnings);
        frameChain = frameChain == null ? null : List.copyOf(frameChain);
    }

    public boolean indistinguishableAtRegistration() {
        return warnings.stream().anyMatch(w -> w.startsWith("INDISTINGUISHABLE"));
    }
}
