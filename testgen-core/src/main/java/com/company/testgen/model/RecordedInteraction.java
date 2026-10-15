package com.company.testgen.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;
import java.util.Objects;

/**
 * Domain model representing a user interaction captured with an element (§6.1, §6.2, §6.3).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RecordedInteraction(
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
    public RecordedInteraction {
        if (attributes == null) {
            attributes = Map.of();
        }
    }

    public static RecordedInteraction fromSnapshot(String stateId, int actionIndex, DomElementSnapshot s) {
        Objects.requireNonNull(s, "snapshot cannot be null");
        return new RecordedInteraction(
                s.elementRefId(),
                stateId,
                actionIndex,
                s.tagName(),
                s.attributes(),
                s.textContent(),
                s.role(),
                s.accessibleName(),
                s.fingerprint()
        );
    }
}
