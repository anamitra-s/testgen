package com.company.testgen.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Represents a validation violation detected by L0 (grammar), L1 (static DOM),
 * or other validation layers (§6.2, §6.7).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ValidationViolation(
        String locatorExpression,
        String elementRefId,
        String reasonCode,           // see table §6.7 (e.g., NOT_UNIQUE, BANNED_PATTERN)
        int    matchCount,           // -1 when N/A
        Severity severity,
        String detail
) {
    public ValidationViolation(String locatorExpression, String elementRefId, String reasonCode, Severity severity, String detail) {
        this(locatorExpression, elementRefId, reasonCode, -1, severity, detail);
    }

    public boolean isBlocking() {
        return severity == Severity.BLOCKING;
    }

    public boolean isAdvisory() {
        return severity == Severity.ADVISORY;
    }
}
