package com.company.testgen.model;

/**
 * Encapsulates a sanitized/redacted payload alongside its audit report (§6.9, §6.13).
 *
 * @param <T> the type of the payload (e.g. PageSnapshot, ParsedTestPlan)
 */
public record Redacted<T>(T value, RedactionReport report) {

    /**
     * Alias for {@link #value()} to support payload naming conventions.
     */
    public T payload() {
        return value;
    }
}
