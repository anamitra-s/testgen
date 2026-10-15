package com.company.testgen.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Digest of an existing page object locator declaration (§6.11).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LocatorDigest(
        String fieldName,
        String initializer
) {}
