package com.company.testgen.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Resolved locator domain model (§6.1, §6.2, §6.10).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ResolvedLocator(
        String elementRefId,
        LocatorStrategy strategy,
        String value,
        String role,                 // populated only for ROLE_NAME
        String expression            // rendered Playwright-Java call, derived not free-text
) {
    public ResolvedLocator(String elementRefId, LocatorStrategy strategy, String value) {
        this(elementRefId, strategy, value, null, null);
    }

    public ResolvedLocator(String elementRefId, LocatorStrategy strategy, String value, String role) {
        this(elementRefId, strategy, value, role, null);
    }
}
