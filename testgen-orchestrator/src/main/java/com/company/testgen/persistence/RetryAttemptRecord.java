package com.company.testgen.persistence;

import java.time.Instant;
import java.util.UUID;

/**
 * Audit record of a retry attempt for a pipeline phase (§6.13, §6.14).
 * Backed by the {@code retry_attempts} table.
 */
public record RetryAttemptRecord(
        Long id,
        UUID jobId,
        int attemptNo,
        String phase,
        Boolean passed,
        String feedbackSent,
        Instant startedAt,
        Instant finishedAt
) {
    public RetryAttemptRecord(UUID jobId, int attemptNo, String phase) {
        this(null, jobId, attemptNo, phase, null, null, Instant.now(), null);
    }
}
