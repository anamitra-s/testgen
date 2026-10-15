package com.company.testgen.persistence;

import com.company.testgen.model.VerificationResult;

import java.time.Instant;
import java.util.UUID;

/**
 * Persisted row representing an L2 verification execution from {@code verification_runs} (§6.8, §6.13, §6.14).
 */
public record VerificationRecord(
        Long id,
        UUID jobId,
        String outcome,
        String skipReason,
        Integer durationMs,
        String failureTail,
        boolean blocking,
        Instant createdAt
) {
    public VerificationResult toResult() {
        return new VerificationResult(
                outcome,
                skipReason,
                durationMs != null ? durationMs : 0,
                failureTail,
                blocking
        );
    }

    public static VerificationRecord fromResult(UUID jobId, VerificationResult r) {
        return new VerificationRecord(
                null,
                jobId,
                r.outcome(),
                r.skipReason(),
                r.durationMs(),
                r.failureTail(),
                r.blocking(),
                Instant.now()
        );
    }
}
