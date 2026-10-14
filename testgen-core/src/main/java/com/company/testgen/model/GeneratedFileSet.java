package com.company.testgen.model;

import java.util.List;

/**
 * A set of generated test files and page objects (§6.10, §6.12).
 */
public record GeneratedFileSet(List<GeneratedFile> files) {
    public GeneratedFileSet {
        if (files == null) {
            files = List.of();
        }
    }
}
