package com.company.testgen.persistence;

import com.company.testgen.model.Severity;
import com.company.testgen.model.ValidationViolation;

/**
 * Persisted row representing a validation violation from {@code validation_results} (§6.13, §6.14).
 */
public record ValidationResultRecord(
        Long id,
        Long attemptId,
        String layer,
        String elementRefId,
        String locatorExpression,
        String reasonCode,
        Integer matchCount,
        Severity severity,
        String detail
) {
    public ValidationViolation toViolation() {
        return new ValidationViolation(
                locatorExpression,
                elementRefId,
                reasonCode,
                matchCount != null ? matchCount : -1,
                severity,
                detail
        );
    }

    public static ValidationResultRecord fromViolation(Long attemptId, String layer, ValidationViolation v) {
        return new ValidationResultRecord(
                null,
                attemptId,
                layer != null ? layer : "L0_L1",
                v.elementRefId(),
                v.locatorExpression(),
                v.reasonCode(),
                v.matchCount(),
                v.severity(),
                v.detail()
        );
    }
}
