package com.company.testgen.phases;

import java.util.Objects;

/**
 * Exception thrown when pipeline cannot proceed automatically and requires human review (§6.12, §9.2).
 */
public class EscalationRequired extends RuntimeException {

    private final String reasonCode;
    private final String report;

    public EscalationRequired(String reasonCode, String report) {
        super(reasonCode + ": " + (report != null ? report : ""));
        this.reasonCode = Objects.requireNonNull(reasonCode, "reasonCode must not be null");
        this.report = report != null ? report : "";
    }

    public String reasonCode() {
        return reasonCode;
    }

    public String report() {
        return report;
    }
}
