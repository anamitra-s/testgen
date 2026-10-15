package com.company.testgen.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Digest of an existing page object class (§6.11).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PageObjectDigest(
        String className,
        String relativePath,
        List<MethodDigest> methods,
        List<LocatorDigest> locators
) {
    public PageObjectDigest {
        if (methods == null) {
            methods = List.of();
        } else {
            methods = List.copyOf(methods);
        }
        if (locators == null) {
            locators = List.of();
        } else {
            locators = List.copyOf(locators);
        }
    }

    public PageObjectDigest(String className, String relativePath) {
        this(className, relativePath, List.of(), List.of());
    }
}
