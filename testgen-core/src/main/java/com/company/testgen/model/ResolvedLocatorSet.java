package com.company.testgen.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Set of resolved locators returned by the Resolve phase (§6.2, §6.10, §6.13).
 * Enforces locator freezing on retries (FR-18, §6.13).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ResolvedLocatorSet(List<ResolvedLocator> locators) {

    public static final ResolvedLocatorSet EMPTY = new ResolvedLocatorSet(List.of());

    public ResolvedLocatorSet {
        if (locators == null) {
            locators = List.of();
        } else {
            locators = List.copyOf(locators);
        }
    }

    public static ResolvedLocatorSet empty() {
        return EMPTY;
    }

    public static ResolvedLocatorSet of(ResolvedLocator... locators) {
        if (locators == null || locators.length == 0) {
            return empty();
        }
        return new ResolvedLocatorSet(List.of(locators));
    }

    public static ResolvedLocatorSet of(List<ResolvedLocator> locators) {
        if (locators == null || locators.isEmpty()) {
            return empty();
        }
        return new ResolvedLocatorSet(locators);
    }

    /**
     * Enforces the locator freeze in CODE (§6.13):
     * If the model attempts to change a locator that was already accepted on a prior attempt,
     * the proposed change is discarded and the accepted value is retained.
     *
     * @param proposed new locators proposed by the LLM
     * @return merged set keeping already accepted locators
     */
    public ResolvedLocatorSet mergeKeepingAccepted(ResolvedLocatorSet proposed) {
        if (proposed == null || proposed.locators().isEmpty()) {
            return this;
        }
        if (this.locators.isEmpty()) {
            return proposed;
        }

        Map<String, ResolvedLocator> merged = new LinkedHashMap<>();
        // Accepted locators are inserted first
        for (ResolvedLocator accepted : this.locators) {
            if (accepted.elementRefId() != null) {
                merged.put(accepted.elementRefId(), accepted);
            }
        }

        // Add proposed locators only if not already accepted
        for (ResolvedLocator p : proposed.locators()) {
            if (p.elementRefId() != null) {
                merged.putIfAbsent(p.elementRefId(), p);
            } else {
                // If elementRefId is null, append directly
                merged.put(p.expression() != null ? p.expression() : p.value(), p);
            }
        }

        return new ResolvedLocatorSet(new ArrayList<>(merged.values()));
    }

    /**
     * Retains only locators that passed validation (i.e. have no BLOCKING violations) (§6.13).
     *
     * @param validation the validation result from L0/L1
     * @return set of locators that have no blocking violations
     */
    public ResolvedLocatorSet retainingPassing(ValidationResult validation) {
        if (validation == null || validation.passed()) {
            return this;
        }

        Set<String> blockingRefs = validation.blocking().stream()
                .map(ValidationViolation::elementRefId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Set<String> blockingExpressions = validation.blocking().stream()
                .map(ValidationViolation::locatorExpression)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        List<ResolvedLocator> passing = this.locators.stream()
                .filter(l -> (l.elementRefId() == null || !blockingRefs.contains(l.elementRefId()))
                        && (l.expression() == null || !blockingExpressions.contains(l.expression())))
                .toList();

        return new ResolvedLocatorSet(passing);
    }

    public Optional<ResolvedLocator> findByElementRefId(String elementRefId) {
        if (elementRefId == null) {
            return Optional.empty();
        }
        return locators.stream()
                .filter(l -> elementRefId.equals(l.elementRefId()))
                .findFirst();
    }

    public int size() {
        return locators.size();
    }

    public boolean isEmpty() {
        return locators.isEmpty();
    }
}
