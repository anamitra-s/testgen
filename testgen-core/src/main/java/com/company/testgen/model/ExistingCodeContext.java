package com.company.testgen.model;

import java.util.List;

/**
 * Encapsulates existing page objects and locators analyzed for the target area (§6.11).
 */
public record ExistingCodeContext(
        String targetArea,
        List<String> pageObjectsJson
) {
    public static final ExistingCodeContext EMPTY = new ExistingCodeContext("", List.of());

    public ExistingCodeContext {
        if (pageObjectsJson == null) {
            pageObjectsJson = List.of();
        }
    }

    public ExistingCodeContext(String targetArea) {
        this(targetArea, List.of());
    }
}
