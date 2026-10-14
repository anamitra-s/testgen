package com.company.testgen.model;

import java.time.Instant;
import java.util.List;

/**
 * Snapshot of a page state (§6.2).
 */
public record PageSnapshot(
        String stateId,                 // distinct DOM state this snapshot represents
        String normalizedUrl,           // query stripped, ids replaced with {id}
        String prunedHtml,              // may be null -> degraded mode
        List<DomElementSnapshot> interactedElements,
        Instant capturedAt
) {
    public boolean hasHtml() {
        return prunedHtml != null && !prunedHtml.isBlank();
    }

    public ElementFingerprint fingerprintFor(String refId) {
        if (interactedElements == null || refId == null) {
            return null;
        }
        return interactedElements.stream()
                .filter(el -> refId.equals(el.elementRefId()))
                .map(DomElementSnapshot::fingerprint)
                .findFirst()
                .orElse(null);
    }
}
