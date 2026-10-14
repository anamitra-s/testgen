package com.company.testgen.model;

/**
 * Resolved locator domain model (§6.2, §6.10).
 */
public record ResolvedLocator(
        String elementRefId,
        LocatorStrategy strategy,
        String value,
        String role,                 // populated only for ROLE_NAME
        String expression            // rendered Playwright-Java call, derived not free-text
) {}
