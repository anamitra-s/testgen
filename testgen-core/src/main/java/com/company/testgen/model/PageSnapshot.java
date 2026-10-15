package com.company.testgen.model;

import java.time.Instant;
import java.util.List;

/**
 * Snapshot of a page state (§6.2, §6.3, FR-07).
 */
public record PageSnapshot(
        String stateId,                 // distinct DOM state this snapshot represents
        String normalizedUrl,           // query stripped, ids replaced with {id}
        String prunedHtml,              // may be null -> degraded mode
        List<DomElementSnapshot> interactedElements,
        Instant capturedAt,
        boolean truncated               // true when DOM was capped/truncated
) {
    public PageSnapshot(String stateId,
                        String normalizedUrl,
                        String prunedHtml,
                        List<DomElementSnapshot> interactedElements,
                        Instant capturedAt) {
        this(stateId, normalizedUrl, prunedHtml, interactedElements, capturedAt, false);
    }

    public boolean hasHtml() {
        return prunedHtml != null && !prunedHtml.isBlank();
    }

    public PageSnapshot withPrunedHtml(String newPrunedHtml, boolean isTruncated) {
        return new PageSnapshot(stateId, normalizedUrl, newPrunedHtml, interactedElements, capturedAt, isTruncated);
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
