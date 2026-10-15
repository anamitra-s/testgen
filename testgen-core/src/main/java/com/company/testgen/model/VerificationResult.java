package com.company.testgen.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Result of L2 dynamic test verification execution (§6.2, §6.8, §6.14).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VerificationResult(
        String outcome,
        String skipReason,
        int durationMs,
        String failureTail,
        boolean blocking
) {
    public VerificationResult(String outcome, String skipReason, int durationMs, String failureTail) {
        this(outcome, skipReason, durationMs, failureTail, false);
    }

    public static VerificationResult passed(int durationMs) {
        return new VerificationResult("PASSED", null, durationMs, null, false);
    }

    public static VerificationResult passedAfterFlake(int durationMs) {
        return new VerificationResult("PASSED_AFTER_FLAKE", null, durationMs, null, false);
    }

    public static VerificationResult skipped(String skipReason) {
        return new VerificationResult("SKIPPED", skipReason, 0, null, false);
    }

    public static VerificationResult failed(String failureTail) {
        return new VerificationResult("FAILED", null, 0, failureTail, false);
    }

    public static VerificationResult failed(String failureTail, int durationMs, boolean blocking) {
        return new VerificationResult("FAILED", null, durationMs, failureTail, blocking);
    }

    public boolean isPassed() {
        return "PASSED".equals(outcome) || "PASSED_AFTER_FLAKE".equals(outcome);
    }

    public boolean isFailure() {
        return "FAILED".equals(outcome);
    }

    public boolean isSkipped() {
        return "SKIPPED".equals(outcome);
    }
}
