package com.company.testgen.model.contract;

import com.company.testgen.model.ElementFingerprint;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * Interacted element object as captured in contract v2 {@code recorded-elements.json} (§6.3).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InteractionPayload(
        String elementRefId,
        String stateId,
        Integer actionIndex,
        String tagName,
        Map<String, String> attributes,
        String textContent,
        String role,
        String accessibleName,
        ElementFingerprint fingerprint
) {
    public InteractionPayload {
        if (attributes == null) {
            attributes = Map.of();
        }
    }
}
