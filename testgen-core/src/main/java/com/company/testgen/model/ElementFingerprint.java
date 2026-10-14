package com.company.testgen.model;

import java.util.Objects;

/**
 * DOM capture (contract v2) element fingerprint (§6.2).
 */
public record ElementFingerprint(
        String tagName,
        String role,                  // ARIA role, computed
        String accessibleName,        // computed accessible name
        String normalizedText,        // trimmed, whitespace-collapsed, <= 120 chars
        int    ordinalAmongRolePeers, // document-order index among same-role elements
        String domPathHash            // sha256 of ancestor tag+ordinal chain (structure only)
) {
    /** Identity: semantic fields only. Structural drift is advisory, not blocking. */
    public boolean matchesIdentity(ElementFingerprint other) {
        if (other == null) {
            return false;
        }
        return tagName != null && tagName.equalsIgnoreCase(other.tagName)
            && Objects.equals(role, other.role)
            && Objects.equals(accessibleName, other.accessibleName);
    }

    public String describe() {
        return tagName + "[role=" + role + ", name=" + accessibleName + "]";
    }
}
