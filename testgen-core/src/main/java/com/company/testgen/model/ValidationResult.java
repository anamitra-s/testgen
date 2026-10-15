package com.company.testgen.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of locator validation (§6.2, §6.7, §6.13).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ValidationResult(List<ValidationViolation> violations) {

    public static final ValidationResult EMPTY = new ValidationResult(List.of());

    public ValidationResult {
        if (violations == null) {
            violations = List.of();
        }
    }

    public boolean passed() {
        return violations.stream().noneMatch(v -> v.severity() == Severity.BLOCKING);
    }

    public List<ValidationViolation> blocking() {
        return violations.stream()
                .filter(v -> v.severity() == Severity.BLOCKING)
                .toList();
    }

    public List<ValidationViolation> advisory() {
        return violations.stream()
                .filter(v -> v.severity() == Severity.ADVISORY)
                .toList();
    }

    public static ValidationResult empty() {
        return EMPTY;
    }

    public static ValidationResult of(ValidationViolation... violations) {
        if (violations == null || violations.length == 0) {
            return empty();
        }
        return new ValidationResult(List.of(violations));
    }

    public static ValidationResult of(List<ValidationViolation> violations) {
        if (violations == null || violations.isEmpty()) {
            return empty();
        }
        return new ValidationResult(List.copyOf(violations));
    }

    public static ValidationResult merge(ValidationResult... results) {
        if (results == null || results.length == 0) {
            return empty();
        }
        List<ValidationViolation> combined = new ArrayList<>();
        for (ValidationResult r : results) {
            if (r != null && r.violations() != null) {
                combined.addAll(r.violations());
            }
        }
        return new ValidationResult(List.copyOf(combined));
    }
}
