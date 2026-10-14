package com.company.testgen.model;

import java.util.List;

/**
 * Set of resolved locators returned by the Resolve phase (§6.2, §6.10).
 */
public record ResolvedLocatorSet(List<ResolvedLocator> locators) {
    public ResolvedLocatorSet {
        if (locators == null) {
            locators = List.of();
        }
    }
}
