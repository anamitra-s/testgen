package com.company.testgen.idempotency;

import java.time.Instant;
import java.util.UUID;

/**
 * Representation of an active or expired job lease (§7.4).
 */
public record Lease(
        UUID jobId,
        String owner,
        long epoch,
        Instant expiresAt
) {
    public boolean isExpired() {
        return expiresAt == null || expiresAt.isBefore(Instant.now());
    }

    public boolean isCurrent(long candidateEpoch) {
        return this.epoch == candidateEpoch && !isExpired();
    }
}
