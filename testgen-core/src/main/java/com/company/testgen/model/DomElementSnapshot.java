package com.company.testgen.model;

import java.util.Map;

/**
 * Snapshot of an interacted DOM element (§6.2).
 */
public record DomElementSnapshot(
        String elementRefId,
        String tagName,
        Map<String, String> attributes,   // post-redaction, allow-listed only
        String textContent,
        String role,
        String accessibleName,
        ElementFingerprint fingerprint
) {}
