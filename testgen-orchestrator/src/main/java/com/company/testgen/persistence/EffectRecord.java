package com.company.testgen.persistence;

import com.company.testgen.idempotency.EffectType;

import java.time.Instant;
import java.util.UUID;

/**
 * Persisted row representing an entry in the external side-effect ledger {@code external_effects} (§6.14, §7.5).
 */
public record EffectRecord(
        Long id,
        UUID jobId,
        EffectType effectType,
        String effectKey,
        EffectStatus status,
        String externalId,
        long leaseEpoch,
        int attempts,
        Instant createdAt,
        Instant confirmedAt
) {
    public EffectRecord(UUID jobId, EffectType effectType, String effectKey, long leaseEpoch) {
        this(null, jobId, effectType, effectKey, EffectStatus.PENDING, null, leaseEpoch, 0, Instant.now(), null);
    }

    public EffectRecord(UUID jobId, com.company.testgen.persistence.EffectType effectType, String effectKey, long leaseEpoch) {
        this(null, jobId, effectType.toIdempotencyType(), effectKey, EffectStatus.PENDING, null, leaseEpoch, 0, Instant.now(), null);
    }
}
