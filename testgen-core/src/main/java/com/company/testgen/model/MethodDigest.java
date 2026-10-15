package com.company.testgen.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Digest of an existing page object method (§6.11).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MethodDigest(
        String name,
        String signature,
        String javadoc,
        List<String> locatorLiterals
) {
    public MethodDigest {
        if (locatorLiterals == null) {
            locatorLiterals = List.of();
        } else {
            locatorLiterals = List.copyOf(locatorLiterals);
        }
    }

    public MethodDigest(String name, String signature) {
        this(name, signature, null, List.of());
    }
}
