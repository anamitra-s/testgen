package com.company.testgen.persistence;

import com.company.testgen.idempotency.EffectType;
import com.company.testgen.model.JobRecord;
import com.company.testgen.model.RedactionReport;
import com.company.testgen.util.Json;
import org.jdbi.v3.core.Handle;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory thread-safe implementation of JobRepository for unit testing and local pipelines.
 */
public class InMemoryJobRepository implements JobRepository {

    private final ConcurrentHashMap<UUID, JobRecord> store = new ConcurrentHashMap<>();
    private EffectRepository effectRepository;

    public InMemoryJobRepository() {
    }

    public InMemoryJobRepository(EffectRepository effectRepository) {
        this.effectRepository = effectRepository;
    }

    public void setEffectRepository(EffectRepository effectRepository) {
        this.effectRepository = effectRepository;
    }

    @Override
    public synchronized void insert(JobRecord job) {
        Objects.requireNonNull(job, "job cannot be null");
        if (job.idempotencyKey() != null) {
            for (JobRecord existing : store.values()) {
                if (job.idempotencyKey().equals(existing.idempotencyKey())) {
                    throw new IllegalStateException("duplicate key value violates unique constraint on idempotency_key: " + job.idempotencyKey());
                }
            }
        }
        store.put(job.id(), job);
    }

    @Override
    public void insert(Handle handle, JobRecord job) {
        insert(job);
    }

    @Override
    public Optional<JobRecord> findById(UUID id) {
        if (id == null) return Optional.empty();
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public Optional<JobRecord> findById(Handle handle, UUID id) {
        return findById(id);
    }

    @Override
    public Optional<JobRecord> findByIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null) return Optional.empty();
        return store.values().stream()
                .filter(j -> idempotencyKey.equals(j.idempotencyKey()))
                .findFirst();
    }

    @Override
    public Optional<JobRecord> findByIdempotencyKey(Handle handle, String idempotencyKey) {
        return findByIdempotencyKey(idempotencyKey);
    }

    @Override
    public boolean update(JobRecord job) {
        Objects.requireNonNull(job, "job cannot be null");
        return store.replace(job.id(), job) != null;
    }

    @Override
    public boolean update(Handle handle, JobRecord job) {
        return update(job);
    }

    @Override
    public boolean deleteById(UUID id) {
        if (id == null) return false;
        return store.remove(id) != null;
    }

    @Override
    public boolean deleteById(Handle handle, UUID id) {
        return deleteById(id);
    }

    @Override
    public List<JobRecord> findAll() {
        return store.values().stream()
                .sorted(Comparator.comparing(JobRecord::createdAt).reversed())
                .toList();
    }

    @Override
    public List<JobRecord> findAll(Handle handle) {
        return findAll();
    }

    @Override
    public List<JobRecord> findByState(String state) {
        return store.values().stream()
                .filter(j -> Objects.equals(j.state(), state))
                .sorted(Comparator.comparing(JobRecord::createdAt).reversed())
                .toList();
    }

    @Override
    public List<JobRecord> findByState(Handle handle, String state) {
        return findByState(state);
    }

    @Override
    public List<JobRecord> findByTargetArea(String targetArea) {
        return store.values().stream()
                .filter(j -> Objects.equals(j.targetArea(), targetArea))
                .sorted(Comparator.comparing(JobRecord::createdAt).reversed())
                .toList();
    }

    @Override
    public List<JobRecord> findByTargetArea(Handle handle, String targetArea) {
        return findByTargetArea(targetArea);
    }

    @Override
    public Optional<Long> reclaim(UUID id, String owner, Instant expiresAt) {
        JobRecord current = store.get(id);
        if (current == null) return Optional.empty();
        Instant now = Instant.now();
        if (current.leaseExpiresAt() == null || current.leaseExpiresAt().isBefore(now) || "FAILED".equalsIgnoreCase(current.state())) {
            long newEpoch = current.leaseEpoch() + 1;
            JobRecord updated = new JobRecord(
                    current.id(), current.idempotencyKey(), current.targetArea(), current.testDescription(),
                    current.requester(), current.recordingBranch(), current.state(), current.confidenceTier(),
                    current.failureReason(), current.promptBundleHash(), current.modelId(), current.orchestratorVersion(),
                    current.recorderVersion(), current.baseCommitSha(), owner, newEpoch, expiresAt,
                    current.redactionReport(), current.mrIid(), current.mrUrl(), current.createdAt(), now, current.completedAt()
            );
            store.put(id, updated);
            return Optional.of(newEpoch);
        }
        return Optional.empty();
    }

    @Override
    public Optional<Long> reclaim(Handle handle, UUID id, String owner, Instant expiresAt) {
        return reclaim(id, owner, expiresAt);
    }

    @Override
    public boolean updateStateFenced(UUID id, String state, long leaseEpoch, Instant expiresAt) {
        JobRecord current = store.get(id);
        if (current == null || current.leaseEpoch() != leaseEpoch) return false;
        JobRecord updated = new JobRecord(
                current.id(), current.idempotencyKey(), current.targetArea(), current.testDescription(),
                current.requester(), current.recordingBranch(), state, current.confidenceTier(),
                current.failureReason(), current.promptBundleHash(), current.modelId(), current.orchestratorVersion(),
                current.recorderVersion(), current.baseCommitSha(), current.leaseOwner(), current.leaseEpoch(),
                expiresAt, current.redactionReport(), current.mrIid(), current.mrUrl(), current.createdAt(),
                Instant.now(), current.completedAt()
        );
        store.put(id, updated);
        return true;
    }

    @Override
    public boolean updateStateFenced(Handle handle, UUID id, String state, long leaseEpoch, Instant expiresAt) {
        return updateStateFenced(id, state, leaseEpoch, expiresAt);
    }

    @Override
    public boolean touch(UUID id, long leaseEpoch, Instant expiresAt) {
        JobRecord current = store.get(id);
        if (current == null || current.leaseEpoch() != leaseEpoch) return false;
        JobRecord updated = new JobRecord(
                current.id(), current.idempotencyKey(), current.targetArea(), current.testDescription(),
                current.requester(), current.recordingBranch(), current.state(), current.confidenceTier(),
                current.failureReason(), current.promptBundleHash(), current.modelId(), current.orchestratorVersion(),
                current.recorderVersion(), current.baseCommitSha(), current.leaseOwner(), current.leaseEpoch(),
                expiresAt, current.redactionReport(), current.mrIid(), current.mrUrl(), current.createdAt(),
                Instant.now(), current.completedAt()
        );
        store.put(id, updated);
        return true;
    }

    @Override
    public boolean touch(Handle handle, UUID id, long leaseEpoch, Instant expiresAt) {
        return touch(id, leaseEpoch, expiresAt);
    }

    @Override
    public boolean completeFenced(UUID id, long leaseEpoch, String confidenceTier, Integer mrIid, String mrUrl) {
        JobRecord current = store.get(id);
        if (current == null || current.leaseEpoch() != leaseEpoch) return false;
        Instant now = Instant.now();
        JobRecord updated = new JobRecord(
                current.id(), current.idempotencyKey(), current.targetArea(), current.testDescription(),
                current.requester(), current.recordingBranch(), "DONE", confidenceTier,
                null, current.promptBundleHash(), current.modelId(), current.orchestratorVersion(),
                current.recorderVersion(), current.baseCommitSha(), current.leaseOwner(), current.leaseEpoch(),
                current.leaseExpiresAt(), current.redactionReport(), mrIid, mrUrl, current.createdAt(),
                now, now
        );
        store.put(id, updated);
        return true;
    }

    @Override
    public boolean completeFenced(Handle handle, UUID id, long leaseEpoch, String confidenceTier, Integer mrIid, String mrUrl) {
        return completeFenced(id, leaseEpoch, confidenceTier, mrIid, mrUrl);
    }

    @Override
    public boolean failFenced(UUID id, long leaseEpoch, String failureReason) {
        JobRecord current = store.get(id);
        if (current == null || current.leaseEpoch() != leaseEpoch) return false;
        Instant now = Instant.now();
        JobRecord updated = new JobRecord(
                current.id(), current.idempotencyKey(), current.targetArea(), current.testDescription(),
                current.requester(), current.recordingBranch(), "FAILED", current.confidenceTier(),
                failureReason, current.promptBundleHash(), current.modelId(), current.orchestratorVersion(),
                current.recorderVersion(), current.baseCommitSha(), current.leaseOwner(), current.leaseEpoch(),
                current.leaseExpiresAt(), current.redactionReport(), current.mrIid(), current.mrUrl(),
                current.createdAt(), now, now
        );
        store.put(id, updated);
        return true;
    }

    @Override
    public boolean failFenced(Handle handle, UUID id, long leaseEpoch, String failureReason) {
        return failFenced(id, leaseEpoch, failureReason);
    }

    @Override
    public List<JobRecord> findExpiredNonTerminal(Duration age) {
        Instant cutoff = Instant.now().minus(age);
        return store.values().stream()
                .filter(j -> {
                    String s = j.state();
                    if ("DONE".equalsIgnoreCase(s) || "FAILED".equalsIgnoreCase(s) || "NEEDS_HUMAN_REVIEW".equalsIgnoreCase(s)) {
                        return false;
                    }
                    return j.leaseExpiresAt() != null && j.leaseExpiresAt().isBefore(cutoff);
                })
                .sorted(Comparator.comparing(JobRecord::createdAt).reversed())
                .toList();
    }

    @Override
    public List<JobRecord> findExpiredNonTerminal(Handle handle, Duration age) {
        return findExpiredNonTerminal(age);
    }

    @Override
    public List<UUID> doneWithoutConfirmedMr() {
        return store.values().stream()
                .filter(j -> "DONE".equalsIgnoreCase(j.state()))
                .filter(j -> {
                    if (effectRepository == null) return false;
                    return effectRepository.findByJobId(j.id()).stream()
                            .noneMatch(e -> e.effectType() == EffectType.MERGE_REQUEST && e.status() == EffectStatus.CONFIRMED);
                })
                .map(JobRecord::id)
                .toList();
    }

    @Override
    public List<UUID> doneWithoutConfirmedMr(Handle handle) {
        return doneWithoutConfirmedMr();
    }

    @Override
    public void markFailed(UUID id, String failureReason) {
        JobRecord current = store.get(id);
        if (current == null) return;
        Instant now = Instant.now();
        JobRecord updated = new JobRecord(
                current.id(), current.idempotencyKey(), current.targetArea(), current.testDescription(),
                current.requester(), current.recordingBranch(), "FAILED", current.confidenceTier(),
                failureReason, current.promptBundleHash(), current.modelId(), current.orchestratorVersion(),
                current.recorderVersion(), current.baseCommitSha(), current.leaseOwner(), current.leaseEpoch(),
                current.leaseExpiresAt(), current.redactionReport(), current.mrIid(), current.mrUrl(),
                current.createdAt(), now, now
        );
        store.put(id, updated);
    }

    @Override
    public void markFailed(Handle handle, UUID id, String failureReason) {
        markFailed(id, failureReason);
    }

    @Override
    public void recordRedaction(UUID id, RedactionReport report) {
        JobRecord current = store.get(id);
        if (current == null) return;
        String reportJson = report != null ? Json.stringify(report) : null;
        recordRedaction(null, id, reportJson);
    }

    @Override
    public void recordRedaction(Handle handle, UUID id, String reportJson) {
        JobRecord current = store.get(id);
        if (current == null) return;
        JobRecord updated = new JobRecord(
                current.id(), current.idempotencyKey(), current.targetArea(), current.testDescription(),
                current.requester(), current.recordingBranch(), current.state(), current.confidenceTier(),
                current.failureReason(), current.promptBundleHash(), current.modelId(), current.orchestratorVersion(),
                current.recorderVersion(), current.baseCommitSha(), current.leaseOwner(), current.leaseEpoch(),
                current.leaseExpiresAt(), reportJson, current.mrIid(), current.mrUrl(),
                current.createdAt(), Instant.now(), current.completedAt()
        );
        store.put(id, updated);
    }
}
