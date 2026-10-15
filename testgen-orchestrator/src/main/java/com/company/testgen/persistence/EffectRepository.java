package com.company.testgen.persistence;

import com.company.testgen.idempotency.EffectType;
import org.jdbi.v3.core.Handle;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository interface for managing the exactly-once external side-effect ledger {@code external_effects} (§6.14, §7.5).
 */
public interface EffectRepository {

    Optional<EffectRecord> find(UUID jobId, EffectType type, String effectKey);

    Optional<EffectRecord> find(Handle handle, UUID jobId, EffectType type, String effectKey);

    void reserve(UUID jobId, EffectType type, String effectKey, long leaseEpoch);

    void reserve(Handle handle, UUID jobId, EffectType type, String effectKey, long leaseEpoch);

    void confirm(UUID jobId, EffectType type, String effectKey, String externalId);

    void confirm(Handle handle, UUID jobId, EffectType type, String effectKey, String externalId);

    void confirm(EffectRecord record, String externalId);

    void confirm(Handle handle, EffectRecord record, String externalId);

    void incrementAttempts(UUID jobId, EffectType type, String effectKey);

    void incrementAttempts(Handle handle, UUID jobId, EffectType type, String effectKey);

    void abandon(UUID jobId, EffectType type, String effectKey, String reason);

    void abandon(Handle handle, UUID jobId, EffectType type, String effectKey, String reason);

    void abandon(EffectRecord record, String reason);

    void abandon(Handle handle, EffectRecord record, String reason);

    List<EffectRecord> findPendingOlderThan(Duration age);

    List<EffectRecord> findPendingOlderThan(Handle handle, Duration age);

    List<EffectRecord> findByJobId(UUID jobId);

    List<EffectRecord> findByJobId(Handle handle, UUID jobId);

    // Backward-compatibility bridge for persistence.EffectType
    default Optional<EffectRecord> find(UUID jobId, com.company.testgen.persistence.EffectType type, String effectKey) {
        return find(jobId, type.toIdempotencyType(), effectKey);
    }

    default Optional<EffectRecord> find(Handle handle, UUID jobId, com.company.testgen.persistence.EffectType type, String effectKey) {
        return find(handle, jobId, type.toIdempotencyType(), effectKey);
    }

    default void reserve(UUID jobId, com.company.testgen.persistence.EffectType type, String effectKey, long leaseEpoch) {
        reserve(jobId, type.toIdempotencyType(), effectKey, leaseEpoch);
    }

    default void reserve(Handle handle, UUID jobId, com.company.testgen.persistence.EffectType type, String effectKey, long leaseEpoch) {
        reserve(handle, jobId, type.toIdempotencyType(), effectKey, leaseEpoch);
    }

    default void confirm(UUID jobId, com.company.testgen.persistence.EffectType type, String effectKey, String externalId) {
        confirm(jobId, type.toIdempotencyType(), effectKey, externalId);
    }

    default void confirm(Handle handle, UUID jobId, com.company.testgen.persistence.EffectType type, String effectKey, String externalId) {
        confirm(handle, jobId, type.toIdempotencyType(), effectKey, externalId);
    }

    default void incrementAttempts(UUID jobId, com.company.testgen.persistence.EffectType type, String effectKey) {
        incrementAttempts(jobId, type.toIdempotencyType(), effectKey);
    }

    default void incrementAttempts(Handle handle, UUID jobId, com.company.testgen.persistence.EffectType type, String effectKey) {
        incrementAttempts(handle, jobId, type.toIdempotencyType(), effectKey);
    }

    default void abandon(UUID jobId, com.company.testgen.persistence.EffectType type, String effectKey, String reason) {
        abandon(jobId, type.toIdempotencyType(), effectKey, reason);
    }

    default void abandon(Handle handle, UUID jobId, com.company.testgen.persistence.EffectType type, String effectKey, String reason) {
        abandon(handle, jobId, type.toIdempotencyType(), effectKey, reason);
    }
}
