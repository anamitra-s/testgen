package com.company.testgen.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Objects;

/**
 * Encapsulates existing page objects, methods, and locators analyzed for the target area (§6.1, §6.2, §6.11).
 * The digest — not raw source — goes into the prompt as context for reuse.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExistingCodeContext(
        String targetArea,
        List<PageObjectDigest> pageObjects
) {
    public static final ExistingCodeContext EMPTY = new ExistingCodeContext("", List.of());

    public ExistingCodeContext {
        if (targetArea == null) {
            targetArea = "";
        }
        if (pageObjects == null) {
            pageObjects = List.of();
        } else {
            pageObjects = List.copyOf(pageObjects);
        }
    }

    public ExistingCodeContext(List<PageObjectDigest> pageObjects) {
        this("", pageObjects);
    }

    public ExistingCodeContext(String targetArea) {
        this(targetArea, List.of());
    }

    public static ExistingCodeContext empty() {
        return EMPTY;
    }

    public List<PageObjectDigest> digests() {
        return pageObjects;
    }

    public boolean isEmpty() {
        return pageObjects.isEmpty();
    }

    public List<String> reusedMethods() {
        if (pageObjects == null) {
            return List.of();
        }
        return pageObjects.stream()
                .filter(Objects::nonNull)
                .flatMap(po -> (po.methods() != null ? po.methods().stream() : java.util.stream.Stream.<MethodDigest>empty())
                        .map(m -> po.className() + "." + m.name() + "()"))
                .toList();
    }
}
