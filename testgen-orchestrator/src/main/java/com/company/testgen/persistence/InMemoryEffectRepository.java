package com.company.testgen.persistence;

import com.company.testgen.idempotency.EffectType;
import org.jdbi.v3.core.Handle;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory test double for {@link EffectRepository}.
 */
public class InMemoryEffectRepository implements EffectRepository {

    private final AtomicLong sequence = new AtomicLong(1L);
    private final Map<String, EffectRecord> storage = new ConcurrentHashMap<>();

    private String key(UUID jobId, EffectType type, String effectKey) {
        return jobId + "::" + type + "::" + effectKey;
    }

    @Override
    public Optional<EffectRecord> find(UUID jobId, EffectType type, String effectKey) {
        return find(null, jobId, type, effectKey);
    }

    @Override
    public Optional<EffectRecord> find(Handle handle, UUID jobId, EffectType type, String effectKey) {
        return Optional.ofNullable(storage.get(key(jobId, type, effectKey)));
    }

    @Override
    public void reserve(UUID jobId, EffectType type, String effectKey, long leaseEpoch) {
        reserve(null, jobId, type, effectKey, leaseEpoch);
    }

    @Override
    public void reserve(Handle handle, UUID jobId, EffectType type, String effectKey, long leaseEpoch) {
        storage.computeIfAbsent(key(jobId, type, effectKey), k -> new EffectRecord(
                sequence.getAndIncrement(),
                jobId,
                type,
                effectKey,
                EffectStatus.PENDING,
                null,
                leaseEpoch,
                0,
                Instant.now(),
                null
        ));
    }

    @Override
    public void confirm(UUID jobId, EffectType type, String effectKey, String externalId) {
        confirm(null, jobId, type, effectKey, externalId);
    }

    @Override
    public void confirm(Handle handle, UUID jobId, EffectType type, String effectKey, String externalId) {
        storage.computeIfPresent(key(jobId, type, effectKey), (k, r) -> new EffectRecord(
                r.id(),
                r.jobId(),
                r.effectType(),
                r.effectKey(),
                EffectStatus.CONFIRMED,
                externalId,
                r.leaseEpoch(),
                r.attempts(),
                r.createdAt(),
                Instant.now()
        ));
    }

    @Override
    public void confirm(EffectRecord record, String externalId) {
        confirm(record.jobId(), record.effectType(), record.effectKey(), externalId);
    }

    @Override
    public void confirm(Handle handle, EffectRecord record, String externalId) {
        confirm(handle, record.jobId(), record.effectType(), record.effectKey(), externalId);
    }

    @Override
    public void incrementAttempts(UUID jobId, EffectType type, String effectKey) {
        incrementAttempts(null, jobId, type, effectKey);
    }

    @Override
    public void incrementAttempts(Handle handle, UUID jobId, EffectType type, String effectKey) {
        storage.computeIfPresent(key(jobId, type, effectKey), (k, r) -> new EffectRecord(
                r.id(),
                r.jobId(),
                r.effectType(),
                r.effectKey(),
                r.status(),
                r.externalId(),
                r.leaseEpoch(),
                r.attempts() + 1,
                r.createdAt(),
                r.confirmedAt()
        ));
    }

    @Override
    public void abandon(UUID jobId, EffectType type, String effectKey, String reason) {
        abandon(null, jobId, type, effectKey, reason);
    }

    @Override
    public void abandon(Handle handle, UUID jobId, EffectType type, String effectKey, String reason) {
        storage.computeIfPresent(key(jobId, type, effectKey), (k, r) -> new EffectRecord(
                r.id(),
                r.jobId(),
                r.effectType(),
                r.effectKey(),
                EffectStatus.ABANDONED,
                r.externalId(),
                r.leaseEpoch(),
                r.attempts(),
                r.createdAt(),
                r.confirmedAt()
        ));
    }

    @Override
    public void abandon(EffectRecord record, String reason) {
        abandon(record.jobId(), record.effectType(), record.effectKey(), reason);
    }

    @Override
    public void abandon(Handle handle, EffectRecord record, String reason) {
        abandon(handle, record.jobId(), record.effectType(), record.effectKey(), reason);
    }

    @Override
    public List<EffectRecord> findPendingOlderThan(Duration age) {
        return findPendingOlderThan(null, age);
    }

    @Override
    public List<EffectRecord> findPendingOlderThan(Handle handle, Duration age) {
        Instant cutoff = Instant.now().minus(age);
        return storage.values().stream()
                .filter(r -> r.status() == EffectStatus.PENDING && r.createdAt().isBefore(cutoff))
                .sorted(Comparator.comparing(EffectRecord::createdAt))
                .toList();
    }

    @Override
    public List<EffectRecord> findByJobId(UUID jobId) {
        return findByJobId(null, jobId);
    }

    @Override
    public List<EffectRecord> findByJobId(Handle handle, UUID jobId) {
        return storage.values().stream()
                .filter(r -> r.jobId().equals(jobId))
                .sorted(Comparator.comparing(EffectRecord::createdAt))
                .toList();
    }

    public void clear() {
        storage.clear();
    }
}
